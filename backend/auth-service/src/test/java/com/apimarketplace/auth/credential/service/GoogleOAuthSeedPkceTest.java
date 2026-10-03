package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.OAuth2ProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-045 / LC-072 seed invariant: every catalog template that authorizes against Google issues a
 * PKCE challenge and does NOT ask for incremental authorization (see the second test). Reads the real
 * {@code scripts/api-migrations} seeds, so a new Google template added with {@code "pkce": false}
 * (the value every one of them used to carry) fails here rather than in a security review.
 */
@DisplayName("Google OAuth seed templates: PKCE on, incremental auth off")
class GoogleOAuthSeedPkceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path seedDir() {
        for (Path p : List.of(Paths.get("..", "..", "scripts", "api-migrations"),
                Paths.get("..", "scripts", "api-migrations"),
                Paths.get("scripts", "api-migrations"))) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("could not locate scripts/api-migrations from "
                + Paths.get("").toAbsolutePath());
    }

    private static List<JsonNode> googleOAuthConfigs() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(seedDir())) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonNode root = JSON.readTree(f.toFile());
                for (JsonNode auth : root.path("auth")) {
                    JsonNode cfg = auth.path("oauth2Config");
                    if (cfg.path("authorizationUrl").asText("").startsWith("https://accounts.google.com/")) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) cfg).put("_file", f.getFileName().toString());
                        out.add(cfg);
                    }
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("every Google template enables PKCE, and the engine therefore sends a code_challenge")
    void pkceOnForEveryGoogleTemplate() throws Exception {
        List<JsonNode> configs = googleOAuthConfigs();
        assertThat(configs).as("the Google templates were found").hasSizeGreaterThan(25);

        OAuth2Engine engine = new OAuth2Engine();
        for (JsonNode cfg : configs) {
            assertThat(cfg.path("pkce").asBoolean(false)).as("%s: pkce", cfg.path("_file").asText()).isTrue();
            assertThat(engine.shouldUsePkce(OAuth2ProviderConfig.fromJson(cfg)))
                    .as("%s: parsed config issues a PKCE challenge", cfg.path("_file").asText()).isTrue();
        }
    }

    @Test
    @DisplayName("no Google template asks for incremental authorization while per-node scope narrowing is not wired")
    void noIncrementalAuthYet() throws Exception {
        // include_granted_scopes=true makes every Google token the UNION of all scopes the user
        // ever granted to the client: a Drive connect would come back carrying gmail.readonly.
        // It can only come back once each connect requests exactly the scopes its nodes need.
        for (JsonNode cfg : googleOAuthConfigs()) {
            assertThat(cfg.path("authorizeExtraParams").has("include_granted_scopes"))
                    .as("%s: include_granted_scopes", cfg.path("_file").asText())
                    .isFalse();
        }
    }
}
