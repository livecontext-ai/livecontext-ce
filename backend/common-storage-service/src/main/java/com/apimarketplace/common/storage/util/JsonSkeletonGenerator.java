package com.apimarketplace.common.storage.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

import static com.apimarketplace.common.storage.config.StorageConstants.*;

/**
 * Generateur de skeleton JSON avec protection contre la recursion infinie.
 * Respecte SRP: seule responsabilite = generer des skeletons JSON.
 */
@Component
public class JsonSkeletonGenerator {

    private static final Logger logger = LoggerFactory.getLogger(JsonSkeletonGenerator.class);

    private final ObjectMapper objectMapper;

    public JsonSkeletonGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Genere un skeleton JSON avec protection contre la recursion infinie.
     */
    public JsonNode generateSkeleton(JsonNode root) {
        return generateSkeleton(root, 0);
    }

    private JsonNode generateSkeleton(JsonNode root, int depth) {
        // Protection contre la recursion infinie
        if (depth >= MAX_DEPTH) {
            logger.warn("Profondeur maximale atteinte ({}) lors de la generation du skeleton", MAX_DEPTH);
            return createTextNode("max_depth_reached");
        }

        if (root == null) {
            return createTextNode("null");
        }

        try {
            if (root.isObject()) {
                return processObjectNode(root, depth);
            } else if (root.isArray()) {
                return processArrayNode(root, depth);
            } else {
                // Valeurs primitives: on ne garde que le type
                return createTextNode(root.getNodeType().toString().toLowerCase());
            }
        } catch (Exception e) {
            logger.error("Erreur generation skeleton a la profondeur {}: {}", depth, e.getMessage(), e);
            return createTextNode("error");
        }
    }

    private JsonNode processObjectNode(JsonNode root, int depth) {
        ObjectNode skeleton = objectMapper.createObjectNode();
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
                logger.warn("Erreur generation skeleton pour cle '{}': {}",
                    field.getKey(), e.getMessage());
                // On continue avec les autres cles
            }
        }

        if (keyCount >= MAX_OBJECT_KEYS) {
            logger.warn("Limite de cles atteinte ({}) pour un objet", MAX_OBJECT_KEYS);
        }

        return skeleton;
    }

    private JsonNode processArrayNode(JsonNode root, int depth) {
        ObjectNode skeleton = objectMapper.createObjectNode();
        skeleton.put("_t", "arr");

        // Fusion intelligente: on merge tous les items du tableau
        JsonNode mergedSchema = mergeArrayItems(root, depth);
        skeleton.set("items", mergedSchema);

        return skeleton;
    }

    private JsonNode mergeArrayItems(JsonNode arrayNode, int depth) {
        if (arrayNode.size() == 0) {
            return createTextNode("empty");
        }

        // Optimisation: limiter le scan pour les grands tableaux
        int limit = Math.min(arrayNode.size(), MAX_ARRAY_ITEMS);

        try {
            // Commencer avec le schema du premier element
            JsonNode merged = generateSkeleton(arrayNode.get(0), depth + 1);

            for (int i = 1; i < limit; i++) {
                try {
                    JsonNode current = generateSkeleton(arrayNode.get(i), depth + 1);
                    merged = mergeSchemas(merged, current);
                } catch (Exception e) {
                    logger.warn("Erreur merge element {} du tableau: {}", i, e.getMessage());
                    // On continue avec les autres elements
                }
            }
            return merged;
        } catch (Exception e) {
            logger.error("Erreur merge elements tableau: {}", e.getMessage(), e);
            return createTextNode("error");
        }
    }

    private JsonNode mergeSchemas(JsonNode s1, JsonNode s2) {
        // Si les types different, ca devient "mixed"
        if (!s1.path("_t").equals(s2.path("_t")) && (s1.isObject() || s2.isObject())) {
            return createTextNode("mixed");
        }

        // Si ce sont deux objets, on fusionne leurs proprietes recursivement
        if (s1.has("props") && s2.has("props")) {
            ObjectNode p1 = (ObjectNode) s1.get("props");
            JsonNode p2 = s2.get("props");

            p2.fieldNames().forEachRemaining(key -> {
                if (!p1.has(key)) {
                    // Cle presente dans s2 mais pas s1 -> on l'ajoute
                    p1.set(key, p2.get(key));
                } else {
                    // Cle presente dans les deux -> on merge recursivement
                    p1.set(key, mergeSchemas(p1.get(key), p2.get(key)));
                }
            });
            return s1;
        }

        return s1; // Par defaut on garde la structure du premier
    }

    private TextNode createTextNode(String value) {
        return new TextNode(value);
    }
}
