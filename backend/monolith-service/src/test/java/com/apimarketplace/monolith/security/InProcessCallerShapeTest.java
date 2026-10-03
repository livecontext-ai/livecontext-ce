package com.apimarketplace.monolith.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The LC-032 caller enumeration, as an assertion instead of a paragraph.
 *
 * <p><b>What this pins and why it is here.</b> The in-process secret is refused unless the caller
 * presents it, and the callers are made to present it by
 * {@link InProcessCallStampingBeanPostProcessor}, which finds their HTTP client by walking bean
 * fields. That walk works on one property of the codebase: <b>every in-process caller keeps its
 * HTTP client in an instance FIELD</b>, rather than building one inside the method that makes the
 * call. If any of these classes is refactored to the second shape, its loopback hop silently stops
 * being stamped and starts answering 401 or 404 - at runtime, on a path no unit test drives.
 *
 * <p>The list below is that enumeration, produced mechanically (every construction of an outbound
 * {@code X-User-ID} header in the tree, plus the one loopback {@code WebClient} caller), and kept
 * executable so it cannot quietly become false. Three previous passes wrote this list as prose and
 * two of the three versions were wrong; a paragraph cannot notice a refactor.
 *
 * <p>It does NOT claim to be closed under future additions: a client added tomorrow is covered by
 * the walk without appearing here. What it catches is the regression that would break the walk.
 */
@DisplayName("LC-032: every in-process caller keeps its HTTP client where the stamping can reach it")
class InProcessCallerShapeTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            // The client JARs. Each is produced by a @Bean method somewhere in the monolith, so the
            // bean IS the client and its template sits one field down.
            "com.apimarketplace.auth.client.AuthClient",
            "com.apimarketplace.agent.client.AgentClient",
            "com.apimarketplace.credential.client.CredentialClient",
            "com.apimarketplace.datasource.client.DataSourceClient",
            "com.apimarketplace.interfaces.client.InterfaceClient",
            "com.apimarketplace.publication.client.PublicationClient",
            "com.apimarketplace.storage.client.StorageClient",
            "com.apimarketplace.trigger.client.TriggerClient",
            "com.apimarketplace.conversation.client.ConversationClient",
            "com.apimarketplace.notification.client.NotificationClient",
            "com.apimarketplace.common.credit.CreditConsumptionClient",

            // Service-side callers that build their outbound headers directly and never touch a
            // shared helper. This is the group the earlier prose versions kept getting wrong.
            "com.apimarketplace.trigger.service.OrchestratorFireClient",
            "com.apimarketplace.publication.config.OrchestratorInternalClient",
            "com.apimarketplace.publication.service.AvatarFileCloneService",
            "com.apimarketplace.publication.controller.PublicationScreeningController",
            "com.apimarketplace.agent.bridge.HttpBridgeAccessClient",
            "com.apimarketplace.agent.cloud.PublicationCloudLlmRuntimeAccess",
            "com.apimarketplace.orchestrator.services.mcp.RemoteToolGateway",
            "com.apimarketplace.orchestrator.services.impl.CatalogMockClient",
            "com.apimarketplace.orchestrator.services.impl.CatalogToolsGateway",
            "com.apimarketplace.orchestrator.config.ConversationStorageClient",
            "com.apimarketplace.orchestrator.services.generation.GenerationExecutionService",
            "com.apimarketplace.orchestrator.trigger.ChatDispatchService",
            "com.apimarketplace.conversation.service.ai.AgentConfigProvider",
            "com.apimarketplace.conversation.service.ai.AgentObservabilityClient",
            "com.apimarketplace.conversation.service.ai.BridgeAccessEnforcer",
            "com.apimarketplace.conversation.service.ai.HttpLlmJsonInvoker",
            "com.apimarketplace.conversation.service.ai.WorkflowContextProvider",
            "com.apimarketplace.agent.service.RunCostNotifier",
            "com.apimarketplace.agent.service.execution.RemoteToolExecutionService",
            "com.apimarketplace.agent.controller.AgentController",
            "com.apimarketplace.agent.service.AgentService",
            "com.apimarketplace.agent.tools.agent.AgentConversationModule",
            "com.apimarketplace.agent.tools.agent.AgentCrudModule",
            "com.apimarketplace.auth.web.MeController",

            // RestClient callers. The walk replaces their built client with a stamped copy; they
            // were missing from this list, and unstamped, until the CE e2e caught every interface
            // delete failing with 409.
            "com.apimarketplace.interfaces.client.OrchestratorCascadeClient",
            "com.apimarketplace.interfaces.client.OrchestratorInterfaceMembershipClient",
            "com.apimarketplace.storage.client.OrchestratorSubWorkflowLineageClient",

            // The one loopback caller that is not a RestTemplate at all. It holds a built
            // WebClient, so what the stamping actually reaches is the BUILDER bean it was built
            // from; this entry is here so a refactor away from the shared builder is noticed.
            "com.apimarketplace.common.storage.service.StorageMappingResolverService"
    })
    @DisplayName("holds its HTTP client in an instance field")
    void holdsItsClientInAField(String className) {
        Class<?> type = load(className);

        List<String> clientFields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (isHttpClientType(field.getType())) {
                    clientFields.add(c.getSimpleName() + "." + field.getName());
                }
            }
        }

        assertThat(clientFields)
                .as("%s makes an in-process call, so the bean walk has to be able to find its HTTP "
                        + "client. Building one inside the calling method instead would leave that "
                        + "hop unstamped, and an unstamped in-process hop is refused.", className)
                .isNotEmpty();
    }

    /**
     * The field check above cannot tell a STAMPED client from an unstamped one, and that blind spot
     * shipped a CE outage.
     *
     * <p>A {@code WebClient} field satisfies {@code holdsItsClientInAField} whether it was built
     * from the injected {@code WebClient.Builder} bean (which the post-processor configures, so the
     * hop carries the secret) or from the static {@code WebClient.builder()} factory (which is
     * never a bean, so it is never post-processed and the hop goes out bare). {@code OAuth2Service}
     * was the second shape: in the CE monolith its {@code services.catalog-url} resolves to this
     * same JVM over loopback, so once peer-address trust was replaced by the secret, every OAuth
     * credential connect would have failed with "Credential template not found" on every
     * self-hosted install, and no test would have gone red.
     *
     * <p>So this scans the SOURCE instead of the classpath: no main source may build an HTTP client
     * from the static factory unless it is on the list below with a reason. Source rather than
     * reflection because the distinction is a call site, not a type, and it is invisible once the
     * object exists.
     */
    @Test
    @DisplayName("no service builds its own client from the static WebClient factory")
    void noServiceBuildsAnUnstampedClient() throws IOException {
        Path backend = repoRoot().resolve("backend");
        assertThat(Files.isDirectory(backend)).as("backend/ should be reachable from the test CWD").isTrue();

        // Legitimate static uses, each for a reason the scan cannot infer.
        List<String> allowed = List.of(
                // The BUILDER BEAN declarations themselves. Something has to call the factory once;
                // these are what everyone else injects, and they are what the post-processor
                // configures.
                "config/CatalogWebClientConfig.java",
                "config/StorageConfig.java",
                "config/WebClientConfig.java",
                // Outbound egress to a third-party model provider, never a loopback hop, so there
                // is no in-process secret to carry and no bean walk to satisfy.
                "provider/AbstractLLMProvider.java",
                // Outbound egress to whatever third-party API a catalog tool targets, pinned to
                // the SSRF-vetted address (LC-073/LC-006). Deliberately NOT a shared bean (see its
                // own javadoc): never a loopback hop to another LiveContext service, so there is no
                // in-process secret to carry.
                "service/http/OutboundHttpClients.java",
                // Outbound egress to whatever external RSS/Atom feed URL the workflow author
                // configured, SSRF-vetted the same way. Never a loopback hop to another
                // LiveContext service.
                "execution/v2/nodes/RssNode.java");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(backend)) {
            sources.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    // A different image with a different security model, out of scope by design.
                    .filter(p -> !p.toString().replace('\\', '/').contains("/catalog-service-import/"))
                    .filter(p -> allowed.stream().noneMatch(a -> p.toString().replace('\\', '/').endsWith(a)))
                    .forEach(p -> {
                        try {
                            String body = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                            if (body.contains("WebClient.builder()") || body.contains("WebClient.create(")) {
                                offenders.add(backend.relativize(p).toString().replace('\\', '/'));
                            }
                        } catch (IOException e) {
                            offenders.add(p + " (unreadable: " + e.getMessage() + ")");
                        }
                    });
        }

        assertThat(offenders)
                .as("a client built from the static factory is never post-processed, so if this "
                        + "class ever makes a loopback call the hop goes out without the in-process "
                        + "secret and is refused, at runtime, on a path no unit test drives. Inject "
                        + "WebClient.Builder instead. If the client genuinely only talks to an "
                        + "external host, add it to the allow-list above WITH that reason.")
                .isEmpty();
    }

    /**
     * Removes block and line comments so the scan reads CODE only.
     *
     * <p>Not cosmetic: the first run of this rule flagged {@code OAuth2Service}, the very class
     * that had just been fixed, because the comment explaining the rule quotes the forbidden call.
     * A guard that punishes documenting it teaches people to stop documenting it. String literals
     * are deliberately NOT stripped: nothing here legitimately puts that call in a string, and
     * pretending to parse Java with a regex is how a guard acquires its own bugs.
     */
    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("backend/monolith-service"))) {
            dir = dir.getParent();
        }
        return dir;
    }

    private static boolean isHttpClientType(Class<?> type) {
        return RestTemplate.class.isAssignableFrom(type)
                || RestOperations.class.isAssignableFrom(type)
                || WebClient.class.isAssignableFrom(type)
                || WebClient.Builder.class.isAssignableFrom(type)
                || RestClient.class.isAssignableFrom(type)
                || RestClient.Builder.class.isAssignableFrom(type);
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, InProcessCallerShapeTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            // Deliberately a failure, not a skip. A silently skipped entry is how a list of callers
            // rots into a list of names, which is the failure mode this test exists to prevent.
            return fail("%s is in the LC-032 caller enumeration but is not on the monolith "
                    + "classpath. Either it was renamed or removed - update the list - or the "
                    + "monolith no longer bundles it, which changes what the stamping must cover.",
                    className);
        }
    }
}
