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
    @DisplayName("application-ce.yml binds the SMTP transport keys, without which the cleartext guard "
        + "is inert in CE and no relay can be configured")
    @SuppressWarnings("unchecked")
    void ceMailTransportKeysArePresent() throws Exception {
        Map<String, Object> root = loadCeYaml();
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Map<String, Object> mail = (Map<String, Object>) spring.get("mail");
        Map<String, Object> properties = (Map<String, Object>) mail.get("properties");

        // CE is the ONLY edition that owns passwords (auth.mode=embedded), so it is
        // the only one that sends a password reset link, and the two transport
        // flags are settled DIFFERENTLY here on purpose: STARTTLS is bound (below),
        // AUTH is deliberately not. Read both assertions before changing either.
        assertThat(mail)
            .as("a self-hosted install cannot point at an authenticated relay without them")
            .containsKeys("username", "password");

        // mail.smtp.auth must stay UNBOUND, and this is the assertion that says so
        // rather than a comment nobody re-reads.
        //
        // Bound with a false default, MailTransportSecurityValidator starts
        // refusing to boot any install whose MAIL_HOST is a non-local relay
        // without AUTH - an install that worked on every earlier release, since
        // this file bound no mail properties at all. Bound with a true default,
        // the shipped credential-less config cannot send at all (measured in
        // MailAuthDefaultContractTest). And the flag buys nothing either way:
        // SmtpAuthNegotiationProbeTest measures that a client with both
        // credentials sends AUTH regardless of it.
        assertThat(properties)
            .as("binding mail.smtp.auth either breaks an upgrade or breaks the credential-less "
                + "default, and credentials alone already authenticate")
            .doesNotContainKey("mail.smtp.auth");

        // STARTTLS is the opposite call, and the difference is worth pinning.
        // Unbound, the session sends in plaintext and NO mainstream relay accepts
        // the mail at all (SendGrid, SES, Mailgun and Gmail all answer 530 Must
        // issue a STARTTLS command first), so the feature was unusable with
        // anything but a local relay. Bound true it is opportunistic: a dev relay
        // that does not advertise TLS is untouched, and the one case that changes
        // (a certificate the JVM does not trust) has mail.smtp.ssl.trust as its
        // escape hatch instead of everyone sending in the clear.
        assertThat(properties)
            .as("without STARTTLS no mainstream relay accepts the message")
            .containsKey("mail.smtp.starttls.enable");

        // mail.smtp.ssl.trust must stay UNBOUND, and this assertion is the guard
        // because the mistake reads as a kindness.
        //
        // An earlier version bound it to ${MAIL_SMTP_SSL_TRUST:} believing an
        // empty value means "use the normal trust store". Measured in
        // auth-service's SmtpSslTrustBindingProbeTest: an empty placeholder binds
        // as a PRESENT key, JavaMail branches on presence, "".split("\s+") is a
        // ONE-element array holding "", and a factory whose trusted-host list is
        // [""] answers isServerTrusted(anything) with false - AFTER the handshake
        // and after CA and hostname validation have already passed. So it blocked
        // delivery to every relay that offers STARTTLS, and only to those, which
        // is why an e2e run against a dev relay that offers none passed anyway.
        //
        // A private-CA relay is handled by trusting the CA (/app/extra-ca, which
        // ce-entrypoint.sh imports into a runtime truststore), not by waiving
        // verification for a host.
        assertThat(properties)
            .as("an empty ssl.trust is an allow-list that allows nothing, not a no-op")
            .doesNotContainKey("mail.smtp.ssl.trust");
        assertThat(String.valueOf(properties.get("mail.smtp.starttls.enable")))
            .isEqualTo("${MAIL_SMTP_STARTTLS:true}");
    }

    @Test
    @DisplayName("application-ce.yml bounds all three SMTP timeouts, which JavaMail otherwise treats "
        + "as infinite on a PUBLIC endpoint that sends mail")
    @SuppressWarnings("unchecked")
    void ceMailTimeoutsAreBounded() throws Exception {
        Map<String, Object> root = loadCeYaml();
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Map<String, Object> mail = (Map<String, Object>) spring.get("mail");
        Map<String, Object> properties = (Map<String, Object>) mail.get("properties");

        // /api/auth/forgot-password is public and unauthenticated. With no timeout,
        // a relay that accepts the TCP connection and never answers holds a thread
        // with nothing to release it.
        assertThat(properties).containsKeys(
            "mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout");
        for (String key : Arrays.asList(
                "mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout")) {
            // Parsed rather than pattern-matched: an earlier version asserted
            // only that the string had no ":0}" in it, which passed for
            // "${X:00}" and for "${X:-5}", and JavaMail reads both as no timeout.
            String declared = String.valueOf(properties.get(key));
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^\\$\\{[A-Z_]+:(-?\\d+)}$").matcher(declared);
            assertThat(matcher.matches())
                .as("%s must be an env placeholder with a numeric default, was %s", key, declared)
                .isTrue();
            assertThat(Integer.parseInt(matcher.group(1)))
                .as("%s must be a POSITIVE number of milliseconds", key)
                .isPositive();
        }
    }

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
    @DisplayName("CE component scan excludes the cloud-only catalog-sync controller but keeps the bundle path mounted")
    void componentScanExcludesCatalogSyncController() {
        ComponentScan scan = MonolithApplication.class.getAnnotation(ComponentScan.class);

        // The LiteLLM/OpenRouter sync is a CLOUD operation. Mounted in CE, an
        // admin could POST ?mode=apply and let the feeds rewrite the local
        // catalog - including renaming the curated seed display names
        // ("Kimi K2.6" -> "kimi-k2.6"), which V416 cannot protect for rows a
        // fresh CE seeds AFTER the migration ran. Asserted by FQCN so a rename
        // or package move, which would silently turn the regex into a no-op,
        // fails here instead of in production.
        assertThat(matchesAnyExclude(scan,
                "com.apimarketplace.agent.catalog.sync.ModelCatalogSyncController"))
            .as("catalog-sync is cloud-only; CE receives the catalog via signed bundles and the seed")
            .isTrue();

        // Only the REST surface is filtered. CatalogBundleController is how CE
        // legitimately receives catalog updates and must stay mounted, and
        // BridgeModelDeriver lives in the same package as the excluded
        // controller - a package-wide regex would take both out.
        assertThat(matchesAnyExclude(scan,
                "com.apimarketplace.agent.catalog.bundle.CatalogBundleController"))
            .as("the bundle path is CE's supported route to catalog updates")
            .isFalse();
        assertThat(matchesAnyExclude(scan,
                "com.apimarketplace.agent.catalog.sync.BridgeModelDeriver"))
            .as("only the controller is excluded, not its whole package")
            .isFalse();
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

    @Test
    @DisplayName("the three cloud-bundle pollers default to a spread schedule, not the quarter hour - "
            + "this file is the ONLY place CE reads them from")
    void bundlePollersDefaultToASpreadSchedule() throws Exception {
        // All three schedulers are @ConditionalOnProperty(...sync.enabled=true) and
        // only CE sets that, so on every install the cron comes from HERE and the
        // annotation default is never reached. Without this assertion, reverting
        // these three lines to `0 */15 * * * *` puts the whole fleet back on the
        // same second - every install downloading the same ~24 MB bundle at once
        // whenever one is published - with the entire suite still green.
        Map<String, Object> root = loadCeYaml();

        for (String[] path : new String[][] {
                {"catalog", "bundle", "sync", "cron"},
                {"api-catalog", "bundle", "sync", "cron"},
                {"skill", "bundle", "sync", "cron"}}) {
            assertThat((String) nestedValue(root, path))
                    .as(String.join(".", path) + " must default to a per-process slot")
                    .contains("PollSpread")
                    .doesNotContain("0 */15 * * * *");
        }
    }

    @Test
    @DisplayName("application-ce.yml sets the MVC async deadline, without which CE serves every "
        + "long file download truncated")
    void asyncRequestTimeoutIsSetForFileStreaming() throws Exception {
        Map<String, Object> root = loadCeYaml();

        // MonolithFileController.proxySignedDownload mounts the SAME endpoint as the
        // cloud storage-service and returns a StreamingResponseBody, so the container's
        // async deadline governs the whole transfer. The container default is 30s.
        // Measured on the cloud mount over the 3 days to 2026-09-21: 112 of 4979
        // requests ran past 30s and 77 past 60s. Losing this property raises no error,
        // it truncates the body on an already-committed response - which is why it needs
        // an assertion rather than a comment.
        Object value = nestedValue(root, "spring", "mvc", "async", "request-timeout");
        assertThat(value)
            .as("spring.mvc.async.request-timeout must stay set in the CE profile")
            .isNotNull();

        // Parsed the way Spring binds it, so `10m` is as valid here as `600000`.
        java.time.Duration timeout = org.springframework.boot.convert.DurationStyle
            .detectAndParse(String.valueOf(value), java.time.temporal.ChronoUnit.MILLIS);
        assertThat(timeout)
            .as("must leave room above the ~61s worst case observed in production, and stay "
                + "FINITE: the async deadline is the only backstop for a stream whose task is "
                + "rejected during a graceful shutdown")
            .isGreaterThanOrEqualTo(java.time.Duration.ofMinutes(10))
            .isLessThanOrEqualTo(java.time.Duration.ofMinutes(30));
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
