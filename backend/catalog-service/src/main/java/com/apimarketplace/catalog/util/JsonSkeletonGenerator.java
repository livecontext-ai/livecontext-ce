package com.apimarketplace.catalog.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

@Component
public class JsonSkeletonGenerator {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(JsonSkeletonGenerator.class);
    
    // Limite de profondeur pour éviter la récursion infinie
    private static final int MAX_DEPTH = 50;
    
    // Limite du nombre de clés dans un objet pour éviter les performances dégradées
    private static final int MAX_OBJECT_KEYS = 10000;
    
    // Limite du nombre d'éléments dans un tableau
    private static final int MAX_ARRAY_ITEMS = 500;

    /**
     * Génère un skeleton JSON avec protection contre la récursion infinie
     */
    public JsonNode generateSkeleton(JsonNode root) {
        return generateSkeleton(root, 0);
    }

    /**
     * True when the skeleton carries no actionable shape - i.e. an empty object
     * {@code {"_t":"obj","props":{}}}, an empty array {@code {"_t":"arr","items":"empty"}},
     * a bare primitive type token, or null. Caller uses this to suppress
     * persisting useless skeletons that would otherwise stick (per-tool first-write-wins
     * cache) and block learning the real shape on a subsequent execution - the
     * Apify {@code /run-sync-get-dataset-items} endpoint is the canonical case:
     * the actor that ran first returned an empty result, so the skeleton became
     * {@code {}} and every later run with a different actor was silently skipped.
     */
    public boolean isTriviallyEmptySkeleton(JsonNode skeleton) {
        if (skeleton == null || skeleton.isNull() || skeleton.isMissingNode()) return true;
        // Bare primitive token (e.g. "string", "number") - no nested shape, useless as a learned schema.
        if (skeleton.isTextual()) return true;
        String type = skeleton.path("_t").asText("");
        if ("obj".equals(type)) {
            JsonNode props = skeleton.path("props");
            return !props.isObject() || props.size() == 0;
        }
        if ("arr".equals(type)) {
            JsonNode items = skeleton.path("items");
            if (items.isMissingNode() || items.isNull()) return true;
            if (items.isTextual() && "empty".equals(items.asText())) return true;
            // arr-of-trivial is also trivial (recursive check)
            return isTriviallyEmptySkeleton(items);
        }
        return false;
    }
    
    private JsonNode generateSkeleton(JsonNode root, int depth) {
        // Protection contre la récursion infinie
        if (depth >= MAX_DEPTH) {
            logger.warn("Profondeur maximale atteinte ({}) lors de la génération du skeleton", MAX_DEPTH);
            return new TextNode("max_depth_reached");
        }
        
        if (root == null) {
            return new TextNode("null");
        }
        
        try {
            if (root.isObject()) {
                ObjectNode skeleton = mapper.createObjectNode();
                skeleton.put("_t", "obj");
                ObjectNode props = skeleton.putObject("props");
                
                Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                int keyCount = 0;
                
                while (fields.hasNext() && keyCount < MAX_OBJECT_KEYS) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    try {
                        props.set(field.getKey(), generateSkeleton(field.getValue(), depth + 1));
                        keyCount++;
                    } catch (Exception e) {
                        logger.warn("Erreur lors de la génération du skeleton pour la clé '{}': {}", 
                            field.getKey(), e.getMessage());
                        // On continue avec les autres clés
                    }
                }
                
                if (keyCount >= MAX_OBJECT_KEYS) {
                    logger.warn("Limite de clés atteinte ({}) pour un objet", MAX_OBJECT_KEYS);
                }
                
                return skeleton;
            } 
            else if (root.isArray()) {
                ObjectNode skeleton = mapper.createObjectNode();
                skeleton.put("_t", "arr");
                
                // FUSION INTELLIGENTE : On merge tous les items du tableau
                JsonNode mergedSchema = mergeArrayItems(root, depth);
                skeleton.set("items", mergedSchema);
                
                return skeleton;
            } 
            else {
                // Valeurs primitives : on ne garde que le type
                return new TextNode(root.getNodeType().toString().toLowerCase());
            }
        } catch (Exception e) {
            logger.error("Erreur lors de la génération du skeleton à la profondeur {}: {}", depth, e.getMessage(), e);
            return new TextNode("error");
        }
    }

    private JsonNode mergeArrayItems(JsonNode arrayNode, int depth) {
        if (arrayNode.size() == 0) return new TextNode("empty");
        
        // Optimisation : Si le tableau est énorme, on ne scanne que les premiers éléments
        // pour éviter de tuer le CPU, c'est souvent suffisant pour avoir le schéma.
        int limit = Math.min(arrayNode.size(), MAX_ARRAY_ITEMS);
        
        try {
            // On commence avec le schéma du premier élément
            JsonNode merged = generateSkeleton(arrayNode.get(0), depth + 1);
            
            for (int i = 1; i < limit; i++) {
                try {
                    JsonNode current = generateSkeleton(arrayNode.get(i), depth + 1);
                    merged = mergeSchemas(merged, current);
                } catch (Exception e) {
                    logger.warn("Erreur lors du merge de l'élément {} du tableau: {}", i, e.getMessage());
                    // On continue avec les autres éléments
                }
            }
            return merged;
        } catch (Exception e) {
            logger.error("Erreur lors du merge des éléments du tableau: {}", e.getMessage(), e);
            return new TextNode("error");
        }
    }

    private JsonNode mergeSchemas(JsonNode s1, JsonNode s2) {
        // Si les types diffèrent (ex: un nombre et un string), ça devient "mixed"
        if (!s1.path("_t").equals(s2.path("_t")) && (s1.isObject() || s2.isObject())) {
             return new TextNode("mixed");
        }

        // Si ce sont deux objets, on fusionne leurs propriétés récursivement
        if (s1.has("props") && s2.has("props")) {
            ObjectNode p1 = (ObjectNode) s1.get("props");
            JsonNode p2 = s2.get("props");
            
            p2.fieldNames().forEachRemaining(key -> {
                if (!p1.has(key)) {
                    // Clé présente dans s2 mais pas s1 -> on l'ajoute
                    p1.set(key, p2.get(key));
                } else {
                    // Clé présente dans les deux -> on merge récursivement
                    p1.set(key, mergeSchemas(p1.get(key), p2.get(key)));
                }
            });
            return s1;
        }
        
        return s1; // Par défaut on garde la structure du premier
    }
}

