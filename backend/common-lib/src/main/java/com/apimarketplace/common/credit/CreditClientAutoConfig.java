package com.apimarketplace.common.credit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Shared {@code @Configuration} that registers the
 * {@link CreditConsumptionClient} bean for any service that needs to
 * debit / refund credits via auth-service.
 *
 * <p><b>Activation model - read this before adding new services.</b>
 * Despite the {@code AutoConfig} suffix, this class is <b>NOT</b> a Spring
 * Boot {@link org.springframework.boot.autoconfigure.AutoConfiguration} -
 * it is not registered in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * It activates only when the importing service explicitly includes
 * {@code com.apimarketplace.common.credit} in its
 * {@link org.springframework.context.annotation.ComponentScan} basePackages.
 * Today: {@code orchestrator-service} and {@code catalog-service}.
 *
 * <p>Other services ({@code auth-service}, {@code agent-service},
 * {@code conversation-service}, {@code publication-service}) ship their
 * own per-service {@code CreditClientConfig} that registers an equivalent
 * bean. Those configs do not currently scan {@code common.credit}, so
 * there is no clash; if a future change adds the scan, the
 * {@link ConditionalOnMissingBean} guard below preserves the per-service
 * bean as the winner. The duplication is intentional during the migration
 * window - collapsing every service onto this shared config is tracked
 * separately.
 *
 * <p>Defaults:
 * <ul>
 *   <li>{@code services.auth-service.url} → {@code http://localhost:8083}</li>
 *   <li>{@code credit.consumption.enabled} → {@code true}</li>
 * </ul>
 * Dead-letter events are forwarded to auth-service via HTTP
 * (auth-service owns the dead-letter table and retry job).
 */
@Configuration
public class CreditClientAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public CreditConsumptionClient creditConsumptionClient(
            @Value("${services.auth-service.url:http://localhost:8083}") String authServiceUrl,
            @Value("${credit.consumption.enabled:true}") boolean enabled,
            @Value("${gateway.filter.secret-key:${GATEWAY_SECRET_KEY:}}") String gatewaySecretKey) {
        CreditConsumptionClient client = new CreditConsumptionClient(authServiceUrl, enabled, gatewaySecretKey);
        client.setDeadLetterHandler(
                new HttpCreditDeadLetterHandler(new RestTemplate(), authServiceUrl));
        return client;
    }
}
