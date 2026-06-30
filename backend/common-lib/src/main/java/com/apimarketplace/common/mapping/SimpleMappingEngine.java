package com.apimarketplace.common.mapping;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;

/**
 * Moteur de mapping simple et robuste qui peut gerer tous les cas d'usage.
 * Utilise une approche plus directe avec Jackson pour la navigation JSON.
 */
public class SimpleMappingEngine {

    private static final ObjectMapper objectMapper = new ObjectMapper(new JsonFactory());

    public static MappingOutcome apply(String rawJson, String mappingJson) throws IOException {
        StrictMappingEngine.StrictMappingSpec spec = objectMapper.readValue(mappingJson, StrictMappingEngine.StrictMappingSpec.class);
        JsonNode root = objectMapper.readTree(rawJson);

        // Collecter les items selon items_path ou alternatives
        List<JsonNode> items = collectItems(root, spec.source);

        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> unresolvedFields = new LinkedHashSet<>();

        for (JsonNode item : items) {
            Map<String, Object> result = new LinkedHashMap<>();
            List<String> itemUnresolved = new ArrayList<>();

            for (Map.Entry<String, StrictMappingEngine.FieldSpec> entry : spec.fields.entrySet()) {
                String fieldName = entry.getKey();
                StrictMappingEngine.FieldSpec fieldSpec = entry.getValue();

                Object value = resolveField(item, root, fieldSpec);
                if (value == null) {
                    if (fieldSpec.defaultValue != null) {
                        value = fieldSpec.defaultValue;
                    }
                }
                if (value == null && Boolean.TRUE.equals(fieldSpec.required)) {
                    itemUnresolved.add(fieldName);
                } else if (value != null) {
                    result.put(fieldName, value);
                }
            }

            if (!itemUnresolved.isEmpty()) {
                unresolvedFields.addAll(itemUnresolved);
            }
            results.add(result);
        }

        MappingOutcome outcome = new MappingOutcome();
        outcome.items = results;
        outcome.itemCount = items.size();
        outcome.unresolvedFields = new ArrayList<>(unresolvedFields);
        return outcome;
    }

    private static List<JsonNode> collectItems(JsonNode root, StrictMappingEngine.SourceSpec source) {
        List<JsonNode> items = new ArrayList<>();

        // Essayer items_path d'abord
        if (source.items_path != null && !source.items_path.trim().isEmpty()) {
            items = evaluatePath(root, source.items_path);
            if (!items.isEmpty()) {
                return items;
            }
        }

        // Essayer root_alternatives
        if (source.root_alternatives != null) {
            for (String alt : source.root_alternatives) {
                items = evaluatePath(root, alt);
                if (!items.isEmpty()) {
                    return items;
                }
            }
        }

        // Fallback sur root
        if (source.root != null && !source.root.trim().isEmpty()) {
            items = evaluatePath(root, source.root);
            if (!items.isEmpty()) {
                return items;
            }
        }

        // Dernier recours : si root est un array, chaque element est un item
        if (root.isArray()) {
            for (JsonNode item : root) {
                items.add(item);
            }
        } else {
            items.add(root);
        }

        return items;
    }

    private static List<JsonNode> evaluatePath(JsonNode root, String path) {
        if (path == null || path.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // Clean the path
        String cleanPath = path.trim();
        if (cleanPath.startsWith("$.")) {
            cleanPath = cleanPath.substring(2);
        }

        List<JsonNode> current = Collections.singletonList(root);

        String[] tokens = tokenizePath(cleanPath);
        for (String token : tokens) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                next.addAll(evaluateToken(node, token));
            }
            current = next;
            if (current.isEmpty()) break;
        }

        return current;
    }

    private static String[] tokenizePath(String path) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;

        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '[') bracketDepth++;
            if (c == ']') bracketDepth--;
            
            if (c == '.' && bracketDepth == 0) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        
        return tokens.toArray(new String[0]);
    }

    private static List<JsonNode> evaluateToken(JsonNode node, String token) {
        if (node == null || node.isNull()) {
            return Collections.emptyList();
        }

        // Gerer les indices d'array
        if (token.contains("[")) {
            int bracketIndex = token.indexOf('[');
            String fieldName = token.substring(0, bracketIndex);
            String bracketContent = token.substring(bracketIndex + 1, token.length() - 1);

            JsonNode target = node.get(fieldName);
            if (target == null || !target.isArray()) {
                return Collections.emptyList();
            }

            if ("*".equals(bracketContent)) {
                List<JsonNode> results = new ArrayList<>();
                for (JsonNode item : target) {
                    results.add(item);
                }
                return results;
            } else {
                try {
                    int index = Integer.parseInt(bracketContent);
                    if (index >= 0 && index < target.size()) {
                        return Collections.singletonList(target.get(index));
                    }
                } catch (NumberFormatException e) {
                    // Ignore invalid index
                }
                return Collections.emptyList();
            }
        } else {
            // Champ simple
            JsonNode target = node.get(token);
            if (target != null) {
                return Collections.singletonList(target);
            }
        }

        return Collections.emptyList();
    }

    private static Object resolveField(JsonNode item, JsonNode root, StrictMappingEngine.FieldSpec fieldSpec) {
        if (fieldSpec.candidates == null || fieldSpec.candidates.isEmpty()) {
            return null;
        }

        // Gerer les arrays
        boolean wantArray = fieldSpec.to != null && fieldSpec.to.startsWith("array<");
        if (wantArray) {
            List<Object> results = new ArrayList<>();
            for (String candidate : fieldSpec.candidates) {
                Object value = evaluateFieldPath(item, root, candidate, fieldSpec.to);
                if (value instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> listValue = (List<Object>) value;
                    results.addAll(listValue);
                } else if (value != null) {
                    results.add(value);
                }
            }
            return results.isEmpty() ? null : results;
        } else {
            // Gerer les scalaires
            for (String candidate : fieldSpec.candidates) {
                Object value = evaluateFieldPath(item, root, candidate, fieldSpec.to);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
    }

    private static Object evaluateFieldPath(JsonNode item, JsonNode root, String path, String targetType) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }

        JsonNode context;
        String cleanPath;

        if (path.startsWith("^^.")) {
            // Chemin ascendant - remonter dans la hierarchie
            cleanPath = path.substring(3);
            context = findAscendantContext(item, root);
        } else if (path.startsWith("@.")) {
            // Relative path - use the current item
            cleanPath = path.substring(2);
            context = item;
        } else if (path.startsWith("$.")) {
            // Absolute path - use the root
            cleanPath = path.substring(2);
            context = root;
        } else {
            // Par defaut, traiter comme relatif
            cleanPath = path;
            context = item;
        }

        if (context == null) {
            return null;
        }

        // Utiliser une approche plus robuste pour les chemins complexes
        List<JsonNode> results = evaluateComplexPath(context, cleanPath);
        
        if (results.isEmpty()) {
            return null;
        }

        // Convertir selon le type cible
        if (targetType != null && targetType.startsWith("array<")) {
            Object arrayResult = convertToArray(results, targetType);
            return arrayResult;
        } else {
            JsonNode first = results.get(0);
            Object scalarResult = convertToScalar(first, targetType);
            return scalarResult;
        }
    }

    private static List<JsonNode> evaluateComplexPath(JsonNode context, String path) {
        if (path == null || path.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // Clean the path
        String cleanPath = path.trim();
        if (cleanPath.startsWith("$.")) {
            cleanPath = cleanPath.substring(2);
        }

        List<JsonNode> current = Collections.singletonList(context);

        // Diviser le chemin en segments en gerant les crochets
        List<String> segments = splitPathSegments(cleanPath);
        
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            
            List<JsonNode> next = new ArrayList<>();
            for (int j = 0; j < current.size(); j++) {
                JsonNode node = current.get(j);
                List<JsonNode> segmentResults = evaluateSegment(node, segment);
                next.addAll(segmentResults);
            }
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }

        return current;
    }

    private static List<String> splitPathSegments(String path) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;

        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '[') bracketDepth++;
            if (c == ']') bracketDepth--;
            
            if (c == '.' && bracketDepth == 0) {
                if (current.length() > 0) {
                    segments.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        
        return segments;
    }

    private static List<JsonNode> evaluateSegment(JsonNode node, String segment) {
        
        if (node == null || node.isNull()) {
            return Collections.emptyList();
        }

        // Gerer les indices d'array
        if (segment.contains("[")) {
            int bracketIndex = segment.indexOf('[');
            String fieldName = segment.substring(0, bracketIndex);
            String bracketContent = segment.substring(bracketIndex + 1, segment.length() - 1);

            JsonNode target = node.get(fieldName);
            if (target == null || !target.isArray()) {
                return Collections.emptyList();
            }

            if ("*".equals(bracketContent)) {
                List<JsonNode> results = new ArrayList<>();
                for (JsonNode item : target) {
                    results.add(item);
                }
                return results;
            } else {
                try {
                    int index = Integer.parseInt(bracketContent);
                    if (index >= 0 && index < target.size()) {
                        JsonNode result = target.get(index);
                        return Collections.singletonList(result);
                    }
                } catch (NumberFormatException e) {
                }
                return Collections.emptyList();
            }
        } else {
            // Champ simple
            JsonNode target = node.get(segment);
            if (target != null) {
                return Collections.singletonList(target);
            }
        }

        return Collections.emptyList();
    }

    private static JsonNode findAscendantContext(JsonNode item, JsonNode root) {
        // Pour l'instant, retourner la racine comme contexte ascendant
        // In a real use case, a more sophisticated logic should be implemented
        return root;
    }

    private static Object convertToScalar(JsonNode node, String targetType) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (targetType == null) {
            targetType = "string";
        }

        switch (targetType.toLowerCase()) {
            case "string":
                return node.asText();
            case "integer":
                return node.isNumber() ? node.intValue() : null;
            case "long":
                return node.isNumber() ? node.longValue() : null;
            case "number":
                return node.isNumber() ? node.doubleValue() : null;
            case "boolean":
                return node.isBoolean() ? node.booleanValue() : null;
            default:
                return node.asText();
        }
    }

    private static Object convertToArray(List<JsonNode> nodes, String targetType) {
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptyList();
        }

        List<Object> results = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (node != null && !node.isNull()) {
                Object value = convertToScalar(node, targetType.replace("array<", "").replace(">", ""));
                if (value != null) {
                    results.add(value);
                }
            }
        }
        return results;
    }

    public static class MappingOutcome {
        public List<Map<String, Object>> items;
        public int itemCount;
        public List<String> unresolvedFields;
    }
}
