package com.apimarketplace.orchestrator.services.imagegeneration;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.storage.client.StorageClient;
import com.apimarketplace.storage.client.dto.FileRefDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class StabilityAiImageService {

    private static final Logger log = LoggerFactory.getLogger(StabilityAiImageService.class);

    private final CredentialClient credentialClient;
    private final StorageClient storageClient;
    private final RestTemplate restTemplate;
    private final String apiUrl;

    public StabilityAiImageService(
            CredentialClient credentialClient,
            StorageClient storageClient,
            @Value("${stability-ai.api-url:https://api.stability.ai}") String apiUrl) {
        this.credentialClient = credentialClient;
        this.storageClient = storageClient;
        this.apiUrl = apiUrl;

        this.restTemplate = new RestTemplate();
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(25).toMillis());
        this.restTemplate.setRequestFactory(factory);
    }

    public record GenerateRequest(
            String prompt,
            String negativePrompt,
            String aspectRatio,
            String stylePreset,
            String tenantId
    ) {}

    public record GenerateResult(
            String storageKey,
            String mimeType,
            long sizeBytes
    ) {}

    public GenerateResult generate(GenerateRequest request) {
        Optional<String> apiKey = credentialClient.getPlatformCredentialForIntegration("stabilityai");
        if (apiKey.isEmpty()) {
            throw new ImageGenerationException("Stability AI platform credential not configured");
        }

        byte[] imageBytes = callStabilityApi(apiKey.get(), request);

        String fileName = UUID.randomUUID() + ".png";
        FileRefDto fileRef = storageClient.genericUpload(
                request.tenantId(), "ai-generated", fileName, "image/png", imageBytes);
        if (fileRef == null) {
            throw new ImageGenerationException("Failed to store generated image");
        }

        log.info("AI image generated and stored for tenant {}: key={}, size={} bytes",
                request.tenantId(), fileRef.path(), imageBytes.length);

        return new GenerateResult(fileRef.path(), "image/png", imageBytes.length);
    }

    private byte[] callStabilityApi(String apiKey, GenerateRequest request) {
        String url = apiUrl + "/v2beta/stable-image/generate/core";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("Accept", "image/*");
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("prompt", request.prompt());
        if (request.negativePrompt() != null && !request.negativePrompt().isBlank()) {
            body.add("negative_prompt", request.negativePrompt());
        }
        if (request.aspectRatio() != null && !request.aspectRatio().isBlank()) {
            body.add("aspect_ratio", request.aspectRatio());
        }
        if (request.stylePreset() != null && !request.stylePreset().isBlank()) {
            body.add("style_preset", request.stylePreset());
        }
        body.add("output_format", "png");

        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, entity, byte[].class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            throw new ImageGenerationException("Stability AI returned " + response.getStatusCode());
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 403) {
                throw new ImageGenerationException("Stability AI authentication failed - check platform API key");
            }
            if (e.getStatusCode().value() == 400) {
                throw new ContentFilteredException("Content filtered by Stability AI: " + e.getResponseBodyAsString());
            }
            throw new ImageGenerationException("Stability AI error: " + e.getMessage());
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new ImageGenerationException("Stability AI timeout or connection error: " + e.getMessage());
        }
    }

    public static class ImageGenerationException extends RuntimeException {
        public ImageGenerationException(String message) { super(message); }
    }

    public static class ContentFilteredException extends RuntimeException {
        public ContentFilteredException(String message) { super(message); }
    }
}
