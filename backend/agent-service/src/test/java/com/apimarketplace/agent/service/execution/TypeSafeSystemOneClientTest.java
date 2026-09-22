package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.ClassifyRequestDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The decision-engine call behind a classify node.
 *
 * <p>Two properties are worth more than the rest and most of this file defends them: the
 * options offered are the node's own category labels, so the answer round-trips to a
 * branch; and every failure returns a response the run can show instead of an exception
 * nobody sees.
 */
@DisplayName("TypeSafeSystemOneClient")
class TypeSafeSystemOneClientTest {

    private static final String URL = "https://api.typesafe.test/v1/systemone";
    private static final String KEY = "ts-test-key";

    private TypeSafeSystemOneClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        client = new TypeSafeSystemOneClient(URL, KEY);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    private ClassifyRequestDto request(String content, String prompt,
                                        ClassifyRequestDto.CategoryDto... categories) {
        return new ClassifyRequestDto(content, prompt, List.of(categories), "typesafe",
                "jev-latest", null, null, "tenant-1", "agent-1");
    }

    private static ClassifyRequestDto.CategoryDto category(String label, String description) {
        return new ClassifyRequestDto.CategoryDto(label, description);
    }

    private static String answer(String choice, String probabilities, String confidence) {
        return """
            {"model":"jev-1.13","answers":{"category":{"type":"choice","choice":"%s",
             "confidence":%s,"probabilities":%s}},
             "usage":{"input_tokens":1150,"output_tokens":0}}
            """.formatted(choice, confidence, probabilities);
    }

    @Nested
    @DisplayName("Routing")
    class Routing {

        @Test
        @DisplayName("claims the typesafe provider and no other")
        void claimsOnlyItsOwnProvider() {
            assertThat(client.supports("typesafe")).isTrue();
            assertThat(client.supports("TypeSafe")).isTrue();
            assertThat(client.supports("anthropic")).isFalse();
            assertThat(client.supports(null)).isFalse();
        }

        @Test
        @DisplayName("recognises its model when a plan named the model but no provider")
        void recognisesTheModelWhenTheProviderIsOmitted() {
            // provider is optional on a classify node, and a plan that names only the model
            // resolves through the CHAT-filtered catalogue, which by design holds no
            // decision model: the lookup returns null and the node would ask a chat
            // provider for a model it has never heard of, failing three layers from the
            // plan that caused it.
            client.setDecisionProvider(
                    new com.apimarketplace.agent.provider.TypeSafeDecisionProvider(
                            true, "k", "jev-latest", 20));

            assertThat(client.serves(null, "jev-latest")).isTrue();
            assertThat(client.serves("", "jev-latest")).isTrue();
            assertThat(client.serves(null, "gpt-5-mini")).isFalse();
            assertThat(client.serves(null, null)).isFalse();
        }

        @Test
        @DisplayName("an explicit chat provider still wins, even on one of its model names")
        void anExplicitProviderIsNeverSecondGuessed() {
            client.setDecisionProvider(
                    new com.apimarketplace.agent.provider.TypeSafeDecisionProvider(
                            true, "k", "jev-latest", 20));

            // The caller said where to go. Recognising the model must only fill a gap, never
            // overrule a choice.
            assertThat(client.serves("anthropic", "jev-latest")).isFalse();
            assertThat(client.serves("typesafe", "anything")).isTrue();
        }

        @Test
        @DisplayName("with no provider bean wired, a model-only plan simply is not claimed")
        void modelOnlyIsNotClaimedWithoutTheProviderBean() {
            assertThat(client.serves(null, "jev-latest")).isFalse();
        }

        @Test
        @DisplayName("reports itself unconfigured when no key is set, so the node can say why")
        void reportsUnconfiguredWithoutAKey() {
            assertThat(new TypeSafeSystemOneClient(URL, "").isConfigured()).isFalse();
            assertThat(new TypeSafeSystemOneClient(URL, null).isConfigured()).isFalse();
            assertThat(client.isConfigured()).isTrue();
        }
    }

    @Nested
    @DisplayName("Request")
    class Request {

        @Test
        @DisplayName("asks one choice question whose options ARE the declared category labels")
        void optionsAreTheDeclaredLabels() {
            server.expect(requestTo(URL))
                    .andExpect(method(org.springframework.http.HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer " + KEY))
                    .andExpect(jsonPath("$.model").value("jev-latest"))
                    .andExpect(jsonPath("$.state").value("a support ticket"))
                    .andExpect(jsonPath("$.questions.category.type").value("choice"))
                    .andExpect(jsonPath("$.questions.category.criteria.billing").value("Payment issues"))
                    .andExpect(jsonPath("$.questions.category.criteria.technical").value("Bugs"))
                    .andRespond(withSuccess(answer("billing", "{\"billing\":0.9,\"technical\":0.1}", "0.88"),
                            MediaType.APPLICATION_JSON));

            client.classify(request("a support ticket", "Route it",
                    category("billing", "Payment issues"), category("technical", "Bugs")),
                    "typesafe", System.currentTimeMillis());

            server.verify();
        }

        @Test
        @DisplayName("content is the state and the prompt is the instruction, kept apart")
        void contentAndPromptAreKeptApart() {
            server.expect(requestTo(URL))
                    .andExpect(jsonPath("$.state").value("the text to judge"))
                    .andExpect(jsonPath("$.questions.category.instructions").value("Route by urgency"))
                    .andRespond(withSuccess(answer("a", "{\"a\":1.0}", "1.0"), MediaType.APPLICATION_JSON));

            client.classify(request("the text to judge", "Route by urgency", category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            server.verify();
        }

        @Test
        @DisplayName("with only a prompt, the prompt is the state and the instruction is the default")
        void promptOnlyBecomesTheState() {
            server.expect(requestTo(URL))
                    .andExpect(jsonPath("$.state").value("Classify: hello"))
                    .andExpect(jsonPath("$.questions.category.instructions")
                            .value("Classify this content into exactly one of the categories."))
                    .andRespond(withSuccess(answer("a", "{\"a\":1.0}", "1.0"), MediaType.APPLICATION_JSON));

            client.classify(request(null, "Classify: hello", category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            server.verify();
        }

        @Test
        @DisplayName("a category with no description sends null, not an empty criterion")
        void blankDescriptionBecomesNull() {
            server.expect(requestTo(URL))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("\"a\":null")))
                    .andRespond(withSuccess(answer("a", "{\"a\":1.0}", "1.0"), MediaType.APPLICATION_JSON));

            client.classify(request("text", null, category("a", "   ")),
                    "typesafe", System.currentTimeMillis());

            server.verify();
        }
    }

    @Nested
    @DisplayName("Response")
    class Response {

        @Test
        @DisplayName("maps the choice, the probabilities and the token counts onto the shared DTO")
        void mapsTheTypedAnswer() {
            server.expect(requestTo(URL)).andRespond(withSuccess(
                    answer("urgent", "{\"urgent\":0.94,\"normal\":0.05,\"spam\":0.01}", "0.93"),
                    MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(
                    request("text", null, category("urgent", "Now"), category("normal", "Later"),
                            category("spam", "Bin")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isTrue();
            assertThat(result.selectedCategory()).isEqualTo("urgent");
            assertThat(result.confidence()).isEqualTo(0.93);
            assertThat(result.probabilities())
                    .containsEntry("urgent", 0.94)
                    .containsEntry("normal", 0.05)
                    .containsEntry("spam", 0.01);
            assertThat(result.promptTokens()).isEqualTo(1150);
            assertThat(result.completionTokens()).isZero();
            assertThat(result.tokensUsed()).isEqualTo(1150);
        }

        @Test
        @DisplayName("BILLING: reports the requested alias, never the concrete version the vendor served")
        void reportsTheBilledModelNotTheServedOne() {
            // The fixture answers with model "jev-1.13" while the request asked for the
            // alias "jev-latest". Only the alias is priced, and on the async completion
            // path the orchestrator keys the ledger on the model in THIS field: reporting
            // the served version would charge an unpriced pair at the 1.0 / 4.0 default
            // rates, roughly 24x the real input rate plus free output tokens, multiplied
            // by this provider's lever. Every future jev-1.x would re-open it.
            server.expect(requestTo(URL)).andRespond(withSuccess(
                    answer("a", "{\"a\":1.0}", "1.0"), MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.model()).isEqualTo("jev-latest");
            assertThat(result.provider()).isEqualTo("typesafe");
        }

        @Test
        @DisplayName("stores the distribution where the LLM path stores its reasoning")
        void reasoningCarriesTheDistribution() {
            server.expect(requestTo(URL)).andRespond(withSuccess(
                    answer("urgent", "{\"urgent\":0.94,\"normal\":0.05,\"spam\":0.01}", "0.93"),
                    MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(
                    request("text", null, category("urgent", "Now"), category("normal", "Later"),
                            category("spam", "Bin")),
                    "typesafe", System.currentTimeMillis());

            // Ranked, highest first, so a workflow already reading `reasoning` keeps getting
            // something meaningful rather than an empty string.
            assertThat(result.reasoning()).isEqualTo("urgent 0.94, normal 0.05, spam 0.01");
        }

        @Test
        @DisplayName("falls back to the winner's probability when no confidence is reported")
        void confidenceFallsBackToTheWinner() {
            server.expect(requestTo(URL)).andRespond(withSuccess("""
                    {"answers":{"category":{"type":"choice","choice":"a",
                     "probabilities":{"a":0.77,"b":0.23}}}}
                    """, MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(
                    request("text", null, category("a", "A"), category("b", "B")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.confidence()).isEqualTo(0.77);
        }

        @Test
        @DisplayName("a response with no usage block still classifies, and bills zero")
        void missingUsageStillClassifies() {
            // Losing the classification because the vendor moved a billing field would be a
            // worse trade than billing one call at zero. The client logs a warning instead,
            // which is what makes a revenue leak findable - see the WARN in readUsage.
            server.expect(requestTo(URL)).andRespond(withSuccess("""
                    {"answers":{"category":{"type":"choice","choice":"a",
                     "confidence":0.9,"probabilities":{"a":0.9,"b":0.1}}}}
                    """, MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(
                    request("text", null, category("a", "A"), category("b", "B")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isTrue();
            assertThat(result.selectedCategory()).isEqualTo("a");
            assertThat(result.promptTokens()).isZero();
            assertThat(result.tokensUsed()).isZero();
        }

        @Test
        @DisplayName("with neither confidence nor probabilities the result is honest about knowing nothing")
        void noConfidenceAndNoProbabilities() {
            server.expect(requestTo(URL)).andRespond(withSuccess("""
                    {"answers":{"category":{"type":"choice","choice":"a"}},
                     "usage":{"input_tokens":10,"output_tokens":0}}
                    """, MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isTrue();
            // Zero, not a comfortable default: an unknown confidence must not read as a
            // certain one to a workflow that routes on it.
            assertThat(result.confidence()).isZero();
            // NULL, not an empty map. The async completion path copies the result map
            // wholesale into the node output, so an empty map would be persisted as
            // `probabilities: {}` - which a template reads as a real, unanimous result
            // rather than as "this engine reported none". Only null is dropped.
            assertThat(result.probabilities()).isNull();
            assertThat(result.reasoning()).isNull();
        }

        @Test
        @DisplayName("a probability for a category that was never offered is dropped")
        void offListProbabilityKeysAreDropped() {
            // The choice is checked against the declared labels; the distribution was not,
            // so a malformed response could write arbitrary keys into a persisted node
            // output that a workflow then reads by name.
            server.expect(requestTo(URL)).andRespond(withSuccess(
                    answer("a", "{\"a\":0.8,\"b\":0.15,\"escalate\":0.05}", "0.8"),
                    MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(
                    request("text", null, category("a", "A"), category("b", "B")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.probabilities()).containsOnlyKeys("a", "b");
        }

        @Test
        @DisplayName("a distribution naming nothing that was offered is treated as no distribution")
        void allOffListProbabilitiesBecomeNull() {
            server.expect(requestTo(URL)).andRespond(withSuccess(
                    answer("a", "{\"nonsense\":1.0}", "0.9"), MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(
                    request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isTrue();
            assertThat(result.probabilities()).isNull();
        }

        @Test
        @DisplayName("an out-of-range confidence is clamped rather than stored as reported")
        void confidenceIsClamped() {
            server.expect(requestTo(URL)).andRespond(withSuccess(
                    answer("a", "{\"a\":1.0}", "1.8"), MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.confidence()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Key resolution")
    class KeyResolution {

        @Test
        @DisplayName("an admin-configured credential is used in preference to the environment key")
        void storedCredentialWinsOverTheEnvironment() {
            // The catalog surfaces this provider when it has EITHER an env key or a stored
            // one. Reading only the environment made an admin-configured key surface the
            // model while every call on it failed with "no API key is configured".
            client.setCredentialResolver(p -> Optional.of("db-key"));

            server.expect(requestTo(URL))
                    .andExpect(header("Authorization", "Bearer db-key"))
                    .andRespond(withSuccess(answer("a", "{\"a\":1.0}", "1.0"), MediaType.APPLICATION_JSON));

            client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            server.verify();
        }

        @Test
        @DisplayName("a stored credential alone is enough to report the provider configured")
        void storedCredentialAloneConfiguresTheClient() {
            TypeSafeSystemOneClient keyless = new TypeSafeSystemOneClient(URL, "");
            assertThat(keyless.isConfigured()).isFalse();

            keyless.setCredentialResolver(p -> Optional.of("db-key"));
            assertThat(keyless.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("an empty stored credential falls through to the environment rather than blanking the key")
        void blankStoredCredentialFallsThrough() {
            client.setCredentialResolver(p -> Optional.of("   "));

            server.expect(requestTo(URL))
                    .andExpect(header("Authorization", "Bearer " + KEY))
                    .andRespond(withSuccess(answer("a", "{\"a\":1.0}", "1.0"), MediaType.APPLICATION_JSON));

            client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            server.verify();
        }
    }

    @Nested
    @DisplayName("Refusals before dispatch")
    class RefusalsBeforeDispatch {

        /**
         * Assert that the endpoint is NOT called. A bare {@code verify()} would pass
         * whatever happened, because no expectation was ever registered: it proves nothing
         * on its own. This registers an expectation of zero calls, which does.
         */
        private void expectNoCall() {
            server.expect(never(), requestTo(URL))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        }

        @Test
        @DisplayName("duplicate labels are refused, because one branch could never be selected")
        void duplicateLabelsAreRefused() {
            expectNoCall();

            ClassifyResponseDto result = client.classify(
                    request("text", null, category("same", "First"), category("same", "Second")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Duplicate classify category label 'same'");
            // Nothing was sent, and that is the point: a request built from those labels
            // would have carried one option fewer than the node shows branches, so one
            // branch could never fire and nothing would say why.
            server.verify();
        }

        @Test
        @DisplayName("a model this provider does not declare is refused, not sent")
        void undeclaredModelIsRefused() {
            // The mirror of serves(): that routes a plan naming only a model, this catches
            // a plan naming this provider with someone else's model. Reachable because the
            // classify help's default model is rewritten from the CHAT catalogue, so an
            // agent that sets provider and leaves model alone arrives with a chat model.
            expectNoCall();
            client.setDecisionProvider(
                    new com.apimarketplace.agent.provider.TypeSafeDecisionProvider(
                            true, "k", "jev-latest", 20));

            ClassifyResponseDto result = client.classify(
                    new ClassifyRequestDto("text", null, List.of(category("a", "A")), "typesafe",
                            "gpt-5-mini", null, null, "tenant-1", "agent-1"),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isFalse();
            assertThat(result.error())
                    .contains("gpt-5-mini")
                    .contains("not a typesafe decision model")
                    // The reader is told the way out, not only that they are wrong.
                    .contains("jev-latest");
            server.verify();
        }

        @Test
        @DisplayName("naming this provider with no model at all is refused the same way")
        void missingModelIsRefused() {
            expectNoCall();
            client.setDecisionProvider(
                    new com.apimarketplace.agent.provider.TypeSafeDecisionProvider(
                            true, "k", "jev-latest", 20));

            ClassifyResponseDto result = client.classify(
                    new ClassifyRequestDto("text", null, List.of(category("a", "A")), "typesafe",
                            null, null, null, "tenant-1", "agent-1"),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("no model").contains("jev-latest");
            server.verify();
        }

        @Test
        @DisplayName("with no provider bean to check against, the call still goes out")
        void withoutTheProviderBeanTheModelIsNotChecked() {
            // There is no declaration to check against, and refusing everything would be
            // worse than trying: the endpoint answers for itself.
            server.expect(requestTo(URL)).andRespond(withSuccess(
                    answer("a", "{\"a\":1.0}", "1.0"), MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(
                    request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("no categories is refused with a message naming what is missing")
        void noCategoriesIsRefused() {
            expectNoCall();

            ClassifyResponseDto result = client.classify(
                    new ClassifyRequestDto("text", null, List.of(), "typesafe", "jev-latest",
                            null, null, "tenant-1", "agent-1"),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("at least one category");
            server.verify();
        }

        @Test
        @DisplayName("a category with no label is refused")
        void blankLabelIsRefused() {
            expectNoCall();

            ClassifyResponseDto result = client.classify(
                    request("text", null, category("  ", "A description with no label")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("needs a label");
            server.verify();
        }

        @Test
        @DisplayName("nothing to classify is refused")
        void noContentIsRefused() {
            expectNoCall();

            ClassifyResponseDto result = client.classify(
                    request(null, null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("needs content or a prompt");
            server.verify();
        }
    }

    @Nested
    @DisplayName("Failures are returned, never thrown")
    class Failures {

        @Test
        @DisplayName("a choice outside the declared labels fails loudly instead of routing nowhere")
        void offListChoiceFails() {
            // The options are the keys we sent, so this is a malformed response rather than
            // an improvising model. It still must not be stored: an unmatched label resolves
            // to index -1, which follows no port and stops the DAG with no error to read.
            server.expect(requestTo(URL)).andRespond(withSuccess(
                    answer("escalate", "{\"a\":1.0}", "0.9"), MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(
                    request("text", null, category("a", "A"), category("b", "B")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("escalate").contains("not one of the declared categories");
            assertThat(result.selectedCategory()).isNull();
        }

        @Test
        @DisplayName("a response with no answer for the question fails rather than reporting success")
        void missingAnswerFails() {
            server.expect(requestTo(URL)).andRespond(
                    withSuccess("{\"answers\":{}}", MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("no answer");
        }

        @Test
        @DisplayName("401 names the key, not the request, so the fix is obvious")
        void unauthorizedNamesTheKey() {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            ClassifyResponseDto result = client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("rejected the API key");
        }

        @Test
        @DisplayName("429 and 529 say the call can be retried, because they pass on their own")
        void throttlingSaysRetryable() {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
            assertThat(client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis()).error()).contains("can be retried");

            server.reset();
            // 529 has no HttpStatus constant: it is TypeSafe's own "overloaded" code, which
            // is exactly why it needs its own branch rather than falling into the default.
            server.expect(requestTo(URL))
                    .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(529)));
            assertThat(client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis()).error())
                    .contains("overloaded").contains("can be retried");
        }

        @Test
        @DisplayName("422 carries the endpoint's own explanation of what it rejected")
        void unprocessableCarriesTheBody() {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body("{\"detail\":\"criteria must not be empty\"}")
                    .contentType(MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.error()).contains("criteria must not be empty");
        }

        @Test
        @DisplayName("a transport failure is returned like any other, not thrown out of the node")
        void transportFailureIsReturned() {
            // Connection refused, DNS failure, read timeout: none of these is an
            // HttpStatusCodeException, so they reach the transport branch. If that branch
            // were missing the exception would escape into the caller's own handler and the
            // run would show a stack trace instead of a sentence.
            server.expect(requestTo(URL)).andRespond(req -> {
                throw new java.net.SocketTimeoutException("Read timed out");
            });

            ClassifyResponseDto result = client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("TypeSafe did not answer").contains("Read timed out");
            assertThat(result.provider()).isEqualTo("typesafe");
        }

        @Test
        @DisplayName("a connection reset names the provider going quiet without promising what it cannot know")
        void connectionResetIsNamedAndReassures() {
            // The 2026-09-22 shape: the request goes out, the peer resets instead of
            // answering. Reported as raw socket vocabulary this reads as a platform bug, and
            // the run's owner cannot tell whether the call was charged or half-applied.
            server.expect(requestTo(URL)).andRespond(req -> {
                throw new java.net.SocketException("Connection reset by peer");
            });

            ClassifyResponseDto result = client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isFalse();
            assertThat(result.error())
                    .contains("TypeSafe did not answer")
                    .contains("Connection reset by peer")
                    .contains("produced no result");
            // And it must NOT promise what this layer cannot know: a read timeout fires after
            // the request was written, and HttpURLConnection retries a POST once on a stale
            // socket, so neither "not charged" nor "not sent twice" is knowable here.
            assertThat(result.error()).doesNotContain("nothing was sent twice");
        }

        @Test
        @DisplayName("the transport log carries the root cause and the elapsed time, and not the request")
        void transportLogCarriesTheCauseAndNotTheRequest() {
            // This log line IS the reason the branch exists: diagnosing the 2026-09-22 outage
            // was slow because the only trace was the exception's class name. Asserting the
            // returned message alone would leave that motivation untested.
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TypeSafeSystemOneClient.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                server.expect(requestTo(URL)).andRespond(req -> {
                    throw new java.net.SocketException("Connection reset by peer");
                });

                client.classify(request("a very distinctive secret payload", null, category("a", "A")),
                        "typesafe", System.currentTimeMillis());

                String line = appender.list.stream()
                        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .collect(java.util.stream.Collectors.joining(" | "));

                assertThat(line)
                        .as("the cause chain and the timing are what make the failure diagnosable")
                        .contains("SocketException")
                        .contains("Connection reset by peer")
                        .contains("ms");
                assertThat(line)
                        .as("the state is the user's content and must never reach the platform log")
                        .doesNotContain("a very distinctive secret payload");
            } finally {
                logger.detachAppender(appender);
            }
        }

        @Test
        @DisplayName("the reported cause is the deepest one, through a multi-level chain")
        void reportedCauseIsTheDeepestOne() {
            server.expect(requestTo(URL)).andRespond(req -> {
                throw new java.io.IOException("outer",
                        new javax.net.ssl.SSLHandshakeException("handshake") {{
                            initCause(new java.io.EOFException("SSL peer shut down incorrectly"));
                        }});
            });

            ClassifyResponseDto result = client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.error())
                    .contains("EOFException")
                    .doesNotContain("outer");
        }

        @Test
        @DisplayName("a transport failure is told apart from a malformed body, which stays redacted")
        void malformedBodyKeepsTheGenericWording() {
            // The two share a catch today only by accident. A body can quote the user's
            // content, so it keeps the guarded wording; a socket error cannot, so it is named.
            server.expect(requestTo(URL))
                    .andRespond(withSuccess("{ not json", org.springframework.http.MediaType.APPLICATION_JSON));

            ClassifyResponseDto result = client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Classification error");
            assertThat(result.error()).doesNotContain("TypeSafe did not answer");
        }

        @Test
        @DisplayName("the request factory is pinned, so the transport cannot change with the classpath")
        void requestFactoryIsPinnedNotDetected() {
            // Left to detection, Spring Boot picks the first client on the classpath and
            // shared-agent-lib puts reactor-netty-http there for WebFlux: this call then runs
            // on Reactor Netty's global pool, which never evicts an idle connection. Nobody
            // chose that, and nothing would have said so. Pin it, and pin this expectation.
            TypeSafeSystemOneClient unbound = new TypeSafeSystemOneClient(URL, KEY);
            RestTemplate restTemplate =
                    (RestTemplate) ReflectionTestUtils.getField(unbound, "restTemplate");
            Object requestFactory = ReflectionTestUtils.getField(restTemplate, "requestFactory");

            assertThat(requestFactory)
                    .as("the transport must be a decision, not whatever the classpath offers")
                    .isInstanceOf(org.springframework.http.client.SimpleClientHttpRequestFactory.class);
        }

        @Test
        @DisplayName("the read timeout is actually wired onto the client, not merely declared")
        void readTimeoutIsApplied() {
            // A classify node holds a workflow open while it waits, so the bound has to be
            // real. Asserting the constant alone would pass with the builder call deleted,
            // which is the refactor this is meant to catch - so read the timeout back off
            // the request factory the RestTemplate was actually built with.
            // A FRESH client, not the shared one: binding MockRestServiceServer swaps the
            // request factory for its own, which would hide the very thing under test.
            TypeSafeSystemOneClient unbound = new TypeSafeSystemOneClient(URL, KEY);
            RestTemplate restTemplate =
                    (RestTemplate) ReflectionTestUtils.getField(unbound, "restTemplate");
            Object requestFactory = ReflectionTestUtils.getField(restTemplate, "requestFactory");
            assertThat(requestFactory).isNotNull();

            Object readTimeout = ReflectionTestUtils.getField(requestFactory, "readTimeout");
            assertThat(readTimeout)
                    .as("the 30s bound must reach the request factory, not just the constant")
                    .isNotNull();
            long millis = readTimeout instanceof java.time.Duration d
                    ? d.toMillis()
                    : ((Number) readTimeout).longValue();
            assertThat(millis).isEqualTo(TypeSafeSystemOneClient.READ_TIMEOUT.toMillis());
            assertThat(TypeSafeSystemOneClient.READ_TIMEOUT).isEqualTo(java.time.Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("a failed call reports the provider and model so the run shows where it went")
        void failureKeepsTheProviderAndModel() {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            ClassifyResponseDto result = client.classify(request("text", null, category("a", "A")),
                    "typesafe", System.currentTimeMillis());

            assertThat(result.provider()).isEqualTo("typesafe");
            assertThat(result.model()).isEqualTo("jev-latest");
        }
    }

    @Nested
    @DisplayName("What the execution detail can show")
    class Transcript {

        private ClassifyResponseDto classifyUrgent(String body) {
            server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            return client.classify(
                    request("text", null, category("urgent", "Now"), category("normal", "Later"),
                            category("spam", "Bin")),
                    "typesafe", System.currentTimeMillis());
        }

        /**
         * agent &gt; metrics renders a CONVERSATION, not the node output. It is built from the
         * rows written out of {@code conversationMessages}, so a null there is a recorded
         * execution showing the question sent to the model and nothing coming back - which is
         * exactly what a decision classification looked like before this turn existed. The
         * verdict was on the node output the whole time; the transcript, which is where a
         * reader looks to see what a model replied, was empty.
         */
        @Test
        @DisplayName("the answer comes back as an ASSISTANT turn, or the metrics panel shows no reply")
        void theAnswerIsCarriedAsATurn() {
            ClassifyResponseDto result = classifyUrgent(
                    answer("urgent", "{\"urgent\":0.94,\"normal\":0.05,\"spam\":0.01}", "0.93"));

            assertThat(result.conversationMessages())
                    .as("a null transcript is how the reply went missing from the execution detail")
                    .isNotNull()
                    .hasSize(1);
            assertThat(result.conversationMessages().get(0).role()).isEqualTo("ASSISTANT");
        }

        @Test
        @DisplayName("that turn transcribes the choice, the confidence and the distribution, and invents no prose")
        void theTurnTranscribesRatherThanNarrates() {
            ClassifyResponseDto result = classifyUrgent(
                    answer("urgent", "{\"urgent\":0.94,\"normal\":0.05,\"spam\":0.01}", "0.93"));

            String content = result.conversationMessages().get(0).content();
            assertThat(content).contains("urgent");
            assertThat(content).contains("0.93");
            // The distribution, which is what tells a reader the call was not close.
            assertThat(content).contains("normal");
            // A decision model produces no sentence, so none is put in its mouth.
            assertThat(content).doesNotContain("because");
        }

        @Test
        @DisplayName("no SYSTEM turn is invented: this request carries no system prompt")
        void noSystemTurnIsInvented() {
            ClassifyResponseDto result = classifyUrgent(
                    answer("urgent", "{\"urgent\":1.0}", "1.0"));

            assertThat(result.systemPrompt()).isNull();
            assertThat(result.conversationMessages())
                    .noneMatch(message -> "SYSTEM".equals(message.role()));
            // The instructions ARE reported, as the user turn, which is where they belong.
            assertThat(result.userPrompt()).isNotBlank();
        }

        @Test
        @DisplayName("a failed call carries no turn, so nothing is shown as an answer that never came")
        void aFailureCarriesNoTurn() {
            ClassifyResponseDto result = classifyUrgent("{\"answers\":{}}");

            assertThat(result.success()).isFalse();
            assertThat(result.conversationMessages()).isNullOrEmpty();
        }
    }
}
