package com.apimarketplace.orchestrator.controllers.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PlanSecretRedactor} - scrubbing raw inline secrets from a workflow plan
 * before it is handed to an anonymous APPLICATION share-link viewer.
 */
@DisplayName("PlanSecretRedactor")
class PlanSecretRedactorTest {

    private Map<String, Object> core(String type, Map<String, Object> body) {
        Map<String, Object> core = new HashMap<>();
        core.put("label", "n1");
        core.put(type, body);
        return core;
    }

    private Map<String, Object> planWith(Map<String, Object>... cores) {
        Map<String, Object> plan = new HashMap<>();
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> c : cores) {
            list.add(c);
        }
        plan.put("cores", list);
        return plan;
    }

    @Test
    @DisplayName("removes httpRequest.authConfig (raw bearer/apiKey/password) but keeps the rest of the node")
    void redactsHttpAuthConfig() {
        Map<String, Object> authConfig = new HashMap<>();
        authConfig.put("bearerToken", "secret-bearer");
        authConfig.put("apiKeyValue", "sk_live_123");
        Map<String, Object> http = new HashMap<>();
        http.put("url", "https://example.com");
        http.put("method", "GET");
        http.put("authConfig", authConfig);
        Map<String, Object> plan = planWith(core("httpRequest", http));

        PlanSecretRedactor.redact(plan);

        Map<String, Object> httpAfter = firstCoreChild(plan, "httpRequest");
        assertThat(httpAfter).doesNotContainKey("authConfig");
        assertThat(httpAfter).containsEntry("url", "https://example.com").containsEntry("method", "GET");
    }

    @Test
    @DisplayName("removes cryptoJwt key/secret/token")
    void redactsCryptoJwt() {
        Map<String, Object> crypto = new HashMap<>();
        crypto.put("key", "raw-key");
        crypto.put("secret", "raw-secret");
        crypto.put("token", "raw-token");
        crypto.put("algorithm", "HS256");
        Map<String, Object> plan = planWith(core("cryptoJwt", crypto));

        PlanSecretRedactor.redact(plan);

        Map<String, Object> after = firstCoreChild(plan, "cryptoJwt");
        assertThat(after).doesNotContainKeys("key", "secret", "token");
        assertThat(after).containsEntry("algorithm", "HS256");
    }

    @Test
    @DisplayName("removes ssh/sftp/database inline password & privateKey (the strip-gap fields)")
    void redactsSshSftpDatabaseInlineSecrets() {
        Map<String, Object> ssh = new HashMap<>(Map.of("host", "h", "password", "p", "privateKey", "pk"));
        Map<String, Object> sftp = new HashMap<>(Map.of("host", "h2", "password", "p2", "privateKey", "pk2"));
        Map<String, Object> db = new HashMap<>(Map.of("host", "h3", "password", "p3"));
        Map<String, Object> plan = planWith(core("ssh", ssh), core("sftp", sftp), core("database", db));

        PlanSecretRedactor.redact(plan);

        assertThat(childOf(plan, 0, "ssh")).doesNotContainKeys("password", "privateKey").containsEntry("host", "h");
        assertThat(childOf(plan, 1, "sftp")).doesNotContainKeys("password", "privateKey").containsEntry("host", "h2");
        assertThat(childOf(plan, 2, "database")).doesNotContainKey("password").containsEntry("host", "h3");
    }

    @Test
    @DisplayName("removes sendEmail inline smtpPassword (raw SMTP secret) and its credentialId")
    void redactsSendEmailSmtpPassword() {
        Map<String, Object> email = new HashMap<>();
        email.put("smtpHost", "smtp.example.com");
        email.put("smtpPassword", "raw-smtp-pw");
        email.put("credentialId", 77);
        Map<String, Object> plan = planWith(core("sendEmail", email));

        PlanSecretRedactor.redact(plan);

        Map<String, Object> after = firstCoreChild(plan, "sendEmail");
        assertThat(after).doesNotContainKeys("smtpPassword", "credentialId");
        assertThat(after).containsEntry("smtpHost", "smtp.example.com");
    }

    @Test
    @DisplayName("removes credential references on mcp/agent step buckets")
    void redactsStepCredentialRefs() {
        Map<String, Object> plan = new HashMap<>();
        Map<String, Object> mcp = new HashMap<>();
        mcp.put("selectedCredentialId", "1");
        mcp.put("platformCredentialId", "2");
        mcp.put("credentialSource", "PLATFORM");
        mcp.put("toolName", "search");
        plan.put("mcps", new ArrayList<>(List.of(mcp)));

        PlanSecretRedactor.redact(plan);

        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) ((List<?>) plan.get("mcps")).get(0);
        assertThat(after).doesNotContainKeys("selectedCredentialId", "platformCredentialId", "credentialSource", "credentialId");
        assertThat(after).containsEntry("toolName", "search");
    }

    @Test
    @DisplayName("null plan and plan without cores are handled without error")
    void handlesNullAndEmpty() {
        PlanSecretRedactor.redact(null);
        Map<String, Object> empty = new HashMap<>();
        PlanSecretRedactor.redact(empty);
        assertThat(empty).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstCoreChild(Map<String, Object> plan, String child) {
        List<?> cores = (List<?>) plan.get("cores");
        return (Map<String, Object>) ((Map<String, Object>) cores.get(0)).get(child);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> childOf(Map<String, Object> plan, int coreIndex, String child) {
        List<?> cores = (List<?>) plan.get("cores");
        return (Map<String, Object>) ((Map<String, Object>) cores.get(coreIndex)).get(child);
    }
}
