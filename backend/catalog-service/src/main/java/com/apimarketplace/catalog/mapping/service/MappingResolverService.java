package com.apimarketplace.catalog.mapping.service;

import com.apimarketplace.catalog.mapping.SourceFormat;
import com.apimarketplace.catalog.mapping.dsl.FieldSpec;
import com.apimarketplace.catalog.mapping.dsl.MappingSpec;
import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import com.apimarketplace.catalog.mapping.entity.MappingDefinitionEntity;
import com.apimarketplace.catalog.mapping.entity.MappingVersionEntity;
import com.apimarketplace.common.mapping.SimpleMappingEngine;
import com.apimarketplace.common.mapping.SimpleMappingService;
import com.apimarketplace.common.mapping.StrictMappingEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Transactional
public class MappingResolverService {

    private static final int PREVIEW_LIMIT = 200;
    private static final int GLOBALS_PARENT_DEPTH = 10;

    private final DetectionService detectionService;
    private final SimpleMappingService simpleMappingService;
    private final ObjectMapper objectMapper;
    private final MappingRegistry mappingRegistry;

    public MappingResolverService(DetectionService detectionService,
                                  SimpleMappingService simpleMappingService,
                                  ObjectMapper objectMapper,
                                  MappingRegistry mappingRegistry) {
        this.detectionService = detectionService;
        this.simpleMappingService = simpleMappingService;
        this.objectMapper = objectMapper;
        this.mappingRegistry = mappingRegistry;
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    private static String sanitizeItemsPath(String value, String fallback) {
        String p = (value == null || value.isBlank()) ? fallback : value.trim();
        if (p.startsWith("(") && p.endsWith(")")) p = p.substring(1, p.length() - 1).trim();
        p = p.replace("(", "").replace(")", "");
        if (!p.startsWith("$")) {
            p = p.startsWith(".") ? "$" + p : "$." + p;
        } else if (!(p.startsWith("$..") || p.startsWith("$[")) && !p.startsWith("$.")) {
            p = p.replaceFirst("^\\$", "\\$.");
        }
        return p;
    }

    private static String sanitizeAbsolute(String path) {
        if (path == null || path.isBlank()) return null;
        String p = path.trim();
        if (p.startsWith("(") && p.endsWith(")")) p = p.substring(1, p.length() - 1).trim();
        p = p.replace("(", "").replace(")", "");
        if (!p.startsWith("$")) {
            p = p.startsWith(".") ? "$" + p : "$." + p;
        } else if (!(p.startsWith("$..") || p.startsWith("$[")) && !p.startsWith("$.")) {
            p = p.replaceFirst("^\\$", "\\$.");
        }
        if ("$.null".equalsIgnoreCase(p)) return null;
        return p;
    }

    public MappingResolutionResult resolve(UUID toolId, byte[] input) {
        Optional<MappingVersionEntity> existing = mappingRegistry.findLatestMappingVersionByToolId(toolId);
        MappingVersionEntity ver = existing.orElse(null);

        MappingResolutionResult result = new MappingResolutionResult();
        try {
            SourceFormat sourceFormat = detectionService.detect(input, SourceFormat.JSON.name());
            result.setSourceFormat(sourceFormat);

            final String sampleJson = new String(input, StandardCharsets.UTF_8);
            final JsonNode sampleRoot = objectMapper.readTree(sampleJson);

            MappingSpec provided = ver != null ? cleanMappingSpec(ver.getParsedSpec()) : null;
            MappingSpec spec;
            if (provided != null) {
                sanitizeSourceSpec(provided);
                normalizeCandidatesNoRename(provided);
                spec = provided;
            } else {
                // Lancer une exception si aucun mapping n'existe
                throw new IllegalArgumentException("No mapping found for tool " + toolId + ". Please create a mapping first.");
            }

            // 1) Appliquer le mapping items (comme avant)
            StrictMappingEngine.StrictMappingSpec strict = toStrict(spec);
            SimpleMappingEngine.MappingOutcome out = simpleMappingService.applyMapping(sampleJson, strict);

            // 2) Resolution explicite des globals depuis le spec (alias imposes) - PRIORITe
            Map<String, Object> explicitGlobals = mapExplicitGlobals(sampleJson, spec);

            // 3) Collecte generique des scalaires globaux (UNIQUEMENT si pas de globals explicites)
            Map<String, Object> globals = new LinkedHashMap<>(explicitGlobals);
            if (explicitGlobals.isEmpty()) {
                // Fallback: decouverte automatique seulement si aucun global explicite defini
                Map<String, Object> discoveredGlobals = computeGlobalScalars(sampleRoot, spec);
                globals.putAll(discoveredGlobals);
            }

            // 5) Construire les items (dedup des listes) + wrapper final
            List<Map<String, Object>> items = (out.items == null) ? List.of() : out.items;
            int limit = Math.min(items.size(), PREVIEW_LIMIT);
            List<Map<String, Object>> childFields = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                Map<String, Object> row = new LinkedHashMap<>(items.get(i));
                row.replaceAll((k, v) -> {
                    if (v instanceof List<?>) {
                        return new ArrayList<>(new LinkedHashSet<>((List<?>) v));
                    }
                    return v;
                });
                childFields.add(row);
            }

            Map<String, Object> wrapper = new LinkedHashMap<>(globals);
            wrapper.put("fields", childFields);

            result.setSuccess(true);
            result.setSpec(spec);
            result.setPreview(wrapper);
            result.setItemCount(out.itemCount);
            result.setUnresolvedFields(out.unresolvedFields == null ? List.of() : out.unresolvedFields);
            result.setFromCache(false);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setError("Resolve error: " + e.getMessage());
            result.setSpec(null);
            result.setPreview(Collections.emptyMap());
            result.setItemCount(0);
            result.setUnresolvedFields(List.of());
        }
        return result;
    }

    // ------------------------------------------------------------------------
    // Cleaning / Sanitizing
    // ------------------------------------------------------------------------

    public MappingResolutionResult create(UUID toolId, byte[] input, final MappingSpec mappingSpec, String createdBy) {
        MappingResolutionResult result = new MappingResolutionResult();
        try {
            SourceFormat sourceFormat = detectionService.detect(input, SourceFormat.JSON.name());
            result.setSourceFormat(sourceFormat);

            final String sampleJson = new String(input, StandardCharsets.UTF_8);
            final JsonNode sampleRoot = objectMapper.readTree(sampleJson);

            MappingSpec spec;
            MappingDefinitionEntity mappingDefinition = findOrCreateMappingDefinition(toolId, createdBy);
            mappingRegistry.saveMappingVersion(mappingDefinition.getId(), mappingSpec, createdBy);
            result.setSpec(mappingSpec);
            result.setFromCache(false);
            // Utiliser le mappingSpec fourni au lieu d'en generer un nouveau
            sanitizeSourceSpec(mappingSpec);
            normalizeCandidatesNoRename(mappingSpec);
            spec = mappingSpec;

            // 1) Appliquer le mapping items (comme avant)
            StrictMappingEngine.StrictMappingSpec strict = toStrict(spec);
            SimpleMappingEngine.MappingOutcome out = simpleMappingService.applyMapping(sampleJson, strict);

            // 2) Resolution explicite des globals depuis le spec (alias imposes) - PRIORITe
            Map<String, Object> explicitGlobals = mapExplicitGlobals(sampleJson, spec);

            // 3) Collecte generique des scalaires globaux (UNIQUEMENT si pas de globals explicites)
            Map<String, Object> globals = new LinkedHashMap<>(explicitGlobals);
            if (explicitGlobals.isEmpty()) {
                // Fallback: decouverte automatique seulement si aucun global explicite defini
                Map<String, Object> discoveredGlobals = computeGlobalScalars(sampleRoot, spec);
                globals.putAll(discoveredGlobals);
            }

            // 5) Construire les items (dedup des listes) + wrapper final
            List<Map<String, Object>> items = (out.items == null) ? List.of() : out.items;
            int limit = Math.min(items.size(), PREVIEW_LIMIT);
            List<Map<String, Object>> childFields = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                Map<String, Object> row = new LinkedHashMap<>(items.get(i));
                row.replaceAll((k, v) -> {
                    if (v instanceof List<?>) {
                        return new ArrayList<>(new LinkedHashSet<>((List<?>) v));
                    }
                    return v;
                });
                childFields.add(row);
            }

            Map<String, Object> wrapper = new LinkedHashMap<>(globals);
            wrapper.put("fields", childFields);

            result.setSuccess(true);
            result.setSpec(spec);
            result.setPreview(wrapper);
            result.setItemCount(out.itemCount);
            result.setUnresolvedFields(out.unresolvedFields == null ? List.of() : out.unresolvedFields);
            result.setFromCache(false);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setError("Resolve error: " + e.getMessage());
            result.setSpec(null);
            result.setPreview(Collections.emptyMap());
            result.setItemCount(0);
            result.setUnresolvedFields(List.of());
        }
        return result;
    }

    private MappingDefinitionEntity findOrCreateMappingDefinition(UUID toolId, String createdBy) {
        List<MappingDefinitionEntity> existing = mappingRegistry.findByToolId(toolId);
        if (!existing.isEmpty()) return existing.get(0);

        MappingDefinitionEntity mappingDefinition = new MappingDefinitionEntity(
                toolId,
                "Mapping for tool " + toolId,
                createdBy
        );
        return mappingRegistry.save(mappingDefinition);
    }

    private MappingSpec cleanMappingSpec(MappingSpec spec) {
        if (spec == null) return null;

        MappingSpec cleaned = new MappingSpec();

        if (spec.getSource() != null) {
            SourceSpec s = new SourceSpec();
            s.setFormat(spec.getSource().getFormat());
            s.setItemsPath(spec.getSource().getItemsPath());
            s.setRoot(spec.getSource().getRoot());
            s.setRootAlternatives(spec.getSource().getRootAlternatives());
            cleaned.setSource(s);
        }
        if (spec.getFields() != null) {
            Map<String, FieldSpec> m = new LinkedHashMap<>();
            spec.getFields().forEach((k, fs) -> {
                FieldSpec nf = new FieldSpec();
                nf.setCandidates(fs.getCandidates());
                nf.setTo(fs.getTo());
                nf.setRequired(fs.getRequired());
                nf.setDefaultValue(fs.getDefaultValue());
                m.put(k, nf);
            });
            cleaned.setFields(m);
        }
        // NOUVEAU : conserver aussi globals
        if (spec.getGlobals() != null) {
            Map<String, FieldSpec> g = new LinkedHashMap<>();
            spec.getGlobals().forEach((k, fs) -> {
                FieldSpec nf = new FieldSpec();
                nf.setCandidates(fs.getCandidates());
                nf.setTo(fs.getTo());
                nf.setRequired(fs.getRequired());
                nf.setDefaultValue(fs.getDefaultValue());
                g.put(k, nf);
            });
            cleaned.setGlobals(g);
        }
        return cleaned;
    }

    private void sanitizeSourceSpec(MappingSpec spec) {
        if (spec == null || spec.getSource() == null) return;
        SourceSpec s = spec.getSource();

        String ip = sanitizeItemsPath(s.getItemsPath(), "$");
        s.setItemsPath(ip);

        LinkedHashSet<String> alts = new LinkedHashSet<>();
        alts.add(ip);
        if (s.getRootAlternatives() != null) {
            for (String a : s.getRootAlternatives()) {
                String z = sanitizeAbsolute(a);
                if (z != null) alts.add(z);
            }
        }
        s.setRootAlternatives(new ArrayList<>(alts));
        s.setFormat("json");
    }

    private void normalizeCandidatesNoRename(MappingSpec spec) {
        if (spec == null) return;

        String itemsPath = spec.getSource() != null ? spec.getSource().getItemsPath() : null;

        // Normalisation pour les fields (items)
        if (spec.getFields() != null) {
            spec.getFields().forEach((name, fs) -> {
                if (fs == null || fs.getCandidates() == null) return;
                LinkedHashSet<String> out = new LinkedHashSet<>();
                for (String raw : fs.getCandidates()) {
                    if (raw == null || raw.isBlank()) continue;
                    String c = normalizeCandidate(raw, itemsPath, true); // autorise @ + absolu
                    out.add(c);
                    // si relatif @.x, on ajoute aussi la version absolue ancree itemsPath
                    if (itemsPath != null && c.startsWith("@.")) {
                        String tail = c.substring(2);
                        if (!tail.isEmpty() && !tail.startsWith(".")) tail = "." + tail;
                        out.add(itemsPath + tail);
                    }
                }
                fs.setCandidates(new ArrayList<>(out));
            });
        }

        // Normalisation pour les globals (hors items) : on **n’ajoute pas** la variante ancree itemsPath
        if (spec.getGlobals() != null) {
            spec.getGlobals().forEach((name, fs) -> {
                if (fs == null || fs.getCandidates() == null) return;
                List<String> norm = fs.getCandidates().stream()
                                      .filter(Objects::nonNull)
                                      .map(String::trim)
                                      .filter(s -> !s.isBlank())
                                      .map(c -> normalizeCandidate(c, "$", false)) // preferer absolu
                                      .distinct()
                                      .collect(Collectors.toList());
                fs.setCandidates(norm);
            });
        }
    }

    private String normalizeCandidate(String raw, String basePath, boolean allowRelativeAt) {
        String c = raw.trim();
        if (c.startsWith("(") && c.endsWith(")")) c = c.substring(1, c.length() - 1).trim();
        c = c.replace("(", "").replace(")", "");

        if (c.startsWith("$..") || c.startsWith("$[")) {
            return c;
        } else if (c.startsWith("@")) {
            if (!allowRelativeAt) {
                // pour les globals on **force** l’absolu
                String tail = c.replaceFirst("^@\\.?", "");
                if (!tail.isEmpty() && !tail.startsWith(".")) tail = "." + tail;
                return (basePath == null ? "$" : basePath) + tail;
            }
            if (!c.startsWith("@.")) c = c.replaceFirst("^@", "@.");
            return c;
        } else if (c.startsWith("$")) {
            if (!(c.startsWith("$..") || c.startsWith("$[")) && !c.startsWith("$.")) {
                c = c.replaceFirst("^\\$", "\\$.");
            }
            return c;
        } else {
            // token nu -> relatif
            c = "@." + c;
            return c;
        }
    }

    // ------------------------------------------------------------------------
    // Globals
    // ------------------------------------------------------------------------

    /**
     * Decouverte generique des scalaires globaux (pour debug/exploration).
     */
    private Map<String, Object> computeGlobalScalars(JsonNode root, MappingSpec spec) {
        Map<String, Object> globals = new LinkedHashMap<>();
        if (root == null || root.isNull()) return globals;

        String hint = spec != null && spec.getSource() != null ? spec.getSource().getItemsPath() : null;
        GenericMappingBuilder.ItemsAnchor anchor = new GenericMappingBuilder(objectMapper).detectItemsPath(root, hint);
        GenericMappingBuilder.NodePath frame = anchor.arrayFrame;

        Set<String> seen = new HashSet<>();
        int climb = 0;
        GenericMappingBuilder.NodePath p = frame;
        while (p != null && climb <= GLOBALS_PARENT_DEPTH) {
            if (p.node.isObject()) {
                collectExternalScalars(p.node, p.absPath, frame.absPath, seen, globals);
            }
            p = p.parent;
            climb++;
        }
        return globals;
    }

    /**
     * Application explicite des mappings globals du spec, ancres a la racine.
     */
    private Map<String, Object> mapExplicitGlobals(String sampleJson, MappingSpec spec) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (spec == null || spec.getGlobals() == null || spec.getGlobals().isEmpty()) return out;

        StrictMappingEngine.StrictMappingSpec gspec = new StrictMappingEngine.StrictMappingSpec();
        StrictMappingEngine.SourceSpec ss = new StrictMappingEngine.SourceSpec();
        ss.format = "json";
        ss.items_path = "$"; // racine
        ss.root = null;
        ss.root_alternatives = List.of("$");
        gspec.source = ss;

        // recopier les FieldSpec dans le modele Strict
        gspec.fields = new LinkedHashMap<>();
        spec.getGlobals().forEach((k, v) -> {
            StrictMappingEngine.FieldSpec f = new StrictMappingEngine.FieldSpec();
            f.candidates = v.getCandidates();
            f.to = v.getTo();
            f.required = v.getRequired();
            f.defaultValue = v.getDefaultValue();
            gspec.fields.put(k, f);
        });

        SimpleMappingEngine.MappingOutcome mout = simpleMappingService.applyMapping(sampleJson, gspec);
        if (mout.items != null && !mout.items.isEmpty()) {
            // on s'attend a 1 "item" (la racine)
            out.putAll(mout.items.get(0));
        }
        return out;
    }

    public boolean hasMappingForTool(String toolId) {
        try {
            UUID apiToolId;
            try {
                apiToolId = UUID.fromString(toolId);
            } catch (IllegalArgumentException e) {
                return false;
            }

            // Check if the API tool exists and has mapping definitions
            if (!mappingRegistry.apiToolExists(apiToolId)) {
                return false;
            }

            List<MappingDefinitionEntity> definitions = mappingRegistry.findByToolId(apiToolId);
            return definitions != null && !definitions.isEmpty();

        } catch (Exception e) {
            return false;
        }
    }

    private void collectExternalScalars(JsonNode obj,
                                        String baseAbs,
                                        String itemsAbs,
                                        Set<String> seen,
                                        Map<String, Object> out) {
        obj.fieldNames().forEachRemaining(fn -> {
            String childAbs = baseAbs + "." + fn;
            if (childAbs.startsWith(itemsAbs)) return; // n’inclut rien sous la branche items
            JsonNode child = obj.get(fn);
            if (child == null || child.isNull()) return;

            if (child.isValueNode()) {
                String key = toSnakeFromAbsolute(childAbs);
                if (seen.add(key)) out.put(key, scalarValue(child));
            } else if (child.isObject()) {
                collectExternalScalars(child, childAbs, itemsAbs, seen, out);
            }
        });
    }

    private Object scalarValue(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isInt()) return n.asInt();
        if (n.isLong()) return n.asLong();
        if (n.isNumber()) return n.numberValue();
        return n.asText();
    }

    private String toSnakeFromAbsolute(String abs) {
        String t = abs.replaceFirst("^\\$\\.", "")
                      .replaceAll("\\[\\*\\]", "")
                      .replaceAll("\\[(\\d+)\\]", "")
                      .replace('.', '_');
        t = t.replaceAll("__+", "_").toLowerCase(Locale.ROOT);
        return t.isEmpty() ? "value" : t;
    }

    // ------------------------------------------------------------------------
    // Strict mapping conversion (items)
    // ------------------------------------------------------------------------

    private StrictMappingEngine.StrictMappingSpec toStrict(MappingSpec spec) {
        StrictMappingEngine.StrictMappingSpec out = new StrictMappingEngine.StrictMappingSpec();

        SourceSpec s = spec.getSource();
        StrictMappingEngine.SourceSpec ss = new StrictMappingEngine.SourceSpec();
        if (s != null) {
            ss.format = s.getFormat();
            ss.items_path = s.getItemsPath();
            ss.root = s.getRoot();
            ss.root_alternatives = s.getRootAlternatives();
        }
        out.source = ss;

        out.fields = new LinkedHashMap<>();
        if (spec.getFields() != null) {
            spec.getFields().forEach((k, v) -> {
                StrictMappingEngine.FieldSpec f = new StrictMappingEngine.FieldSpec();
                f.candidates = v.getCandidates();
                f.to = v.getTo();
                f.required = v.getRequired();
                f.defaultValue = v.getDefaultValue();
                out.fields.put(k, f);
            });
        }
        return out;
    }

    // ------------------------------------------------------------------------
    // Generic builder (fallback)
    // ------------------------------------------------------------------------

    static final class GenericMappingBuilder {
        private final ObjectMapper om;

        GenericMappingBuilder(ObjectMapper om) {
            this.om = om;
        }

        MappingSpec buildFromSample(String sampleJson) throws Exception {
            JsonNode root = om.readTree(sampleJson);
            ItemsAnchor anchor = detectItemsPath(root, null);

            SourceSpec source = new SourceSpec();
            source.setFormat("json");
            source.setItemsPath(anchor.itemsPath);
            source.setRoot(null);
            source.setRootAlternatives(List.of(anchor.itemsPath));

            Map<String, FieldSpec> fields = new TreeMap<>();
            JsonNode firstItem = firstItemNode(root, anchor.itemsPath);
            if (firstItem == null) firstItem = root;
            collectItemFields(firstItem, "@", anchor.itemsPath, fields);

            MappingSpec spec = new MappingSpec();
            spec.setSource(source);
            spec.setFields(fields);
            spec.setGlobals(new LinkedHashMap<>()); // par defaut vide
            return spec;
        }

        ItemsAnchor detectItemsPath(JsonNode root, String itemsPathHint) {
            if (itemsPathHint != null && !itemsPathHint.isBlank()) {
                String p = sanitizeItemsPath(itemsPathHint, itemsPathHint);
                NodePath frame = findArrayFrameForItemsPath(root, p);
                if (frame != null) return new ItemsAnchor(p, frame);
            }
            List<NodePath> arrays = new ArrayList<>();
            walk(root, "$", null, arrays);

            NodePath best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            String bestItems = "$";
            for (NodePath frame : arrays) {
                JsonNode arr = frame.node;
                if (!arr.isArray() || arr.size() == 0) continue;

                int objCount = 0;
                int scalarLeaves = 0;
                for (JsonNode el : arr) {
                    if (el.isObject()) {
                        objCount++;
                        scalarLeaves += countScalarLeaves(el);
                    } else if (el.isArray()) {
                        scalarLeaves += Math.max(1, countScalarLeaves(el));
                    } else {
                        scalarLeaves++;
                    }
                }
                double objRatio = (double) objCount / Math.max(1.0, arr.size());
                double avgLeaves = scalarLeaves / Math.max(1.0, arr.size());
                int depth = countDots(frame.absPath);
                double score = objRatio * 2.0 + avgLeaves * 0.2 - depth * 0.1;

                if (score > bestScore) {
                    bestScore = score;
                    best = frame;
                    bestItems = frame.absPath + "[*]";
                }
            }
            if (best == null) {
                if (root.isArray()) return new ItemsAnchor("$[*]", new NodePath(root, "$", null));
                return new ItemsAnchor("$", new NodePath(root, "$", null));
            }
            return new ItemsAnchor(bestItems, best);
        }

        private NodePath findArrayFrameForItemsPath(JsonNode root, String itemsPath) {
            String framePath = itemsPath;
            int idx = itemsPath.indexOf("[*]");
            if (idx >= 0) framePath = itemsPath.substring(0, idx);
            else {
                int lastDot = itemsPath.lastIndexOf('.');
                if (lastDot > 1) framePath = itemsPath.substring(0, lastDot);
            }
            List<JsonNode> nodes = resolveAbsolute(root, framePath);
            if (!nodes.isEmpty()) {
                return new NodePath(nodes.get(0), framePath, parentOf(root, framePath));
            }
            return null;
        }

        private NodePath parentOf(JsonNode root, String absPath) {
            int lastDot = absPath.lastIndexOf('.');
            if (lastDot <= 1) return new NodePath(root, "$", null);
            String parent = absPath.substring(0, lastDot);
            List<JsonNode> nodes = resolveAbsolute(root, parent);
            if (nodes.isEmpty()) return new NodePath(root, "$", null);
            return new NodePath(nodes.get(0), parent, parentOf(root, parent));
        }

        private List<JsonNode> resolveAbsolute(JsonNode root, String absPath) {
            if (!absPath.startsWith("$")) return List.of();
            String body = absPath.startsWith("$.") ? absPath.substring(2) : absPath.substring(1);
            List<String> tokens = tokenize(body);
            List<JsonNode> cur = List.of(root);
            for (String t : tokens) cur = step(cur, t);
            return cur;
        }

        private void walk(JsonNode node, String path, NodePath parent, List<NodePath> arrays) {
            NodePath frame = new NodePath(node, path, parent);
            if (node.isArray()) arrays.add(frame);
            if (node.isObject()) {
                node.fieldNames().forEachRemaining(fn -> walk(node.get(fn), path + "." + fn, frame, arrays));
            } else if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) walk(node.get(i), path + "[" + i + "]", frame, arrays);
            }
        }

        private int countDots(String p) {
            int c = 0;
            for (char ch : p.toCharArray()) if (ch == '.') c++;
            return c;
        }

        private int countScalarLeaves(JsonNode n) {
            if (n == null || n.isNull()) return 0;
            if (n.isValueNode()) return 1;
            final AtomicInteger sum = new AtomicInteger();
            if (n.isObject()) n.fields().forEachRemaining(e -> sum.addAndGet(countScalarLeaves(e.getValue())));
            else if (n.isArray()) for (JsonNode el : n) sum.addAndGet(countScalarLeaves(el));
            return sum.get();
        }

        private JsonNode firstItemNode(JsonNode root, String itemsPath) {
            List<String> tokens = tokenize(itemsPath.startsWith("$.") ? itemsPath.substring(2) : itemsPath.substring(1));
            List<JsonNode> cur = List.of(root);
            for (String t : tokens) cur = step(cur, t);
            return cur.isEmpty() ? null : cur.get(0);
        }

        private void collectItemFields(JsonNode node, String relPath, String itemsPath, Map<String, FieldSpec> out) {
            if (node == null || node.isNull()) return;

            if (node.isValueNode()) {
                String name = toSnake(relPath);
                FieldSpec f = new FieldSpec();
                f.setTo(guessType(node));
                f.setRequired(true);
                f.setCandidates(List.of(toRelJsonPath(relPath), toAbsFromItems(itemsPath, relPath)));
                out.put(name, f);
                return;
            }

            if (node.isObject()) {
                node.fieldNames().forEachRemaining(fn ->
                                                           collectItemFields(node.get(fn), relPath + "." + fn, itemsPath, out)
                                                  );
            } else if (node.isArray()) {
                List<JsonNode> els = new ArrayList<>();
                node.forEach(els::add);
                boolean allScalar = els.stream().allMatch(JsonNode::isValueNode);
                if (allScalar) {
                    String name = toSnake(relPath);
                    FieldSpec f = new FieldSpec();
                    f.setTo("array<" + narrowestArrayType(els) + ">");
                    f.setRequired(true);
                    f.setCandidates(List.of(toRelJsonPath(relPath + "[*]"), toAbsFromItems(itemsPath, relPath + "[*]")));
                    out.put(name, f);
                } else {
                    collectArrayChildren(els, relPath, itemsPath, out);
                }
            }
        }

        private String narrowestArrayType(List<JsonNode> nodes) {
            if (nodes == null || nodes.isEmpty()) return "string";
            boolean allBool = true, allInt = true, allLongLike = true, allNumber = true;
            for (JsonNode n : nodes) {
                if (n == null || n.isNull()) continue;
                if (!n.isBoolean()) allBool = false;
                if (!n.isInt()) allInt = false;
                if (!(n.isIntegralNumber() && !n.canConvertToInt()) && !n.isLong()) {
                    if (!n.isInt()) allLongLike = false;
                }
                if (!n.isNumber()) allNumber = false;
            }
            if (allBool) return "boolean";
            if (allInt) return "integer";
            if (allLongLike) return "long";
            if (allNumber) return "number";
            return "string";
        }

        private void collectArrayChildren(List<JsonNode> els, String relPath, String itemsPath, Map<String, FieldSpec> out) {
            JsonNode probe = null;
            for (JsonNode e : els) {
                if (e != null && !e.isNull()) {
                    probe = e;
                    break;
                }
            }
            if (probe == null) return;

            if (probe.isObject()) {
                probe.fieldNames().forEachRemaining(fn -> {
                    String childRel = relPath + "[*]." + fn;
                    collectArrayObjectField(els, fn, childRel, itemsPath, out);
                });
            } else if (probe.isArray()) {
                collectItemFields(probe, relPath + "[*]", itemsPath, out);
            }
        }

        private void collectArrayObjectField(List<JsonNode> els, String key, String relBase, String itemsPath, Map<String, FieldSpec> out) {
            List<JsonNode> children = new ArrayList<>();
            for (JsonNode e : els) if (e != null && e.has(key)) children.add(e.get(key));
            if (children.isEmpty()) return;

            boolean allScalar = children.stream().allMatch(JsonNode::isValueNode);
            if (allScalar) {
                String name = toSnake(relBase);
                FieldSpec f = new FieldSpec();
                f.setTo("array<" + narrowestArrayType(children) + ">");
                f.setRequired(true);
                f.setCandidates(List.of(toRelJsonPath(relBase), toAbsFromItems(itemsPath, relBase)));
                out.put(name, f);
                return;
            }

            boolean allArrayScalar = children.stream().allMatch(n -> n.isArray() && arrayAllScalar(n));
            if (allArrayScalar) {
                String name = toSnake(relBase);
                FieldSpec f = new FieldSpec();
                f.setTo("array<" + narrowestArrayType(flatten(children)) + ">");
                f.setRequired(true);
                f.setCandidates(List.of(toRelJsonPath(relBase + "[*]"), toAbsFromItems(itemsPath, relBase + "[*]")));
                out.put(name, f);
                return;
            }

            JsonNode probe = null;
            for (JsonNode c : children) {
                if (c != null && !c.isNull()) {
                    probe = c;
                    break;
                }
            }
            if (probe != null) collectItemFields(probe, relBase, itemsPath, out);
        }

        private boolean arrayAllScalar(JsonNode n) {
            for (JsonNode el : n) if (!el.isValueNode()) return false;
            return true;
        }

        private List<JsonNode> flatten(List<JsonNode> arrays) {
            List<JsonNode> out = new ArrayList<>();
            for (JsonNode a : arrays) for (JsonNode el : a) out.add(el);
            return out;
        }

        private String toRelJsonPath(String rel) {
            String tail = rel.replaceFirst("^@\\.?", "");
            return tail.isEmpty() ? "@" : "@." + tail;
        }

        private String toAbsFromItems(String itemsPath, String rel) {
            String tail = rel.replaceFirst("^@\\.?", "");
            if (!tail.isEmpty() && !tail.startsWith(".")) tail = "." + tail;
            return itemsPath + tail;
        }

        private String toSnake(String relPath) {
            String t = relPath.replaceAll("^@\\.?", "")
                              .replaceAll("^\\$\\.?", "")
                              .replaceAll("\\[\\*\\]", "")
                              .replaceAll("\\[(\\d+)\\]", "")
                              .replace('.', '_');
            t = t.replaceAll("__+", "_");
            if (t.isEmpty()) t = "value";
            return t.toLowerCase(Locale.ROOT);
        }

        private String guessType(JsonNode n) {
            if (n.isTextual()) return "string";
            if (n.isBoolean()) return "boolean";
            if (n.isInt()) return "integer";
            if (n.isLong()) return "long";
            if (n.isNumber()) return "number";
            return "string";
        }

        private List<String> tokenize(String body) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            int b = 0;
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '[') b++;
                if (c == ']') b--;
                if (c == '.' && b == 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else cur.append(c);
            }
            if (cur.length() > 0) out.add(cur.toString());
            return out;
        }

        private List<JsonNode> step(List<JsonNode> bases, String token) {
            List<JsonNode> next = new ArrayList<>();
            String field = token;
            String bracket = null;
            int bi = token.indexOf('[');
            if (bi >= 0) {
                field = token.substring(0, bi);
                bracket = token.substring(bi + 1, token.length() - 1);
            }
            for (JsonNode base : bases) {
                JsonNode target = base;
                if (!field.isEmpty()) {
                    target = base.get(field);
                    if (target == null) continue;
                }
                if (bracket == null) {
                    if (target.isArray()) for (JsonNode el : target) next.add(el);
                    else next.add(target);
                } else {
                    if (!target.isArray()) continue;
                    if ("*".equals(bracket)) {
                        for (JsonNode el : target) next.add(el);
                    } else {
                        try {
                            int idx = Integer.parseInt(bracket);
                            if (idx >= 0 && idx < target.size()) next.add(target.get(idx));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            return next;
        }

        static final class NodePath {
            final JsonNode node;
            final String absPath;
            final NodePath parent;

            NodePath(JsonNode n, String p, NodePath parent) {
                this.node = n;
                this.absPath = p;
                this.parent = parent;
            }
        }

        static final class ItemsAnchor {
            final String itemsPath;
            final NodePath arrayFrame;

            ItemsAnchor(String p, NodePath f) {
                this.itemsPath = p;
                this.arrayFrame = f;
            }
        }
    }

    // ------------------------------------------------------------------------
    // DTO
    // ------------------------------------------------------------------------

    public static class MappingResolutionResult {
        private boolean success;
        private String error;
        private SourceFormat sourceFormat;
        private MappingSpec spec;
        /**
         * Un objet (map) : globaux a la racine + "fields" : List<Map<...>>
         */
        private Map<String, Object> preview;
        private int itemCount;
        private List<String> unresolvedFields;
        private boolean fromCache;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public SourceFormat getSourceFormat() {
            return sourceFormat;
        }

        public void setSourceFormat(SourceFormat sourceFormat) {
            this.sourceFormat = sourceFormat;
        }

        public MappingSpec getSpec() {
            return spec;
        }

        public void setSpec(MappingSpec spec) {
            this.spec = spec;
        }

        public Map<String, Object> getPreview() {
            return preview == null ? Collections.emptyMap() : preview;
        }

        public void setPreview(Map<String, Object> preview) {
            this.preview = preview;
        }

        public int getItemCount() {
            return itemCount;
        }

        public void setItemCount(int itemCount) {
            this.itemCount = itemCount;
        }

        public List<String> getUnresolvedFields() {
            return unresolvedFields == null ? Collections.emptyList() : unresolvedFields;
        }

        public void setUnresolvedFields(List<String> unresolvedFields) {
            this.unresolvedFields = unresolvedFields;
        }

        public boolean isFromCache() {
            return fromCache;
        }

        public void setFromCache(boolean fromCache) {
            this.fromCache = fromCache;
        }
    }
}
