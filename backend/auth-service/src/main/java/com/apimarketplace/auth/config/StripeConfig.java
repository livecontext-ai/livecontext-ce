package com.apimarketplace.auth.config;

import com.stripe.Stripe;
import com.stripe.StripeClient;
import com.apimarketplace.auth.service.PriceCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Set;

@Configuration
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class StripeConfig {

    private static final Logger logger = LoggerFactory.getLogger(StripeConfig.class);
    @Bean
    public StripeClient stripeClient(@Value("${billing.stripe.secretKey}") String secretKey) {
        return new StripeClient(secretKey);
    }
    @Autowired
    private PriceCacheService priceCacheService;
    
    @Value("${billing.stripe.secretKey}")
    private String secretKey;

    @PostConstruct
    public void initStripe() {
        try {
            if (secretKey != null && !secretKey.trim().isEmpty()) {
                Stripe.apiKey = secretKey;
                // SDK 31+: API version is managed by the SDK itself (2025-03-31.acacia)
                // No need to set it manually - the SDK is compiled against this version
                logger.info("Stripe initialise avec succes (SDK 31.0.0, API 2025-03-31.acacia)");
                
                // Log des prix en cache (sans exposer les IDs complets)
                if (priceCacheService != null) {
                    Map<String, String> cachedPrices = priceCacheService.getAllCachedPrices();
                    if (cachedPrices != null && !cachedPrices.isEmpty()) {
                        Set<String> planCodes = cachedPrices.keySet().stream()
                            .map(key -> key.substring(0, key.lastIndexOf('_')))
                            .collect(java.util.stream.Collectors.toSet());
                        logger.info("Plans Stripe en cache: {}", planCodes);
                    } else {
                        logger.warn("Aucun prix en cache trouve");
                    }
                }
            } else {
                logger.warn("Cle secrete Stripe non configuree");
            }
        } catch (Exception e) {
            logger.error("Erreur lors de l'initialisation de Stripe", e);
        }
    }
}
