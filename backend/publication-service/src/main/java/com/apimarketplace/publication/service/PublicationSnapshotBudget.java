package com.apimarketplace.publication.service;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one size budget of every marketplace publication snapshot (table, interface, workflow,
 * application and agent listings): at most {@link #maxTableRows()} rows per copied table, and at
 * most {@link #maxBytes()} serialized bytes for the whole snapshot.
 *
 * <p>A publication embeds its tables as inline rows in one JSONB value, so an oversized table must
 * fail the publish loudly, with the table and the limit named, before anything is persisted. It
 * is never truncated silently: {@link DataSourceClient#getAllItems} reads one row past
 * {@link DataSourceClient#MAX_COPY_ROWS}, so a table above the limit always shows up here as one.
 *
 * <p>The limits are the agent snapshot's ({@code publication.agent-snapshot.*}), which predate
 * this class and now apply to every listing type. The row limit is clamped to
 * {@link DataSourceClient#MAX_COPY_ROWS}: above it the copy could no longer tell a larger table.
 */
@Component
public class PublicationSnapshotBudget {

    private static final Logger logger = LoggerFactory.getLogger(PublicationSnapshotBudget.class);

    public static final long DEFAULT_MAX_BYTES = 15L * 1024 * 1024;
    public static final int DEFAULT_MAX_TABLE_ROWS = 5000;

    /** Depth bound of the table walk: a deserialized snapshot is acyclic, only depth can run away. */
    private static final int MAX_DEPTH = 200;

    /** Who is publishing: decides the refusal code (agents keep theirs) and how the fix reads. */
    public enum Listing {
        AGENT(PublicationValidationException.AGENT_SNAPSHOT_TOO_LARGE,
                "Remove it from the agent's resource selection or reduce its content.",
                "Remove the heaviest resources from the agent's selection or reduce their content."),
        WORKFLOW(PublicationValidationException.PUBLICATION_SNAPSHOT_TOO_LARGE,
                "Delete rows from that table, or stop using it in this workflow (and its sub-workflows "
                        + "and agents), then publish again.",
                "Reduce the content of the heaviest resources listed, then publish again."),
        TABLE(PublicationValidationException.PUBLICATION_SNAPSHOT_TOO_LARGE,
                "Delete rows from the table, then publish again.",
                "Reduce the table's content, then publish again."),
        INTERFACE(PublicationValidationException.PUBLICATION_SNAPSHOT_TOO_LARGE,
                "Delete rows from that table, or detach it from the interface, then publish again.",
                "Reduce the content of the interface and its table, then publish again."),
        SKILL(PublicationValidationException.PUBLICATION_SNAPSHOT_TOO_LARGE,
                "Delete rows from that table, then publish again.",
                "Reduce the skill's content, then publish again.");

        /** The listing of a standalone resource publication. */
        public static Listing forResource(com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType type) {
            if (type == null) {
                return TABLE;
            }
            return switch (type) {
                case INTERFACE -> INTERFACE;
                case SKILL -> SKILL;
                case AGENT -> AGENT;
                case WORKFLOW -> WORKFLOW;
                default -> TABLE;
            };
        }

        private final String errorCode;
        private final String rowsFix;
        private final String bytesFix;

        Listing(String errorCode, String rowsFix, String bytesFix) {
            this.errorCode = errorCode;
            this.rowsFix = rowsFix;
            this.bytesFix = bytesFix;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    private final ObjectMapper objectMapper;
    private final long maxBytes;
    private final int maxTableRows;

    @Autowired
    public PublicationSnapshotBudget(ObjectMapper objectMapper,
                                     @Value("${publication.agent-snapshot.max-bytes:15728640}") long maxBytes,
                                     @Value("${publication.agent-snapshot.max-table-rows:5000}") int maxTableRows) {
        this.objectMapper = objectMapper;
        this.maxBytes = maxBytes;
        if (maxTableRows > DataSourceClient.MAX_COPY_ROWS) {
            logger.warn("publication.agent-snapshot.max-table-rows={} is above the {} rows a publication copy "
                    + "reads; using {}", maxTableRows, DataSourceClient.MAX_COPY_ROWS, DataSourceClient.MAX_COPY_ROWS);
        }
        this.maxTableRows = Math.min(maxTableRows, DataSourceClient.MAX_COPY_ROWS);
    }

    /** The default limits, for a service built without Spring (unit tests). */
    public static PublicationSnapshotBudget defaults(ObjectMapper objectMapper) {
        return new PublicationSnapshotBudget(objectMapper, DEFAULT_MAX_BYTES, DEFAULT_MAX_TABLE_ROWS);
    }

    public long maxBytes() {
        return maxBytes;
    }

    public int maxTableRows() {
        return maxTableRows;
    }

    /**
     * Refuse one copied table above the row limit, as soon as it is read (the agent builder calls
     * this per table so it fails before building the rest).
     */
    public void assertTableRows(Listing listing, String id, String name, int rows) {
        if (rows <= maxTableRows) {
            return;
        }
        String label = name != null ? name : (id != null ? id : "?");
        // A copy stops one row past MAX_COPY_ROWS, so its real size is only known to be larger:
        // say so, and leave the row count out of the breakdown rather than print the cut-off count.
        boolean truncated = rows > DataSourceClient.MAX_COPY_ROWS;
        String count = truncated ? "more than " + DataSourceClient.MAX_COPY_ROWS : String.valueOf(rows);
        String reason = "Table '" + label + "' has " + count + " rows (max " + maxTableRows
                + " rows per published table).";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("maxTableRows", maxTableRows);
        details.put("breakdown", List.of(breakdownEntry("datasource", id, name, truncated ? null : rows, null)));
        throw new PublicationValidationException(listing.errorCode, reason + " " + listing.rowsFix, details);
    }

    /**
     * Refuse a fully assembled snapshot that breaks the budget: first any copied table above the
     * row limit (found at any depth, in every snapshot shape), then the total serialized size,
     * with a heaviest-first breakdown. Call it before anything is persisted.
     */
    public void assertWithinBudget(Map<String, Object> snapshot, Listing listing) {
        if (snapshot == null) {
            return;
        }
        for (TableCopy table : tableCopies(snapshot)) {
            assertTableRows(listing, table.id(), table.name(), table.rows().size());
        }
        long size;
        try {
            size = objectMapper.writeValueAsBytes(snapshot).length;
        } catch (Exception e) {
            // Only a size guard: an unserializable snapshot fails at persistence with its own error.
            logger.warn("Snapshot size guard skipped (serialization failed): {}", e.getMessage());
            return;
        }
        if (size <= maxBytes) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sizeBytes", size);
        details.put("maxBytes", maxBytes);
        details.put("breakdown", breakdown(snapshot));
        String reason = "Publication snapshot is " + toMb(size) + " MB (max " + toMb(maxBytes) + " MB).";
        // The reason without the fix: a surface that knows its own fix (an MCP tool) appends it.
        details.put("reason", reason);
        throw new PublicationValidationException(listing.errorCode, reason + " " + listing.bytesFix, details);
    }

    /** A table copied into a snapshot: its id when known, its name, its rows. */
    record TableCopy(String id, String name, List<?> rows) {}

    /**
     * Every table copy in a snapshot, whatever its shape: a workflow plan's table node
     * ({@code _snapshot_ds_items}, also inside sub-workflow and agent-embedded plans), and a
     * table map carrying {@code items} with its {@code mappingSpec} (a TABLE listing, an
     * interface's embedded table, an agent's {@code datasources} entry, keyed by its id).
     */
    List<TableCopy> tableCopies(Map<String, Object> snapshot) {
        List<TableCopy> out = new ArrayList<>();
        collectTables(snapshot, null, out, 0);
        return out;
    }

    private void collectTables(Object node, String key, List<TableCopy> out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            if (map.get("_snapshot_ds_items") instanceof List<?> rows) {
                Object id = map.get("dataSourceId");
                Object name = map.get("_snapshot_ds_name");
                out.add(new TableCopy(id != null ? id.toString() : null, name != null ? name.toString() : null, rows));
            } else if (map.get("items") instanceof List<?> rows && map.containsKey("mappingSpec")) {
                Object name = map.get("name");
                // These maps carry no id field. The key is the table id only where a section is
                // keyed by it (an agent's datasources); elsewhere it is a field name
                // ("embeddedTable"), which must never be handed out as a table id.
                String id = key != null && key.chars().allMatch(Character::isDigit) && !key.isEmpty() ? key : null;
                out.add(new TableCopy(id, name != null ? name.toString() : null, rows));
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> || value instanceof List<?>) {
                    // Rows themselves are data, never table copies: no need to walk them.
                    if ("_snapshot_ds_items".equals(entry.getKey()) || "items".equals(entry.getKey())) {
                        continue;
                    }
                    collectTables(value, String.valueOf(entry.getKey()), out, depth + 1);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object element : list) {
                collectTables(element, null, out, depth + 1);
            }
        }
    }

    /**
     * Per-resource serialized weight, heaviest first (top 8). An agent snapshot is broken down by
     * its sections (workflows / interfaces / datasources / subAgents); any other snapshot by its
     * copied tables. The landing interface is listed in both.
     */
    private List<Map<String, Object>> breakdown(Map<String, Object> snapshot) {
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean agentShape = false;
        for (Map.Entry<String, String> section : Map.of(
                "workflows", "workflow",
                "interfaces", "interface",
                "datasources", "datasource",
                "subAgents", "agent").entrySet()) {
            Object raw = snapshot.get(section.getKey());
            if (!(raw instanceof Map<?, ?> map)) continue;
            agentShape = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object value = e.getValue();
                String name = null;
                Integer items = null;
                if (value instanceof Map<?, ?> vm) {
                    Object n = vm.get("name");
                    if (n == null && vm.get("agent") instanceof Map<?, ?> am) n = am.get("name");
                    name = n != null ? n.toString() : null;
                    if (vm.get("items") instanceof List<?> l) items = l.size();
                }
                entries.add(breakdownEntry(section.getValue(),
                        String.valueOf(e.getKey()), name, items, approxBytes(value)));
            }
        }
        if (!agentShape) {
            for (TableCopy table : tableCopies(snapshot)) {
                entries.add(breakdownEntry("datasource", table.id(), table.name(), table.rows().size(),
                        approxBytes(table.rows())));
            }
        }
        Object landing = snapshot.get("landingInterface");
        if (landing != null) {
            entries.add(breakdownEntry("landingInterface", null, null, null, approxBytes(landing)));
        }
        entries.sort((a, b) -> Long.compare(
                ((Number) b.getOrDefault("approxBytes", 0L)).longValue(),
                ((Number) a.getOrDefault("approxBytes", 0L)).longValue()));
        return entries.size() > 8 ? new ArrayList<>(entries.subList(0, 8)) : entries;
    }

    private long approxBytes(Object value) {
        try {
            return value != null ? objectMapper.writeValueAsBytes(value).length : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Map<String, Object> breakdownEntry(String type, String id, String name,
                                              Integer items, Long approxBytes) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        if (id != null) entry.put("id", id);
        if (name != null) entry.put("name", name);
        if (items != null) entry.put("items", items);
        if (approxBytes != null) entry.put("approxBytes", approxBytes);
        return entry;
    }

    private static String toMb(long bytes) {
        return String.valueOf(Math.round(bytes / (1024.0 * 1024.0) * 10.0) / 10.0);
    }
}
