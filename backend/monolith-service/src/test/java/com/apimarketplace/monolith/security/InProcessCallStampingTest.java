package com.apimarketplace.monolith.security;

import com.apimarketplace.common.web.MonolithSecurityFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * LC-032, compliance half: the monolith's own clients present this boot's in-process secret, and
 * nothing else does.
 *
 * <p>These tests drive real {@code RestTemplate} calls through the interceptor the bean walk
 * attaches, rather than asserting that the walk found something. That distinction is the point:
 * "the interceptor list has one entry" would pass even if the interceptor stamped the wrong header,
 * stamped every host, or stamped nothing.
 *
 * <p>Every one of them fails on the pre-fix tree, where neither the interceptor nor the walk
 * exists.
 */
@DisplayName("LC-032 in-process call stamping")
class InProcessCallStampingTest {

    private static final int PORT = 8080;
    private static final String SELF = "http://localhost:" + PORT + "/api/internal/credentials/all";
    private static final String EXTERNAL = "https://api.openai.com/v1/chat/completions";
    private static final String OTHER_LOOPBACK_SERVICE = "http://127.0.0.1:9000/workflow-files/x";

    private InProcessCallStampingBeanPostProcessor newStamper() {
        return new InProcessCallStampingBeanPostProcessor(new InProcessCallTarget(PORT));
    }

    // ── What the interceptor puts on the wire ──────────────────────────────────────────────

    @Nested
    @DisplayName("the header on the wire")
    class OnTheWire {

        @Test
        @DisplayName("a call to this monolith carries this boot's secret")
        void selfCallIsStamped() {
            RestTemplate template = new RestTemplate();
            newStamper().postProcessAfterInitialization(template, "restTemplate");
            MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
            server.expect(requestTo(SELF))
                    .andExpect(header(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER,
                            MonolithSecurityFilter.inProcessSecret()))
                    .andRespond(withSuccess());

            template.getForObject(SELF, String.class);

            server.verify();
        }

        @Test
        @DisplayName("a call to a provider API carries nothing, or the secret leaves the box")
        void externalCallIsNotStamped() {
            RestTemplate template = new RestTemplate();
            newStamper().postProcessAfterInitialization(template, "restTemplate");
            MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
            server.expect(requestTo(EXTERNAL))
                    .andExpect(headerDoesNotExist(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER))
                    .andRespond(withSuccess());

            template.getForObject(EXTERNAL, String.class);

            server.verify();
        }

        @Test
        @DisplayName("a call to another service on this host carries nothing either")
        void otherLoopbackServiceIsNotStamped() {
            RestTemplate template = new RestTemplate();
            newStamper().postProcessAfterInitialization(template, "restTemplate");
            MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
            server.expect(requestTo(OTHER_LOOPBACK_SERVICE))
                    .andExpect(headerDoesNotExist(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER))
                    .andRespond(withSuccess());

            template.getForObject(OTHER_LOOPBACK_SERVICE, String.class);

            server.verify();
        }
    }

    // ── What the bean walk reaches ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("what the bean walk reaches")
    class BeanWalk {

        @Test
        @DisplayName("a template held in a private field of a bean, which is how every client holds one")
        void depthOneFieldIsStamped() {
            ClientHoldingATemplate bean = new ClientHoldingATemplate();
            newStamper().postProcessAfterInitialization(bean, "client");

            assertStamped(bean.restTemplate);
        }

        @Test
        @DisplayName("a template two levels down, which is how a listener holds a client that holds one")
        void depthTwoFieldIsStamped() {
            ListenerHoldingAClient bean = new ListenerHoldingAClient();
            newStamper().postProcessAfterInitialization(bean, "listener");

            assertStamped(bean.client.restTemplate);
        }

        @Test
        @DisplayName("a template declared as RestOperations, not as the concrete type")
        void interfaceTypedFieldIsStamped() {
            ClientTypedByInterface bean = new ClientTypedByInterface();
            newStamper().postProcessAfterInitialization(bean, "client");

            assertStamped((RestTemplate) bean.restOperations);
        }

        @Test
        @DisplayName("a template inherited from a superclass in this product")
        void inheritedFieldIsStamped() {
            SubclassOfAClient bean = new SubclassOfAClient();
            newStamper().postProcessAfterInitialization(bean, "client");

            assertStamped(bean.restTemplate);
        }

        @Test
        @DisplayName("a cycle in the bean graph terminates instead of hanging")
        void cyclesTerminate() {
            ListenerHoldingAClient parent = new ListenerHoldingAClient();
            parent.client.back = parent;

            newStamper().postProcessAfterInitialization(parent, "listener");

            assertStamped(parent.client.restTemplate);
        }

        @Test
        @DisplayName("the same template seen twice is stamped once, not once per owner")
        void stampingIsIdempotent() {
            InProcessCallStampingBeanPostProcessor stamper = newStamper();
            ClientHoldingATemplate bean = new ClientHoldingATemplate();

            stamper.postProcessAfterInitialization(bean, "client");
            stamper.postProcessAfterInitialization(bean, "sameClientSeenAgain");
            stamper.postProcessAfterInitialization(bean.restTemplate, "andItsTemplateAsABean");

            assertThat(bean.restTemplate.getInterceptors()).hasSize(1);
            assertThat(stamper.stampedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a WebClient.Builder bean is stamped, whoever declared it")
        void webClientBuilderBeansAreStamped() {
            // This application declares its own WebClient.Builder beans (CatalogWebClientConfig,
            // StorageConfig), which REPLACE Spring Boot's auto-configured one. A WebClientCustomizer
            // would therefore never have run, and the storage mapping resolver's loopback hop would
            // have started answering 401. Post-processing the builder bean itself is what covers it
            // regardless of where the builder came from.
            org.springframework.web.reactive.function.client.WebClient.Builder builder =
                    org.springframework.web.reactive.function.client.WebClient.builder();

            newStamper().postProcessAfterInitialization(builder, "webClientBuilder");

            java.util.concurrent.atomic.AtomicReference<org.springframework.web.reactive.function.client.ClientRequest>
                    captured = new java.util.concurrent.atomic.AtomicReference<>();
            builder.exchangeFunction(request -> {
                captured.set(request);
                return reactor.core.publisher.Mono.just(
                        org.springframework.web.reactive.function.client.ClientResponse
                                .create(org.springframework.http.HttpStatus.OK).build());
            }).build().get().uri("http://localhost:" + PORT + "/api/internal/tools/mapping/1")
                    .retrieve().bodyToMono(String.class).block();

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().headers()
                    .getFirst(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER))
                    .isEqualTo(MonolithSecurityFilter.inProcessSecret());
        }

        @Test
        @DisplayName("a bean outside this product is left alone")
        void foreignBeansAreNotWalked() {
            // A field walk over every bean in the context would be an unbounded reach into other
            // people's objects (a DataSource, an EntityManagerFactory, a connection pool). Only
            // this product's own types are opened.
            com.example.inprocess.ForeignHolder foreign = new com.example.inprocess.ForeignHolder();
            InProcessCallStampingBeanPostProcessor stamper = newStamper();

            stamper.postProcessAfterInitialization(foreign, "foreign");

            assertThat(foreign.restTemplate.getInterceptors()).isEmpty();
            assertThat(stamper.stampedCount()).isZero();
        }

        private void assertStamped(RestTemplate template) {
            assertThat(template.getInterceptors())
                    .as("the client must carry the stamping interceptor, or its loopback hop 401s")
                    .hasSize(1);
            assertThat(template.getInterceptors().get(0)).isInstanceOf(InProcessCallInterceptor.class);
        }
    }

    // ── RestClient: the walk used to skip it, and every CE interface delete failed ───────────

    /**
     * Regression: {@code OrchestratorCascadeClient}, {@code OrchestratorInterfaceMembershipClient}
     * and {@code OrchestratorSubWorkflowLineageClient} hold a {@code RestClient} built from the
     * static factory. The walk only knew {@code RestTemplate} and {@code WebClient.Builder}, so
     * their loopback hops went out without the secret and were answered 404: in CE, deleting any
     * interface failed with 409 ("failed to scrub workflow plan references").
     */
    @Nested
    @DisplayName("RestClient fields and builders")
    class RestClients {

        @Test
        @DisplayName("a final RestClient field is replaced by a copy that stamps a self call")
        void restClientFieldIsStamped() {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            ClientHoldingARestClient bean = new ClientHoldingARestClient(builder.build());
            server.expect(requestTo(SELF))
                    .andExpect(header(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER,
                            MonolithSecurityFilter.inProcessSecret()))
                    .andRespond(withSuccess());

            newStamper().postProcessAfterInitialization(bean, "client");
            bean.restClient.get().uri(SELF).retrieve().toBodilessEntity();

            server.verify();
        }

        @Test
        @DisplayName("the replaced RestClient still sends nothing to an external host")
        void restClientExternalCallIsNotStamped() {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            ClientHoldingARestClient bean = new ClientHoldingARestClient(builder.build());
            server.expect(requestTo(EXTERNAL))
                    .andExpect(headerDoesNotExist(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER))
                    .andRespond(withSuccess());

            newStamper().postProcessAfterInitialization(bean, "client");
            bean.restClient.get().uri(EXTERNAL).retrieve().toBodilessEntity();

            server.verify();
        }

        @Test
        @DisplayName("a RestClient.Builder field gets the interceptor for clients built later")
        void restClientBuilderFieldIsStamped() {
            ClientHoldingARestClientBuilder bean = new ClientHoldingARestClientBuilder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(bean.builder).build();
            server.expect(requestTo(SELF))
                    .andExpect(header(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER,
                            MonolithSecurityFilter.inProcessSecret()))
                    .andRespond(withSuccess());

            newStamper().postProcessAfterInitialization(bean, "client");
            bean.builder.build().get().uri(SELF).retrieve().toBodilessEntity();

            server.verify();
        }

        @Test
        @DisplayName("walking the same bean twice does not stack a second interceptor")
        void restClientStampingIsIdempotent() {
            InProcessCallStampingBeanPostProcessor stamper = newStamper();
            ClientHoldingARestClient bean = new ClientHoldingARestClient(RestClient.create());

            stamper.postProcessAfterInitialization(bean, "client");
            RestClient afterFirst = bean.restClient;
            stamper.postProcessAfterInitialization(bean, "client");

            assertThat(bean.restClient).isSameAs(afterFirst);
            assertThat(interceptorsOf(bean.restClient)).hasSize(1);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "com.apimarketplace.interfaces.client.OrchestratorCascadeClient",
                "com.apimarketplace.interfaces.client.OrchestratorInterfaceMembershipClient",
                "com.apimarketplace.storage.client.OrchestratorSubWorkflowLineageClient"
        })
        @DisplayName("the shipped orchestrator RestClient callers come out stamped")
        void shippedRestClientCallersAreStamped(String className) throws Exception {
            Object client = Class.forName(className)
                    .getConstructor(String.class)
                    .newInstance("http://localhost:" + PORT);

            newStamper().postProcessAfterInitialization(client, "client");

            Field field = client.getClass().getDeclaredField("restClient");
            field.setAccessible(true);
            assertThat(interceptorsOf((RestClient) field.get(client)))
                    .as("%s calls the orchestrator over loopback in CE; without the interceptor that "
                            + "hop is refused", className)
                    .hasSize(1)
                    .allMatch(InProcessCallInterceptor.class::isInstance);
        }

        private List<ClientHttpRequestInterceptor> interceptorsOf(RestClient client) {
            List<ClientHttpRequestInterceptor> seen = new ArrayList<>();
            client.mutate().requestInterceptors(seen::addAll);
            return seen;
        }
    }

    // ── Fixtures. Their package is what makes them "this product's own types". ──────────────

    static class ClientHoldingARestClient {
        private final RestClient restClient;

        ClientHoldingARestClient(RestClient restClient) {
            this.restClient = restClient;
        }
    }

    static class ClientHoldingARestClientBuilder {
        private final RestClient.Builder builder = RestClient.builder();
    }

    static class ClientHoldingATemplate {
        private final RestTemplate restTemplate = new RestTemplate();
    }

    static class ClientWithBackReference {
        private final RestTemplate restTemplate = new RestTemplate();
        private ListenerHoldingAClient back;
    }

    static class ListenerHoldingAClient {
        private final ClientWithBackReference client = new ClientWithBackReference();
    }

    static class ClientTypedByInterface {
        private final RestOperations restOperations = new RestTemplate();
    }

    static class BaseClient {
        protected final RestTemplate restTemplate = new RestTemplate();
    }

    static class SubclassOfAClient extends BaseClient {
    }
}
