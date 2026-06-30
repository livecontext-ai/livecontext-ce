package com.apimarketplace.trigger.service;

import java.util.UUID;

/**
 * Shared token generator for all standalone endpoint types (webhook, chat, form, share).
 */
public final class StandaloneEndpointTokenGenerator {

    private StandaloneEndpointTokenGenerator() {
    }

    public static String generate(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateWebhookToken() {
        return generate("wh_");
    }

    public static String generateChatToken() {
        return generate("ch_");
    }

    public static String generateFormToken() {
        return generate("fm_");
    }

    public static String generateShareToken() {
        return generate("cs_");
    }
}
