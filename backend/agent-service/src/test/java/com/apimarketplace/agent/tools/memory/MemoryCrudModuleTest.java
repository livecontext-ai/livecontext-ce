package com.apimarketplace.agent.tools.memory;

import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.config.GuardOverrides;
import com.apimarketplace.agent.domain.AgentMemoryEntity;
import com.apimarketplace.agent.domain.AgentMemoryEntity.MemorySource;
import com.apimarketplace.agent.domain.AgentMemoryEntity.MemoryType;
import com.apimarketplace.agent.memory.MemoryPromptSection;
import com.apimarketplace.agent.memory.MemoryService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("MemoryCrudModule")
class MemoryCrudModuleTest {

    private static final String TENANT = "42";
    private static final String ORG = "org-alpha";
    private static final UUID CALLER_AGENT = UUID.randomUUID();

    @Mock private MemoryService memoryService;
    @Mock private MemoryPromptSection promptSection;
    @Mock private AgentService agentService;

    private MemoryCrudModule module;

    @BeforeEach
    void setUp() {
        module = new MemoryCrudModule(memoryService, promptSection, agentService, new AgentDefaultsConfig());
        lenient().when(agentService.findById(any())).thenReturn(Optional.empty());
    }

    private ToolExecutionContext ctx() {
        return ctx(new HashMap<>());
    }

    private ToolExecutionContext ctx(Map<String, Object> credentials) {
        return new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(), null, null, ORG, "MEMBER");
    }

    private ToolExecutionContext ctxWithCallerAgent() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("__agentId__", CALLER_AGENT.toString());
        return ctx(creds);
    }
    /**
     * A save that INSERTED, which is what these tests mean unless they say otherwise.
     *
     * <p>The outcome exists because a save can replace an entry a person wrote, and
     * the module has to report that rather than answer a flat "SAVED". The tests
     * that are about a replacement build their own outcome.
     */
    private static MemoryService.SaveOutcome created(AgentMemoryEntity entity) {
        return new MemoryService.SaveOutcome(entity, true, null, null);
    }


    private static AgentMemoryEntity entity(String slug) {
        AgentMemoryEntity e = new AgentMemoryEntity();
        e.setId(UUID.randomUUID());
        e.setSlug(slug);
        e.setTitle("Title of " + slug);
        e.setSummary("Summary of " + slug);
        e.setContent("Body of " + slug);
        e.setType(MemoryType.PROJECT);
        e.setSource(MemorySource.AGENT);
        e.setPinned(false);
        e.setTags(List.of());
        return e;
    }

    private ToolExecutionResult run(String action, Map<String, Object> params, ToolExecutionContext context) {
        return module.execute(action, params, TENANT, context).orElseThrow();
    }

    @Nested
    @DisplayName("workspace requirement")
    class WorkspaceRequirement {

        @Test
        @DisplayName("refuses every action when the call carries no workspace, and explains why")
        void refusesWithoutWorkspace() {
            ToolExecutionContext noOrg =
                new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, null, null);

            ToolExecutionResult result = run("list", Map.of(), noOrg);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("workspace");
        }
    }

    @Nested
    @DisplayName("save")
    class Save {

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.ValueSource(strings = {"private", "user", "", "   "})
        @DisplayName("refuses an invalid scope instead of silently sharing the fact with the workspace")
        void invalidScopeDoesNotBecomeWorkspaceMemory(String scope) {
            ToolExecutionResult result = run("save", Map.of("title", "Preference", "summary", "Sam prefers short answers.",
                "scope", scope), ctxWithCallerAgent());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ENUM_VALUE);
            verify(memoryService, never()).save(any(), any());
        }

        @Test
        @DisplayName("refuses a non-string scope instead of treating it as an omitted parameter")
        void nonStringScopeDoesNotBecomeWorkspaceMemory() {
            ToolExecutionResult result = run("save", Map.of("title", "Preference", "summary", "Sam prefers short answers.",
                "scope", List.of("agent")), ctxWithCallerAgent());

            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ENUM_VALUE);
            verify(memoryService, never()).save(any(), any());
        }

        @Test
        @DisplayName("requires a title, and shows a complete call the agent can copy")
        void requiresTitle() {
            ToolExecutionResult result = run("save", Map.of("summary", "x"), ctx());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(result.error()).contains("memory(action='save'");
        }

        @Test
        @DisplayName("requires a summary and teaches the difference between stating a fact and pointing at one")
        void requiresSummary() {
            ToolExecutionResult result = run("save", Map.of("title", "x"), ctx());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("GOOD:").contains("BAD:");
        }

        @Test
        @DisplayName("defaults to workspace scope so a saved fact is useful to the whole workspace")
        void defaultsToWorkspaceScope() {
            when(memoryService.save(any(), anyString())).thenReturn(created(entity("release-cadence")));

            run("save", Map.of("title", "Release cadence", "summary", "Ships Thursdays"), ctxWithCallerAgent());

            ArgumentCaptor<MemoryService.SaveRequest> captor =
                ArgumentCaptor.forClass(MemoryService.SaveRequest.class);
            verify(memoryService).save(captor.capture(), anyString());
            assertThat(captor.getValue().agentId()).isNull();
        }

        @Test
        @DisplayName("scopes to the CALLING agent for scope='agent', never to an agent id the caller supplied")
        void agentScopeComesFromCredentialsNotParameters() {
            when(memoryService.save(any(), anyString())).thenReturn(created(entity("note")));
            UUID someoneElse = UUID.randomUUID();

            run("save", Map.of("title", "Note", "summary", "s",
                "scope", "agent", "agent_id", someoneElse.toString()), ctxWithCallerAgent());

            ArgumentCaptor<MemoryService.SaveRequest> captor =
                ArgumentCaptor.forClass(MemoryService.SaveRequest.class);
            verify(memoryService).save(captor.capture(), anyString());
            assertThat(captor.getValue().agentId())
                .as("an agent must not be able to write into a sibling's private memory by naming it")
                .isEqualTo(CALLER_AGENT)
                .isNotEqualTo(someoneElse);
        }

        @Test
        @DisplayName("marks the entry as agent-written, so a human can tell it from one they asserted")
        void stampsAgentSource() {
            when(memoryService.save(any(), anyString())).thenReturn(created(entity("s")));

            run("save", Map.of("title", "T", "summary", "S"), ctx());

            ArgumentCaptor<MemoryService.SaveRequest> captor =
                ArgumentCaptor.forClass(MemoryService.SaveRequest.class);
            verify(memoryService).save(captor.capture(), anyString());
            assertThat(captor.getValue().source()).isEqualTo(MemorySource.AGENT);
        }

        @Test
        @DisplayName("rejects an unknown type and lists the four, with what each one is for")
        void rejectsUnknownType() {
            ToolExecutionResult result = run("save",
                Map.of("title", "T", "summary", "S", "type", "notatype"), ctx());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("user").contains("feedback").contains("project").contains("reference");
            verify(memoryService, never()).save(any(), anyString());
        }

        @Test
        @DisplayName("accepts tags as a comma-joined string, which models emit as often as an array")
        void acceptsCommaJoinedTags() {
            when(memoryService.save(any(), anyString())).thenReturn(created(entity("s")));

            run("save", Map.of("title", "T", "summary", "S", "tags", "release, deploy"), ctx());

            ArgumentCaptor<MemoryService.SaveRequest> captor =
                ArgumentCaptor.forClass(MemoryService.SaveRequest.class);
            verify(memoryService).save(captor.capture(), anyString());
            assertThat(captor.getValue().tags()).containsExactly("release", "deploy");
        }

        @Test
        @DisplayName("tells the agent the entry is readable now but joins its index only on the next run")
        void explainsTheFrozenSnapshot() {
            when(memoryService.save(any(), anyString())).thenReturn(created(entity("release-cadence")));

            ToolExecutionResult result = run("save", Map.of("title", "T", "summary", "S"), ctx());

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat((String) data.get("note"))
                .contains("NEXT run")
                .contains("frozen");
        }

        @Test
        @DisplayName("surfaces a VIEWER refusal as a permission error, not a generic failure")
        void mapsViewerRefusalToPermissionDenied() {
            when(memoryService.save(any(), anyString()))
                .thenThrow(new MemoryService.MemoryWriteForbiddenException("read-only"));

            ToolExecutionResult result = run("save", Map.of("title", "T", "summary", "S"), ctx());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        }

        @Test
        @DisplayName("passes the injection-scan rejection through verbatim, so the agent can rewrite and retry")
        void surfacesGuardRejection() {
            when(memoryService.save(any(), anyString()))
                .thenThrow(new MemoryService.MemoryValidationException("refused: prompt_injection ... save again"));

            ToolExecutionResult result = run("save", Map.of("title", "T", "summary", "S"), ctx());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("prompt_injection").contains("save again");
        }
    }

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("addresses an entry by its slug, the handle shown on every index line")
        void readsBySlug() {
            when(memoryService.getBySlugAndRecordRecall(ORG, null, "release-cadence"))
                .thenReturn(Optional.of(entity("release-cadence")));

            ToolExecutionResult result = run("get", Map.of("slug", "release-cadence"), ctx());

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data.get("content")).isEqualTo("Body of release-cadence");
        }

        @Test
        @DisplayName("also accepts a UUID, so a caller holding one from the API is not stuck")
        void readsById() {
            AgentMemoryEntity found = entity("s");
            when(memoryService.getByIdVisibleToAgent(found.getId(), ORG, null)).thenReturn(Optional.of(found));

            ToolExecutionResult result = run("get", Map.of("memory_id", found.getId().toString()), ctx());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("says which parameter to pass when neither was given")
        void requiresAnIdentifier() {
            ToolExecutionResult result = run("get", Map.of(), ctx());

            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(result.error()).contains("slug").contains("memory_id");
        }

        @Test
        @DisplayName("on a miss, points at the two actions that would find the entry")
        void missSuggestsRecovery() {
            when(memoryService.getBySlugAndRecordRecall(anyString(), any(), anyString()))
                .thenReturn(Optional.empty());

            ToolExecutionResult result = run("get", Map.of("slug", "nope"), ctx());

            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            assertThat(result.error()).contains("memory(action='search'").contains("memory(action='list')");
        }
    }

    @Nested
    @DisplayName("list")
    class ListAction {

        @Test
        @DisplayName("returns a paginated envelope of summaries, never the bodies")
        void returnsSummariesOnly() {
            when(memoryService.listVisible(ORG, null))
                .thenReturn(List.of(entity("a"), entity("b")));

            ToolExecutionResult result = run("list", Map.of(), ctx());

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            List<?> items = (List<?>) data.get("memories");
            assertThat(items).hasSize(2);
            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) items.get(0);
            assertThat(first).doesNotContainKey("content");
        }

        @Test
        @DisplayName("filters by type before paginating, so the reported total matches the filter")
        void filtersByType() {
            AgentMemoryEntity userFact = entity("u");
            userFact.setType(MemoryType.USER);
            when(memoryService.listVisible(ORG, null)).thenReturn(List.of(userFact, entity("p")));

            ToolExecutionResult result = run("list", Map.of("type", "user"), ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat((List<?>) data.get("memories")).hasSize(1);
        }

        @Test
        @DisplayName("returns the injected index block verbatim for as_index, which is how a CLI agent discovers memory")
        void asIndexReturnsThePromptBlock() {
            when(promptSection.render(ORG, null)).thenReturn("<recalled-memory>\n## Index\n- [user] a: b\n</recalled-memory>");

            ToolExecutionResult result = run("list", Map.of("as_index", true), ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat((String) data.get("index")).contains("## Index").contains("- [user] a: b");
        }

        @Test
        @DisplayName("says the workspace is empty rather than returning a blank index with no explanation")
        void asIndexOnEmptyWorkspaceExplainsItself() {
            when(promptSection.render(ORG, null)).thenReturn("");

            ToolExecutionResult result = run("list", Map.of("as_index", true), ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat((String) data.get("note")).contains("no memories yet").contains("memory(action='save'");
        }
    }

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("requires a query and explains that search also covers bodies, unlike list")
        void requiresQuery() {
            ToolExecutionResult result = run("search", Map.of(), ctx());

            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(result.error()).contains("title, summary AND body");
        }

        @Test
        @DisplayName("on no hits, says search is word-based so the agent retries differently instead of concluding nothing exists")
        void emptyResultExplainsWhy() {
            when(memoryService.search(anyString(), any(), anyString(), anyInt())).thenReturn(List.of());

            ToolExecutionResult result = run("search", Map.of("query", "cadence"), ctx());

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat((String) data.get("note")).contains("word-based").contains("not semantic");
        }

        @Test
        @DisplayName("returns summaries and points at get for the full entry")
        void hitsPointAtGet() {
            when(memoryService.search(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(entity("a")));

            ToolExecutionResult result = run("search", Map.of("query", "cadence"), ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data.get("count")).isEqualTo(1);
            assertThat((String) data.get("note")).contains("memory(action='get'");
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("resolves a slug to its row before deleting, so the agent never needs a UUID")
        void deletesBySlug() {
            AgentMemoryEntity target = entity("stale");
            when(memoryService.findBySlugVisibleToAgent(ORG, null, "stale")).thenReturn(Optional.of(target));

            ToolExecutionResult result = run("delete", Map.of("slug", "stale"), ctx());

            assertThat(result.success()).isTrue();
            verify(memoryService).deleteVisibleToAgent(target.getId(), ORG, "MEMBER", null);
            // A delete is not the agent reaching for the fact, so it must not inflate
            // the recall counter a person prunes by.
            verify(memoryService, never()).getBySlugAndRecordRecall(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("reports a missing slug as not-found instead of silently succeeding")
        void missingSlugIsNotFound() {
            when(memoryService.findBySlugVisibleToAgent(anyString(), any(), anyString()))
                .thenReturn(Optional.empty());

            ToolExecutionResult result = run("delete", Map.of("slug", "ghost"), ctx());

            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            verify(memoryService, never()).deleteVisibleToAgent(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("surfaces a VIEWER refusal as a permission error")
        void viewerCannotDelete() {
            AgentMemoryEntity target = entity("x");
            when(memoryService.findBySlugVisibleToAgent(anyString(), any(), anyString()))
                .thenReturn(Optional.of(target));
            org.mockito.Mockito.doThrow(new MemoryService.MemoryWriteForbiddenException("read-only"))
                .when(memoryService).deleteVisibleToAgent(any(), anyString(), anyString(), any());

            ToolExecutionResult result = run("delete", Map.of("slug", "x"), ctx());

            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        }
    }

    @Test
    @DisplayName("handles exactly the five data actions and leaves help to its own module")
    void handlesOnlyItsOwnActions() {
        assertThat(module.canHandle("save")).isTrue();
        assertThat(module.canHandle("get")).isTrue();
        assertThat(module.canHandle("list")).isTrue();
        assertThat(module.canHandle("search")).isTrue();
        assertThat(module.canHandle("delete")).isTrue();
        assertThat(module.canHandle("help")).isFalse();
        assertThat(module.execute("help", Map.of(), TENANT, ctx())).isEmpty();
    }
    @Nested
    @DisplayName("read-only access mode")
    class ReadOnlyMode {

        /** An agent whose configuration set memoryAccessMode='read'. */
        private ToolExecutionContext readOnly() {
            Map<String, Object> creds = new HashMap<>();
            creds.put("__memoryAccessMode__", "read");
            return ctx(creds);
        }

        @Test
        @DisplayName("refuses save, so a narrowly-scoped agent cannot write into every agent's context")
        void refusesSave() {
            ToolExecutionResult result = run("save", Map.of(
                "title", "Release cadence", "summary", "The team ships on Thursdays."), readOnly());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("read-only");
            // The refusal has to happen before the write, not be reported after it.
            verify(memoryService, never()).save(any(), anyString());
        }

        @Test
        @DisplayName("refuses delete for the same reason, since removing a fact changes every agent's context too")
        void refusesDelete() {
            ToolExecutionResult result = run("delete", Map.of("slug", "release-cadence"), readOnly());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("read-only");
            verify(memoryService, never()).deleteVisibleToAgent(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("still allows the four reads, or the agent would carry an index it cannot open")
        void allowsEveryRead() {
            when(memoryService.listVisible(anyString(), any())).thenReturn(List.of(entity("a-fact")));
            when(memoryService.search(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(entity("a-fact")));
            when(memoryService.getBySlugAndRecordRecall(anyString(), any(), anyString()))
                .thenReturn(Optional.of(entity("a-fact")));

            // search is a READ and is registered as one. Denying it would leave the
            // agent with a memory index in its prompt and no way to open anything in
            // it, which is worse than having no memory at all.
            assertThat(run("list", Map.of(), readOnly()).success()).isTrue();
            assertThat(run("search", Map.of("query", "cadence"), readOnly()).success()).isTrue();
            assertThat(run("get", Map.of("slug", "a-fact"), readOnly()).success()).isTrue();
        }

        @Test
        @DisplayName("an agent with no mode set keeps full access, which is the default every other family has")
        void absentModeMeansWrite() {
            when(memoryService.save(any(), anyString())).thenReturn(created(entity("release-cadence")));

            ToolExecutionResult result = run("save", Map.of(
                "title", "Release cadence", "summary", "The team ships on Thursdays."), ctx());

            assertThat(result.success()).isTrue();
            verify(memoryService).save(any(), anyString());
        }

        @Test
        @DisplayName("reads the mode from the plain credential key as well as the namespaced one")
        void acceptsThePlainCredentialKey() {
            // Tool controllers forward 'memoryAccessMode'; the in-process agent loop
            // forwards '__memoryAccessMode__'. Honouring only one of the two would
            // leave the restriction working on one execution path and silently absent
            // on the other, which is the shape of gap that ships unnoticed.
            Map<String, Object> creds = new HashMap<>();
            creds.put("memoryAccessMode", "read");

            ToolExecutionResult result = run("save", Map.of(
                "title", "Release cadence", "summary", "s"), ctx(creds));

            assertThat(result.success()).isFalse();
            verify(memoryService, never()).save(any(), anyString());
        }
    }

    @Nested
    @DisplayName("results the agent reads back")
    class ResultShape {

        @Test
        @DisplayName("a save onto an entry a person switched off says so, instead of reporting a plain success")
        void saveReportsASwitchedOffEntry() {
            AgentMemoryEntity off = entity("stale-fact");
            off.setIsActive(false);
            when(memoryService.save(any(), anyString())).thenReturn(created(off));

            ToolExecutionResult result = run("save", Map.of(
                "title", "Stale fact", "summary", "Something a person turned off."), ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            // The help tells the agent to read this field. Without it the agent sees
            // SAVED, sees nothing in its index next run, and saves again forever.
            assertThat(data.get("is_active")).isEqualTo(false);
            assertThat((String) data.get("note")).contains("switched off");
            assertThat((String) data.get("note")).doesNotContain("joins the memory index");
        }

        @Test
        @DisplayName("an ordinary save still promises the index, and says the entry is live")
        void saveReportsALiveEntry() {
            when(memoryService.save(any(), anyString())).thenReturn(created(entity("release-cadence")));

            ToolExecutionResult result = run("save", Map.of(
                "title", "Release cadence", "summary", "The team ships on Thursdays."), ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data.get("is_active")).isEqualTo(true);
            assertThat((String) data.get("note")).contains("NEXT run");
        }

        @Test
        @DisplayName("refuses an unknown type filter on list rather than silently returning everything")
        void listRefusesAnUnknownType() {
            when(memoryService.listVisible(anyString(), any())).thenReturn(List.of(entity("a"), entity("b")));

            ToolExecutionResult result = run("list", Map.of("type", "feedbacks"), ctx());

            // It used to drop the filter it could not parse, so asking for a type that
            // does not exist returned the WHOLE list and the agent read unrelated rows
            // as that type. save refuses the same value.
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("feedback");
        }

        @Test
        @DisplayName("still lists everything when no type filter is given at all")
        void listWithoutATypeIsUnfiltered() {
            when(memoryService.listVisible(anyString(), any())).thenReturn(List.of(entity("a"), entity("b")));

            assertThat(run("list", Map.of(), ctx()).success()).isTrue();
        }
    }
    @Nested
    @DisplayName("branches a plain conversation reaches")
    class NoCallingAgent {

        @Test
        @DisplayName("explains that scope='agent' needs an agent, instead of quietly storing a workspace entry")
        void privateScopeWithoutAnAgentIsRefused() {
            // A plain chat has no agent for the entry to be private TO. Falling back to
            // workspace scope would be the worst outcome: the caller asked for private
            // and would get an entry every agent in the workspace can read.
            ToolExecutionResult result = run("save", Map.of(
                "action", "save", "scope", "agent",
                "title", "Private note", "summary", "Meant for one agent only."), ctx());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("scope='agent'");
            assertThat(result.error()).contains("Do not broaden the scope");
            verify(memoryService, never()).save(any(), anyString());
        }
    }

    @Nested
    @DisplayName("delete by id")
    class DeleteById {

        @Test
        @DisplayName("goes through the scope-checked resolver, so an id is not a way around agent privacy")
        void deleteByIdIsScopeChecked() {
            AgentMemoryEntity target = entity("a-fact");

            ToolExecutionResult result = run("delete",
                Map.of("memory_id", target.getId().toString()), ctxWithCallerAgent());

            assertThat(result.success()).isTrue();
            // Ids are not secret here: the tab renders one per row and save returns one.
            // Deleting by id has to be resolved through the same visibility rule as a
            // slug, or any agent that had ever seen an id could delete a sibling's
            // private entry.
            verify(memoryService).deleteVisibleToAgent(target.getId(), ORG, "MEMBER", CALLER_AGENT);
        }

        @Test
        @DisplayName("reports an id it cannot see as not-found, never as forbidden")
        void anInvisibleIdIsNotFound() {
            // The scope check lives in the service, which refuses a row the caller
            // cannot see. What is asserted here is how the tool REPORTS that refusal.
            org.mockito.Mockito.doThrow(new MemoryService.MemoryNotFoundException("Memory not found"))
                .when(memoryService).deleteVisibleToAgent(any(), anyString(), any(), any());

            ToolExecutionResult result = run("delete",
                Map.of("memory_id", UUID.randomUUID().toString()), ctxWithCallerAgent());

            // 404-not-403 all the way down: an EXECUTION_FAILED here would read as a
            // bug and invite a retry, and a PERMISSION_DENIED would confirm to the
            // caller that a sibling agent holds an entry with that id.
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("what a refusal points the agent at")
    class RefusalGuidance {

        @Test
        @DisplayName("does not tell the agent to find an id in list, which list does not return")
        void deleteRefusalPointsAtWhatListActuallyGives() {
            ToolExecutionResult result = run("delete", Map.of(), ctx());

            assertThat(result.success()).isFalse();
            // list returns summaries only (no id); the id comes back from get and
            // from save. Telling the agent otherwise costs it a turn and teaches it
            // the tool's own documentation is unreliable.
            assertThat(result.error())
                .contains("gives you the slug")
                .doesNotContain("shows both");
        }

        @Test
        @DisplayName("a listed row carries no id, which is what makes that wording matter")
        void listRowsReallyHaveNoId() {
            when(memoryService.listVisible(anyString(), any())).thenReturn(List.of(entity("a")));

            ToolExecutionResult result = run("list", Map.of(), ctx());

            Map<?, ?> data = (Map<?, ?>) result.data();
            List<?> items = (List<?>) data.get("memories");
            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) items.get(0);
            assertThat(first).doesNotContainKey("id").containsKey("slug");
        }
    }

        @Nested
    @DisplayName("a save that replaced something")
    class SaveReplacement {

        private MemoryService.SaveOutcome replacing(MemorySource previousSource) {
            return new MemoryService.SaveOutcome(
                entity("deploy-cadence"), false, "Deploy cadence", previousSource);
        }

        @Test
        @DisplayName("says CREATED on an insert, so the agent can report a new fact as new")
        void anInsertSaysCreated() {
            when(memoryService.save(any(), anyString())).thenReturn(created(entity("a-fact")));

            ToolExecutionResult result = run("save", Map.of(
                "title", "A fact", "summary", "Worth keeping."), ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).containsEntry("status", "CREATED");
            // Nothing was displaced, so nothing about a replacement is reported: an
            // always-present null field reads as "there was something" to a model.
            assertThat(data).doesNotContainKey("replaced_title");
            assertThat(data).doesNotContainKey("replaced_source");
        }

        @Test
        @DisplayName("says REPLACED and names what it overwrote, instead of a flat SAVED")
        void aReplacementIsReported() {
            when(memoryService.save(any(), anyString())).thenReturn(replacing(MemorySource.AGENT));

            ToolExecutionResult result = run("save", Map.of(
                "title", "Deploy cadence", "summary", "Ships Tuesdays now."), ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).containsEntry("status", "REPLACED");
            assertThat(data).containsEntry("replaced_title", "Deploy cadence");
            assertThat(data.get("note").toString()).contains("omitted fields were kept");
            assertThat(data).containsEntry("replaced_source", "agent");
            assertThat(data.get("note").toString())
                .as("its own earlier note, so this reads as the correction it is")
                .contains("earlier agent entry");
        }

        @Test
        @DisplayName("tells the agent to SAY SO when the entry it replaced was a person's")
        void overwritingAPersonIsCalledOut() {
            when(memoryService.save(any(), anyString())).thenReturn(replacing(MemorySource.USER));

            ToolExecutionResult result = run("save", Map.of(
                "title", "Deploy cadence", "summary", "Ships Tuesdays now."), ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            // The whole point. The slug is derived from the title, so an agent can
            // replace a hand-written entry it never saw. Reported as "SAVED" the
            // person learns their note is gone by missing it weeks later.
            assertThat(data).containsEntry("replaced_source", "user");
            assertThat(data.get("note").toString())
                .contains("written by a PERSON")
                .contains("say in your reply what you overwrote");
        }

        @Test
        @DisplayName("still leads with the switched-off warning, which makes the write pointless")
        void switchedOffWinsOverTheReplacementNote() {
            AgentMemoryEntity off = entity("deploy-cadence");
            off.setIsActive(false);
            when(memoryService.save(any(), anyString())).thenReturn(
                new MemoryService.SaveOutcome(off, false, "Deploy cadence", MemorySource.USER));

            ToolExecutionResult result = run("save", Map.of(
                "title", "Deploy cadence", "summary", "Ships Tuesdays now."), ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            // Both are true, and only one changes what the agent should do next: an
            // entry that will never be recalled makes the replacement moot.
            assertThat(data).containsEntry("is_active", false);
            assertThat(data.get("note").toString()).contains("switched off");
        }
    }

        @Nested
    @DisplayName("per-turn save cap")
    class PerTurnCap {

        private ToolExecutionContext inOneTurn() {
            Map<String, Object> creds = new HashMap<>();
            creds.put("turnId", "turn-1");
            return ctx(creds);
        }

        @Test
        @DisplayName("stops a run that decides everything is memorable from filling the workspace in one turn")
        void theCapStopsARunawayTurn() {
            when(memoryService.save(any(), anyString())).thenAnswer(inv -> created(entity("saved")));
            int cap = new AgentDefaultsConfig().getMaxPerResourcePerTurn();

            int accepted = 0;
            ToolExecutionResult refusal = null;
            for (int i = 0; i < cap + 3; i++) {
                ToolExecutionResult result = run("save", Map.of(
                    "title", "Fact " + i, "summary", "Something worth keeping, number " + i + "."),
                    inOneTurn());
                if (result.success()) {
                    accepted++;
                } else if (refusal == null) {
                    refusal = result;
                }
            }

            // The cap exists because a single answer can look infinitely memorable to
            // a model, and the workspace holds 200 entries for everyone.
            assertThat(accepted).isEqualTo(cap);
            assertThat(refusal).isNotNull();
            assertThat(refusal.error()).contains("LIMIT REACHED");
        }

        @Test
        @DisplayName("honours a per-chat override of the cap instead of the platform default")
        void aChatOverrideRaisesTheCap() {
            when(memoryService.save(any(), anyString())).thenAnswer(inv -> created(entity("saved")));
            int platformDefault = new AgentDefaultsConfig().getMaxPerResourcePerTurn();
            Map<String, Object> creds = new HashMap<>();
            creds.put("turnId", "turn-override");
            creds.put(GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN, platformDefault + 2);
            ToolExecutionContext context = ctx(creds);

            int accepted = 0;
            for (int i = 0; i < platformDefault + 4; i++) {
                if (run("save", Map.of("title", "Fact " + i,
                    "summary", "Something worth keeping, number " + i + "."), context).success()) {
                    accepted++;
                }
            }

            // The ladder is per-agent, then per-chat, then the YAML default. Read in the
            // wrong order - or not read at all - the cap silently stays at the platform
            // figure, and an operator who raised it for one workspace sees no effect and
            // no error.
            assertThat(accepted).isEqualTo(platformDefault + 2);
        }

        @Test
        @DisplayName("counts each turn separately, so a long conversation is not capped in total")
        void theCapIsPerTurnNotPerConversation() {
            when(memoryService.save(any(), anyString())).thenAnswer(inv -> created(entity("saved")));
            int cap = new AgentDefaultsConfig().getMaxPerResourcePerTurn();

            for (int turn = 0; turn < 2; turn++) {
                Map<String, Object> creds = new HashMap<>();
                creds.put("turnId", "turn-" + turn);
                ToolExecutionContext context = ctx(creds);
                for (int i = 0; i < cap; i++) {
                    assertThat(run("save", Map.of("title", "Turn " + turn + " fact " + i,
                        "summary", "Something worth keeping."), context).success())
                        .as("turn %d, save %d", turn, i)
                        .isTrue();
                }
            }
        }

        @Test
        @DisplayName("does not apply when the call carries no turn, so a person on the REST surface is never capped")
        void noTurnMeansNoCap() {
            when(memoryService.save(any(), anyString())).thenAnswer(inv -> created(entity("saved")));
            int cap = new AgentDefaultsConfig().getMaxPerResourcePerTurn();

            for (int i = 0; i < cap + 2; i++) {
                assertThat(run("save", Map.of(
                    "title", "Fact " + i, "summary", "Something worth keeping, number " + i + "."),
                    ctx()).success()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("how a refusal is coded")
    class RefusalCoding {

        @Test
        @DisplayName("a refused write is a parameter problem, not a transient failure to retry")
        void validationRefusalsAreParameterErrors() {
            when(memoryService.save(any(), anyString()))
                .thenThrow(new MemoryService.MemoryValidationException(
                    "This workspace already holds the maximum of 200 shared memories."));

            ToolExecutionResult result = run("save", Map.of(
                "title", "One more", "summary", "There is no room for this."), ctx());

            // EXECUTION_FAILED reads as transient to a model and invites the identical
            // retry, which will be refused identically. A full workspace, a length cap
            // and an injection refusal are all things the CALLER has to change.
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        }
    }

    @Nested
    @DisplayName("an id the caller mistyped")
    class MalformedId {

        @Test
        @DisplayName("get says which parameter to pass instead of reporting the entry as missing")
        void getWithABadIdAsksForAHandle() {
            ToolExecutionResult result = run("get", Map.of("memory_id", "not-a-uuid"), ctx());

            // A garbled id parses to null, which lands in the same branch as passing
            // nothing at all. "No such memory" would send the agent looking for an
            // entry that may well exist, instead of telling it to fix the call.
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verify(memoryService, never()).getByIdVisibleToAgent(any(), anyString(), any());
        }

        @Test
        @DisplayName("delete does the same, and deletes nothing")
        void deleteWithABadIdDeletesNothing() {
            ToolExecutionResult result = run("delete", Map.of("memory_id", "not-a-uuid"), ctx());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verify(memoryService, never()).deleteVisibleToAgent(any(), anyString(), any(), any());
            verify(memoryService, never()).findBySlugVisibleToAgent(anyString(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("as_index")
    class AsIndex {

        @Test
        @DisplayName("renders the block for the CALLING agent, so a CLI session gets its own view")
        void rendersForTheCallingAgent() {
            when(promptSection.render(anyString(), any())).thenReturn("<recalled-memory>...");

            ToolExecutionResult result = run("list", Map.of("as_index", true), ctxWithCallerAgent());

            assertThat(result.success()).isTrue();
            // The agent id has to travel: this action exists for CLI sessions, which
            // never receive an injected prompt, and rendering with a null scope would
            // hand them the workspace view while hiding their own private entries.
            verify(promptSection).render(ORG, CALLER_AGENT);
        }
    }

    @Nested
    @DisplayName("how a READ reports a bad parameter")
    class ReadRefusals {

        @Test
        @DisplayName("a slug with no letters is a parameter problem, not a transient failure")
        void anUnusableSlugIsAParameterError() {
            when(memoryService.getBySlugAndRecordRecall(anyString(), any(), anyString()))
                .thenThrow(new MemoryService.MemoryValidationException(
                    "'???' contains no letters or digits, so it cannot identify a memory."));

            ToolExecutionResult result = run("get", Map.of("slug", "???"), ctx());

            // The writes were fixed to answer INVALID_PARAMETER_VALUE and the reads
            // were not, so the same class of refusal read as "try again" on one half of
            // the tool and "fix the call" on the other.
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        }
    }

    @Nested
    @DisplayName("what delete reports back")
    class DeleteResult {

        @Test
        @DisplayName("answers with the STORED handle, not the words the caller typed")
        void reportsTheStoredSlug() {
            AgentMemoryEntity target = entity("release-cadence");
            when(memoryService.findBySlugVisibleToAgent(anyString(), any(), anyString()))
                .thenReturn(Optional.of(target));

            ToolExecutionResult result = run("delete", Map.of("slug", "Release Cadence"), ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            // Echoing "Release Cadence" invites the agent to address its next call with
            // a handle that does not exist, since the index shows "release-cadence".
            assertThat(data.get("slug")).isEqualTo("release-cadence");
        }
    }

    @Nested
    @DisplayName("search parameters")
    class SearchParameters {

        @Test
        @DisplayName("forwards the caller's limit instead of always asking for the default")
        void forwardsTheLimit() {
            when(memoryService.search(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(entity("a")));

            run("search", Map.of("query", "cadence", "limit", 25), ctx());

            // A limit the tool accepts and then ignores is worse than not offering one.
            verify(memoryService).search(anyString(), any(), org.mockito.ArgumentMatchers.eq("cadence"),
                org.mockito.ArgumentMatchers.eq(25));
        }

        @Test
        @DisplayName("uses its own default when the caller passes none")
        void defaultsTheLimit() {
            when(memoryService.search(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(entity("a")));

            run("search", Map.of("query", "cadence"), ctx());

            verify(memoryService).search(anyString(), any(), org.mockito.ArgumentMatchers.eq("cadence"),
                org.mockito.ArgumentMatchers.eq(10));
        }
    }

    @Nested
    @DisplayName("list filtering and tag parsing")
    class ListFilteringAndTags {

        @Test
        @DisplayName("narrows the list by a word, matching the slug as well as the prose")
        void queryNarrowsTheList() {
            when(memoryService.listVisible(anyString(), any())).thenReturn(List.of(
                entity("release-cadence"), entity("holiday-policy")));

            ToolExecutionResult result = run("list", Map.of("query", "release"), ctx());

            // Read as ROWS, not as one flattened string: the envelope also carries a
            // page header and its suggested filters, so a slug appearing anywhere in
            // that scaffolding would satisfy a toString() match without a single row
            // having been filtered.
            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            List<?> items = (List<?>) data.get("memories");
            assertThat(items).hasSize(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> only = (Map<String, Object>) items.get(0);
            assertThat(only).containsEntry("slug", "release-cadence");
        }

        @Test
        @DisplayName("narrows the list by a TAG, which is what the help says tags are for")
        void queryMatchesTags() {
            AgentMemoryEntity tagged = entity("release-cadence");
            tagged.setTags(List.of("deploy"));
            AgentMemoryEntity untagged = entity("holiday-policy");
            untagged.setTags(List.of("hr"));
            when(memoryService.listVisible(anyString(), any())).thenReturn(List.of(tagged, untagged));

            ToolExecutionResult result = run("list", Map.of("query", "deploy"), ctx());

            // The help calls tags "for filtering", and until the query matched them
            // that was simply untrue: an agent that dutifully tagged its entries could
            // not then find them by tag, and nothing said so.
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            List<?> items = (List<?>) data.get("memories");
            assertThat(items).hasSize(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> only = (Map<String, Object>) items.get(0);
            assertThat(only).containsEntry("slug", "release-cadence");
        }

        @Test
        @DisplayName("returns the tags on a listed row, so a filter the agent can apply is one it can see")
        void listRowsCarryTags() {
            AgentMemoryEntity tagged = entity("release-cadence");
            tagged.setTags(List.of("deploy", "process"));
            when(memoryService.listVisible(anyString(), any())).thenReturn(List.of(tagged));

            ToolExecutionResult result = run("list", Map.of(), ctx());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            List<?> items = (List<?>) data.get("memories");
            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) items.get(0);
            // Without them the agent cannot tell why a row matched, or which tag to ask
            // for next. They are a handful of short words, so the cost is nil.
            assertThat(first).containsEntry("tags", List.of("deploy", "process"));
        }

        @Test
        @DisplayName("survives an entry with no tags at all, which is most of them")
        void listRowsTolerateAbsentTags() {
            AgentMemoryEntity untagged = entity("release-cadence");
            untagged.setTags(null);
            when(memoryService.listVisible(anyString(), any())).thenReturn(List.of(untagged));

            ToolExecutionResult result = run("list", Map.of("query", "release"), ctx());

            assertThat(result.success()).isTrue();
        }

                @Test
        @DisplayName("drops nulls out of a tag array instead of storing them")
        void tagsSurviveANullEntry() {
            when(memoryService.save(any(), anyString())).thenReturn(created(entity("a-fact")));
            List<String> withNull = new java.util.ArrayList<>();
            withNull.add("deploy");
            withNull.add(null);
            withNull.add("release");
            Map<String, Object> params = new HashMap<>();
            params.put("title", "A fact");
            params.put("summary", "Something worth keeping.");
            params.put("tags", withNull);

            // A model emitting ['a', null, 'b'] is not exotic, and a null reaching the
            // JSONB column is a 500 on a write the caller cannot diagnose.
            assertThat(run("save", params, ctx()).success()).isTrue();
            ArgumentCaptor<MemoryService.SaveRequest> captor =
                ArgumentCaptor.forClass(MemoryService.SaveRequest.class);
            verify(memoryService).save(captor.capture(), anyString());
            assertThat(captor.getValue().tags()).containsExactly("deploy", "release");
        }
    }
}
