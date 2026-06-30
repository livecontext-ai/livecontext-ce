package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.services.imagegeneration.StabilityAiImageService;
import com.apimarketplace.orchestrator.services.imagegeneration.StabilityAiImageService.ContentFilteredException;
import com.apimarketplace.orchestrator.services.imagegeneration.StabilityAiImageService.ImageGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/internal/image-generation")
public class InternalImageGenerationController {

    private static final Logger log = LoggerFactory.getLogger(InternalImageGenerationController.class);

    private final StabilityAiImageService stabilityAiImageService;

    public InternalImageGenerationController(StabilityAiImageService stabilityAiImageService) {
        this.stabilityAiImageService = stabilityAiImageService;
    }

    @PostMapping("/screening-replace")
    public ResponseEntity<?> generateScreeningReplacement(@RequestBody ScreeningReplaceRequest request) {
        if (request.prompt == null || request.prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "prompt is required"));
        }
        if (request.tenantId == null || request.tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        if (request.prompt.length() > 500) {
            return ResponseEntity.badRequest().body(Map.of("error", "prompt exceeds 500 characters"));
        }

        try {
            var generateRequest = new StabilityAiImageService.GenerateRequest(
                    request.prompt,
                    request.negativePrompt,
                    request.aspectRatio,
                    request.stylePreset,
                    request.tenantId
            );

            StabilityAiImageService.GenerateResult result = stabilityAiImageService.generate(generateRequest);

            return ResponseEntity.ok(Map.of(
                    "storageKey", result.storageKey(),
                    "mimeType", result.mimeType(),
                    "sizeBytes", result.sizeBytes()
            ));
        } catch (ContentFilteredException e) {
            log.warn("Content filtered for screening replacement: {}", e.getMessage());
            return ResponseEntity.status(422).body(Map.of("error", "content_filtered", "message", e.getMessage()));
        } catch (ImageGenerationException e) {
            log.error("Image generation failed for screening replacement: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "generation_failed", "message", e.getMessage()));
        }
    }

    public static class ScreeningReplaceRequest {
        public String prompt;
        public String negativePrompt;
        public String aspectRatio;
        public String stylePreset;
        public String tenantId;
    }
}
