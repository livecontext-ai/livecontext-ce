package com.apimarketplace.catalog.mapping.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

/**
 * PathResolver pour parser et naviguer les chemins JSON de maniere stricte.
 *
 * Supporte :
 * - Chemins relatifs : @.field, @.field.subfield
 * - Chemins absolus : $.field, $.field.subfield
 * - Navigation parent : ^^.field (remonte d'un niveau), ^^^.field (remonte de 2 niveaux)
 * - Index d'array : field[0], field[*]
 * - Wildcards simples : field[*] (pas de $.. ni ?())
 *
 * En mode strict, interdit les patterns $.. et ?().
 */
public class PathResolver {

    private static final Logger logger = LoggerFactory.getLogger(PathResolver.class);

    /**
     * Parse un chemin JSON en segments pour navigation stricte.
     *
     * @param path Le chemin JSON (ex: "@.user.username", "^^.data.id", "field[0]")
     * @param strictMode Si true, interdit $.. et ?()
     * @return Liste des segments du chemin
     * @throws IllegalArgumentException Si le chemin contient des patterns interdits en mode strict
     */
    public static List<PathSegment> parsePath(String path, boolean strictMode) {
        if (path == null || path.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String trimmedPath = path.trim();

        // Verifications en mode strict
        if (strictMode) {
            if (trimmedPath.contains("$..")) {
                throw new IllegalArgumentException("Recursive search $.. not allowed in strict mode: " + path);
            }
            if (trimmedPath.contains("?(")) {
                throw new IllegalArgumentException("Filter expressions ?() not allowed in strict mode: " + path);
            }
        }

        List<PathSegment> segments = new ArrayList<>();

        // Detecter le type de chemin
        PathType pathType = detectPathType(trimmedPath);
        String workingPath = trimmedPath;

        // Clean the prefix according to type
        switch (pathType) {
            case RELATIVE:
                workingPath = trimmedPath.substring(2); // Remove @.
                break;
            case ABSOLUTE:
                workingPath = trimmedPath.substring(2); // Remove $.
                break;
            case PARENT_NAVIGATION:
                // Compter les ^ et extraire le chemin
                int parentLevels = 0;
                while (workingPath.startsWith("^")) {
                    parentLevels++;
                    workingPath = workingPath.substring(1);
                }
                if (workingPath.startsWith(".")) {
                    workingPath = workingPath.substring(1);
                }
                segments.add(new PathSegment(PathSegmentType.PARENT_NAVIGATION, null, parentLevels));
                break;
            case SIMPLE:
                // Pas de prefixe, chemin simple
                break;
        }

        // Gerer les chemins speciaux comme $[*] ou $[0]
        if (workingPath.equals("[*]")) {
            segments.add(new PathSegment(PathSegmentType.FIELD_WILDCARD, null, null));
            return segments;
        } else if (workingPath.matches("^\\[\\d+\\]$")) {
            int index = Integer.parseInt(workingPath.substring(1, workingPath.length() - 1));
            segments.add(new PathSegment(PathSegmentType.FIELD_INDEX, null, index));
            return segments;
        }

        // Parser les segments restants
        if (!workingPath.isEmpty()) {
            String[] parts = workingPath.split("\\.");
            for (String part : parts) {
                if (part.contains("[")) {
                    // Segment avec index d'array
                    String fieldName = part.substring(0, part.indexOf("["));
                    String indexStr = part.substring(part.indexOf("[") + 1, part.indexOf("]"));

                    if (indexStr.equals("*")) {
                        segments.add(new PathSegment(PathSegmentType.FIELD_WILDCARD, fieldName, null));
                    } else {
                        try {
                            int index = Integer.parseInt(indexStr);
                            segments.add(new PathSegment(PathSegmentType.FIELD_INDEX, fieldName, index));
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid array index: " + indexStr + " in path: " + path);
                        }
                    }
                } else {
                    // Segment de champ simple
                    segments.add(new PathSegment(PathSegmentType.FIELD, part, null));
                }
            }
        }

        logger.debug("Parsed path '{}' into {} segments: {}", path, segments.size(), segments);
        return segments;
    }

    /**
     * evalue un chemin sur un nœud JSON avec navigation parent supportee.
     *
     * @param node Le nœud JSON de depart
     * @param path Le chemin a evaluer
     * @param parentStack Pile des nœuds parents pour navigation ^^
     * @param strictMode Mode strict active
     * @return Le nœud resultant ou null si non trouve
     */
    public static JsonNode evaluatePath(JsonNode node, String path, Stack<JsonNode> parentStack, boolean strictMode) {
        if (node == null || path == null || path.trim().isEmpty()) {
            return node;
        }

        List<PathSegment> segments = parsePath(path, strictMode);
        JsonNode current = node;

        for (PathSegment segment : segments) {
            if (current == null) {
                logger.debug("Path evaluation stopped: current node is null at segment {}", segment);
                return null;
            }

            current = evaluateSegment(current, segment, parentStack);
            if (current == null) {
                logger.debug("Path evaluation stopped: segment {} returned null", segment);
                return null;
            }
        }

        return current;
    }

    /**
     * evalue un segment de chemin sur un nœud JSON.
     */
    private static JsonNode evaluateSegment(JsonNode node, PathSegment segment, Stack<JsonNode> parentStack) {
        switch (segment.getType()) {
            case PARENT_NAVIGATION:
                // Navigation vers le parent
                int levels = segment.getIndex() != null ? segment.getIndex() : 1;
                JsonNode parent = node;
                for (int i = 0; i < levels && !parentStack.isEmpty(); i++) {
                    parent = parentStack.pop();
                }
                logger.debug("Parent navigation: went up {} levels", levels);
                return parent;

            case FIELD:
                JsonNode fieldNode = node.get(segment.getFieldName());
                logger.debug("Field access: {} -> {}", segment.getFieldName(), fieldNode != null ? fieldNode.getNodeType() : "null");
                return fieldNode;

            case FIELD_INDEX:
                JsonNode arrayNode = node.get(segment.getFieldName());
                if (arrayNode != null && arrayNode.isArray()) {
                    int index = segment.getIndex();
                    if (index >= 0 && index < arrayNode.size()) {
                        JsonNode element = arrayNode.get(index);
                        logger.debug("Array index access: {}[{}] -> {}", segment.getFieldName(), index, element != null ? element.getNodeType() : "null");
                        return element;
                    }
                }
                logger.debug("Array index access failed: {}[{}] not found", segment.getFieldName(), segment.getIndex());
                return null;

            case FIELD_WILDCARD:
                if (segment.getFieldName() == null) {
                    // Wildcard sur la racine (ex: $[*])
                    if (node.isArray()) {
                        logger.debug("Root wildcard access: [*] -> array with {} elements", node.size());
                        return node; // Retourne l'array complet pour traitement ulterieur
                    }
                    logger.debug("Root wildcard access failed: not an array");
                    return null;
                } else {
                    // Wildcard sur un champ specifique (ex: items[*])
                    JsonNode wildcardNode = node.get(segment.getFieldName());
                    if (wildcardNode != null && wildcardNode.isArray()) {
                        logger.debug("Array wildcard access: {}[*] -> array with {} elements", segment.getFieldName(), wildcardNode.size());
                        return wildcardNode; // Retourne l'array complet pour traitement ulterieur
                    }
                    logger.debug("Array wildcard access failed: {}[*] not found or not array", segment.getFieldName());
                    return null;
                }

            default:
                logger.warn("Unknown path segment type: {}", segment.getType());
                return null;
        }
    }

    /**
     * Detecte le type de chemin base sur son prefixe.
     */
    private static PathType detectPathType(String path) {
        if (path.startsWith("@.")) {
            return PathType.RELATIVE;
        } else if (path.startsWith("$.")) {
            return PathType.ABSOLUTE;
        } else if (path.startsWith("^")) {
            return PathType.PARENT_NAVIGATION;
        } else {
            return PathType.SIMPLE;
        }
    }

    /**
     * Types de chemins supportes.
     */
    public enum PathType {
        RELATIVE,      // @.field
        ABSOLUTE,      // $.field
        PARENT_NAVIGATION, // ^^.field
        SIMPLE         // field (pas de prefixe)
    }

    /**
     * Types de segments de chemin.
     */
    public enum PathSegmentType {
        FIELD,              // field
        FIELD_INDEX,        // field[0]
        FIELD_WILDCARD,     // field[*]
        PARENT_NAVIGATION   // ^^ (remonte)
    }

    /**
     * Represente un segment de chemin parse.
     */
    public static class PathSegment {
        private final PathSegmentType type;
        private final String fieldName;
        private final Integer index; // Pour index d'array ou niveaux de parent

        public PathSegment(PathSegmentType type, String fieldName, Integer index) {
            this.type = type;
            this.fieldName = fieldName;
            this.index = index;
        }

        public PathSegmentType getType() { return type; }
        public String getFieldName() { return fieldName; }
        public Integer getIndex() { return index; }

        @Override
        public String toString() {
            switch (type) {
                case FIELD:
                    return fieldName;
                case FIELD_INDEX:
                    return fieldName + "[" + index + "]";
                case FIELD_WILDCARD:
                    return fieldName + "[*]";
                case PARENT_NAVIGATION:
                    return "^".repeat(index != null ? index : 1);
                default:
                    return "unknown";
            }
        }
    }
}
