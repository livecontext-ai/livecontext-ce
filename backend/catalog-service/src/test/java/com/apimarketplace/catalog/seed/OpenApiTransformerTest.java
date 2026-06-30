package com.apimarketplace.catalog.seed;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenApiTransformer")
class OpenApiTransformerTest {

    private OpenApiTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new OpenApiTransformer();
    }

    private SeedManifest.SeedSpec createSpec(String id, String category, String subcategory) {
        SeedManifest.SeedSpec spec = new SeedManifest.SeedSpec();
        spec.setId(id);
        spec.setCategory(category);
        spec.setSubcategory(subcategory);
        spec.setAuthType("apiKey");
        spec.setAuthHeaderName("X-API-Key");
        spec.setIconSlug("cloud");
        return spec;
    }

    private OpenAPI createMinimalOpenApi(String title, String description, String baseUrl) {
        OpenAPI openApi = new OpenAPI();
        Info info = new Info();
        info.setTitle(title);
        info.setDescription(description);
        openApi.setInfo(info);
        Server server = new Server();
        server.setUrl(baseUrl);
        openApi.setServers(List.of(server));
        return openApi;
    }

    @Nested
    @DisplayName("API-level fields")
    class ApiLevelFields {

        @Test
        @DisplayName("should map API name and description from OpenAPI info")
        void shouldMapApiNameAndDescription() {
            OpenAPI openApi = createMinimalOpenApi("Weather API", "Get weather data", "https://api.weather.com");
            SeedManifest.SeedSpec spec = createSpec("weather", "Weather", "Forecasts");

            JsonNode result = transformer.transform(openApi, spec);

            assertEquals("Weather API", result.get("apiName").asText());
            assertEquals("Get weather data", result.get("apiDescription").asText());
        }

        @Test
        @DisplayName("should use spec id as fallback when title is null")
        void shouldFallbackToSpecId() {
            OpenAPI openApi = new OpenAPI();
            openApi.setInfo(new Info());
            SeedManifest.SeedSpec spec = createSpec("my-api", "General", "APIs");

            JsonNode result = transformer.transform(openApi, spec);

            assertEquals("my-api", result.get("apiName").asText());
        }

        @Test
        @DisplayName("should map category and subcategory from manifest")
        void shouldMapCategoryFromManifest() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            SeedManifest.SeedSpec spec = createSpec("test", "Data", "Analytics");

            JsonNode result = transformer.transform(openApi, spec);

            assertEquals("Data", result.get("selectedCategory").asText());
            assertEquals("Analytics", result.get("selectedSubcategory").asText());
            assertTrue(result.get("isCustomCategory").asBoolean());
            assertTrue(result.get("isCustomSubcategory").asBoolean());
        }

        @Test
        @DisplayName("should map base URL from first server")
        void shouldMapBaseUrl() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.example.com/v2");
            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");

            JsonNode result = transformer.transform(openApi, spec);

            assertEquals("https://api.example.com/v2", result.path("apiConfig").path("baseUrl").asText());
        }

        @Test
        @DisplayName("should map authorization from manifest")
        void shouldMapAuthorization() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");

            JsonNode result = transformer.transform(openApi, spec);

            JsonNode auth = result.path("apiConfig").path("authorization");
            assertEquals("apiKey", auth.get("type").asText());
            assertEquals("X-API-Key", auth.get("headerName").asText());
        }

        @Test
        @DisplayName("should set monetization to FREEMIUM")
        void shouldSetFreemiumPricing() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");

            JsonNode result = transformer.transform(openApi, spec);

            assertEquals("FREEMIUM", result.path("monetization").path("pricing").asText());
        }

        @Test
        @DisplayName("should map iconSlug")
        void shouldMapIconSlug() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");

            JsonNode result = transformer.transform(openApi, spec);

            assertEquals("cloud", result.get("iconSlug").asText());
        }
    }

    @Nested
    @DisplayName("Tool extraction from paths")
    class ToolExtraction {

        @Test
        @DisplayName("should create one tool per operation")
        void shouldCreateToolPerOperation() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            PathItem pathItem = new PathItem();
            pathItem.setGet(new Operation().operationId("getUsers").summary("Get all users"));
            pathItem.setPost(new Operation().operationId("createUser").summary("Create a user"));
            openApi.path("/users", pathItem);

            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            JsonNode result = transformer.transform(openApi, spec);

            JsonNode tools = result.get("mcpTools");
            assertEquals(2, tools.size());
        }

        @Test
        @DisplayName("should use operationId as tool name")
        void shouldUseOperationId() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            PathItem pathItem = new PathItem();
            pathItem.setGet(new Operation().operationId("getCurrentWeather").summary("Get weather"));
            openApi.path("/weather", pathItem);

            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            JsonNode result = transformer.transform(openApi, spec);

            assertEquals("getCurrentWeather", result.get("mcpTools").get(0).get("name").asText());
        }

        @Test
        @DisplayName("should fallback to summary slug when no operationId")
        void shouldFallbackToSummarySlug() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            PathItem pathItem = new PathItem();
            pathItem.setGet(new Operation().summary("Get Current Weather"));
            openApi.path("/weather", pathItem);

            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            JsonNode result = transformer.transform(openApi, spec);

            assertEquals("get_current_weather", result.get("mcpTools").get(0).get("name").asText());
        }

        @Test
        @DisplayName("should map endpoint and method")
        void shouldMapEndpointAndMethod() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            PathItem pathItem = new PathItem();
            pathItem.setPost(new Operation().operationId("create").summary("Create"));
            openApi.path("/items", pathItem);

            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            JsonNode result = transformer.transform(openApi, spec);

            JsonNode tool = result.get("mcpTools").get(0);
            assertEquals("/items", tool.get("endpoint").asText());
            assertEquals("POST", tool.get("method").asText());
            assertEquals("HTTP", tool.get("protocol").asText());
        }

        @Test
        @DisplayName("should set isCustomToolName to true")
        void shouldSetCustomToolName() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            PathItem pathItem = new PathItem();
            pathItem.setGet(new Operation().operationId("test").summary("Test"));
            openApi.path("/test", pathItem);

            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            JsonNode result = transformer.transform(openApi, spec);

            assertTrue(result.get("mcpTools").get(0).get("isCustomToolName").asBoolean());
        }

        @Test
        @DisplayName("should truncate long descriptions to 250 chars")
        void shouldTruncateLongDescriptions() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            PathItem pathItem = new PathItem();
            String longDesc = "A".repeat(300);
            pathItem.setGet(new Operation().operationId("test").description(longDesc));
            openApi.path("/test", pathItem);

            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            JsonNode result = transformer.transform(openApi, spec);

            String desc = result.get("mcpTools").get(0).get("description").asText();
            assertTrue(desc.length() <= 250);
            assertTrue(desc.endsWith("..."));
        }
    }

    @Nested
    @DisplayName("Parameter extraction")
    class ParameterExtraction {

        @Test
        @DisplayName("should separate path, query, and header params")
        void shouldSeparateParamTypes() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            PathItem pathItem = new PathItem();
            Operation op = new Operation().operationId("test").summary("Test");
            op.setParameters(List.of(
                    new Parameter().name("id").in("path").required(true).schema(new Schema<>().type("string")),
                    new Parameter().name("limit").in("query").required(false).schema(new Schema<>().type("integer")),
                    new Parameter().name("X-Custom").in("header").required(false).schema(new Schema<>().type("string"))
            ));
            pathItem.setGet(op);
            openApi.path("/items/{id}", pathItem);

            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            JsonNode result = transformer.transform(openApi, spec);

            JsonNode tool = result.get("mcpTools").get(0);
            assertEquals(1, tool.get("pathParameters").size());
            assertEquals("id", tool.get("pathParameters").get(0).get("name").asText());
            assertTrue(tool.get("pathParameters").get(0).get("required").asBoolean());

            assertEquals(1, tool.get("queryParameters").size());
            assertEquals("limit", tool.get("queryParameters").get(0).get("name").asText());

            assertEquals(1, tool.get("headers").size());
            assertEquals("X-Custom", tool.get("headers").get(0).get("name").asText());
        }

        @Test
        @DisplayName("should extract body params from request body schema")
        @SuppressWarnings("unchecked")
        void shouldExtractBodyParams() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            PathItem pathItem = new PathItem();
            Operation op = new Operation().operationId("create").summary("Create");

            Schema<Object> schema = new Schema<>();
            Map<String, Schema> props = new LinkedHashMap<>();
            props.put("name", new Schema<>().type("string").description("The name"));
            props.put("age", new Schema<>().type("integer"));
            schema.setProperties(props);
            schema.setRequired(List.of("name"));

            MediaType mediaType = new MediaType().schema(schema);
            Content content = new Content();
            content.addMediaType("application/json", mediaType);
            RequestBody requestBody = new RequestBody().content(content);
            op.setRequestBody(requestBody);

            pathItem.setPost(op);
            openApi.path("/users", pathItem);

            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            JsonNode result = transformer.transform(openApi, spec);

            JsonNode bodyParams = result.get("mcpTools").get(0).get("bodyParams");
            assertNotNull(bodyParams);
            assertEquals(2, bodyParams.size());
            assertEquals("name", bodyParams.get(0).get("name").asText());
            assertTrue(bodyParams.get(0).get("required").asBoolean());
            assertEquals("age", bodyParams.get(1).get("name").asText());
            assertFalse(bodyParams.get(1).get("required").asBoolean());
        }
    }

    @Nested
    @DisplayName("Response extraction")
    class ResponseExtraction {

        @Test
        @DisplayName("should extract 200 response with schema")
        @SuppressWarnings("unchecked")
        void shouldExtract200Response() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            PathItem pathItem = new PathItem();
            Operation op = new Operation().operationId("test").summary("Test");

            Schema<Object> respSchema = new Schema<>();
            respSchema.setType("object");
            Map<String, Schema> props = new LinkedHashMap<>();
            props.put("id", new Schema<>().type("integer"));
            props.put("name", new Schema<>().type("string"));
            respSchema.setProperties(props);

            MediaType mediaType = new MediaType().schema(respSchema);
            Content content = new Content();
            content.addMediaType("application/json", mediaType);
            ApiResponse apiResponse = new ApiResponse().description("Success").content(content);
            ApiResponses responses = new ApiResponses();
            responses.addApiResponse("200", apiResponse);
            op.setResponses(responses);

            pathItem.setGet(op);
            openApi.path("/test", pathItem);

            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            JsonNode result = transformer.transform(openApi, spec);

            JsonNode response = result.get("mcpTools").get(0).get("response");
            assertNotNull(response);
            assertEquals("JSON", response.get("type").asText());
            assertEquals(200, response.get("statusCode").asInt());
            assertEquals("Success", response.get("description").asText());
            assertNotNull(response.get("schema"));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle OpenAPI with no paths")
        void shouldHandleNoPaths() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");

            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            JsonNode result = transformer.transform(openApi, spec);

            assertEquals(0, result.get("mcpTools").size());
        }

        @Test
        @DisplayName("should handle no servers with fallback URL")
        void shouldHandleNoServers() {
            OpenAPI openApi = new OpenAPI();
            openApi.setInfo(new Info().title("Test").description("Desc"));

            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            JsonNode result = transformer.transform(openApi, spec);

            assertEquals("https://api.example.com", result.path("apiConfig").path("baseUrl").asText());
        }

        @Test
        @DisplayName("should handle null auth type")
        void shouldHandleNullAuth() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            spec.setAuthType(null);

            JsonNode result = transformer.transform(openApi, spec);

            assertTrue(result.path("apiConfig").path("authorization").isMissingNode());
        }

        @Test
        @DisplayName("should handle all HTTP methods")
        void shouldHandleAllMethods() {
            OpenAPI openApi = createMinimalOpenApi("Test", "Desc", "https://api.test.com");
            PathItem pathItem = new PathItem();
            pathItem.setGet(new Operation().operationId("get").summary("Get"));
            pathItem.setPost(new Operation().operationId("post").summary("Post"));
            pathItem.setPut(new Operation().operationId("put").summary("Put"));
            pathItem.setDelete(new Operation().operationId("delete").summary("Delete"));
            pathItem.setPatch(new Operation().operationId("patch").summary("Patch"));
            openApi.path("/resource", pathItem);

            SeedManifest.SeedSpec spec = createSpec("test", "Cat", "Sub");
            JsonNode result = transformer.transform(openApi, spec);

            assertEquals(5, result.get("mcpTools").size());
        }
    }
}
