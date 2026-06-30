package com.apimarketplace.catalog.mapping.adapter;

import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import com.apimarketplace.catalog.mapping.util.PathResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Adaptateur JSON generique pour l'engin de mapping strict.
 * - Chemins relatifs (@.) et absolus ($.)
 * - Tableaux (index et wildcards), recursif $..field
 * - Aucune logique metier specifique au format/source
 */
@Component
public class JsonAdapter implements SourceAdapter {
    private static final ThreadLocal<JsonNode> DOC_ROOT = new ThreadLocal<>();
    private static final Logger logger = LoggerFactory.getLogger(JsonAdapter.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean isCollection(SourceSpec sourceSpec, byte[] input) {
        try {
            JsonNode root = getRootNode(input);
            DOC_ROOT.set(root); // NEW
            String rootPath = sourceSpec.getRoot();

            if (rootPath == null || rootPath.trim().isEmpty()) {
                return root.isArray();
            }

            Object target = evaluateJsonPath(root, rootPath);
            return target instanceof JsonNode && ((JsonNode) target).isArray();
        } catch (Exception e) {
            logger.debug("Error checking if JSON is collection: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Iterable<?> iterateItems(SourceSpec sourceSpec, byte[] input) {
        try {
            JsonNode root = getRootNode(input);
            DOC_ROOT.set(root); // <-- memorise la racine pour evalScalar
            boolean strictMode = sourceSpec.getStrictMode() != null ? sourceSpec.getStrictMode() : true;

            String itemsPath = sourceSpec.getItemsPath();
            String rootPath = sourceSpec.getRoot();
            List<String> rootAlternatives = sourceSpec.getRootAlternatives();

            String pathToUse = null;

            if (itemsPath != null && !itemsPath.trim().isEmpty()) {
                pathToUse = itemsPath;
            } else if (rootPath != null && !rootPath.trim().isEmpty()) {
                pathToUse = rootPath;
            } else if (rootAlternatives != null && !rootAlternatives.isEmpty()) {
                for (String alt : rootAlternatives) {
                    if (alt == null || alt.trim().isEmpty()) continue;
                    try {
                        List<Object> test = new ArrayList<>();
                        collectItemsWithStrictPath(root, alt, test, strictMode);
                        if (!test.isEmpty()) {
                            pathToUse = alt;
                            break;
                        }
                    } catch (Exception ignore) { /* try next */ }
                }
            }

            if (pathToUse == null || pathToUse.trim().isEmpty()) {
                if (root.isArray()) return root;
                return Collections.singletonList(root);
            }

            List<Object> allItems = new ArrayList<>();
            collectItemsWithStrictPath(root, pathToUse, allItems, strictMode);
            return allItems;
        } catch (Exception e) {
            logger.debug("Error iterating JSON items: {}", e.getMessage());
            return Collections.emptyList();
        } finally {
            DOC_ROOT.remove();
        }
    }

    /**
     * evalue un chemin JSON et retourne une valeur scalaire si possible.
     */
    @Override
    public Object evalScalar(Object context, String candidate) {
        try {
            if (!(context instanceof final JsonNode node)) return null;
            boolean strictMode = true;

            // *** NOUVEAU : chemins absolus -> on part de la racine du document ***
            if (candidate != null) {
                String c = candidate.trim();
                if (c.startsWith("$")) {
                    JsonNode docRoot = DOC_ROOT.get();
                    if (docRoot != null) {
                        try {
                            Stack<JsonNode> parentStack = new Stack<>();
                            JsonNode result = PathResolver.evaluatePath(docRoot, c, parentStack, strictMode);
                            return extractPrimitiveValue(result);
                        } catch (IllegalArgumentException eAbs) {
                            // fallback non-strict
                            try {
                                Stack<JsonNode> parentStack = new Stack<>();
                                JsonNode result = PathResolver.evaluatePath(docRoot, c, parentStack, false);
                                return extractPrimitiveValue(result);
                            } catch (Exception e2) {
                                logger.debug("evalScalar abs-root failed for path '{}': {}", c, e2.getMessage());
                                // on continue sur l’eval relative classique juste en dessous
                            }
                        }
                    }
                }
            }

            // *** Comportement existant : evaluation relative depuis l’item ***
            try {
                Stack<JsonNode> parentStack = new Stack<>();
                JsonNode result = PathResolver.evaluatePath(node, candidate, parentStack, strictMode);
                return extractPrimitiveValue(result);
            } catch (IllegalArgumentException e) {
                try {
                    Stack<JsonNode> parentStack = new Stack<>();
                    JsonNode result = PathResolver.evaluatePath(node, candidate, parentStack, false);
                    return extractPrimitiveValue(result);
                } catch (Exception e2) {
                    logger.debug("evalScalar failed for path '{}': {}", candidate, e2.getMessage());
                    return null;
                }
            }
        } catch (Exception e) {
            logger.debug("Error evaluating JSON scalar: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Iterable<?> evalNodes(Object context, String pathAnyOf) {
        try {
            if (context instanceof final JsonNode node) {
                Object result = evaluateJsonPath(node, pathAnyOf);
                if (result instanceof List) return (List<?>) result;
                if (result instanceof JsonNode && ((JsonNode) result).isArray()) return (JsonNode) result;
                return Collections.singletonList(result);
            }
            return Collections.emptyList();
        } catch (Exception e) {
            logger.debug("Error evaluating JSON nodes: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public Map<String, Object> flatten(byte[] input) {
        Map<String, Object> flattened = new HashMap<>();
        try {
            JsonNode root = getRootNode(input);
            flattenNode(root, "", flattened);
        } catch (Exception e) {
            logger.debug("Error flattening JSON: {}", e.getMessage());
        }
        return flattened;
    }

    @Override
    public Object getRoot(byte[] input) {
        try {
            return getRootNode(input);
        } catch (Exception e) {
            logger.debug("Error getting JSON root: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode getRootNode(byte[] input) throws Exception {
        String content = new String(input, StandardCharsets.UTF_8);
        return objectMapper.readTree(content);
    }

    /**
     * Collecte les items selon un chemin strict resolu par PathResolver.
     */
    private void collectItemsWithStrictPath(JsonNode node, String path, List<Object> results, boolean strictMode) {
        if (path == null || path.trim().isEmpty()) {
            results.add(node);
            return;
        }

        try {
            List<PathResolver.PathSegment> segments = PathResolver.parsePath(path, strictMode);
            collectItemsWithSegments(node, segments, 0, results);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid path '{}' in strict mode: {}, trying non-strict mode", path, e.getMessage());
            try {
                List<PathResolver.PathSegment> segments = PathResolver.parsePath(path, false);
                collectItemsWithSegments(node, segments, 0, results);
            } catch (Exception e2) {
                logger.warn("Both strict and non-strict modes failed for path '{}': {}, using legacy", path, e2.getMessage());
                collectItemsRecursivelyLegacy(node, path, results);
            }
        }
    }

    private void collectItemsWithSegments(JsonNode node, List<PathResolver.PathSegment> segments, int i, List<Object> results) {
        if (i >= segments.size()) {
            results.add(node);
            return;
        }

        PathResolver.PathSegment seg = segments.get(i);
        switch (seg.getType()) {
            case FIELD: {
                JsonNode fieldNode = node.get(seg.getFieldName());
                if (fieldNode != null) collectItemsWithSegments(fieldNode, segments, i + 1, results);
                break;
            }
            case FIELD_INDEX: {
                JsonNode arrayNode = node.get(seg.getFieldName());
                if (arrayNode != null && arrayNode.isArray()) {
                    int index = seg.getIndex();
                    if (index >= 0 && index < arrayNode.size()) {
                        collectItemsWithSegments(arrayNode.get(index), segments, i + 1, results);
                    }
                }
                break;
            }
            case FIELD_WILDCARD: {
                if (seg.getFieldName() == null) {
                    if (node.isArray()) {
                        for (JsonNode el : node) collectItemsWithSegments(el, segments, i + 1, results);
                    }
                } else {
                    JsonNode wildcardNode = node.get(seg.getFieldName());
                    if (wildcardNode != null && wildcardNode.isArray()) {
                        for (JsonNode el : wildcardNode) collectItemsWithSegments(el, segments, i + 1, results);
                    } else if (wildcardNode != null) {
                        collectItemsWithSegments(wildcardNode, segments, i + 1, results);
                    }
                }
                break;
            }
            case PARENT_NAVIGATION: {
                // Non supporte pour l'instant (necessite une pile d'ancetres).
                logger.warn("Parent navigation (^^) not implemented in collectItemsWithSegments");
                break;
            }
        }
    }

    /**
     * Fallback legacy simple pour collecter des items si la resolution stricte echoue.
     */
    private void collectItemsRecursivelyLegacy(JsonNode node, String path, List<Object> results) {
        if (path == null || path.trim().isEmpty()) {
            results.add(node);
            return;
        }

        if (path.startsWith("$.")) path = path.substring(2);
        else if (path.startsWith("$")) path = path.substring(1);

        String[] parts = path.split("\\.");
        collectItemsRecursive(node, parts, 0, results);
    }

    /**
     * evalue un chemin JSON (absolu/relatif, tableaux, recursif simple).
     */
    private Object evaluateJsonPath(JsonNode node, String path) {
        if (path == null || path.trim().isEmpty()) return node;

        if (path.startsWith("$")) {
            JsonNode root = DOC_ROOT.get();
            if (root != null) {
                node = root; // repartir de la racine
            }
        }

        // Relatif @.
        if (path.startsWith("@.")) path = path.substring(2);

        // Absolu $. (on evalue depuis node courant)
        if (path.startsWith("$.")) path = path.substring(2);

        // Recursif $..field
        if (path.startsWith("$..")) {
            String fieldName = path.substring(3);
            return findRecursive(node, fieldName);
        }

        // Wildcards de base
        if (path.equals("$[*]") || path.equals("*")) {
            if (node.isArray()) return node;
            return Collections.singletonList(node);
        }

        // $[index] et $[*]
        if (path.startsWith("$[")) {
            String indexStr = path.substring(2, path.length() - 1);
            if (indexStr.equals("*")) return node.isArray() ? node : Collections.singletonList(node);
            try {
                int index = Integer.parseInt(indexStr);
                if (node.isArray() && index >= 0 && index < node.size()) return node.get(index);
            } catch (NumberFormatException ignored) { }
            return null;
        }

        String[] parts = path.split("\\.");
        JsonNode current = node;

        for (String part : parts) {
            if (current == null) return null;

            if (part.contains("[")) {
                String fieldName = part.substring(0, part.indexOf("["));
                String indexStr = part.substring(part.indexOf("[") + 1, part.indexOf("]"));

                if (!fieldName.isEmpty()) current = current.get(fieldName);

                if (current != null && current.isArray()) {
                    if (indexStr.equals("*")) {
                        return current; // on retourne l'array pour usage en amont
                    }
                    try {
                        int index = Integer.parseInt(indexStr);
                        if (index >= 0 && index < current.size()) {
                            current = current.get(index);
                        } else {
                            return null;
                        }
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else {
                    return null;
                }
            } else {
                current = current.get(part);
            }
        }
        return current;
    }

    /**
     * Recherche recursive par nom de champ.
     */
    private List<Object> findRecursive(JsonNode node, String fieldName) {
        List<Object> results = new ArrayList<>();
        if (node.isObject()) {
            if (node.has(fieldName)) results.add(node.get(fieldName));
            node.fieldNames().forEachRemaining(f -> results.addAll(findRecursive(node.get(f), fieldName)));
        } else if (node.isArray()) {
            for (JsonNode child : node) results.addAll(findRecursive(child, fieldName));
        }
        return results;
    }

    /**
     * Extrait une valeur primitive d’un JsonNode (string/number/boolean/null).
     * Pour objets/tableaux -> null (pas de serialisation brute).
     */
    private Object extractPrimitiveValue(Object value) {
        if (value == null) return null;
        if (!(value instanceof final JsonNode node)) return value;

        if (node.isTextual()) return node.asText();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isNumber()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNull()) return null;

        // Objets/arrays -> pas de primitive : indiquer null
        return null;
    }

    private void collectItemsRecursive(JsonNode node, String[] parts, int idx, List<Object> results) {
        if (idx >= parts.length) {
            results.add(node);
            return;
        }
        String part = parts[idx];

        if (part.contains("[")) {
            String fieldName = part.substring(0, part.indexOf("["));
            String indexStr = part.substring(part.indexOf("[") + 1, part.indexOf("]"));

            JsonNode target = node;
            if (!fieldName.isEmpty()) target = node.get(fieldName);

            if (target != null && target.isArray()) {
                if (indexStr.equals("*")) {
                    for (JsonNode el : target) collectItemsRecursive(el, parts, idx + 1, results);
                } else {
                    try {
                        int index = Integer.parseInt(indexStr);
                        if (index >= 0 && index < target.size()) {
                            collectItemsRecursive(target.get(index), parts, idx + 1, results);
                        }
                    } catch (NumberFormatException ignored) { }
                }
            }
        } else {
            JsonNode child = node.get(part);
            if (child != null) {
                if (child.isArray()) {
                    for (JsonNode el : child) collectItemsRecursive(el, parts, idx + 1, results);
                } else {
                    collectItemsRecursive(child, parts, idx + 1, results);
                }
            }
        }
    }

    private void flattenNode(JsonNode node, String path, Map<String, Object> flattened) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(entry -> {
                String newPath = path.isEmpty() ? entry : path + "." + entry;
                flattenNode(node.get(entry), newPath, flattened);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String newPath = path + "[" + i + "]";
                flattenNode(node.get(i), newPath, flattened);
            }
        } else {
            if (node.isTextual()) flattened.put(path, node.asText());
            else if (node.isInt()) flattened.put(path, node.asInt());
            else if (node.isLong()) flattened.put(path, node.asLong());
            else if (node.isNumber()) flattened.put(path, node.asDouble());
            else if (node.isBoolean()) flattened.put(path, node.asBoolean());
            else if (node.isNull()) flattened.put(path, null);
            else flattened.put(path, null);
        }
    }
}
