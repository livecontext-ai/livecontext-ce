package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.generation.GenerationExecutionService;
import com.apimarketplace.orchestrator.services.generation.GenerationExecutionService.GenerationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GenerateNode}.
 *
 * <p>The behaviours pinned here are the ones that cost the customer money when
 * they go wrong: a refusal must reach the run intact (the refusal is what tells
 * the author what to fix, and it happened before anything was charged), a run
 * that produced no asset must FAIL (the call was charged anyway), and nothing a
 * workflow author puts in the node's params may reach the billing quantity.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GenerateNode")
class GenerateNodeTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private V2TemplateAdapter templateAdapter;
    @Mock private GenerationExecutionService generationExecutionService;

    @Captor private ArgumentCaptor<Map<String, Object>> paramsCaptor;

    private ExecutionContext context;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        context = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
        // Identity resolution: params carry literal values (incl. raw FileRef maps).
        when(templateAdapter.resolveTemplates(anyMap(), any()))
            .thenAnswer(inv -> new LinkedHashMap<>((Map<String, Object>) inv.getArgument(0)));
    }

    private GenerateNode node(Map<String, Object> params) {
        GenerateNode node = new GenerateNode("agent:make_clip", params);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.getGenerationExecutionService()).thenReturn(generationExecutionService);
        when(registry.getTemplateAdapter()).thenReturn(templateAdapter);
        node.acceptServices(registry);
        return node;
    }

    /** A successful generation as the catalog reports it. */
    private static GenerationResult produced() {
        Map<String, Object> fileRef = new HashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "tenant-1/generated/clip.mp4");
        fileRef.put("name", "clip.mp4");
        fileRef.put("mimeType", "video/mp4");
        fileRef.put("size", 1024);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", "seedance-2.0-fast");
        data.put("kind", "video");
        data.put("provider", "Seedance");
        data.put("file", fileRef);
        data.put("billed_quantity", new BigDecimal("10"));
        data.put("billed_unit", "second");
        data.put("billed_credits", new BigDecimal("600"));
        data.put("provider_response", Map.of("id", "job-77"));
        return new GenerationResult(true, data, null);
    }

    // ==================== Service availability ====================

    @Nested
    @DisplayName("service availability")
    class ServiceAvailability {

        @Test
        @DisplayName("no generation service wired -> node FAILS with an actionable message, never an NPE")
        void nullServiceFailsExplicitly() {
            GenerateNode bare = new GenerateNode("agent:make_clip", Map.of("model", "seedance-2.0-fast"));

            NodeExecutionResult result = bare.execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("generation service"),
                "failure must name what is missing, got: " + message);
            assertTrue(message.contains("administrator"),
                "failure must say who can enable it, got: " + message);
        }
    }

    // ==================== Happy path ====================

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("a failure that left a PAID asset at the provider names the link to it")
        void aPaidButUnstoredAssetIsNamedInTheFailure() {
            // Billing commits before the asset is fetched and stored, so a
            // transient storage failure leaves something the customer has
            // already been charged for behind a short-lived provider link. The
            // service carries that link on the failing result on purpose; the
            // node printing only the error text is what made it unreachable.
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                    anyString(), anyMap(), any(), any()))
                .thenReturn(GenerationResult.failed("The asset could not be stored.",
                        Map.of("asset_url", "https://provider.example/tmp/abc123")));

            NodeExecutionResult result = node(Map.of("model", "seedance-2.0-fast", "prompt", "a cat")).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("https://provider.example/tmp/abc123"),
                "the reader is the person who was charged, and the link expires, got: " + message);
            assertTrue(message.contains("charged"), message);
        }

        @Test
        @DisplayName("an ordinary refusal names no link, because nothing was produced or charged")
        void anOrdinaryRefusalNamesNoLink() {
            // The negative half: a refusal happens before the provider is
            // called, so offering a recovery link would send the reader chasing
            // something that was never made.
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                    anyString(), anyMap(), any(), any()))
                .thenReturn(GenerationResult.failed(
                        "PLATFORM_NOT_AVAILABLE: this model is not sold on the platform key."));

            NodeExecutionResult result = node(Map.of("model", "seedance-2.0-fast", "prompt", "a cat")).execute(context);

            assertFalse(result.isSuccess());
            assertFalse(result.errorMessage().orElse("").contains("Download it now"));
        }

        @Test
        @DisplayName("a provider refusal AFTER resolution reports the RESOLVED prompt; it used to report the {{...}} template, which read as unresolved")
        @SuppressWarnings("unchecked")
        void aFailureAfterResolutionReportsResolvedParams() {
            Map<String, Object> values = new java.util.HashMap<>();
            values.put("{{trigger:t.output.text}}", "a paper boat");
            when(templateAdapter.resolveTemplates(anyMap(), any()))
                .thenAnswer(TemplateResolutionStubs.resolving(values));
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                    anyString(), anyMap(), any(), any()))
                .thenReturn(GenerationResult.failed("'duration_seconds' must be <= 12"));

            NodeExecutionResult result = node(Map.of("model", "seedance-2.0-fast",
                    "prompt", "{{trigger:t.output.text}}")).execute(context);

            assertFalse(result.isSuccess());
            Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals("a paper boat", params.get("prompt"));
        }

        @Test
        @DisplayName("a template that cannot be resolved FAILS the node, and never reaches a paid provider")
        void anUnresolvableTemplateIsNotPaidFor() {
            // This used to be swallowed: the failure was logged as a warning and
            // the RAW params were sent on, so a prompt of
            // "{{trigger:x.output.text}}" went to the provider verbatim, an
            // asset was produced from the template's own source code, and the
            // customer was charged for it. The run was green and an asset came
            // back, so nothing said otherwise.
            //
            // Shipped without a test, which is how it survived: nothing in this
            // file ever made resolution throw.
            when(templateAdapter.resolveTemplates(anyMap(), any()))
                .thenThrow(new IllegalStateException("unknown node reference: trigger:x"));

            NodeExecutionResult result = node(Map.of("model", "seedance-2.0-fast",
                    "prompt", "{{trigger:x.output.text}}")).execute(context);

            assertFalse(result.isSuccess());
            verifyNoInteractions(generationExecutionService);
        }

        @Test
        @DisplayName("produces the canonical FileRef under `file` plus model, kind, provider and what it billed")
        void happyPathOutput() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("prompt", "a paper boat in a rain gutter");
            params.put("duration_seconds", 10);

            NodeExecutionResult result = node(params).execute(context);

            assertTrue(result.isSuccess(), () -> "expected success, got: " + result.errorMessage().orElse(""));
            Map<String, Object> output = result.output();
            assertEquals("seedance-2.0-fast", output.get("model"));
            assertEquals("video", output.get("kind"));
            assertEquals("Seedance", output.get("provider"));
            assertEquals(new BigDecimal("10"), output.get("billed_quantity"));
            assertEquals("second", output.get("billed_unit"));
            // What it COST, reported rather than left to be reconstructed from the size and a rate
            // that can be republished between the run and the reading.
            assertEquals(new BigDecimal("600"), output.get("billed_credits"));

            @SuppressWarnings("unchecked")
            Map<String, Object> file = (Map<String, Object>) output.get("file");
            assertEquals("tenant-1/generated/clip.mp4", file.get("path"));
            assertEquals("video/mp4", file.get("mimeType"));
        }

        @Test
        @DisplayName("keeps the provider payload under provider_response so it can never shadow `file`")
        void providerPayloadStaysNested() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            NodeExecutionResult result = node(Map.of("model", "seedance-2.0-fast")).execute(context);

            Map<String, Object> output = result.output();
            assertTrue(output.get("provider_response") instanceof Map,
                "the provider payload must stay under its own key");
            @SuppressWarnings("unchecked")
            Map<String, Object> provider = (Map<String, Object>) output.get("provider_response");
            assertEquals("job-77", provider.get("id"));
        }

        @Test
        @DisplayName("normalises the model id and forwards the credential source the author chose")
        void forwardsModelAndCredentialSource() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "  Seedance-2.0-Fast  ");
            params.put("credential_source", "USER");
            params.put("prompt", "hello");

            node(params).execute(context);

            verify(generationExecutionService).generate(eq("tenant-1"), eq("run-1"), eq("agent:make_clip"),
                eq("seedance-2.0-fast"), paramsCaptor.capture(), eq("user"), any());
            assertEquals("hello", paramsCaptor.getValue().get("prompt"));
        }

        @Test
        @DisplayName("forwards WHICH own key the author pinned, and never as a generation parameter")
        void forwardsThePinnedCredentialId() {
            // The inspector has offered this choice since the node shipped, but
            // nothing carried it: the account's default key ran whatever was
            // picked, on an account that holds several keys for the provider.
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("credential_source", "user");
            params.put("credential_id", 42);
            params.put("prompt", "hello");

            node(params).execute(context);

            verify(generationExecutionService).generate(anyString(), anyString(), anyString(),
                anyString(), paramsCaptor.capture(), eq("user"), eq(42L));
            // A control key, not a dimension of the asset: projected onto the
            // provider's request it would be refused as an unknown parameter.
            assertFalse(paramsCaptor.getValue().containsKey("credential_id"),
                "credential_id names an account object and must never reach the provider");
        }

        @Test
        @DisplayName("an id written as text is the same choice, since a resolved template is always text")
        void acceptsATextualCredentialId() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("credential_source", "user");
            params.put("credential_id", " 42 ");
            params.put("prompt", "hello");

            node(params).execute(context);

            verify(generationExecutionService).generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), eq("user"), eq(42L));
        }

        @Test
        @DisplayName("an unusable id runs on the account's default key instead of failing the node")
        void anUnusableCredentialIdFallsBackToTheDefault() {
            // A template that resolved to nothing, or a saved id from a deleted
            // credential, must not break a workflow that ran yesterday: the
            // catalog itself falls back to the integration's default in exactly
            // this case, and refusing here would be stricter than the thing that
            // actually resolves the key.
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("credential_source", "user");
            params.put("credential_id", "");
            params.put("prompt", "hello");

            NodeExecutionResult result = node(params).execute(context);

            assertTrue(result.isSuccess(), "an unusable id must not fail a run that can still be served");
            verify(generationExecutionService).generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), eq("user"), eq((Long) null));
        }

        @Test
        @DisplayName("no pinned id at all is forwarded as none, which is the account's default key")
        void noPinnedCredentialId() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("credential_source", "user");
            params.put("prompt", "hello");

            node(params).execute(context);

            verify(generationExecutionService).generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), eq("user"), eq((Long) null));
        }

        @Test
        @DisplayName("an UNSTATED credential source runs on the platform key, the one the builder quotes")
        void unstatedCredentialSourceIsThePlatformKey() {
            // Three producers write this node and only two of them state the
            // field, so its absence has to mean something definite. Left to the
            // executor downstream it means "try the author's own key first",
            // which is a different provider account and a different price from
            // the one every surface showed. Sending null here is what let a plan
            // run under an arrangement nobody chose.
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("prompt", "hello");

            node(params).execute(context);

            verify(generationExecutionService).generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), eq("platform"), any());
        }

        @Test
        @DisplayName("a BLANK credential source is treated the same, since it states nothing either")
        void blankCredentialSourceIsThePlatformKey() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("credential_source", "   ");
            params.put("prompt", "hello");

            node(params).execute(context);

            verify(generationExecutionService).generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), eq("platform"), any());
        }
    }

    // ==================== Refusals reach the run intact ====================

    @Nested
    @DisplayName("refusals from the generation catalog")
    class Refusals {

        @Test
        @DisplayName("unknown model -> node FAILS carrying the list of models the platform does offer")
        void unknownModelFails() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any()))
                .thenReturn(GenerationResult.failed(
                    "Unknown generation model 'made-up-model'. Available: seedance-2.0-fast, eleven-v3"));

            NodeExecutionResult result = node(Map.of("model", "made-up-model")).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("Unknown generation model 'made-up-model'"), message);
            // The recovery hint is the whole value of the refusal: it must not be
            // rewritten into a generic "generation failed".
            assertTrue(message.contains("seedance-2.0-fast"),
                "the accepted values must survive to the run, got: " + message);
        }

        @Test
        @DisplayName("a parameter the model does not accept -> node FAILS with the accepted list intact")
        void unacceptedParameterFails() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any()))
                .thenReturn(GenerationResult.failed(
                    "model 'seedance-2.0-fast' does not accept 'voice'. It accepts: aspect_ratio, "
                        + "duration_seconds, prompt"));

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("voice", "rachel");

            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("does not accept 'voice'"), message);
            assertTrue(message.contains("It accepts: aspect_ratio, duration_seconds, prompt"),
                "the accepted values must survive to the run, got: " + message);
        }

        @Test
        @DisplayName("a value outside the model's limits -> node FAILS with the bound intact")
        void outOfLimitsValueFails() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any()))
                .thenReturn(GenerationResult.failed("'duration_seconds' must be <= 12"));

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("duration_seconds", 60);

            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("must be <= 12"),
                "the bound must survive to the run");
        }

        @Test
        @DisplayName("the failure output still carries a null `file`, so nothing downstream reads a stale asset")
        void failureOutputHasNullFile() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any()))
                .thenReturn(GenerationResult.failed("'duration_seconds' must be <= 12"));

            NodeExecutionResult result = node(Map.of("model", "seedance-2.0-fast")).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.output().containsKey("file"));
            assertNull(result.output().get("file"));
        }
    }

    // ==================== Charged but nothing produced ====================

    @Nested
    @DisplayName("charged but no asset")
    class NoAsset {

        @Test
        @DisplayName("upstream success with no file -> node FAILS and says the call was still charged")
        void successWithoutFileFails() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("model", "seedance-2.0-fast");
            data.put("kind", "video");
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(new GenerationResult(true, data, null));

            NodeExecutionResult result = node(Map.of("model", "seedance-2.0-fast")).execute(context);

            assertFalse(result.isSuccess(),
                "a run that produced nothing must FAIL: the customer has already been charged, and a "
                    + "downstream node running on an empty file would hide that");
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("no file"), message);
            assertTrue(message.contains("charged"),
                "the failure must say the call was charged anyway, got: " + message);
        }

        @Test
        @DisplayName("a `file` without a storage path is not an asset -> node FAILS")
        void fileWithoutPathFails() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("file", Map.of("name", "clip.mp4"));
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(new GenerationResult(true, data, null));

            NodeExecutionResult result = node(Map.of("model", "seedance-2.0-fast")).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("charged"));
        }
    }

    // ==================== Billing is not caller-influenced ====================

    @Nested
    @DisplayName("billable quantity is server-derived")
    class BillingIsServerDerived {

        @Test
        @DisplayName("the node never sends a quantity: `generate` has no quantity argument at all")
        void nodeSendsNoQuantity() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("duration_seconds", 10);

            node(params).execute(context);

            verify(generationExecutionService).generate(anyString(), anyString(), anyString(),
                anyString(), paramsCaptor.capture(), any(), any());
            Map<String, Object> sent = paramsCaptor.getValue();
            // duration_seconds is a generation parameter and DOES travel; the platform
            // derives the billed size from it on its own side. What must never travel is
            // a quantity or a price the node could have chosen.
            assertEquals(10, sent.get("duration_seconds"));
            assertFalse(sent.containsKey("quantity"));
            assertFalse(sent.containsKey("billed_quantity"));
        }

        @Test
        @DisplayName("a node param named like a billing key is forwarded as a generation param, "
            + "so the catalog refuses it instead of it silently pricing the call")
        void billingLookalikeParamsAreNotControlKeys() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("billed_quantity", 0);
            params.put("__generationQuantity__", 0);

            node(params).execute(context);

            verify(generationExecutionService).generate(anyString(), anyString(), anyString(),
                anyString(), paramsCaptor.capture(), any(), any());
            Map<String, Object> sent = paramsCaptor.getValue();
            // They are ordinary (unknown) generation parameters here, which the catalog
            // rejects by name. What matters is that the node gives them NO special
            // meaning: a zero quantity must never become a free generation.
            assertEquals(0, sent.get("billed_quantity"));
            assertEquals(0, sent.get("__generationQuantity__"));
        }

        @Test
        @DisplayName("billed_quantity in the output comes from the response, not from the node's params")
        void billedQuantityComesFromTheResponse() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("billed_quantity", 0);

            NodeExecutionResult result = node(params).execute(context);

            assertEquals(new BigDecimal("10"), result.output().get("billed_quantity"),
                "the node must report what the platform billed, not what the params claimed");
        }
    }

    // ==================== Local validation (costs nothing) ====================

    @Nested
    @DisplayName("local validation")
    class LocalValidation {

        @Test
        @DisplayName("missing model -> node FAILS without calling the generation service at all")
        void missingModelFailsWithoutCalling() {
            NodeExecutionResult result = node(Map.of("prompt", "hello")).execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("model is required"));
            verify(generationExecutionService, never()).generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any());
        }

        @Test
        @DisplayName("an unknown credential_source -> node FAILS without calling, naming both valid values")
        void badCredentialSourceFailsWithoutCalling() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("credential_source", "borrowed");

            NodeExecutionResult result = node(params).execute(context);

            assertFalse(result.isSuccess());
            String message = result.errorMessage().orElse("");
            assertTrue(message.contains("'platform'"), message);
            assertTrue(message.contains("'user'"), message);
            verify(generationExecutionService, never()).generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any());
        }

        @Test
        @DisplayName("credential_source is a node control key, never forwarded as a generation param")
        void credentialSourceIsNotAGenerationParam() {
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(), any())).thenReturn(produced());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("model", "seedance-2.0-fast");
            params.put("credential_source", "platform");

            node(params).execute(context);

            verify(generationExecutionService).generate(anyString(), anyString(), anyString(),
                anyString(), paramsCaptor.capture(), eq("platform"), any());
            assertFalse(paramsCaptor.getValue().containsKey("credential_source"),
                "credential_source configures the node, it is not a parameter the provider understands");
            assertFalse(paramsCaptor.getValue().containsKey("model"),
                "model configures the node, it is passed as its own argument");
        }
    }

    @Nested
    @DisplayName("resolved_params on the success path")
    class SuccessPathReport {

        @Test
        @DisplayName("BUG: a long prompt comes back whole in the SUCCESS output, not re-described by a second report pass")
        void longPromptSurvivesTheSuccessPath() {
            String prompt = "A slow dolly shot over a harbour at dawn." + " Gulls circle the masts.".repeat(200);
            when(generationExecutionService.generate(anyString(), anyString(), anyString(),
                    anyString(), anyMap(), any(), any()))
                .thenReturn(produced());

            NodeExecutionResult result = node(Map.of("model", "seedance-2.0-fast", "prompt", prompt)).execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            Map<String, Object> reported = (Map<String, Object>) result.output().get("resolved_params");
            org.assertj.core.api.Assertions.assertThat(reported.get("prompt")).isEqualTo(prompt);
        }
    }
}
