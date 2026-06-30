package com.apimarketplace.monolith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.apimarketplace.common.bundle.TrustedKeys;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.event.RedisEventBus;
import com.apimarketplace.conversation.streaming.DmEventPublisher;
import com.apimarketplace.monolith.config.MonolithAdapterConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.context.annotation.ComponentScan;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the PR14 (2026-05-13) 2-line fix in {@code application-ce.yml} that ports PR13's
 * V196 GUC protection to the CE Monolith profile. Without these clauses, V196 fail-fasts
 * on every fresh CE Monolith install - empirically verified on 2026-05-13 in a docker
 * rebuild + restart run (livecontext-app crash-loop on V196 RAISE EXCEPTION).
 *
 * Layer A: JDBC URL `?options=-c lc.migration.source_timezone=UTC` (libpq startup option,
 * server-side at session start). The URL must encode the option to survive any future
 * `SPRING_DATASOURCE_URL` env var override that drops the suffix.
 *
 * Layer B: Hikari `connection-init-sql` carries `SET lc.migration.source_timezone = 'UTC'`
 * alongside the existing `SET search_path` clause, delimited by `;` (JDBC accepts
 * multi-statement init scripts). Layer B is belt-and-braces against Layer A being
 * stripped - Hikari runs it on every physical connection creation including pre-warmed
 * `minimum-idle` connections.
 *
 * If either of these assertions fails, the CE Monolith has lost the V196 protection and
 * will crash on next fresh install. See plan.md §11.ter "PR14 CE Monolith application-ce.yml
 * GUC gap" for the design rationale.
 */
class MonolithCeConfigContractTest {

    @Test
    @DisplayName("application-ce.yml JDBC URL carries libpq options= for lc.migration.source_timezone=UTC (PR14 Layer A)")
    @SuppressWarnings("unchecked")
    void jdbcUrlCarriesLcMigrationSourceTimezoneOption() throws Exception {
        Map<String, Object> root = loadCeYaml();
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
        String url = (String) datasource.get("url");

        assertThat(url)
            .as("PR14 Layer A: the libpq `options=` startup parameter must be encoded in the URL "
                + "so V196 sees the GUC at session start. URL-encoded form: -c%20lc.migration.source_timezone%3DUTC")
            .contains("options=-c%20lc.migration.source_timezone%3DUTC");
    }

    @Test
    @DisplayName("application-ce.yml Hikari connection-init-sql sets lc.migration.source_timezone alongside search_path (PR14 Layer B)")
    @SuppressWarnings("unchecked")
    void hikariConnectionInitSqlCarriesLcMigrationSourceTimezone() throws Exception {
        Map<String, Object> root = loadCeYaml();
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
        Map<String, Object> hikari = (Map<String, Object>) datasource.get("hikari");
        String initSql = (String) hikari.get("connection-init-sql");

        assertThat(initSql)
            .as("PR14 Layer B: every pooled connection acquires with the GUC set, so an env-var "
                + "override that drops the URL options= still leaves V196 with a valid session-level GUC")
            .contains("SET lc.migration.source_timezone = 'UTC'");

        assertThat(initSql)
            .as("Pre-existing search_path setup must be preserved alongside the new GUC clause - "
                + "Hibernate relies on it to find tables in any schema without explicit prefix")
            .contains("SET search_path TO orchestrator");
    }

    @Test
    @DisplayName("application-ce.yml Hikari connection-init-sql uses `;` to delimit the two SET statements (multi-statement contract)")
    @SuppressWarnings("unchecked")
    void hikariConnectionInitSqlUsesSemicolonDelimiter() throws Exception {
        Map<String, Object> root = loadCeYaml();
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
        Map<String, Object> hikari = (Map<String, Object>) datasource.get("hikari");
        String initSql = (String) hikari.get("connection-init-sql");

        // Strict regex: the search_path statement MUST end with a `;` BEFORE the source_timezone
        // statement starts. `[^;]*` ensures no semicolon appears inside the search_path list
        // (which contains commas but no semicolons today) - anchoring forces the delimiter to be
        // the boundary between the two SETs, not an incidental punctuation. A refactor that
        // converts the delimiter to a newline-only would fail this regex even though the file
        // still "contains a semicolon" somewhere.
        //
        // The contract this pins: Hikari forwards the whole string as one JDBC execute() call;
        // pgjdbc routes that through the PostgreSQL `simple_query` protocol (the default -
        // `preferQueryMode=simple` is the implicit pgjdbc setting). simple_query splits on `;`
        // and runs the statements sequentially. If a customer ever overrode preferQueryMode to
        // `extended` or `extendedForPrepared`, multi-statement init breaks silently - out of
        // scope for PR14 but noted here so a future regression can locate the assumption.
        assertThat(initSql)
            .as("The two SET statements must be `;`-separated; the search_path list must NOT "
                + "contain an embedded semicolon (today it's comma-separated, this regex catches "
                + "a future innocent refactor that swaps to semicolon-separated schemas)")
            .matches("(?s).*SET\\s+search_path\\s+TO\\s+[^;]*;\\s*SET\\s+lc\\.migration\\.source_timezone\\s*=.*");
    }

    @Test
    @DisplayName("CE component scan keeps chat attachments mounted while excluding reactive v3 chat and stream controllers")
    void componentScanKeepsAttachmentControllerMounted() {
        ComponentScan scan = MonolithApplication.class.getAnnotation(ComponentScan.class);

        assertThat(matchesAnyExclude(scan, "com.apimarketplace.conversation.controller.v3.AttachmentController"))
            .as("AttachmentController uses AttachmentService/StorageService only and must remain mounted in CE")
            .isFalse();
        assertThat(matchesAnyExclude(scan, "com.apimarketplace.conversation.controller.v3.ChatControllerV3"))
            .as("ChatControllerV3 depends on reactive stream wiring and is replaced by MonolithChatController")
            .isTrue();
        assertThat(matchesAnyExclude(scan, "com.apimarketplace.conversation.controller.v3.StreamControllerV3"))
            .as("StreamControllerV3 depends on reactive stream state and is replaced by CE no-op stubs")
            .isTrue();
    }

    @Test
    @DisplayName("application-ce.yml disables http.server.requests metrics to avoid high-cardinality URI tag warnings")
    @SuppressWarnings("unchecked")
    void ceDisablesHttpServerRequestMetrics() throws Exception {
        Map<String, Object> root = loadCeYaml();
        Map<String, Object> management = (Map<String, Object>) root.get("management");
        Map<String, Object> metrics = (Map<String, Object>) management.get("metrics");
        Map<String, Object> enable = (Map<String, Object>) metrics.get("enable");

        assertThat(enable.get("http.server.requests"))
            .as("CE exposes only health/info actuator endpoints; request metrics add noisy URI tag cap warnings "
                + "without user-facing value in the monolith Docker profile")
            .isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("application-ce.yml enables the real catalog ToolsGateway for CE workflow CRUD nodes")
    void ceWorkflowCrudNodesUseRealToolsGateway() throws Exception {
        Map<String, Object> root = loadCeYaml();
        String selfUrl = "http://localhost:${PORT:8080}";

        assertThat(nestedValue(root, "orchestrator", "mock", "enabled"))
            .as("CE has no MockToolsGateway bean; leaving mock mode at the Java default makes workflow CRUD nodes "
                + "fall back to passthrough instead of calling the datasource CRUD executor")
            .isEqualTo(Boolean.FALSE);
        assertThat(nestedValue(root, "orchestrator", "catalog", "enabled"))
            .as("CatalogToolsGateway must be mounted in CE so table:create/read/update/delete workflow nodes execute")
            .isEqualTo(Boolean.TRUE);
        assertThat(nestedValue(root, "orchestrator", "catalog", "base-url"))
            .as("Non-CRUD catalog tool execution in the monolith must loop back to the embedded gateway")
            .isEqualTo(selfUrl);
    }

    @Test
    @DisplayName("MonolithAdapterConfig exposes catalogToolsGateway alias for orchestrator qualifier injection")
    void ceExposesCatalogToolsGatewayAliasForExecutionInjector() throws Exception {
        String gatewayBeanName = "com.apimarketplace.orchestrator.services.impl.CatalogToolsGateway";
        SimpleBeanDefinitionRegistry registry = new SimpleBeanDefinitionRegistry();
        GenericBeanDefinition definition = new GenericBeanDefinition();
        definition.setBeanClassName(gatewayBeanName);
        registry.registerBeanDefinition(gatewayBeanName, definition);

        BeanDefinitionRegistryPostProcessor aliasRegistrar = MonolithAdapterConfig.monolithCatalogToolsGatewayAlias();
        aliasRegistrar.postProcessBeanDefinitionRegistry(registry);

        assertThat(registry.getAliases(gatewayBeanName))
            .as("The CE monolith uses fully-qualified component bean names, while ExecutionServiceInjector injects "
                + "the historical 'catalogToolsGateway' qualifier")
            .contains("catalogToolsGateway");
    }

    @Test
    @DisplayName("MonolithAdapterConfig exposes Redis EventBus for CE WebSocket bridge publishers")
    void ceUsesRedisEventBusForWebSocketBridge() {
        MonolithAdapterConfig config = new MonolithAdapterConfig();

        EventBus eventBus = config.monolithRedisEventBus(
            mock(StringRedisTemplate.class),
            mock(RedisMessageListenerContainer.class),
            new SimpleMeterRegistry()
        );

        assertThat(eventBus)
            .as("CE WebSocket publishers must write to Redis ws:* channels because MonolithWsHandler bridges Redis to browser sessions")
            .isInstanceOf(RedisEventBus.class);
    }

    @Test
    @DisplayName("MonolithAdapterConfig exposes the DM Redis publisher for CE live DM WebSocket events")
    @SuppressWarnings("unchecked")
    void ceExposesDmEventPublisherForLiveWebSocketEvents() {
        MonolithAdapterConfig config = new MonolithAdapterConfig();

        DmEventPublisher publisher = config.dmEventPublisher(
            mock(ReactiveRedisTemplate.class),
            new ObjectMapper().findAndRegisterModules()
        );

        assertThat(publisher)
            .as("CE DM messages must publish to Redis ws:dm:* and ws:dm-inbox:* channels so MonolithWsHandler can fan them out")
            .isNotNull();
    }

    @Test
    @DisplayName("application-ce.yml routes monolith-only HTTP clients back to the embedded server")
    void ceInterServiceClientsUseEmbeddedServerUrl() throws Exception {
        Map<String, Object> root = loadCeYaml();
        String selfUrl = "http://localhost:${PORT:8080}";

        assertThat(nestedValue(root, "services", "trigger-service", "url"))
            .as("AgentController and AgentService read services.trigger-service.url for agent schedules; "
                + "in CE monolith this must not fall back to localhost:8091")
            .isEqualTo(selfUrl);
        assertThat(nestedValue(root, "agent", "conversation", "base-url"))
            .as("Agent-service's ConversationClient reads agent.conversation.base-url; "
                + "CE agent conversations must not call localhost:8087")
            .isEqualTo(selfUrl);
        assertThat(nestedValue(root, "orchestrator", "conversation", "base-url"))
            .as("Orchestrator conversation clients also run inside the same CE monolith process")
            .isEqualTo(selfUrl);
        assertThat(nestedValue(root, "conversation", "service", "url"))
            .as("ConversationAgentService self-callback URL should stay aligned with the CE monolith port")
            .isEqualTo(selfUrl);
    }

    @Test
    @DisplayName("application-ce.yml maps Cloud Link OAuth and IP hash settings to CE Docker env vars")
    void ceCloudLinkUsesBackendCallbackAndGeneratedIpHashKey() throws Exception {
        Map<String, Object> root = loadCeYaml();

        assertThat(nestedValue(root, "cloud-link", "keycloak-url"))
            .as("CE Cloud Link must use the public Keycloak hostname configured for the cloud realm")
            .isEqualTo("${CLOUD_KEYCLOAK_URL:https://auth.livecontext.ai/realms/livecontext}");
        assertThat(nestedValue(root, "cloud-link", "client-id"))
            .as("The cloud realm config extends the existing public PKCE client")
            .isEqualTo("${CLOUD_LINK_CLIENT_ID:livecontext-frontend}");
        assertThat(nestedValue(root, "cloud-link", "redirect-uri"))
            .as("OAuth codes must return to the CE backend callback, not a frontend URL with code= in the query string")
            .isEqualTo("${CLOUD_LINK_REDIRECT_URI:http://localhost:8080/api/cloud-link/callback}");
        assertThat(nestedValue(root, "cloud-link", "ip-hash", "key-v1"))
            .as("ce-entrypoint.sh generates IP_HASH_HMAC_KEY_V1 on first boot; the Spring property must read it")
            .isEqualTo("${CLOUD_LINK_IP_HASH_KEY_V1:${IP_HASH_HMAC_KEY_V1:}}");
        assertThat(nestedValue(root, "cloud-link", "ip-hash", "current-version"))
            .as("CE should boot with the V1 IP hash key unless an operator explicitly rotates to V2")
            .isEqualTo("${CLOUD_LINK_IP_HASH_CURRENT_VERSION:${KEY_HMAC_CURRENT_VERSION:1}}");
    }

    @Test
    @DisplayName("application-ce.yml bakes the cloud's PUBLIC Ed25519 key as the default catalog.bundle.trusted-keys "
            + "so a fresh CE trusts signed bundles out of the box (no more TRUST_UNCONFIGURED)")
    @SuppressWarnings("unchecked")
    void ceBakesCloudPublicKeyAsDefaultTrustedKey() throws Exception {
        Map<String, Object> root = loadCeYaml();
        Map<String, Object> catalog = (Map<String, Object>) root.get("catalog");
        Map<String, Object> bundle = (Map<String, Object>) catalog.get("bundle");
        String raw = (String) bundle.get("trusted-keys");

        // Shape: ${CATALOG_BUNDLE_TRUSTED_KEYS:<default>}. The env var name has no ':',
        // and the default carries no ':' or '}', so stripping the first '${NAME:' and the
        // trailing '}' yields exactly the baked default value.
        assertThat(raw)
            .as("trusted-keys must keep the CATALOG_BUNDLE_TRUSTED_KEYS env override")
            .startsWith("${CATALOG_BUNDLE_TRUSTED_KEYS:");
        String bakedDefault = raw.replaceFirst("^\\$\\{[^:]+:", "").replaceFirst("}$", "");

        // The empty default is exactly the bug: TrustedKeyRegistry.hasKeys()=false, so
        // CatalogBundleSyncScheduler records TRUST_UNCONFIGURED on every 15-min tick.
        assertThat(bakedDefault)
            .as("an empty default reintroduces the TRUST_UNCONFIGURED sync failures")
            .isNotBlank()
            .contains("livecontext-prod-v1=");

        // Parse the baked default through the SAME class the runtime uses
        // (TrustedKeyRegistry / ApiCatalogTrustedKeyRegistry both delegate to it).
        // A malformed base64 key would silently parse to zero keys, back to square one.
        TrustedKeys keys = new TrustedKeys(bakedDefault);
        assertThat(keys.hasKeys())
            .as("the baked key must decode to a usable pinned key")
            .isTrue();
        assertThat(keys.keyIds()).containsExactly("livecontext-prod-v1");
        assertThat(keys.find("livecontext-prod-v1"))
            .as("the pinned keyId must resolve to a real Ed25519 public key the verifier can use")
            .isPresent();
    }

    private Map<String, Object> loadCeYaml() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application-ce.yml")) {
            if (in == null) {
                throw new IllegalStateException(
                    "application-ce.yml not found on test classpath - check src/main/resources packaging");
            }
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object nestedValue(Map<String, Object> root, String... path) {
        Object current = root;
        for (String segment : path) {
            assertThat(current)
                .as("Expected YAML path segment '%s' in %s", segment, String.join(".", path))
                .isInstanceOf(Map.class);
            current = ((Map<String, Object>) current).get(segment);
        }
        return current;
    }

    private static boolean matchesAnyExclude(ComponentScan scan, String className) {
        return Arrays.stream(scan.excludeFilters())
            .flatMap(filter -> Arrays.stream(filter.pattern()))
            .map(Pattern::compile)
            .anyMatch(pattern -> pattern.matcher(className).matches());
    }
}
