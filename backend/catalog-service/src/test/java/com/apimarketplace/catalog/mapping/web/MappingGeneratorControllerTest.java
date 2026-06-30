package com.apimarketplace.catalog.mapping.web;

import com.apimarketplace.catalog.mapping.generator.DeepInfraStrictMappingGenerator;
import com.apimarketplace.catalog.mapping.generator.MappingGenerationException;
import com.apimarketplace.catalog.mapping.generator.StrictMappingConstraints;
import com.apimarketplace.catalog.mapping.service.MappingGeneratorService;
import com.apimarketplace.catalog.mapping.web.MappingGeneratorController.ErrorResponse;
import com.apimarketplace.catalog.mapping.web.MappingGeneratorController.FileValidationRequest;
import com.apimarketplace.catalog.mapping.web.MappingGeneratorController.FileValidationResponse;
import com.apimarketplace.catalog.mapping.web.MappingGeneratorController.MappingGenerationRequest;
import com.apimarketplace.catalog.mapping.web.MappingGeneratorController.SimpleMappingRequest;
import com.apimarketplace.catalog.mapping.web.MappingGeneratorController.StatusResponse;
import com.apimarketplace.catalog.service.ToolContextService;
import com.apimarketplace.catalog.service.ToolResponseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MappingGeneratorController")
class MappingGeneratorControllerTest {

    @Mock
    private MappingGeneratorService mappingGeneratorService;

    @Mock
    private ToolContextService toolContextService;

    @Mock
    private DeepInfraStrictMappingGenerator deepInfraGenerator;

    private ObjectMapper objectMapper;
    private MappingGeneratorController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new MappingGeneratorController(
                mappingGeneratorService, objectMapper, toolContextService, deepInfraGenerator);
    }

    @Nested
    @DisplayName("POST /generate")
    class GenerateMappingTests {

        @Test
        @DisplayName("should return bad request when sample is null")
        void returnsBadRequestWhenSampleNull() {
            MappingGenerationRequest request = new MappingGenerationRequest();
            request.setSample(null);

            ResponseEntity<?> response = controller.generateMapping(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should return bad request when sample is empty")
        void returnsBadRequestWhenSampleEmpty() {
            MappingGenerationRequest request = new MappingGenerationRequest();
            request.setSample("   ");

            ResponseEntity<?> response = controller.generateMapping(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should generate mapping without tool context")
        void generatesMappingWithoutToolContext() throws Exception {
            MappingGenerationRequest request = new MappingGenerationRequest();
            request.setSample("{\"data\": [1, 2, 3]}");

            String generatedMapping = "{\"source\":{\"format\":\"json\"}}";

            when(mappingGeneratorService.generateStrictMapping(eq("{\"data\": [1, 2, 3]}"), any(StrictMappingConstraints.class)))
                    .thenReturn(generatedMapping);

            ResponseEntity<?> response = controller.generateMapping(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("should generate mapping with tool context")
        void generatesMappingWithToolContext() throws Exception {
            MappingGenerationRequest request = new MappingGenerationRequest();
            request.setSample("{\"data\": [1, 2, 3]}");
            request.setToolId("tool-123");

            ToolContextService.ToolContext toolContext = new ToolContextService.ToolContext();
            toolContext.setToolName("Search API");
            toolContext.setToolCategoryName("Search");
            toolContext.setToolSubCategoryName("Web");
            toolContext.setHttpMethod("GET");
            toolContext.setEndpoint("/search");
            toolContext.setToolDescriptionFull("Searches the web");

            when(toolContextService.loadToolContext("tool-123")).thenReturn(Optional.of(toolContext));
            when(mappingGeneratorService.generateStrictMappingWithContext(
                    anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn("{\"source\":{\"format\":\"json\"}}");

            ResponseEntity<?> response = controller.generateMapping(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("should return bad request when tool not found")
        void returnsBadRequestWhenToolNotFound() {
            MappingGenerationRequest request = new MappingGenerationRequest();
            request.setSample("{\"data\": [1, 2, 3]}");
            request.setToolId("unknown-tool");

            when(toolContextService.loadToolContext("unknown-tool")).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.generateMapping(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should return 500 on MappingGenerationException")
        void returns500OnMappingGenerationException() throws Exception {
            MappingGenerationRequest request = new MappingGenerationRequest();
            request.setSample("{\"data\": [1]}");

            when(mappingGeneratorService.generateStrictMapping(anyString(), any(StrictMappingConstraints.class)))
                    .thenThrow(new MappingGenerationException("AI failed"));

            ResponseEntity<?> response = controller.generateMapping(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("should return 500 on unexpected exception")
        void returns500OnUnexpectedException() throws Exception {
            MappingGenerationRequest request = new MappingGenerationRequest();
            request.setSample("{\"data\": [1]}");

            when(mappingGeneratorService.generateStrictMapping(anyString(), any(StrictMappingConstraints.class)))
                    .thenThrow(new RuntimeException("Unexpected error"));

            ResponseEntity<?> response = controller.generateMapping(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("POST /generate-simple")
    class GenerateSimpleMappingTests {

        @Test
        @DisplayName("should generate simple mapping successfully")
        void generatesSimpleMappingSuccessfully() throws Exception {
            SimpleMappingRequest request = new SimpleMappingRequest();
            request.setSample("{\"items\": []}");

            when(mappingGeneratorService.generateStrictMapping("{\"items\": []}"))
                    .thenReturn("{\"source\":{\"format\":\"json\"}}");

            ResponseEntity<?> response = controller.generateSimpleMapping(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("should return bad request when sample is null")
        void returnsBadRequestWhenSampleNull() {
            SimpleMappingRequest request = new SimpleMappingRequest();
            request.setSample(null);

            ResponseEntity<?> response = controller.generateSimpleMapping(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should return bad request when sample is empty")
        void returnsBadRequestWhenSampleEmpty() {
            SimpleMappingRequest request = new SimpleMappingRequest();
            request.setSample("  ");

            ResponseEntity<?> response = controller.generateSimpleMapping(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("POST /generate-external/{tool_id}")
    class GenerateMappingExternalTests {

        @Test
        @DisplayName("should return service unavailable when deepInfra not configured")
        void returnsServiceUnavailableWhenNotConfigured() {
            // Create controller without deepInfra generator
            MappingGeneratorController ctrlWithoutDeepInfra = new MappingGeneratorController(
                    mappingGeneratorService, objectMapper, toolContextService, null);

            MappingGenerationRequest request = new MappingGenerationRequest();
            request.setSample("{\"data\": [1]}");

            ResponseEntity<?> response = ctrlWithoutDeepInfra.generateMappingExternal("tool-1", request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("should return service unavailable when deepInfra not available")
        void returnsServiceUnavailableWhenNotAvailable() {
            MappingGenerationRequest request = new MappingGenerationRequest();
            request.setSample("{\"data\": [1]}");

            when(deepInfraGenerator.isAvailable()).thenReturn(false);

            ResponseEntity<?> response = controller.generateMappingExternal("tool-1", request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("should return bad request when sample is empty")
        void returnsBadRequestWhenSampleEmpty() {
            MappingGenerationRequest request = new MappingGenerationRequest();
            request.setSample("");

            ResponseEntity<?> response = controller.generateMappingExternal("tool-1", request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should generate external mapping with tool context")
        void generatesExternalMappingWithToolContext() throws Exception {
            MappingGenerationRequest request = new MappingGenerationRequest();
            request.setSample("{\"data\": [1]}");

            ToolContextService.ToolContext toolContext = new ToolContextService.ToolContext();
            toolContext.setToolName("Search API");
            toolContext.setToolCategoryName("Search");
            toolContext.setToolSubCategoryName("Web");
            toolContext.setHttpMethod("GET");
            toolContext.setEndpoint("/search");
            toolContext.setToolDescriptionFull("Searches the web");

            when(deepInfraGenerator.isAvailable()).thenReturn(true);
            when(toolContextService.loadToolContext("tool-1")).thenReturn(Optional.of(toolContext));
            when(deepInfraGenerator.generateStrictMappingWithContext(
                    anyString(), any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn("{\"source\":{\"format\":\"json\"}}");

            ResponseEntity<?> response = controller.generateMappingExternal("tool-1", request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("POST /generate-external-simple")
    class GenerateSimpleMappingExternalTests {

        @Test
        @DisplayName("should return service unavailable when deepInfra not configured")
        void returnsServiceUnavailableWhenNotConfigured() {
            MappingGeneratorController ctrlWithoutDeepInfra = new MappingGeneratorController(
                    mappingGeneratorService, objectMapper, toolContextService, null);

            SimpleMappingRequest request = new SimpleMappingRequest();
            request.setSample("{\"data\": [1]}");

            ResponseEntity<?> response = ctrlWithoutDeepInfra.generateSimpleMappingExternal(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("should return bad request when sample is null")
        void returnsBadRequestWhenSampleNull() {
            SimpleMappingRequest request = new SimpleMappingRequest();
            request.setSample(null);

            ResponseEntity<?> response = controller.generateSimpleMappingExternal(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should generate simple external mapping successfully")
        void generatesSimpleExternalMappingSuccessfully() throws Exception {
            SimpleMappingRequest request = new SimpleMappingRequest();
            request.setSample("{\"data\": [1]}");

            when(deepInfraGenerator.isAvailable()).thenReturn(true);
            when(deepInfraGenerator.generateStrictMapping(anyString(), any()))
                    .thenReturn("{\"source\":{\"format\":\"json\"}}");

            ResponseEntity<?> response = controller.generateSimpleMappingExternal(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("GET /status")
    class GetGeneratorStatusTests {

        @Test
        @DisplayName("should return available status")
        void returnsAvailableStatus() {
            when(mappingGeneratorService.isGeneratorAvailable()).thenReturn(true);

            ResponseEntity<?> response = controller.getGeneratorStatus();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            StatusResponse body = (StatusResponse) response.getBody();
            assertThat(body.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("should return unavailable status")
        void returnsUnavailableStatus() {
            when(mappingGeneratorService.isGeneratorAvailable()).thenReturn(false);

            ResponseEntity<?> response = controller.getGeneratorStatus();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            StatusResponse body = (StatusResponse) response.getBody();
            assertThat(body.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("should return 500 on exception")
        void returns500OnException() {
            when(mappingGeneratorService.isGeneratorAvailable())
                    .thenThrow(new RuntimeException("Check failed"));

            ResponseEntity<?> response = controller.getGeneratorStatus();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("GET /status-external")
    class GetExternalGeneratorStatusTests {

        @Test
        @DisplayName("should return false when deepInfra not configured")
        void returnsFalseWhenNotConfigured() {
            MappingGeneratorController ctrlWithoutDeepInfra = new MappingGeneratorController(
                    mappingGeneratorService, objectMapper, toolContextService, null);

            ResponseEntity<?> response = ctrlWithoutDeepInfra.getExternalGeneratorStatus();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            StatusResponse body = (StatusResponse) response.getBody();
            assertThat(body.isAvailable()).isFalse();
            assertThat(body.getMessage()).contains("not configured");
        }

        @Test
        @DisplayName("should return availability from deepInfra")
        void returnsAvailabilityFromDeepInfra() {
            when(deepInfraGenerator.isAvailable()).thenReturn(true);

            ResponseEntity<?> response = controller.getExternalGeneratorStatus();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            StatusResponse body = (StatusResponse) response.getBody();
            assertThat(body.isAvailable()).isTrue();
        }
    }

    @Nested
    @DisplayName("POST /validate-file")
    class ValidateFileTests {

        @Test
        @DisplayName("should validate small file")
        void validatesSmallFile() {
            FileValidationRequest request = new FileValidationRequest();
            request.setSample("{\"data\": [1]}");

            ResponseEntity<?> response = controller.validateFile(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            FileValidationResponse body = (FileValidationResponse) response.getBody();
            assertThat(body.isIsLargeFile()).isFalse();
            assertThat(body.isWillBeProcessed()).isTrue();
            assertThat(body.getProcessingMethod()).isEqualTo("full-processing");
        }

        @Test
        @DisplayName("should return bad request when sample is null")
        void returnsBadRequestWhenSampleNull() {
            FileValidationRequest request = new FileValidationRequest();
            request.setSample(null);

            ResponseEntity<?> response = controller.validateFile(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should return bad request when sample is empty")
        void returnsBadRequestWhenSampleEmpty() {
            FileValidationRequest request = new FileValidationRequest();
            request.setSample("   ");

            ResponseEntity<?> response = controller.validateFile(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("GET /latest/{tool_id}")
    class GetLatestMappingSpecTests {

        @Test
        @DisplayName("should return default mapping spec with tool context")
        void returnsDefaultMappingSpecWithToolContext() {
            ToolContextService.ToolContext toolContext = new ToolContextService.ToolContext();
            toolContext.setToolName("Instagram Reels");

            when(toolContextService.loadToolContext("tool-1")).thenReturn(Optional.of(toolContext));

            ResponseEntity<?> response = controller.getLatestMappingSpec("tool-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("should return default mapping spec without tool context")
        void returnsDefaultMappingSpecWithoutToolContext() {
            when(toolContextService.loadToolContext("tool-1")).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getLatestMappingSpec("tool-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("should handle exception in loadToolContext gracefully")
        void handlesExceptionInLoadToolContext() {
            when(toolContextService.loadToolContext("tool-1"))
                    .thenThrow(new RuntimeException("DB error"));

            // The method catches exceptions on loadToolContext, so it should still return OK
            ResponseEntity<?> response = controller.getLatestMappingSpec("tool-1");

            // It still generates a default mapping spec
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("Inner DTOs")
    class InnerDtoTests {

        @Test
        @DisplayName("MappingGenerationRequest getters and setters")
        void mappingGenerationRequestGettersSetters() {
            MappingGenerationRequest request = new MappingGenerationRequest();
            request.setSample("sample");
            request.setToolId("tool-id");

            assertThat(request.getSample()).isEqualTo("sample");
            assertThat(request.getToolId()).isEqualTo("tool-id");
        }

        @Test
        @DisplayName("SimpleMappingRequest getters and setters")
        void simpleMappingRequestGettersSetters() {
            SimpleMappingRequest request = new SimpleMappingRequest();
            request.setSample("sample");

            assertThat(request.getSample()).isEqualTo("sample");
        }

        @Test
        @DisplayName("ErrorResponse getters and setters")
        void errorResponseGettersSetters() {
            ErrorResponse error = new ErrorResponse("test error");

            assertThat(error.getError()).isEqualTo("test error");

            error.setError("updated error");
            assertThat(error.getError()).isEqualTo("updated error");
        }

        @Test
        @DisplayName("StatusResponse getters and setters")
        void statusResponseGettersSetters() {
            StatusResponse status = new StatusResponse(true);
            assertThat(status.isAvailable()).isTrue();
            assertThat(status.getMessage()).isNull();

            StatusResponse statusWithMsg = new StatusResponse(false, "Not available");
            assertThat(statusWithMsg.isAvailable()).isFalse();
            assertThat(statusWithMsg.getMessage()).isEqualTo("Not available");

            statusWithMsg.setAvailable(true);
            assertThat(statusWithMsg.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("FileValidationRequest getters and setters")
        void fileValidationRequestGettersSetters() {
            FileValidationRequest request = new FileValidationRequest();
            request.setSample("test");

            assertThat(request.getSample()).isEqualTo("test");
        }

        @Test
        @DisplayName("FileValidationResponse getters and setters")
        void fileValidationResponseGettersSetters() {
            FileValidationResponse response = new FileValidationResponse();
            response.setFileSizeBytes(1024);
            response.setMaxFileSizeBytes(1048576);
            response.setIsLargeFile(false);
            response.setWillBeProcessed(true);
            response.setProcessingMethod("full-processing");
            response.setMessage("OK");

            assertThat(response.getFileSizeBytes()).isEqualTo(1024);
            assertThat(response.getMaxFileSizeBytes()).isEqualTo(1048576);
            assertThat(response.isIsLargeFile()).isFalse();
            assertThat(response.isWillBeProcessed()).isTrue();
            assertThat(response.getProcessingMethod()).isEqualTo("full-processing");
            assertThat(response.getMessage()).isEqualTo("OK");
        }
    }
}
