package com.apimarketplace.catalog.service.submission;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ToolParameterService {
    private final JdbcTemplate jdbcTemplate;

    public void saveParameters(ApiToolEntity tool, JsonNode toolData, String toolDisplayName) {
        long currentTime = System.currentTimeMillis();

        // Delete existing parameters for this tool (upsert behavior)
        String deleteSql = "DELETE FROM api_tool_parameters WHERE api_tool_id = ?";
        int deleted = jdbcTemplate.update(deleteSql, tool.getId());
        if (deleted > 0) {
            log.debug("Deleted {} existing parameters for tool {}", deleted, toolDisplayName);
        }

        // Headers
        if (toolData.has("headers") && toolData.get("headers").isArray()) {
            for (JsonNode header : toolData.get("headers")) {
                String sql = "INSERT INTO api_tool_parameters (id, api_tool_id, parameter_type, name, data_type, is_required, description, example_value, default_value, allowed_values, file_path, extras, is_hidden, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, to_json(?::json), ?, ?)";
                jdbcTemplate.update(sql,
                        UUID.randomUUID(),
                        tool.getId(),
                        "header",
                        header.path("name").asText(),
                        "string",
                        header.path("required").asBoolean(false),
                        header.path("description").asText(),
                        header.path("value").asText(),
                        extractTextOrNull(header.path("defaultValue")),
                        extractAllowedValuesJson(header),
                        null,
                        extrasAsJson(header),
                        header.path("isHidden").asBoolean(false),
                        currentTime
                );
            }
        }

        // Path Parameters
        if (toolData.has("pathParameters") && toolData.get("pathParameters").isArray()) {
            for (JsonNode pathParam : toolData.get("pathParameters")) {
                String paramName = pathParam.path("name").asText();
                boolean isHidden = pathParam.path("isHidden").asBoolean(false);
                String defaultValue = extractTextOrNull(pathParam.path("defaultValue"));
                String allowedValues = extractAllowedValuesJson(pathParam);
                log.info("Catalog receiving path param '{}': isHidden={}, defaultValue={}, allowedValues={}",
                        paramName, isHidden, defaultValue, allowedValues);

                String sql = "INSERT INTO api_tool_parameters (id, api_tool_id, parameter_type, name, data_type, is_required, description, example_value, default_value, allowed_values, file_path, extras, is_hidden, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, to_json(?::json), ?, ?)";
                jdbcTemplate.update(sql,
                        UUID.randomUUID(),
                        tool.getId(),
                        "path",
                        pathParam.path("name").asText(),
                        pathParam.path("type").asText("string"),
                        pathParam.path("required").asBoolean(false),
                        pathParam.path("description").asText(),
                        pathParam.path("exampleValue").asText(pathParam.path("example").asText()),
                        defaultValue,
                        allowedValues,
                        null,
                        extrasAsJson(pathParam),
                        pathParam.path("isHidden").asBoolean(false),
                        currentTime
                );
            }
        }

        // Query Parameters
        if (toolData.has("queryParameters") && toolData.get("queryParameters").isArray()) {
            for (JsonNode queryParam : toolData.get("queryParameters")) {
                String sql = "INSERT INTO api_tool_parameters (id, api_tool_id, parameter_type, name, data_type, is_required, description, example_value, default_value, allowed_values, file_path, extras, is_hidden, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, to_json(?::json), ?, ?)";
                jdbcTemplate.update(sql,
                        UUID.randomUUID(),
                        tool.getId(),
                        "query",
                        queryParam.path("name").asText(),
                        queryParam.path("type").asText("string"),
                        queryParam.path("required").asBoolean(false),
                        queryParam.path("description").asText(),
                        queryParam.path("exampleValue").asText(queryParam.path("example").asText()),
                        extractTextOrNull(queryParam.path("defaultValue")),
                        extractAllowedValuesJson(queryParam),
                        null,
                        extrasAsJson(queryParam),
                        queryParam.path("isHidden").asBoolean(false),
                        currentTime
                );
            }
        }

        // Body Parameters
        if (toolData.has("bodyParams") && toolData.get("bodyParams").isArray()) {
            for (JsonNode bodyParam : toolData.get("bodyParams")) {
                String sql = "INSERT INTO api_tool_parameters (id, api_tool_id, parameter_type, name, data_type, is_required, description, example_value, default_value, allowed_values, file_path, extras, is_hidden, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, to_json(?::json), ?, ?)";
                jdbcTemplate.update(sql,
                        UUID.randomUUID(),
                        tool.getId(),
                        "body",
                        bodyParam.path("name").asText(),
                        bodyParam.path("type").asText("string"),
                        bodyParam.path("required").asBoolean(false),
                        bodyParam.path("description").asText(),
                        bodyParam.path("value").asText(),
                        extractTextOrNull(bodyParam.path("defaultValue")),
                        extractAllowedValuesJson(bodyParam),
                        null,
                        extrasAsJson(bodyParam),
                        bodyParam.path("isHidden").asBoolean(false),
                        currentTime
                );
            }
        }

        log.info("Parameters saved for tool {}", toolDisplayName);
    }

    private String extrasAsJson(JsonNode paramNode) {
        if (paramNode == null) {
            return "{}";
        }
        JsonNode extras = paramNode.path("extras");
        if (extras == null || extras.isMissingNode() || extras.isNull()) {
            return "{}";
        }
        return extras.toString();
    }

    private String extractTextOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    /**
     * Extract the {@code allowedValues} field from a param node as a JSON-serialized
     * array string, suitable for the {@code allowed_values TEXT} column.
     *
     * <p>Returns {@code null} when the field is absent / null / not an array / empty.
     * The DB column accepts NULL; passing {@code "[]"} would store an empty array which
     * is semantically different ("explicitly no admissible value") and never what we want here.
     */
    private String extractAllowedValuesJson(JsonNode paramNode) {
        if (paramNode == null) {
            return null;
        }
        JsonNode allowed = paramNode.path("allowedValues");
        if (allowed == null || allowed.isMissingNode() || allowed.isNull()) {
            return null;
        }
        if (!allowed.isArray() || allowed.size() == 0) {
            return null;
        }
        return allowed.toString();
    }
}
