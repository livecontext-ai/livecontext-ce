package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ToolResponseEntity;
import com.apimarketplace.catalog.repository.ToolResponseRepository;
import com.apimarketplace.catalog.util.JsonSkeletonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class StructureSkeletonService {

    private final ToolResponseRepository repository;
    private final JsonSkeletonGenerator skeletonGenerator;
    private final ObjectMapper mapper;

    public StructureSkeletonService(ToolResponseRepository repository, JsonSkeletonGenerator skeletonGenerator, ObjectMapper mapper) {
        this.repository = repository;
        this.skeletonGenerator = skeletonGenerator;
        this.mapper = mapper;
    }

    /**
     * Recupere les cles de la racine du squelette
     */
    @Transactional(readOnly = true)
    public List<ToolResponseRepository.StructureNode> getRootStructure(UUID responseId) {
        return repository.getStructureRoot(responseId);
    }

    /**
     * Recupere les cles d'un sous-niveau du squelette
     * @param pathArray chemin complet (segments) envoye par le frontend
     */
    @Transactional(readOnly = true)
    public List<ToolResponseRepository.StructureNode> getPathStructure(UUID responseId, String[] pathArray) {
        if (pathArray == null || pathArray.length == 0) {
            return getRootStructure(responseId);
        }
        
        // Charger la reponse complete et traverser le JSON en Java
        // C'est plus robuste que SQL car on peut gerer dynamiquement props/items
        return repository.findById(responseId)
            .map(response -> {
                try {
                    String skeletonStr = response.getStructureSkeleton();
                    if (skeletonStr == null) return new ArrayList<ToolResponseRepository.StructureNode>();
                    
                    JsonNode currentNode = mapper.readTree(skeletonStr);

                    // Traverser le chemin
                    for (String segment : pathArray) {
                        if (currentNode == null) {
                            break;
                        }

                        // Gerer le saut "props" ou "items" selon le type du parent
                        if (currentNode.has("props")) {
                            currentNode = currentNode.get("props").get(segment);
                        } else if (currentNode.has("items")) {
                            // Pour un tableau, le squelette fusionne tout dans 'items'
                            // Le segment suivant doit etre trouve dans items->props
                            JsonNode items = currentNode.get("items");
                            if (items.has("props")) {
                                currentNode = items.get("props").get(segment);
                            } else {
                                currentNode = null;
                            }
                        } else {
                            currentNode = null;
                        }
                    }

                    if (currentNode == null) {
                        return new ArrayList<ToolResponseRepository.StructureNode>();
                    }
                    
                    // Une fois arrive au noeud cible, extraire ses enfants
                    List<ToolResponseRepository.StructureNode> result = new ArrayList<>();
                    JsonNode childrenContainer = null;
                    
                    if (currentNode.has("_t")) {
                        String type = currentNode.get("_t").asText();
                        if ("obj".equals(type) && currentNode.has("props")) {
                            childrenContainer = currentNode.get("props");
                        } else if ("arr".equals(type) && currentNode.has("items")) {
                            // Pour un tableau, on renvoie la structure des items
                            JsonNode items = currentNode.get("items");
                            if (items.has("props")) {
                                childrenContainer = items.get("props");
                            } else {
                                // Cas d'un tableau de primitifs ou sans props
                                // On peut retourner items lui-meme s'il a un type
                                // Mais pour la navigation, on veut les cles...
                                // Si tableau de primitifs, pas d'enfants a afficher
                            }
                        }
                    }
                    
                    if (childrenContainer != null) {
                        final JsonNode container = childrenContainer; // effectively final for lambda
                        container.fieldNames().forEachRemaining(key -> {
                            JsonNode child = container.get(key);
                            String childType = child.has("_t") ? child.get("_t").asText() : null;
                            
                            // Si le noeud enfant est une valeur textuelle (primitive) et n'a pas de propriété "_t",
                            // alors sa valeur texte EST le type (ex: "string", "number", "boolean")
                            if (childType == null && child.isTextual()) {
                                childType = child.asText();
                            }
                            
                            // Fix: Handle explicitly null "type" in JSON (which might be parsed as text "null" or actual null)
                            if ("null".equals(childType)) {
                                childType = null;
                            }
                            
                            boolean hasChildren = "obj".equals(childType) || "arr".equals(childType);
                            
                            final String finalChildType = childType;
                            
                            result.add(new ToolResponseRepository.StructureNode() {
                                @Override
                                public String getKey() { return key; }
                                @Override
                                public String getType() { return finalChildType; }
                                @Override
                                public Boolean getHasChildren() { return hasChildren; }
                            });
                        });
                    }
                    
                    return result;
                    
                } catch (Exception e) {
                    e.printStackTrace();
                    return new ArrayList<ToolResponseRepository.StructureNode>();
                }
            })
            .orElse(new ArrayList<>());
    }

    // Methode helper pour construire le path string "{a,b,c}" pour PostgreSQL
    private String buildPostgresArrayString(List<String> pathParts) {
        return "{" + String.join(",", pathParts) + "}";
    }

    /**
     * Genere et sauvegarde le squelette pour une reponse donnee
     */
    @Transactional
    public void generateAndSaveSkeleton(UUID responseId) {
        repository.findById(responseId).ifPresent(response -> {
            try {
                String jsonContent = response.getExampleJsonb();
                if (jsonContent != null && !jsonContent.trim().isEmpty()) {
                    JsonNode root = mapper.readTree(jsonContent);
                    JsonNode skeleton = skeletonGenerator.generateSkeleton(root);
                    
                    // Utiliser ObjectMapper pour convertir en JSON string plutôt que toString()
                    response.setStructureSkeleton(mapper.writeValueAsString(skeleton));
                    
                    // Fix: Assurer que le champ 'example' n'est pas vide pour satisfaire @NotBlank
                    if (response.getExample() == null || response.getExample().trim().isEmpty()) {
                        // Utiliser exampleJsonb ou un placeholder
                        response.setExample(jsonContent);
                    }
                    
                    repository.save(response);
                }
            } catch (Exception e) {
                // Log error but don't block
            }
        });
    }

    /**
     * Recupere le skeleton complet pour un tool (par toolId)
     * Retourne la reponse par defaut si elle existe
     *
     * @param toolId UUID du tool
     * @return Map contenant le skeleton et les paths extraits
     */
    @Transactional
    public java.util.Map<String, Object> getSkeletonByToolId(UUID toolId) {
        return repository.findByToolIdAndIsDefaultTrue(toolId)
            .or(() -> repository.findByToolId(toolId).stream().findFirst())
            .map(response -> {
                java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
                result.put("toolId", toolId.toString());
                result.put("responseId", response.getId().toString());

                String skeletonStr = response.getStructureSkeleton();

                // Auto-generate skeleton from example_jsonb if missing
                if ((skeletonStr == null || skeletonStr.isEmpty()) && response.getExampleJsonb() != null && !response.getExampleJsonb().trim().isEmpty()) {
                    try {
                        JsonNode root = mapper.readTree(response.getExampleJsonb());
                        JsonNode generated = skeletonGenerator.generateSkeleton(root);
                        skeletonStr = mapper.writeValueAsString(generated);
                        response.setStructureSkeleton(skeletonStr);
                        if (response.getExample() == null || response.getExample().trim().isEmpty()) {
                            response.setExample(response.getExampleJsonb());
                        }
                        repository.save(response);
                    } catch (Exception e) {
                        // Continue with null skeleton
                    }
                }

                if (skeletonStr != null && !skeletonStr.isEmpty()) {
                    try {
                        JsonNode skeleton = mapper.readTree(skeletonStr);
                        result.put("skeleton", skeleton);

                        // Extract flattened paths for SpEL mapping help
                        List<String> paths = extractPaths(skeleton, "");
                        result.put("paths", paths);
                    } catch (Exception e) {
                        result.put("error", "Failed to parse skeleton: " + e.getMessage());
                    }
                } else {
                    result.put("skeleton", null);
                    result.put("paths", List.of());
                }

                return result;
            })
            .orElse(java.util.Map.of(
                "toolId", toolId.toString(),
                "error", "No response found for this tool"
            ));
    }

    /**
     * Extract flattened paths from skeleton for SpEL mapping help
     */
    private List<String> extractPaths(JsonNode node, String prefix) {
        List<String> paths = new ArrayList<>();

        if (node == null) return paths;

        if (node.has("_t")) {
            String type = node.get("_t").asText();
            if ("obj".equals(type) && node.has("props")) {
                JsonNode props = node.get("props");
                props.fieldNames().forEachRemaining(key -> {
                    String newPath = prefix.isEmpty() ? key : prefix + "." + key;
                    JsonNode child = props.get(key);

                    if (child.isTextual()) {
                        // Primitive type
                        paths.add(newPath + " -> " + child.asText());
                    } else if (child.has("_t")) {
                        String childType = child.get("_t").asText();
                        if ("obj".equals(childType) || "arr".equals(childType)) {
                            paths.add(newPath + " -> " + childType);
                            paths.addAll(extractPaths(child, newPath));
                        }
                    }
                });
            } else if ("arr".equals(type) && node.has("items")) {
                JsonNode items = node.get("items");
                paths.addAll(extractPaths(items, prefix + "[]"));
            }
        }

        return paths;
    }

    /**
     * Lance un batch de migration pour les reponses sans squelette
     * @param batchSize nombre d'elements a traiter
     * @return nombre d'elements traites
     */
    @Transactional
    public int runMigrationBatch(int batchSize) {
        List<ToolResponseEntity> responses = repository.findResponsesWithoutSkeleton(batchSize);
        int count = 0;
        
        for (ToolResponseEntity response : responses) {
            try {
                String jsonContent = response.getExampleJsonb();
                if (jsonContent != null && !jsonContent.trim().isEmpty()) {
                    JsonNode root = mapper.readTree(jsonContent);
                    JsonNode skeleton = skeletonGenerator.generateSkeleton(root);
                    count += repository.updateStructureSkeleton(
                            response.getId(),
                            mapper.writeValueAsString(skeleton));
                }
            } catch (Exception e) {
                // Log error but continue migration batch
            }
        }
        return count;
    }
}

