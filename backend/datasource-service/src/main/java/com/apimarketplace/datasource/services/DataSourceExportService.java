package com.apimarketplace.datasource.services;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.*;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels;
import com.apimarketplace.datasource.exception.ResourceNotFoundException;
import com.apimarketplace.datasource.persistence.DataSourceEnhancedRepositories;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

/**
 * Service d'export optimisé avec compression pour les données de DataSource
 * Supporte CSV, JSON et XLSX avec compression GZIP pour les grandes réponses
 */
@Service
@Transactional(readOnly = true)
public class DataSourceExportService {
    
    private final DataSourceEnhancedRepositories repositories;
    private final DataSourceEnhancedService enhancedService;
    private final ObjectMapper objectMapper;
    private static final int COMPRESSION_THRESHOLD = 100 * 1024; // 100 KB

    @Autowired
    public DataSourceExportService(
            DataSourceEnhancedRepositories repositories,
            DataSourceEnhancedService enhancedService,
            ObjectMapper objectMapper) {
        this.repositories = repositories;
        this.enhancedService = enhancedService;
        this.objectMapper = objectMapper;
    }

    /**
     * Backward-compat 9-arg overload - exports under tenant-only scope. Callers in an org
     * context SHOULD prefer the 11-arg variant below; otherwise org teammates cannot export
     * a datasource created by their admin (different tenant_id, same workspace).
     */
    public ExportResult exportData(
            Long dataSourceId,
            String tenantId,
            ExportFormat format,
            List<Long> ids,
            List<String> columns,
            String search,
            String sort,
            Integer limit,
            String cursor) throws IOException {
        return exportData(dataSourceId, tenantId, null, null, format, ids, columns, search, sort, limit, cursor);
    }

    /**
     * Exporte les données dans le format demandé avec compression automatique.
     *
     * <p>{@code organizationId} + {@code orgRole} from {@code X-Organization-ID} /
     * {@code X-Organization-Role} headers are routed through {@link
     * DataSourceEnhancedService#resolveAccessibleTenantId} - when the caller's tenant doesn't
     * own the datasource but the datasource belongs to the caller's org workspace AND the
     * caller has org access (via {@link DataSourceService#canAccessViaOrg}), the effective
     * tenantId swaps to the owner's so the underlying tenant-filtered queries return rows.
     * Closes the gap where org teammates could not export a datasource created by their admin.
     */
    public ExportResult exportData(
            Long dataSourceId,
            String tenantId,
            String organizationId,
            String orgRole,
            ExportFormat format,
            List<Long> ids,
            List<String> columns,
            String search,
            String sort,
            Integer limit,
            String cursor) throws IOException {

        // Org-aware tenant resolution: teammates of the owning workspace can export under the
        // owner's tenantId. When orgId is null/blank or the caller's tenant already owns the
        // datasource, this returns the caller's tenantId unchanged.
        String effectiveTenantId = enhancedService.resolveAccessibleTenantId(
                dataSourceId, tenantId, organizationId, orgRole);

        // Validation de l'accès tenant (against the resolved tenant)
        if (!repositories.dataSourceExists(dataSourceId, effectiveTenantId)) {
            throw new ResourceNotFoundException("DataSource", dataSourceId);
        }
        // Re-bind for the rest of the method - keeps the diff in the read paths small.
        tenantId = effectiveTenantId;
        
        // Construction de la requête de pagination
        PaginationRequest paginationRequest = buildPaginationRequest(
            ids, search, sort, limit, cursor
        );
        
        // Récupération des données
        List<DataSourceItemRow> rows;
        if (ids != null && !ids.isEmpty()) {
            // Récupérer seulement les lignes sélectionnées
            rows = repositories.findByIds(dataSourceId, tenantId, ids);
        } else {
            // Récupérer toutes les données (avec filtres)
            // Pour l'export complet, récupérer par batches pour optimiser la mémoire
            List<DataSourceItemRow> allRows = new ArrayList<>();
            String nextCursor = cursor;
            int batchCount = 0;
            final int MAX_BATCHES = 1000; // Limite de sécurité
            final int BATCH_SIZE = 10000; // Large batch size for export
            
            do {
                PaginationRequest batchRequest = new PaginationRequest(
                    null, null, BATCH_SIZE,
                    nextCursor,
                    paginationRequest.sort(),
                    paginationRequest.filter(),
                    paginationRequest.query()
                );
                
                List<DataSourceItemRow> batch = 
                    repositories.findItemsWithPagination(dataSourceId, tenantId, batchRequest);
                
                if (batch.isEmpty()) {
                    break;
                }
                
                // Le repository retourne limit + 1 pour détecter s'il y a plus de données
                // On prend seulement les BATCH_SIZE premiers
                List<DataSourceItemRow> actualBatch = batch.size() > BATCH_SIZE 
                    ? batch.subList(0, BATCH_SIZE)
                    : batch;
                
                allRows.addAll(actualBatch);
                
                // Générer le cursor suivant si on a récupéré une batch complète
                if (batch.size() > BATCH_SIZE) {
                    DataSourceItemRow lastItem = actualBatch.get(actualBatch.size() - 1);
                    DataSourceEnhancedModels.KeysetCursor cursorObj = new DataSourceEnhancedModels.KeysetCursor(
                        lastItem.createdAt().toEpochMilli(),
                        lastItem.id()
                    );
                    nextCursor = cursorObj.encode();
                } else {
                    // Dernière batch
                    nextCursor = null;
                }
                batchCount++;
            } while (nextCursor != null && batchCount < MAX_BATCHES);
            
            rows = allRows;
        }
        
        // Filtrage des colonnes si nécessaire
        if (columns != null && !columns.isEmpty()) {
            rows = filterColumns(rows, columns);
        }
        
        // Export selon le format
        return switch (format) {
            case CSV -> exportAsCSV(rows, dataSourceId);
            case JSON -> exportAsJSON(rows, dataSourceId, columns);
            case XLSX -> exportAsXLSX(rows, dataSourceId, columns);
        };
    }
    
    /**
     * Export CSV optimisé avec compression
     */
    private ExportResult exportAsCSV(List<DataSourceItemRow> rows, Long dataSourceId) throws IOException {
        StringBuilder csv = new StringBuilder();
        
        // En-têtes fixes
        csv.append("ID,Priority,Created At,Updated At");
        
        // En-têtes dynamiques (déduits des données)
        Set<String> dataColumns = extractDataColumns(rows);
        for (String col : dataColumns) {
            csv.append(",").append(escapeCsvField(col));
        }
        csv.append("\n");
        
        // Données
        for (DataSourceItemRow row : rows) {
            csv.append(row.id())
               .append(",")
               .append(row.priority() != null ? row.priority() : "")
               .append(",")
               .append(escapeCsvField(formatDate(row.createdAt())))
               .append(",")
               .append(escapeCsvField(row.updatedAt() != null ? formatDate(row.updatedAt()) : ""));
            
            // Colonnes dynamiques
            Map<String, Object> data = row.data() != null ? row.data() : Map.of();
            for (String col : dataColumns) {
                csv.append(",");
                Object value = data.get(col);
                if (value != null) {
                    csv.append(escapeCsvField(String.valueOf(value)));
                }
            }
            csv.append("\n");
        }
        
        byte[] csvBytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        boolean shouldCompress = csvBytes.length > COMPRESSION_THRESHOLD;
        
        if (shouldCompress) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
                 Writer writer = new OutputStreamWriter(gzos, StandardCharsets.UTF_8)) {
                writer.write(csv.toString());
            }
            csvBytes = baos.toByteArray();
        }
        
        HttpHeaders headers = createHeaders("csv", dataSourceId, shouldCompress);
        return new ExportResult(csvBytes, headers, shouldCompress);
    }
    
    /**
     * Export JSON optimisé avec compression
     */
    private ExportResult exportAsJSON(
            List<DataSourceItemRow> rows, 
            Long dataSourceId, 
            List<String> columns) throws IOException {
        
        Map<String, Object> jsonResponse = new HashMap<>();
        jsonResponse.put("exportDate", Instant.now().toString());
        jsonResponse.put("dataSourceId", dataSourceId);
        jsonResponse.put("totalRows", rows.size());
        
        // Métadonnées des colonnes
        List<Map<String, Object>> columnInfos = new ArrayList<>();
        if (columns != null && !columns.isEmpty()) {
            for (String col : columns) {
                columnInfos.add(Map.of(
                    "field", col,
                    "name", col.startsWith("data.") ? col.substring(5) : col
                ));
            }
        } else {
            Set<String> dataColumns = extractDataColumns(rows);
            for (String col : dataColumns) {
                columnInfos.add(Map.of(
                    "field", "data." + col,
                    "name", col
                ));
            }
        }
        jsonResponse.put("columns", columnInfos);
        
        // Filtrer les données pour exclure tenant_id et data_source_id
        List<Map<String, Object>> sanitizedData = rows.stream()
            .map(row -> {
                Map<String, Object> rowData = new HashMap<>();
                rowData.put("id", row.id());
                rowData.put("priority", row.priority());
                rowData.put("created_at", row.createdAt().toString());
                if (row.updatedAt() != null) {
                    rowData.put("updated_at", row.updatedAt().toString());
                }
                // Inclure seulement les données JSON (pas tenant_id ni data_source_id)
                if (row.data() != null) {
                    rowData.putAll(row.data());
                }
                return rowData;
            })
            .collect(Collectors.toList());
        
        jsonResponse.put("data", sanitizedData);
        
        byte[] jsonBytes = objectMapper.writeValueAsBytes(jsonResponse);
        boolean shouldCompress = jsonBytes.length > COMPRESSION_THRESHOLD;
        
        if (shouldCompress) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                gzos.write(jsonBytes);
            }
            jsonBytes = baos.toByteArray();
        }
        
        HttpHeaders headers = createHeaders("json", dataSourceId, shouldCompress);
        return new ExportResult(jsonBytes, headers, shouldCompress);
    }
    
    /**
     * Export Excel optimisé avec compression
     */
    private ExportResult exportAsXLSX(
            List<DataSourceItemRow> rows, 
            Long dataSourceId, 
            List<String> columns) throws IOException {
        
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Data");
            
            // Style pour les en-têtes
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            
            // Créer l'en-tête
            Row headerRow = sheet.createRow(0);
            int colNum = 0;
            
            headerRow.createCell(colNum++).setCellValue("ID");
            headerRow.getCell(colNum - 1).setCellStyle(headerStyle);
            headerRow.createCell(colNum++).setCellValue("Priority");
            headerRow.getCell(colNum - 1).setCellStyle(headerStyle);
            headerRow.createCell(colNum++).setCellValue("Created At");
            headerRow.getCell(colNum - 1).setCellStyle(headerStyle);
            headerRow.createCell(colNum++).setCellValue("Updated At");
            headerRow.getCell(colNum - 1).setCellStyle(headerStyle);
            
            // Colonnes dynamiques
            Set<String> dataColumns = extractDataColumns(rows);
            if (columns != null && !columns.isEmpty()) {
                dataColumns = dataColumns.stream()
                    .filter(col -> columns.stream().anyMatch(c -> c.equals(col) || c.equals("data." + col)))
                    .collect(Collectors.toSet());
            }
            
            List<String> orderedColumns = new ArrayList<>(dataColumns);
            Collections.sort(orderedColumns);
            for (String colName : orderedColumns) {
                Cell cell = headerRow.createCell(colNum++);
                cell.setCellValue(colName);
                cell.setCellStyle(headerStyle);
            }
            
            // Données
            int rowNum = 1;
            for (DataSourceItemRow row : rows) {
                Row dataRow = sheet.createRow(rowNum++);
                colNum = 0;
                
                dataRow.createCell(colNum++).setCellValue(row.id());
                dataRow.createCell(colNum++).setCellValue(row.priority() != null ? row.priority() : 0);
                dataRow.createCell(colNum++).setCellValue(formatDate(row.createdAt()));
                dataRow.createCell(colNum++).setCellValue(
                    row.updatedAt() != null ? formatDate(row.updatedAt()) : ""
                );
                
                Map<String, Object> data = row.data() != null ? row.data() : Map.of();
                for (String colName : orderedColumns) {
                    Cell cell = dataRow.createCell(colNum++);
                    Object value = data.get(colName);
                    setCellValue(cell, value);
                }
            }
            
            // Auto-size les colonnes (limité à 50 pour performance)
            int maxCols = Math.min(colNum, 50);
            for (int i = 0; i < maxCols; i++) {
                sheet.autoSizeColumn(i);
            }
            
            // Convertir en byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            byte[] xlsxBytes = outputStream.toByteArray();
            
            // Compression GZIP pour Excel
            boolean shouldCompress = xlsxBytes.length > COMPRESSION_THRESHOLD;
            if (shouldCompress) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                    gzos.write(xlsxBytes);
                }
                xlsxBytes = baos.toByteArray();
            }
            
            HttpHeaders headers = createHeaders("xlsx", dataSourceId, shouldCompress);
            return new ExportResult(xlsxBytes, headers, shouldCompress);
        }
    }
    
    private void setCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof Number) {
            cell.setCellValue(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            cell.setCellValue((Boolean) value);
        } else if (value instanceof Instant) {
            cell.setCellValue(formatDate((Instant) value));
        } else {
            cell.setCellValue(value.toString());
        }
    }
    
    private Set<String> extractDataColumns(List<DataSourceItemRow> rows) {
        Set<String> columns = new LinkedHashSet<>();
        for (DataSourceItemRow row : rows) {
            if (row.data() != null) {
                columns.addAll(row.data().keySet());
            }
        }
        return columns;
    }
    
    private List<DataSourceItemRow> filterColumns(List<DataSourceItemRow> rows, List<String> columns) {
        Set<String> columnSet = columns.stream()
            .map(col -> col.startsWith("data.") ? col.substring(5) : col)
            .filter(col -> !col.equals("id") && !col.equals("priority") && 
                          !col.equals("created_at") && !col.equals("updated_at"))
            .collect(Collectors.toSet());
        
        return rows.stream()
            .map(row -> {
                Map<String, Object> filteredData = new HashMap<>();
                Map<String, Object> originalData = row.data() != null ? row.data() : Map.of();
                
                for (String col : columnSet) {
                    if (originalData.containsKey(col)) {
                        filteredData.put(col, originalData.get(col));
                    }
                }
                
                return new DataSourceItemRow(
                    row.id(),
                    row.dataSourceId(),
                    row.tenantId(),
                    filteredData,
                    row.priority(),
                    row.createdAt(),
                    row.updatedAt()
                );
            })
            .collect(Collectors.toList());
    }
    
    private PaginationRequest buildPaginationRequest(
            List<Long> ids, String search, String sort, Integer limit, String cursor) {
        
        List<SortRequest> sortRequests = parseSort(sort);
        Map<String, Object> filter = Map.of();
        
        return new PaginationRequest(
            null,
            null,
            limit != null ? limit : 10000,
            cursor,
            sortRequests,
            filter,
            search
        );
    }
    
    private List<SortRequest> parseSort(String sort) {
        if (sort == null || sort.trim().isEmpty()) {
            return List.of();
        }
        
        List<SortRequest> sortRequests = new ArrayList<>();
        String[] parts = sort.split(",");
        
        for (String part : parts) {
            String[] colSort = part.trim().split(":");
            if (colSort.length == 2) {
                try {
                    SortDirection direction = SortDirection.fromValue(colSort[1].trim());
                    sortRequests.add(new SortRequest(colSort[0].trim(), direction));
                } catch (IllegalArgumentException e) {
                    sortRequests.add(new SortRequest(colSort[0].trim(), SortDirection.ASC));
                }
            }
        }
        
        return sortRequests;
    }
    
    private String escapeCsvField(String field) {
        if (field == null) {
            return "";
        }
        
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }
    
    private String formatDate(Instant instant) {
        if (instant == null) {
            return "";
        }
        // Exports are user-facing: emit UTC so the file matches the rest of
        // the platform (DB, API, UI). Previously used ZoneId.systemDefault()
        // which produced Europe/Berlin timestamps on prod and confused users
        // comparing exports against UTC timestamps in the UI.
        return instant.atZone(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"));
    }
    
    private HttpHeaders createHeaders(String format, Long dataSourceId, boolean compressed) {
        HttpHeaders headers = new HttpHeaders();
        String mimeType;

        switch (format) {
            case "csv":
                mimeType = "text/csv";
                break;
            case "json":
                mimeType = "application/json";
                break;
            case "xlsx":
                mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                break;
            default:
                mimeType = "application/octet-stream";
        }

        headers.setContentType(MediaType.parseMediaType(mimeType));

        String filename = "datasource_" + dataSourceId + "_export_" +
            LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) +
            "." + format;

        headers.setContentDispositionFormData("attachment", filename);

        if (compressed) {
            headers.set("Content-Encoding", "gzip");
        }

        return headers;
    }
    
    public enum ExportFormat {
        CSV, JSON, XLSX;
        
        public static ExportFormat fromString(String format) {
            try {
                return valueOf(format.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid format: " + format + ". Supported: CSV, JSON, XLSX");
            }
        }
    }
    
    public record ExportResult(byte[] data, HttpHeaders headers, boolean compressed) {}
}

