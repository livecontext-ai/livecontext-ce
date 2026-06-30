package com.apimarketplace.catalog.mapping.config;

import com.apimarketplace.catalog.mapping.generator.DeepInfraStrictMappingGenerator;
import com.apimarketplace.catalog.mapping.generator.StrictMappingGenerator;
import com.apimarketplace.catalog.mapping.service.MappingGeneratorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Configuration for the strict mapping generator system.
 * 
 * This configuration sets up the mapping generator with caching,
 * AI model configuration, and service beans.
 */
@Configuration
@EnableCaching
public class MappingGeneratorConfig {
    
    @Value("${ai.mapping.enabled:true}")
    private boolean mappingEnabled;
    
    @Value("${ai.mapping.provider:deepinfra}")
    private String mappingProvider;
    
    @Value("${ai.mapping.max-fallbacks:4}")
    private int maxFallbacks;
    
    @Value("${ai.mapping.timeout-ms:300000}")
    private int timeoutMs;
    
    
    // DeepInfra configuration
    @Value("${ai.mapping.deepinfra.url:https://api.deepinfra.com/v1/openai/chat/completions}")
    private String deepInfraUrl;
    
    @Value("${ai.mapping.deepinfra.model:meta-llama/Meta-Llama-3-8B-Instruct}")
    private String deepInfraModel;
    
    @Value("${ai.mapping.deepinfra.token:}")
    private String deepInfraToken;
    
    @Value("${ai.mapping.deepinfra.context.window-size:32768}")
    private int deepInfraContextWindowSize;
    
    @Value("${ai.mapping.deepinfra.context.max-tokens:2048}")
    private int deepInfraMaxTokens;
    
    @Value("${ai.mapping.deepinfra.context.max-file-size:1048576}")
    private long deepInfraMaxFileSize;
    
    @Value("${ai.mapping.deepinfra.context.chunk-size:2048}")
    private int deepInfraChunkSize;
    
    @Value("${ai.mapping.deepinfra.context.preserve-structure:true}")
    private boolean deepInfraPreserveStructure;
    
    @Value("${ai.mapping.deepinfra.context.max-string-length:200}")
    private int deepInfraMaxStringLength;
    
    @Value("${ai.mapping.deepinfra.context.array-sample-size:1}")
    private int deepInfraArraySampleSize;
    
    @Value("${ai.mapping.deepinfra.context.min-occurrences-for-reference:3}")
    private int deepInfraMinOccurrencesForReference;
    
    @Value("${ai.mapping.deepinfra.context.min-length-for-reference:200}")
    private int deepInfraMinLengthForReference;
    
    // Parametres de generation AI
    @Value("${ai.mapping.deepinfra.temperature:0.1}")
    private double deepInfraTemperature;
    
    @Value("${ai.mapping.deepinfra.top_p:0.9}")
    private double deepInfraTopP;
    
    @Value("${ai.mapping.deepinfra.top_k:40}")
    private int deepInfraTopK;
    
    @Value("${ai.mapping.deepinfra.stop_sequences:}")
    private String deepInfraStopSequences;
    
    @Value("${ai.mapping.deepinfra.presence_penalty:0.0}")
    private double deepInfraPresencePenalty;
    
    @Value("${ai.mapping.deepinfra.frequency_penalty:0.0}")
    private double deepInfraFrequencyPenalty;
    
    @Value("${ai.mapping.deepinfra.seed:0}")
    private long deepInfraSeed;
    
    @Value("${ai.mapping.deepinfra.max_steps:1}")
    private int deepInfraMaxSteps;
    
    // Configuration to force JSON output
    @Value("${ai.mapping.deepinfra.force_json_response:true}")
    private boolean deepInfraForceJsonResponse;
    
    @Value("${ai.mapping.deepinfra.json_mode:true}")
    private boolean deepInfraJsonMode;
    
    @Value("${ai.mapping.deepinfra.response_format:json_object}")
    private String deepInfraResponseFormat;
    
    @Value("${ai.mapping.deepinfra.system_prompt_override:true}")
    private boolean deepInfraSystemPromptOverride;
    
    @Value("${ai.mapping.deepinfra.max_prompt_length:30000}")
    private int deepInfraMaxPromptLength;
    
    /**
     * WebClient bean for HTTP calls to AI services.
     * Configured to handle large payloads (50MB).
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .codecs(configurer -> {
                    configurer.defaultCodecs().maxInMemorySize(50 * 1024 * 1024); // 50MB
                });
    }
    
    /**
     * Cache manager for mapping generation results.
     */
    @Bean
    public CacheManager cacheManager() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager();
        cacheManager.setCacheNames(List.of("mapping-cache"));
        return cacheManager;
    }
    
    
    /**
     * DeepInfra-based mapping generator (only if AI mapping is enabled and provider is deepinfra).
     */
    @Bean
    @ConditionalOnProperty(name = "ai.mapping.enabled", havingValue = "true")
    @ConditionalOnProperty(name = "ai.mapping.provider", havingValue = "deepinfra")
    public StrictMappingGenerator deepInfraStrictMappingGenerator(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper) {
        
        return new DeepInfraStrictMappingGenerator(
                webClientBuilder,
                objectMapper,
                deepInfraUrl,
                deepInfraModel,
                deepInfraToken,
                timeoutMs,
                deepInfraContextWindowSize,
                deepInfraMaxTokens,
                deepInfraMaxFileSize,
                deepInfraChunkSize,
                deepInfraPreserveStructure,
                deepInfraMaxStringLength,
                deepInfraArraySampleSize,
                deepInfraMinOccurrencesForReference,
                deepInfraMinLengthForReference,
                deepInfraTemperature,
                deepInfraTopP,
                deepInfraTopK,
                deepInfraStopSequences,
                deepInfraPresencePenalty,
                deepInfraFrequencyPenalty,
                deepInfraSeed,
                deepInfraMaxSteps,
                deepInfraForceJsonResponse,
                deepInfraJsonMode,
                deepInfraResponseFormat,
                deepInfraSystemPromptOverride,
                deepInfraMaxPromptLength
        );
    }
}
