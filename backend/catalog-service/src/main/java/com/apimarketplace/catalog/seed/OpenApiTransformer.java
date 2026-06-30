package com.apimarketplace.catalog.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Transforms a parsed OpenAPI object + SeedSpec manifest entry into the JsonNode
 * structure consumed by ApiService.processApiSubmission().
 */
@Component
@Slf4j
public class OpenApiTransformer {

    private static final int MAX_DESCRIPTION_LENGTH = 250;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode transform(OpenAPI openApi, SeedManifest.SeedSpec spec) {
        ObjectNode root = mapper.createObjectNode();

        // API-level fields
        String title = openApi.getInfo() != null ? openApi.getInfo().getTitle() : spec.getId();
        String description = openApi.getInfo() != null ? openApi.getInfo().getDescription() : "";
        root.put("apiName", title != null ? title : spec.getId());
        root.put("apiDescription", description != null ? description : "");

        // Category
        root.put("selectedCategory", spec.getCategory() != null ? spec.getCategory() : "APIs");
        root.put("isCustomCategory", true);
        root.put("selectedSubcategory", spec.getSubcategory() != null ? spec.getSubcategory() : "General");
        root.put("isCustomSubcategory", true);

        if (spec.getIconSlug() != null) {
            root.put("iconSlug", spec.getIconSlug());
        }

        // API config
        ObjectNode apiConfig = mapper.createObjectNode();
        String baseUrl = resolveBaseUrl(openApi);
        apiConfig.put("baseUrl", baseUrl);
        apiConfig.put("visibility", "public");

        // Authorization
        if (spec.getAuthType() != null && !spec.getAuthType().isBlank()) {
            ObjectNode auth = mapper.createObjectNode();
            auth.put("type", spec.getAuthType());
            if (spec.getAuthHeaderName() != null) {
                auth.put("headerName", spec.getAuthHeaderName());
            }
            apiConfig.set("authorization", auth);
        }
        root.set("apiConfig", apiConfig);

        // Monetization - FREEMIUM with unlimited free requests for seeds
        ObjectNode monetization = mapper.createObjectNode();
        monetization.put("pricing", "FREEMIUM");
        monetization.put("freeRequestsPerUser", 999999);
        root.set("monetization", monetization);

        // Tools from paths
        ArrayNode mcpTools = mapper.createArrayNode();
        if (openApi.getPaths() != null) {
            for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
                String path = pathEntry.getKey();
                PathItem pathItem = pathEntry.getValue();
                addOperations(mcpTools, path, pathItem);
            }
        }
        root.set("mcpTools", mcpTools);

        return root;
    }

    private String resolveBaseUrl(OpenAPI openApi) {
        if (openApi.getServers() != null && !openApi.getServers().isEmpty()) {
            String url = openApi.getServers().get(0).getUrl();
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        return "https://api.example.com";
    }

    private void addOperations(ArrayNode mcpTools, String path, PathItem pathItem) {
        addOperation(mcpTools, path, "GET", pathItem.getGet());
        addOperation(mcpTools, path, "POST", pathItem.getPost());
        addOperation(mcpTools, path, "PUT", pathItem.getPut());
        addOperation(mcpTools, path, "DELETE", pathItem.getDelete());
        addOperation(mcpTools, path, "PATCH", pathItem.getPatch());
    }

    private void addOperation(ArrayNode mcpTools, String path, String method, Operation operation) {
        if (operation == null) return;

        ObjectNode tool = mapper.createObjectNode();

        // Tool name: prefer operationId, fallback to summary-based slug
        String name = operation.getOperationId();
        if (name == null || name.isBlank()) {
            name = operation.getSummary() != null
                    ? toSlug(operation.getSummary())
                    : method.toLowerCase() + "_" + toSlug(path);
        }
        tool.put("name", name);
        tool.put("isCustomToolName", true);

        // Description: summary + description, capped at 250 chars
        String desc = buildDescription(operation.getSummary(), operation.getDescription());
        tool.put("description", desc);

        tool.put("endpoint", path);
        tool.put("method", method);
        tool.put("protocol", "HTTP");
        tool.put("toolCategory", "API Tools");
        tool.put("isCustomCategory", true);

        // Parameters
        if (operation.getParameters() != null) {
            ArrayNode pathParams = mapper.createArrayNode();
            ArrayNode queryParams = mapper.createArrayNode();
            ArrayNode headers = mapper.createArrayNode();

            for (Parameter param : operation.getParameters()) {
                ObjectNode paramNode = buildParameter(param);
                String in = param.getIn();
                if ("path".equals(in)) {
                    pathParams.add(paramNode);
                } else if ("query".equals(in)) {
                    queryParams.add(paramNode);
                } else if ("header".equals(in)) {
                    headers.add(paramNode);
                }
            }

            if (!pathParams.isEmpty()) tool.set("pathParameters", pathParams);
            if (!queryParams.isEmpty()) tool.set("queryParameters", queryParams);
            if (!headers.isEmpty()) tool.set("headers", headers);
        }

        // Request body → bodyParams (flattened)
        if (operation.getRequestBody() != null) {
            ArrayNode bodyParams = flattenRequestBody(operation.getRequestBody());
            if (bodyParams != null && !bodyParams.isEmpty()) {
                tool.set("bodyParams", bodyParams);
            }
        }

        // Response
        ObjectNode responseNode = buildResponse(operation.getResponses());
        if (responseNode != null) {
            tool.set("response", responseNode);
        }

        mcpTools.add(tool);
    }

    private ObjectNode buildParameter(Parameter param) {
        ObjectNode node = mapper.createObjectNode();
        node.put("name", param.getName());

        String type = "string";
        if (param.getSchema() != null && param.getSchema().getType() != null) {
            type = param.getSchema().getType();
        }
        node.put("type", type);
        node.put("required", param.getRequired() != null && param.getRequired());

        if (param.getDescription() != null) {
            node.put("description", param.getDescription());
        }
        if (param.getExample() != null) {
            node.put("example", String.valueOf(param.getExample()));
        }

        // Allowed values (enum)
        if (param.getSchema() != null && param.getSchema().getEnum() != null) {
            ArrayNode allowedValues = mapper.createArrayNode();
            for (Object enumVal : param.getSchema().getEnum()) {
                allowedValues.add(String.valueOf(enumVal));
            }
            node.set("allowedValues", allowedValues);
        }

        return node;
    }

    @SuppressWarnings("rawtypes")
    private ArrayNode flattenRequestBody(RequestBody requestBody) {
        if (requestBody.getContent() == null) return null;

        // Prefer application/json
        MediaType mediaType = requestBody.getContent().get("application/json");
        if (mediaType == null) {
            // Fallback to first available content type
            mediaType = requestBody.getContent().values().stream().findFirst().orElse(null);
        }
        if (mediaType == null || mediaType.getSchema() == null) return null;

        Schema schema = mediaType.getSchema();
        ArrayNode bodyParams = mapper.createArrayNode();

        Map<String, Schema> properties = schema.getProperties();
        if (properties != null) {
            List<String> requiredFields = schema.getRequired() != null ? schema.getRequired() : List.of();
            for (Map.Entry<String, Schema> prop : properties.entrySet()) {
                ObjectNode paramNode = mapper.createObjectNode();
                paramNode.put("name", prop.getKey());
                paramNode.put("type", prop.getValue().getType() != null ? prop.getValue().getType() : "string");
                paramNode.put("required", requiredFields.contains(prop.getKey()));
                if (prop.getValue().getDescription() != null) {
                    paramNode.put("value", prop.getValue().getDescription());
                }
                bodyParams.add(paramNode);
            }
        }

        return bodyParams;
    }

    @SuppressWarnings("rawtypes")
    private ObjectNode buildResponse(ApiResponses responses) {
        if (responses == null) return null;

        // Find first 2xx response
        ApiResponse successResponse = null;
        String statusCode = "200";
        for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
            if (entry.getKey().startsWith("2")) {
                successResponse = entry.getValue();
                statusCode = entry.getKey();
                break;
            }
        }
        if (successResponse == null) return null;

        ObjectNode responseNode = mapper.createObjectNode();
        responseNode.put("type", "JSON");
        responseNode.put("statusCode", Integer.parseInt(statusCode));

        if (successResponse.getDescription() != null) {
            responseNode.put("description", successResponse.getDescription());
        }

        // Extract schema from content
        if (successResponse.getContent() != null) {
            MediaType mediaType = successResponse.getContent().get("application/json");
            if (mediaType == null) {
                mediaType = successResponse.getContent().values().stream().findFirst().orElse(null);
            }
            if (mediaType != null && mediaType.getSchema() != null) {
                try {
                    Schema respSchema = mediaType.getSchema();
                    JsonNode schemaJson = mapper.valueToTree(respSchema);
                    responseNode.set("schema", schemaJson);
                } catch (Exception e) {
                    log.debug("Could not serialize response schema: {}", e.getMessage());
                }
            }
        }

        return responseNode;
    }

    private String buildDescription(String summary, String description) {
        if (summary != null && description != null && !description.isBlank()) {
            String combined = summary + " - " + description;
            return truncate(combined);
        }
        if (summary != null && !summary.isBlank()) {
            return truncate(summary);
        }
        if (description != null && !description.isBlank()) {
            return truncate(description);
        }
        return "";
    }

    private String truncate(String text) {
        if (text.length() <= MAX_DESCRIPTION_LENGTH) return text;
        return text.substring(0, MAX_DESCRIPTION_LENGTH - 3) + "...";
    }

    private String toSlug(String text) {
        return text.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .replaceAll("_+", "_");
    }
}
