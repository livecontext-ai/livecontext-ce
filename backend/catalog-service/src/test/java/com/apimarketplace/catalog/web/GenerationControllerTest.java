package com.apimarketplace.catalog.web;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.catalog.service.generation.DynamicOptionsResolver;
import com.apimarketplace.catalog.service.generation.GenerationRegistry;
import com.apimarketplace.catalog.service.generation.GenerationSpec;
import com.apimarketplace.catalog.tools.generation.GenerationModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GenerationController}, the HTTP face the workflow
 * builder and the {@code agent:generate} node speak to.
 *
 * <p>Two things are load bearing here. The model listing must carry
 * {@code apiToolId} and {@code integrationName}, because those are what a price
 * quote is keyed on and the inspector cannot show a price without them. And the
 * execute endpoint must hand the call to {@link GenerationModule} unchanged,
 * with the caller's billing scope re-expressed in the shape the catalog's own
 * execution path already reads, so a generation started from a workflow is
 * charged to that run exactly as one started from the chat is charged to its
 * stream.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GenerationController")
class GenerationControllerTest {

    private static final UUID TOOL_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private GenerationRegistry registry;
    @Mock private GenerationModule module;
    @Mock private DynamicOptionsResolver optionsResolver;
    @Mock private com.apimarketplace.catalog.service.generation.PlatformSalesResolver platformSales;

    @Captor private ArgumentCaptor<Map<String, Object>> parametersCaptor;
    @Captor private ArgumentCaptor<ToolExecutionContext> contextCaptor;

    private GenerationController controller;

    @BeforeEach
    void setUp() {
        controller = new GenerationController(registry, module, optionsResolver, platformSales);
    }

    private static GenerationRegistry.GenerationModel videoModel() {
        return videoModel(Map.of());
    }

    /** The same model, carrying values inherited from the catalogue. */
    private static GenerationRegistry.GenerationModel videoModel(Map<String, List<String>> inherited) {
        GenerationSpec.Model model = new GenerationSpec.Model(
                "seedance-2.0-fast", "seedance/fast-2", "Seedance 2.0 Fast",
                java.util.Set.of("prompt", "duration_seconds", "aspect_ratio"),
                java.util.Set.of("prompt"),
                Map.of("duration_seconds", new GenerationSpec.Constraint(
                                List.of(), BigDecimal.ONE, BigDecimal.valueOf(12)),
                        "aspect_ratio", new GenerationSpec.Constraint(
                                List.of("16:9", "9:16"), null, null)),
                new GenerationSpec.Price("second", BigDecimal.ZERO, BigDecimal.valueOf(60), null, null));

        GenerationSpec spec = new GenerationSpec("video", "model", "content.video_url",
                Map.of(), Map.of(), List.of(model));

        return new GenerationRegistry.GenerationModel(
                "seedance-2.0-fast", "video", model, spec, TOOL_ID,
                "seedance/create-video", "seedance", "Seedance", "seedance",
                "seedance", "async_poll", inherited);
    }

    /** A model whose price moves with a choice and with the files attached to it. */
    private static GenerationRegistry.GenerationModel modulatedModel() {
        GenerationSpec.Model model = new GenerationSpec.Model(
                "seedance-2.0-tiered", "seedance/tiered", "Seedance 2.0 Tiered",
                java.util.Set.of("prompt", "duration_seconds", "resolution", "reference_image"),
                java.util.Set.of("prompt", "resolution"),
                Map.of("resolution", new GenerationSpec.Constraint(
                        List.of("720p", "1080p"), null, null)),
                new GenerationSpec.Price("second", BigDecimal.ZERO, BigDecimal.valueOf(60), null, null,
                        List.of(new GenerationSpec.PriceModifier("resolution",
                                        Map.of("720p", BigDecimal.ONE, "1080p", BigDecimal.valueOf(2)), null),
                                new GenerationSpec.PriceModifier("reference_image", null,
                                        new BigDecimal("0.05")))));

        GenerationSpec spec = new GenerationSpec("video", "model", "content.video_url",
                Map.of(), Map.of(), List.of(model));

        return new GenerationRegistry.GenerationModel(
                "seedance-2.0-tiered", "video", model, spec, TOOL_ID,
                "seedance/create-video", "seedance", "Seedance", "seedance",
                "seedance", "async_poll", Map.of());
    }

    // ── models ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("lists each model with the two keys a price quote is keyed on")
    void listsModelsWithQuoteKeys() {
        when(registry.list(null)).thenReturn(List.of(videoModel()));
        when(registry.kinds()).thenReturn(List.of("video"));

        ResponseEntity<Map<String, Object>> response = controller.models(null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getBody().get("models");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("model")).isEqualTo("seedance-2.0-fast");
        assertThat(row.get("kind")).isEqualTo("video");
        assertThat(row.get("provider")).isEqualTo("Seedance");
        // Without these the inspector can only show a generic note, never a price.
        assertThat(row.get("apiToolId")).isEqualTo(TOOL_ID.toString());
        assertThat(row.get("integrationName")).isEqualTo("seedance");
        assertThat(response.getBody().get("count")).isEqualTo(1);
        assertThat(response.getBody().get("kinds")).isEqualTo(List.of("video"));
    }

    @Test
    @DisplayName("reports the price as components, so a surface can say 'per second' and not only a number")
    void reportsPriceComponents() {
        when(registry.list(null)).thenReturn(List.of(videoModel()));
        when(registry.kinds()).thenReturn(List.of("video"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) controller.models(null).getBody().get("models");
        @SuppressWarnings("unchecked")
        Map<String, Object> price = (Map<String, Object>) rows.get(0).get("price");

        assertThat(price.get("unit")).isEqualTo("second");
        assertThat(price.get("unitCredits")).isEqualTo("60");
        assertThat(price.get("baseCredits")).isEqualTo("0");
    }

    @Test
    @DisplayName("publishes the factor TABLE, so a surface can price the form without a round trip")
    void reportsPriceModifiers() {
        // The rule, not one result of it. This route is what the studio composer and the workflow
        // inspector read to work out what the choices currently in the form do to the rate; the
        // amount itself is still the server's. Emitted only here, a surface that shows a total
        // which is not rate x size has nothing to explain it with, and the reader cannot tell a
        // surcharge from an arithmetic error without spending.
        when(registry.list(null)).thenReturn(List.of(modulatedModel()));
        when(registry.kinds()).thenReturn(List.of("video"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) controller.models(null).getBody().get("models");
        @SuppressWarnings("unchecked")
        Map<String, Object> price = (Map<String, Object>) rows.get(0).get("price");
        @SuppressWarnings("unchecked")
        Map<String, Object> modifiers = (Map<String, Object>) price.get("modifiers");

        // The SAME wire names the agent listing uses, from the same method: two spellings of one
        // table is how a surface prices a call the tool prices differently.
        assertThat(modifiers).containsOnlyKeys("resolution", "reference_image");
        @SuppressWarnings("unchecked")
        Map<String, Object> byValue = (Map<String, Object>) modifiers.get("resolution");
        assertThat(byValue).containsEntry(
                "by_value", Map.of("720p", BigDecimal.ONE, "1080p", BigDecimal.valueOf(2)));
        @SuppressWarnings("unchecked")
        Map<String, Object> perFile = (Map<String, Object>) modifiers.get("reference_image");
        assertThat(perFile).containsEntry("per_file", new BigDecimal("0.05"));
    }

    @Test
    @DisplayName("says nothing about factors for a model whose price depends only on its size")
    void reportsNoModifiersForAPlainModel() {
        // Which is every model that existed before factors did. An empty table published as `{}`
        // would have a surface draw an explanation slot on every row in the catalogue.
        when(registry.list(null)).thenReturn(List.of(videoModel()));
        when(registry.kinds()).thenReturn(List.of("video"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) controller.models(null).getBody().get("models");
        @SuppressWarnings("unchecked")
        Map<String, Object> price = (Map<String, Object>) rows.get(0).get("price");

        assertThat(price).doesNotContainKey("modifiers");
    }

    @Test
    @DisplayName("reports a text cap, and drops a parameter that restricts nothing")
    void reportsTextCapsLikeTheAgentSurfaceDoes() {
        // The builder and the agent describe the same models; two surfaces
        // disagreeing is how a form shows a limit the tool does not enforce, or
        // hides one it does. Before this, a cap-only constraint published a bare
        // {} here: it claims a restriction and names none, and every OpenAI
        // image model shipped is exactly that shape.
        GenerationSpec.Model model = new GenerationSpec.Model(
                "img-capped", "img", "Capped",
                java.util.Set.of("prompt", "seed"),
                java.util.Set.of("prompt"),
                Map.of("prompt", new GenerationSpec.Constraint(List.of(), null, null, 4096),
                        "seed", new GenerationSpec.Constraint(List.of(), null, null)),
                new GenerationSpec.Price("call", BigDecimal.valueOf(9), BigDecimal.ZERO, null, null));
        GenerationSpec spec = new GenerationSpec("image", "model", "$binary",
                Map.of(), Map.of(), List.of(model));
        when(registry.list(null)).thenReturn(List.of(new GenerationRegistry.GenerationModel(
                "img-capped", "image", model, spec, java.util.UUID.randomUUID(),
                "openai/create-image", "openai", "OpenAI", "openai", "openai_key", "sync")));
        when(registry.kinds()).thenReturn(List.of("image"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) controller.models(null).getBody().get("models");
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) rows.get(0).get("limits");

        assertThat(limits).containsOnlyKeys("prompt");
        assertThat(limits.get("prompt")).isEqualTo(Map.of("maxLength", 4096));
    }

    @Test
    @DisplayName("reports each model's accepted params and their limits, so the form can only offer valid values")
    void reportsAcceptsAndLimits() {
        when(registry.list(null)).thenReturn(List.of(videoModel()));
        when(registry.kinds()).thenReturn(List.of("video"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) controller.models(null).getBody().get("models");
        Map<String, Object> row = rows.get(0);

        assertThat(new java.util.HashSet<>((java.util.Collection<?>) row.get("accepts")))
                .isEqualTo(java.util.Set.of("prompt", "duration_seconds", "aspect_ratio"));
        assertThat(new java.util.ArrayList<>((java.util.Collection<?>) row.get("required")))
                .isEqualTo(List.of("prompt"));

        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) row.get("limits");
        @SuppressWarnings("unchecked")
        Map<String, Object> duration = (Map<String, Object>) limits.get("duration_seconds");
        assertThat(duration.get("min")).isEqualTo(BigDecimal.ONE);
        assertThat(duration.get("max")).isEqualTo(BigDecimal.valueOf(12));
        @SuppressWarnings("unchecked")
        Map<String, Object> aspect = (Map<String, Object>) limits.get("aspect_ratio");
        assertThat(new java.util.ArrayList<>((List<?>) aspect.get("allowed")))
                .isEqualTo(List.of("16:9", "9:16"));
    }

    @Test
    @DisplayName("reports what a model sends whatever the caller passes, so a picker can group by tier")
    void reportsWhatIsFixed() {
        // A provider that charges differently for a quality or an output size
        // sells each as its own model id. On this surface three pickers render
        // one provider's models as a flat list, and without this field the only
        // thing telling those ids apart is their label text.
        GenerationSpec.Model tier = new GenerationSpec.Model(
                "img-tall", "img", "Image (tall)",
                java.util.Set.of("prompt"), java.util.Set.of("prompt"), Map.of(),
                Map.of("size", "1024x1536"),
                new GenerationSpec.Price("call", BigDecimal.valueOf(15), null, null, null));
        GenerationSpec spec = new GenerationSpec("image", "model", "$binary",
                Map.of(), Map.of("response_format", "b64_json"), List.of(tier));
        GenerationRegistry.GenerationModel m = new GenerationRegistry.GenerationModel(
                "img-tall", "image", tier, spec, TOOL_ID, "acme/create-image", "acme",
                "Acme", "acme", "acme", "sync");
        when(registry.list(null)).thenReturn(List.of(m));
        when(registry.kinds()).thenReturn(List.of("image"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) controller.models(null).getBody().get("models");

        // The model's own tier AND the endpoint's scaffolding: both are sent on
        // every call, so a field that claims to list them cannot show one half.
        assertThat(rows.get(0)).containsEntry("fixed",
                Map.of("response_format", "b64_json", "size", "1024x1536"));
    }

    @Test
    @DisplayName("a model that pins nothing carries no 'fixed' key at all")
    void reportsNothingFixedWhenNothingIs() {
        when(registry.list(null)).thenReturn(List.of(videoModel()));
        when(registry.kinds()).thenReturn(List.of("video"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) controller.models(null).getBody().get("models");

        assertThat(rows.get(0)).doesNotContainKey("fixed");
    }

    @Test
    @DisplayName("narrows the listing to one kind when asked")
    void narrowsByKind() {
        when(registry.list("video")).thenReturn(List.of(videoModel()));
        when(registry.kinds()).thenReturn(List.of("video", "image"));

        controller.models("video");

        verify(registry).list("video");
    }

    // ── execute ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("hands the call to the shared generation module rather than running a second implementation")
    void delegatesToTheGenerationModule() {
        when(module.execute(eq("create"), anyMap(), anyString(), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("file", Map.of("path", "p")))));

        ResponseEntity<Map<String, Object>> response = controller.execute(
                Map.of("model", "seedance-2.0-fast", "params", Map.of("prompt", "a boat")),
                "tenant-1", null, "RUN", "run-1", "agent:make_clip");

        assertThat(response.getBody().get("success")).isEqualTo(true);
        verify(module).execute(eq("create"), parametersCaptor.capture(), eq("tenant-1"), any());
        assertThat(parametersCaptor.getValue()).containsEntry("model", "seedance-2.0-fast");
    }

    @Test
    @DisplayName("re-expresses a RUN scope as the workflow-run marker the catalog execution path reads")
    void runScopeBecomesWorkflowRunMarker() {
        when(module.execute(eq("create"), anyMap(), anyString(), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("file", Map.of("path", "p")))));

        controller.execute(Map.of("model", "m"), "tenant-1", "org-9", "RUN", "run-1", "agent:make_clip");

        verify(module).execute(eq("create"), anyMap(), eq("tenant-1"), contextCaptor.capture());
        ToolExecutionContext context = contextCaptor.getValue();
        assertThat(context.tenantId()).isEqualTo("tenant-1");
        assertThat(context.orgId()).isEqualTo("org-9");
        // Without these the credit debit is scoped to nothing and the run is not charged.
        assertThat(context.credentials()).containsEntry("__workflowRunId__", "run-1");
        assertThat(context.credentials()).containsEntry("__nodeId__", "agent:make_clip");
    }

    @Test
    @DisplayName("a STREAM scope becomes the chat marker, not the workflow one")
    void streamScopeBecomesStreamMarker() {
        when(module.execute(eq("create"), anyMap(), anyString(), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("file", Map.of("path", "p")))));

        controller.execute(Map.of("model", "m"), "tenant-1", null, "STREAM", "stream-7", null);

        verify(module).execute(eq("create"), anyMap(), eq("tenant-1"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().credentials())
                .containsEntry("__streamId__", "stream-7")
                .doesNotContainKey("__workflowRunId__");
    }

    @Test
    @DisplayName("no scope at all sends no marker, rather than inventing one that would bill the wrong run")
    // What happens NEXT to a scope-less generation is not decided here: the
    // controller only refuses to guess. The refusal lives one layer down, in
    // CatalogToolBillingService.preflightReserve, and is pinned by
    // GenerationFailClosedTest.NoBillingScope.
    void noScopeSendsNoMarker() {
        when(module.execute(eq("create"), anyMap(), any(), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("file", Map.of("path", "p")))));

        controller.execute(Map.of("model", "m"), "tenant-1", null, null, null, null);

        verify(module).execute(eq("create"), anyMap(), eq("tenant-1"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().credentials()).isEmpty();
    }

    @Test
    @DisplayName("the pinned key is lifted onto the CONTEXT, which is the only channel an agent cannot write")
    void liftsThePinnedKeyOntoTheContext() {
        // The one link this controller contributes. The generation module
        // reads a pin from the execution context and never from the parameter
        // map, precisely so an agent calling the same module cannot state one;
        // if the controller stopped moving it across, the app dialog and the
        // workflow node would silently lose the ability to choose a key while
        // every other test in the branch still passed.
        when(module.execute(eq("create"), anyMap(), any(), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("file", Map.of("path", "p")))));

        controller.execute(Map.of("model", "m", "credential_source", "user", "credential_id", 42),
                "tenant-1", null, "RUN", "run-1", "agent:make_clip");

        verify(module).execute(eq("create"), anyMap(), eq("tenant-1"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().credentials()).containsEntry("__credentialId__", 42);
    }

    @Test
    @DisplayName("a generation started from the app carries its pinned key the same way")
    void theAppDialogPinIsLiftedToo() {
        when(module.execute(eq("create"), anyMap(), any(), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("file", Map.of("path", "p")))));

        controller.executeForUser(
                Map.of("model", "m", "credential_source", "user", "credential_id", 7), "tenant-1", null);

        verify(module).execute(eq("create"), anyMap(), eq("tenant-1"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().credentials()).containsEntry("__credentialId__", 7);
    }

    @Test
    @DisplayName("a blank pin is not carried at all, so the run uses the account default")
    void aBlankPinIsNotCarried() {
        when(module.execute(eq("create"), anyMap(), any(), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("file", Map.of("path", "p")))));

        controller.executeForUser(
                Map.of("model", "m", "credential_source", "user", "credential_id", "  "), "tenant-1", null);

        verify(module).execute(eq("create"), anyMap(), eq("tenant-1"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().credentials()).doesNotContainKey("__credentialId__");
    }

    @Test
    @DisplayName("a refusal is relayed verbatim with its code, so the accepted values reach the caller")
    void relaysRefusalVerbatim() {
        when(module.execute(eq("create"), anyMap(), any(), any()))
                .thenReturn(Optional.of(ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                        "model 'm' does not accept 'voice'. It accepts: prompt")));

        ResponseEntity<Map<String, Object>> response = controller.execute(
                Map.of("model", "m", "params", Map.of("voice", "rachel")),
                "tenant-1", null, "RUN", "run-1", "agent:make_clip");

        assertThat(response.getBody().get("success")).isEqualTo(false);
        assertThat(response.getBody().get("error"))
                .isEqualTo("model 'm' does not accept 'voice'. It accepts: prompt");
        assertThat(response.getBody().get("errorCode")).isEqualTo("INVALID_PARAMETER_VALUE");
        assertThat(response.getBody()).doesNotContainKey("data");
    }

    @Test
    @DisplayName("a module that handled nothing is reported as a failure, never as an empty success")
    void unhandledDispatchIsAFailure() {
        when(module.execute(eq("create"), anyMap(), any(), any())).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.execute(
                Map.of("model", "m"), "tenant-1", null, null, null, null);

        assertThat(response.getBody().get("success")).isEqualTo(false);
        assertThat(String.valueOf(response.getBody().get("error"))).contains("dispatch failed");
    }

    /**
     * A failure that carries data carries it for one reason: the customer has
     * ALREADY been charged and the asset exists behind a link that expires.
     * This endpoint used to answer with the message alone, so the link the
     * module went to the trouble of returning never reached anybody.
     */
    @Test
    @DisplayName("a failure that carries a recovery payload passes it through, link included")
    void relaysTheRecoveryPayloadOnFailure() {
        Map<String, Object> recovery = Map.of(
                "asset_url", "https://cdn.example.com/v/job-1.mp4",
                "provider_response", Map.of("id", "job-1"));
        when(module.execute(eq("create"), anyMap(), any(), any()))
                .thenReturn(Optional.of(new ToolExecutionResult(false, recovery,
                        "The generation ran but no asset could be retrieved: HTTP 504.",
                        ToolErrorCode.EXECUTION_FAILED, Map.of())));

        ResponseEntity<Map<String, Object>> response = controller.execute(
                Map.of("model", "m"), "tenant-1", null, "RUN", "run-1", "agent:make_clip");

        assertThat(response.getBody().get("success")).isEqualTo(false);
        assertThat(response.getBody().get("data")).isEqualTo(recovery);
    }

    @Test
    @DisplayName("the user-facing endpoint passes it through too, so a person can still fetch what they bought")
    void userEndpointRelaysTheRecoveryPayloadOnFailure() {
        Map<String, Object> recovery = Map.of("asset_url", "https://cdn.example.com/v/job-2.mp4");
        when(module.execute(eq("create"), anyMap(), any(), any()))
                .thenReturn(Optional.of(new ToolExecutionResult(false, recovery,
                        "The generation ran but no asset could be retrieved: HTTP 504.",
                        ToolErrorCode.EXECUTION_FAILED, Map.of())));

        ResponseEntity<Map<String, Object>> response =
                controller.executeForUser(Map.of("model", "m"), "tenant-1", null);

        assertThat(response.getBody().get("success")).isEqualTo(false);
        assertThat(response.getBody().get("data")).isEqualTo(recovery);
    }

    @Test
    @DisplayName("the app's Generate button mints its OWN billing scope, or the run is refused for having none")
    void userEndpointMintsItsOwnScope() {
        // THE BOUNDARY THIS ENDPOINT OWNS. There is no workflow run and no chat
        // stream behind an interactive click, so the scope is created here; a
        // generation that reaches billing without one is refused outright
        // (UNBILLABLE_GENERATION). Deleting the line left the whole suite green
        // while the app's Generate button stopped working entirely, and the
        // "obvious" repair for that symptom, relaxing the refusal, is a free
        // generation.
        when(module.execute(eq("create"), anyMap(), any(), any()))
                .thenReturn(Optional.of(new ToolExecutionResult(true, Map.of(), null, null, Map.of())));

        controller.executeForUser(Map.of("model", "m"), "tenant-1", null);

        verify(module).execute(eq("create"), anyMap(), any(), contextCaptor.capture());
        Object streamId = contextCaptor.getValue().credentials().get("__streamId__");
        assertThat(streamId)
                .as("an interactive call bills under a STREAM scope, like a chat generation")
                .isNotNull();
        assertThat(String.valueOf(streamId)).startsWith("ui-");
    }

    @Test
    @DisplayName("each click gets its own scope, so two generations are two charges and not one")
    void eachClickMintsADistinctScope() {
        // A constant would satisfy the test above and make every generation from
        // this surface share one scope: the ledger dedups on the scope, so the
        // second click would be free.
        when(module.execute(eq("create"), anyMap(), any(), any()))
                .thenReturn(Optional.of(new ToolExecutionResult(true, Map.of(), null, null, Map.of())));

        controller.executeForUser(Map.of("model", "m"), "tenant-1", null);
        controller.executeForUser(Map.of("model", "m"), "tenant-1", null);

        verify(module, org.mockito.Mockito.times(2))
                .execute(eq("create"), anyMap(), any(), contextCaptor.capture());
        var scopes = contextCaptor.getAllValues().stream()
                .map(ctx -> String.valueOf(ctx.credentials().get("__streamId__")))
                .toList();
        assertThat(scopes.get(0)).isNotEqualTo(scopes.get(1));
    }

    @Test
    @DisplayName("an unauthenticated caller is refused before anything is dispatched")
    void anUnauthenticatedCallerIsRefused() {
        ResponseEntity<Map<String, Object>> response =
                controller.executeForUser(Map.of("model", "m"), null, null);

        assertThat(response.getBody().get("success")).isEqualTo(false);
        verifyNoInteractions(module);
    }

    @Test
    @DisplayName("an absent body is an empty parameter map, so the module answers 'model is required'")
    void absentBodyIsAnEmptyParameterMap() {
        when(module.execute(eq("create"), anyMap(), any(), any()))
                .thenReturn(Optional.of(ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                        "Parameter 'model' is required.")));

        ResponseEntity<Map<String, Object>> response =
                controller.execute(null, "tenant-1", null, null, null, null);

        assertThat(response.getBody().get("success")).isEqualTo(false);
        verify(module).execute(eq("create"), parametersCaptor.capture(), eq("tenant-1"), any());
        assertThat(parametersCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("regression: the browser gets the WHOLE inherited list, not the agent's sample")
    void inheritedListIsNotCappedForTheBrowser() {
        // The two surfaces share the shaping and differ on exactly one thing:
        // the agent pays for this payload in context and takes a sample, the
        // browser pays in bytes and takes the lot. Passing the agent's cap here
        // would silently hide 88 voices from a picker.
        List<String> hundred = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) hundred.add("voice-" + i);
        when(registry.list(null)).thenReturn(List.of(videoModel(Map.of("prompt", hundred))));

        Map<String, Object> body = controller.models(null).getBody();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("models");
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) rows.get(0).get("limits");
        @SuppressWarnings("unchecked")
        Map<String, Object> prompt = (Map<String, Object>) limits.get("prompt");

        assertThat((List<?>) prompt.get("allowed")).hasSize(100);
        assertThat(prompt).doesNotContainKey("allowedTruncated");
        // Still marked as a suggestion: nothing refuses a value outside it.
        assertThat(prompt).containsEntry("allowedEnforced", false);
    }

    // ── model-options ───────────────────────────────────────────────────────

    @Test
    @DisplayName("hands the question to the module and passes its answer through whole")
    void optionsDelegatesAndReturnsTheAnswer() {
        when(module.execute(eq("options"), anyMap(), eq("tenant-1"), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of(
                        "options", List.of(Map.of("value", "21m00", "label", "Rachel")),
                        "count", 1,
                        "credential_source", "user"))));

        ResponseEntity<Map<String, Object>> response = controller.options(
                "tts-fast", "voice", "user", null, "tenant-1", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("success")).isEqualTo(true);
        assertThat(response.getBody().get("count")).isEqualTo(1);
        // WHICH key answered has to reach the dialog: it shows the payer beside
        // the field, and a list from another key offers ids a run cannot use.
        assertThat(response.getBody().get("credential_source")).isEqualTo("user");
    }

    @Test
    @DisplayName("the chosen payer travels, because the answer depends on which key is asked")
    void optionsForwardsTheCredentialSource() {
        when(module.execute(eq("options"), anyMap(), eq("tenant-1"), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("options", List.of()))));

        controller.options("tts-fast", "voice", "platform", "42", "tenant-1", "org-1");

        verify(module).execute(eq("options"), parametersCaptor.capture(), eq("tenant-1"),
                contextCaptor.capture());
        assertThat(parametersCaptor.getValue())
                .containsEntry("model", "tts-fast")
                .containsEntry("parameter", "voice")
                .containsEntry("credential_source", "platform");
        // The pinned key rides the CONTEXT, never the parameter map: the module
        // behind this is the one an agent also reaches, and an agent must not be
        // able to name a credential.
        assertThat(parametersCaptor.getValue()).doesNotContainKey("credential_id");
        assertThat(contextCaptor.getValue().credentials()).containsKey("__credentialId__");
    }

    @Test
    @DisplayName("no pinned key means the account default, not an empty one")
    void optionsOmitsABlankCredentialId() {
        when(module.execute(eq("options"), anyMap(), eq("tenant-1"), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("options", List.of()))));

        controller.options("tts-fast", "voice", "user", "", "tenant-1", null);

        verify(module).execute(eq("options"), anyMap(), eq("tenant-1"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().credentials()).doesNotContainKey("__credentialId__");
    }

    @Test
    @DisplayName("a signed-out caller is refused without the module being asked anything")
    void optionsRequiresAUser() {
        ResponseEntity<Map<String, Object>> response =
                controller.options("tts-fast", "voice", null, null, "  ", null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().get("success")).isEqualTo(false);
        verifyNoInteractions(module);
    }

    @Test
    @DisplayName("a refusal carries its REASON, which is not the same fact as an empty list")
    void optionsPassesTheReasonThrough() {
        when(module.execute(eq("options"), anyMap(), eq("tenant-1"), any()))
                .thenReturn(Optional.of(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                        "no ElevenLabs key is connected")));

        ResponseEntity<Map<String, Object>> response =
                controller.options("tts-fast", "voice", "user", null, "tenant-1", null);

        // An empty dropdown says "this account has no voices". The dialog has to
        // be able to say "connect a key" instead.
        assertThat(response.getBody().get("success")).isEqualTo(false);
        assertThat(String.valueOf(response.getBody().get("error"))).contains("key is connected");
        assertThat(response.getBody()).doesNotContainKey("options");
    }

    @Test
    @DisplayName("the model list marks which fields are worth asking about")
    void modelsMarkOptionsAvailable() {
        when(registry.list(null)).thenReturn(List.of(videoModel()));
        when(optionsResolver.unifiedDynamicParameters(any())).thenReturn(java.util.Set.of("prompt"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) controller.models(null).getBody().get("models");
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) rows.get(0).get("limits");
        @SuppressWarnings("unchecked")
        Map<String, Object> prompt = (Map<String, Object>) limits.get("prompt");

        // Without it the dialog draws a text box for a field that has a list,
        // and the reader types an opaque identifier by hand.
        assertThat(prompt).containsEntry("optionsAvailable", true);
    }

    // ── which key can run it here ───────────────────────────────────────────

    @Test
    @DisplayName("each row says which key can run it, so a surface stops offering a payer that cannot")
    void modelsCarryRunsOn() {
        // Renaming this field silently degraded every model to 'unknown' once
        // already, because the reader looked for the old key and nothing
        // asserted the new one.
        when(registry.list(null)).thenReturn(List.of(videoModel()));
        when(optionsResolver.unifiedDynamicParameters(any())).thenReturn(java.util.Set.of());
        when(platformSales.resolve(any())).thenReturn(java.util.Map.of(
                com.apimarketplace.catalog.service.generation.PlatformSalesResolver.ModelRef
                        .of(videoModel()),
                com.apimarketplace.catalog.service.generation.PlatformSalesResolver.Verdict
                        .PLATFORM_OR_OWN_KEY));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) controller.models(null).getBody().get("models");

        assertThat(rows.get(0)).containsEntry("runsOn", "platform_or_own_key");
    }

    @Test
    @DisplayName("a model the resolver did not answer for is 'unknown', never a confident claim")
    void anUnresolvedModelIsUnknown() {
        when(registry.list(null)).thenReturn(List.of(videoModel()));
        when(optionsResolver.unifiedDynamicParameters(any())).thenReturn(java.util.Set.of());
        // Nothing resolved for it: the row must not default to a payer.
        when(platformSales.resolve(any())).thenReturn(java.util.Map.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) controller.models(null).getBody().get("models");

        assertThat(rows.get(0)).containsEntry("runsOn", "unknown");
    }

    @Test
    @DisplayName("the model identity asked about is the one the published prices are keyed on")
    void modelRefIsBuiltFromTheModel() {
        var ref = com.apimarketplace.catalog.service.generation.PlatformSalesResolver.ModelRef
                .of(videoModel());

        // The single shaping point: two surfaces asking differently would get
        // different answers for the same model.
        assertThat(ref.integrationName()).isEqualTo("seedance");
        assertThat(ref.apiToolId()).isEqualTo(TOOL_ID.toString());
        assertThat(ref.modelId()).isEqualTo("seedance-2.0-fast");
    }
}
