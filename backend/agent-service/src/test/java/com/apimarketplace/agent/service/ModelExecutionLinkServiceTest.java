package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.ModelExecutionLinkEntity;
import com.apimarketplace.agent.domain.ModelExecutionLinkScope;
import com.apimarketplace.agent.repository.ModelExecutionLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ModelExecutionLinkService")
@ExtendWith(MockitoExtension.class)
class ModelExecutionLinkServiceTest {

    @Mock private ModelExecutionLinkRepository repository;

    private ModelExecutionLinkService service;

    @BeforeEach
    void setUp() {
        service = new ModelExecutionLinkService(repository);
    }

    @Test
    @DisplayName("resolve returns the execution target for an enabled ALL-scope link on any surface")
    void resolveReturnsRouteForEnabledLink() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.ALL, true)));

        Optional<ModelExecutionLinkService.ExecutionRoute> route =
            service.resolve("anthropic", "claude-opus-4-8", "CHAT");

        assertThat(route).isPresent();
        assertThat(route.get().executionProvider()).isEqualTo("codex");
        assertThat(route.get().executionModel()).isEqualTo("gpt-5.3-codex");
    }

    @Test
    @DisplayName("resolve falls back to the billed model id when execution_model is null")
    void resolveFallsBackToBilledModelWhenExecutionModelNull() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "claude-code", null, ModelExecutionLinkScope.ALL, true)));

        Optional<ModelExecutionLinkService.ExecutionRoute> route =
            service.resolve("anthropic", "claude-opus-4-8", "WORKFLOW");

        assertThat(route).isPresent();
        assertThat(route.get().executionProvider()).isEqualTo("claude-code");
        assertThat(route.get().executionModel()).isEqualTo("claude-opus-4-8");
    }

    @Test
    @DisplayName("resolve is case-insensitive on the provider and exact on the model id")
    void resolveProviderCaseInsensitiveModelExact() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.ALL, true)));

        assertThat(service.resolve("ANTHROPIC", "claude-opus-4-8", "CHAT")).isPresent();
        assertThat(service.resolve("anthropic", "Claude-Opus-4-8", "CHAT")).isEmpty(); // model id is exact
    }

    @Test
    @DisplayName("resolve skips disabled links")
    void resolveSkipsDisabledLinks() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.ALL, false)));

        assertThat(service.resolve("anthropic", "claude-opus-4-8", "CHAT")).isEmpty();
    }

    @Test
    @DisplayName("resolve returns empty for an unknown pair or blank inputs without hitting the DB on blanks")
    void resolveEmptyForUnknownOrBlank() {
        assertThat(service.resolve(null, "m", "CHAT")).isEmpty();
        assertThat(service.resolve("anthropic", " ", "CHAT")).isEmpty();
        verify(repository, never()).findAll(); // blank inputs short-circuit before any query

        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.resolve("anthropic", "claude-opus-4-8", "CHAT")).isEmpty();
    }

    // ── Scope precedence ─────────────────────────────────────────────────────

    @Test
    @DisplayName("resolve: an exact-surface (WORKFLOW) link WINS over the ALL wildcard for a WORKFLOW run (symmetric to CHAT)")
    void resolveWorkflowExactWinsOverAll() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "openrouter", "or-model", ModelExecutionLinkScope.ALL, true),
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.WORKFLOW, true)));

        Optional<ModelExecutionLinkService.ExecutionRoute> route =
            service.resolve("anthropic", "claude-opus-4-8", "WORKFLOW");

        assertThat(route).isPresent();
        assertThat(route.get().executionProvider()).isEqualTo("codex"); // the WORKFLOW row, not the ALL row
    }

    @Test
    @DisplayName("resolve: an UNKNOWN non-null activity source (e.g. a surface that never reaches this chokepoint) matches only the ALL wildcard, never a surface-scoped row")
    void resolveUnknownSourceFallsBackToAllOnly() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "openrouter", "or-model", ModelExecutionLinkScope.ALL, true),
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.CHAT, true)));

        // "SUB_AGENT" is not an enum scope value, so it maps to no surface: only ALL can apply.
        Optional<ModelExecutionLinkService.ExecutionRoute> route =
            service.resolve("anthropic", "claude-opus-4-8", "SUB_AGENT");

        assertThat(route).isPresent();
        assertThat(route.get().executionProvider()).isEqualTo("openrouter"); // ALL row, never the CHAT row
    }

    @Test
    @DisplayName("resolve: an exact-surface (CHAT) link WINS over the ALL wildcard for a CHAT run")
    void resolveExactSurfaceWinsOverAll() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "openrouter", "or-model", ModelExecutionLinkScope.ALL, true),
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.CHAT, true)));

        Optional<ModelExecutionLinkService.ExecutionRoute> route =
            service.resolve("anthropic", "claude-opus-4-8", "CHAT");

        assertThat(route).isPresent();
        assertThat(route.get().executionProvider()).isEqualTo("codex"); // the CHAT row, not the ALL row
    }

    @Test
    @DisplayName("resolve: a run on a DIFFERENT surface (WORKFLOW) falls back to the ALL wildcard, not the CHAT-scoped row")
    void resolveDifferentSurfaceFallsBackToAll() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "openrouter", "or-model", ModelExecutionLinkScope.ALL, true),
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.CHAT, true)));

        Optional<ModelExecutionLinkService.ExecutionRoute> route =
            service.resolve("anthropic", "claude-opus-4-8", "WORKFLOW");

        assertThat(route).isPresent();
        assertThat(route.get().executionProvider()).isEqualTo("openrouter"); // ALL row, NOT the CHAT row
    }

    @Test
    @DisplayName("resolve: a CHAT-only link does NOT apply to a WORKFLOW run when there is no ALL wildcard")
    void resolveSurfaceScopedLinkDoesNotLeakToOtherSurfaces() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.CHAT, true)));

        assertThat(service.resolve("anthropic", "claude-opus-4-8", "CHAT")).isPresent();      // matches its surface
        assertThat(service.resolve("anthropic", "claude-opus-4-8", "WORKFLOW")).isEmpty();    // no ALL fallback
    }

    @Test
    @DisplayName("resolve: CONVERSATION is treated as CHAT so a CHAT-scoped link applies to a conversation-format run")
    void resolveConversationAliasesChat() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.CHAT, true)));

        assertThat(service.resolve("anthropic", "claude-opus-4-8", "CONVERSATION")).isPresent();
    }

    @Test
    @DisplayName("resolve: a null activity source picks the ALL wildcard and never a surface-scoped row")
    void resolveNullSourceMatchesOnlyAllRow() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "openrouter", "or-model", ModelExecutionLinkScope.ALL, true),
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.CHAT, true)));

        Optional<ModelExecutionLinkService.ExecutionRoute> route =
            service.resolve("anthropic", "claude-opus-4-8", null);

        assertThat(route).isPresent();
        assertThat(route.get().executionProvider()).isEqualTo("openrouter"); // ALL row, never the CHAT row
    }

    @Test
    @DisplayName("resolve: a null activity source with only a surface-scoped link (no ALL) resolves to empty")
    void resolveNullSourceWithOnlySurfaceScopedLinkIsEmpty() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.CHAT, true)));

        assertThat(service.resolve("anthropic", "claude-opus-4-8", null)).isEmpty(); // CHAT row not reachable
    }

    @Test
    @DisplayName("resolve: a DISABLED exact-surface row falls through to the enabled ALL wildcard (disabled = ignored, not a block)")
    void resolveDisabledExactSurfaceFallsThroughToAll() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "openrouter", "or-model", ModelExecutionLinkScope.ALL, true),
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.CHAT, false)));

        Optional<ModelExecutionLinkService.ExecutionRoute> route =
            service.resolve("anthropic", "claude-opus-4-8", "CHAT");

        assertThat(route).isPresent();
        assertThat(route.get().executionProvider()).isEqualTo("openrouter"); // disabled CHAT row skipped, ALL applies
    }

    // ── Admin CRUD ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("upsert ACCEPTS a non-bridge execution provider (e.g. openrouter) - the target may be any provider now, not just a CLI bridge")
    void upsertAcceptsNonBridgeExecutionProvider() {
        when(repository.findByBilledProviderAndBilledModelAndScope("anthropic", "claude-opus-4-8", ModelExecutionLinkScope.ALL))
            .thenReturn(Optional.empty());
        when(repository.save(any(ModelExecutionLinkEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelExecutionLinkEntity saved = service.upsert(
            "anthropic", "claude-opus-4-8", "openrouter", "anthropic/claude-3.5-sonnet",
            ModelExecutionLinkScope.ALL, true);

        assertThat(saved.getExecutionProvider()).isEqualTo("openrouter"); // not a CLI bridge - allowed
        assertThat(saved.getExecutionModel()).isEqualTo("anthropic/claude-3.5-sonnet");
        verify(repository).save(any());
    }

    @Test
    @DisplayName("upsert persists the scope and a null scope defaults to ALL")
    void upsertPersistsScopeAndDefaultsNullToAll() {
        when(repository.findByBilledProviderAndBilledModelAndScope("anthropic", "claude-opus-4-8", ModelExecutionLinkScope.CHAT))
            .thenReturn(Optional.empty());
        when(repository.findByBilledProviderAndBilledModelAndScope("openai", "gpt-5", ModelExecutionLinkScope.ALL))
            .thenReturn(Optional.empty());
        when(repository.save(any(ModelExecutionLinkEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelExecutionLinkEntity chatScoped = service.upsert(
            "anthropic", "claude-opus-4-8", "codex", null, ModelExecutionLinkScope.CHAT, true);
        assertThat(chatScoped.getScope()).isEqualTo(ModelExecutionLinkScope.CHAT);

        ModelExecutionLinkEntity nullScoped = service.upsert(
            "openai", "gpt-5", "codex", null, null, true);
        assertThat(nullScoped.getScope()).isEqualTo(ModelExecutionLinkScope.ALL); // null ⇒ ALL
    }

    @Test
    @DisplayName("upsert rejects blank required fields")
    void upsertRejectsBlankFields() {
        assertThatThrownBy(() -> service.upsert(" ", "m", "codex", null, ModelExecutionLinkScope.ALL, true))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upsert("anthropic", "", "codex", null, ModelExecutionLinkScope.ALL, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("upsert normalizes the providers (lowercase) and persists, then invalidates the cache so resolve sees the new link immediately")
    void upsertNormalizesPersistsAndInvalidatesCache() {
        // Cache is first populated empty, then must be rebuilt after the write.
        when(repository.findAll()).thenReturn(List.of(), List.of(
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.ALL, true)));
        when(repository.findByBilledProviderAndBilledModelAndScope("anthropic", "claude-opus-4-8", ModelExecutionLinkScope.ALL))
            .thenReturn(Optional.empty());
        when(repository.save(any(ModelExecutionLinkEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Prime the (empty) cache.
        assertThat(service.resolve("anthropic", "claude-opus-4-8", "CHAT")).isEmpty();

        ModelExecutionLinkEntity saved = service.upsert(
            "ANTHROPIC", "claude-opus-4-8", "CODEX", "gpt-5.3-codex", ModelExecutionLinkScope.ALL, true);

        assertThat(saved.getBilledProvider()).isEqualTo("anthropic"); // lowercased
        assertThat(saved.getExecutionProvider()).isEqualTo("codex");  // lowercased
        // Cache was invalidated by the write: resolve now reflects the new link without waiting the TTL.
        assertThat(service.resolve("anthropic", "claude-opus-4-8", "CHAT")).isPresent();
    }

    @Test
    @DisplayName("upsert that loses a concurrent-create race (unique-constraint violation) retries as an UPDATE of the winner's row instead of surfacing a 500")
    void upsertRetriesAsUpdateOnConcurrentCreateRace() {
        ModelExecutionLinkEntity winnersRow =
            link("anthropic", "claude-opus-4-8", "openrouter", "or-model", ModelExecutionLinkScope.ALL, true);
        // First attempt: the row does not exist yet, but our INSERT loses the race.
        // Retry: the winner's row is now visible and gets updated in place.
        when(repository.findByBilledProviderAndBilledModelAndScope("anthropic", "claude-opus-4-8", ModelExecutionLinkScope.ALL))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winnersRow));
        when(repository.save(any(ModelExecutionLinkEntity.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_model_execution_links_billed_scope\""))
            .thenAnswer(inv -> inv.getArgument(0));

        ModelExecutionLinkEntity saved = service.upsert(
            "anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.ALL, true);

        // Last write wins, applied onto the winner's row (same outcome as sequential PUTs).
        assertThat(saved).isSameAs(winnersRow);
        assertThat(saved.getExecutionProvider()).isEqualTo("codex");
        assertThat(saved.getExecutionModel()).isEqualTo("gpt-5.3-codex");
        verify(repository, org.mockito.Mockito.times(2)).save(any(ModelExecutionLinkEntity.class));
    }

    @Test
    @DisplayName("delete returns true when a scoped link existed and false when it did not")
    void deleteReturnsWhetherLinkExisted() {
        when(repository.findByBilledProviderAndBilledModelAndScope("anthropic", "claude-opus-4-8", ModelExecutionLinkScope.CHAT))
            .thenReturn(Optional.of(link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.CHAT, true)));
        when(repository.findByBilledProviderAndBilledModelAndScope("openai", "gpt-5", ModelExecutionLinkScope.ALL))
            .thenReturn(Optional.empty());

        assertThat(service.delete("anthropic", "claude-opus-4-8", ModelExecutionLinkScope.CHAT)).isTrue();
        assertThat(service.delete("openai", "gpt-5", ModelExecutionLinkScope.ALL)).isFalse();
    }

    @Test
    @DisplayName("a write that lands while a rebuild is in flight does not poison the cache (TOCTOU guard): the next resolve sees the new link, not the stale snapshot")
    void writeDuringRebuildDoesNotPoisonCache() {
        when(repository.findByBilledProviderAndBilledModelAndScope("anthropic", "claude-opus-4-8", ModelExecutionLinkScope.ALL))
            .thenReturn(Optional.empty());
        when(repository.save(any(ModelExecutionLinkEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        java.util.concurrent.atomic.AtomicInteger findAllCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(repository.findAll()).thenAnswer(inv -> {
            // First load races a concurrent write: the write commits (and bumps the
            // write-version) BEFORE this stale (empty) result is returned to snapshot().
            if (findAllCalls.getAndIncrement() == 0) {
                service.upsert("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.ALL, true);
                return List.of(); // stale view, predates the write
            }
            return List.of(link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.ALL, true));
        });

        // First resolve drives the racing rebuild; its stale empty snapshot must NOT be published.
        assertThat(service.resolve("anthropic", "claude-opus-4-8", "CHAT")).isEmpty();
        // Next resolve rebuilds (cache was not poisoned for a full TTL) and sees the new link.
        assertThat(service.resolve("anthropic", "claude-opus-4-8", "CHAT")).isPresent();
    }

    // ── resolveSingleCompletionTarget (json-completion, third link consumer) ─────

    @Test
    @DisplayName("singleCompletion: no link keeps the requested pair verbatim")
    void singleCompletionNoLinkVerbatim() {
        when(repository.findAll()).thenReturn(List.of());

        ModelExecutionLinkService.SingleCompletionTarget target =
            service.resolveSingleCompletionTarget("anthropic", "claude-haiku-4-5");

        assertThat(target.provider()).isEqualTo("anthropic");
        assertThat(target.model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    @DisplayName("singleCompletion: an ALL-scope link to an API provider swaps the execution pair (compaction summariser gap)")
    void singleCompletionApiLinkSwaps() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-haiku-4-5", "openrouter", "anthropic/claude-haiku", ModelExecutionLinkScope.ALL, true)));

        ModelExecutionLinkService.SingleCompletionTarget target =
            service.resolveSingleCompletionTarget("anthropic", "claude-haiku-4-5");

        assertThat(target.provider()).isEqualTo("openrouter");
        assertThat(target.model()).isEqualTo("anthropic/claude-haiku");
    }

    @Test
    @DisplayName("singleCompletion: a bridge-target link throws BRIDGE_EXECUTION_NOT_RELAYABLE instead of silently hitting the billed key")
    void singleCompletionBridgeLinkThrows() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "claude-code", null, ModelExecutionLinkScope.ALL, true)));

        assertThatThrownBy(() -> service.resolveSingleCompletionTarget("anthropic", "claude-opus-4-8"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("BRIDGE_EXECUTION_NOT_RELAYABLE")
            .hasMessageContaining("claude-code");
    }

    @Test
    @DisplayName("singleCompletion: only ALL-scope links apply - a surface-scoped link is ignored (no activity source on a bare completion)")
    void singleCompletionIgnoresSurfaceScopedLinks() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-haiku-4-5", "openrouter", "or-model", ModelExecutionLinkScope.CHAT, true)));

        ModelExecutionLinkService.SingleCompletionTarget target =
            service.resolveSingleCompletionTarget("anthropic", "claude-haiku-4-5");

        assertThat(target.provider()).isEqualTo("anthropic");
        assertThat(target.model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    @DisplayName("singleCompletion: a disabled link keeps the requested pair verbatim")
    void singleCompletionDisabledLinkVerbatim() {
        when(repository.findAll()).thenReturn(List.of(
            link("anthropic", "claude-haiku-4-5", "openrouter", "or-model", ModelExecutionLinkScope.ALL, false)));

        ModelExecutionLinkService.SingleCompletionTarget target =
            service.resolveSingleCompletionTarget("anthropic", "claude-haiku-4-5");

        assertThat(target.provider()).isEqualTo("anthropic");
        assertThat(target.model()).isEqualTo("claude-haiku-4-5");
    }

    private static ModelExecutionLinkEntity link(String billedProvider, String billedModel,
                                                 String executionProvider, String executionModel,
                                                 ModelExecutionLinkScope scope, boolean enabled) {
        ModelExecutionLinkEntity e = new ModelExecutionLinkEntity();
        e.setBilledProvider(billedProvider);
        e.setBilledModel(billedModel);
        e.setExecutionProvider(executionProvider);
        e.setExecutionModel(executionModel);
        e.setScope(scope);
        e.setEnabled(enabled);
        return e;
    }
}
