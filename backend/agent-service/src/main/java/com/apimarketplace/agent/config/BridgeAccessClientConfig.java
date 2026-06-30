package com.apimarketplace.agent.config;

import com.apimarketplace.agent.bridge.BridgeAccessClient;
import com.apimarketplace.agent.bridge.BridgeAccessGuard;
import com.apimarketplace.agent.bridge.HttpBridgeAccessClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the CLI-bridge access guard against the auth-service internal endpoint.
 *
 * <p>Only meaningful in the CE monolith where bridges actually dispatch on
 * the admin's shared CLI session. The same bean registration in cloud
 * agent-service is harmless (bridges are not installed there), but the
 * {@link BridgeAccessGuard#isBridgeProvider(String)} short-circuit keeps
 * non-bridge providers off the auth-service round-trip regardless.
 */
@Configuration
public class BridgeAccessClientConfig {

    @Bean
    public BridgeAccessClient bridgeAccessClient(
            @Value("${services.auth-service.url:http://localhost:8083}") String authUrl) {
        return new HttpBridgeAccessClient(authUrl);
    }

    @Bean
    public BridgeAccessGuard bridgeAccessGuard(
            BridgeAccessClient bridgeAccessClient,
            @Value("${ai.agent.bridge.guard.fail-closed:true}") boolean failClosedWhenClientAbsent) {
        // Default is fail-CLOSED: a deployment that ships bridges must not let a null
        // client become a silent bypass of the shared-subscription quota. Cloud
        // agent-service (no bridges) can set ai.agent.bridge.guard.fail-closed=false
        // to preserve the legacy "no-op allow" behaviour.
        return new BridgeAccessGuard(bridgeAccessClient, failClosedWhenClientAbsent);
    }
}
