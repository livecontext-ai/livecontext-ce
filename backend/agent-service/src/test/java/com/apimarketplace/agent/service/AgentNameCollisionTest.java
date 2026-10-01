package com.apimarketplace.agent.service;

import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent names against the V269 partial unique index
 * {@code agent.agents (organization_id, name) WHERE is_active}.
 *
 * <p>Before this fix only createAgent checked the index: cloning the same agent twice
 * produced a second "X (Copy)" and died on the index, and a rename to a taken name did
 * the same. The allocator is the one primitive every path now goes through. The workspace
 * lock and the real index are exercised against Postgres by AgentNameAllocationPostgresTest;
 * here the repository is mocked, so this class pins the decisions, not the SQL.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent name collisions (V269 index)")
class AgentNameCollisionTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "11111111-1111-4111-8111-111111111111";

    @Mock private AgentRepository agentRepository;
    @Mock private AgentDefaultsConfig defaults;
    @Mock private OrgAccessGuard orgAccessService;
    @Mock private com.apimarketplace.agent.repository.AgentMetricsAggregationRepository metricsAggregationRepository;

    @InjectMocks
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        lenient().when(orgAccessService.canWrite(any(), any(), any(), any(), any())).thenReturn(true);
        lenient().when(defaults.getTemperature()).thenReturn(0.7);
        lenient().when(defaults.getMaxTokens()).thenReturn(4096);
        lenient().when(defaults.getMaxIterations()).thenReturn(25);
        lenient().when(defaults.getExecutionTimeout()).thenReturn(600);
        lenient().when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * Makes each of {@code names} an ACTIVE agent of ORG_ID, answering both the exact-name finder
     * and the prefix query the way Postgres would (the LIKE pattern is unescaped and matched as a
     * literal prefix).
     */
    private void activeInOrg(String... names) {
        Set<String> taken = Set.of(names);
        lenient().when(agentRepository.findByOrganizationIdStrictAndNameAndIsActiveTrue(eq(ORG_ID), anyString()))
                .thenAnswer(inv -> taken.contains((String) inv.getArgument(1))
                        ? Optional.of(agent(UUID.randomUUID(), inv.getArgument(1)))
                        : Optional.empty());
        lenient().when(agentRepository.findActiveNamesByOrganizationIdStrictAndNamePrefix(eq(ORG_ID), anyString()))
                .thenAnswer(inv -> {
                    String pattern = inv.getArgument(1);
                    assertThat(pattern).endsWith("%");
                    String prefix = unescapeLike(pattern.substring(0, pattern.length() - 1));
                    return taken.stream().filter(n -> n.startsWith(prefix)).toList();
                });
    }

    private static String unescapeLike(String s) {
        return s.replace("\\%", "%").replace("\\_", "_").replace("\\\\", "\\");
    }

    private static AgentEntity agent(UUID id, String name) {
        AgentEntity e = new AgentEntity();
        e.setId(id);
        e.setTenantId(TENANT_ID);
        e.setOrganizationId(ORG_ID);
        e.setName(name);
        e.setIsActive(true);
        e.setTemperature(BigDecimal.valueOf(0.5));
        e.setMaxTokens(2048);
        e.setMaxIterations(10);
        e.setExecutionTimeout(300);
        return e;
    }

    // =====================================================================
    // The allocator
    // =====================================================================

    @Nested
    @DisplayName("allocateAgentName")
    class Allocator {

        @Test
        @DisplayName("a free name is returned unchanged")
        void freeNameUnchanged() {
            activeInOrg();
            assertThat(agentService.allocateAgentName(ORG_ID, "Nova")).isEqualTo("Nova");
        }

        @Test
        @DisplayName("skips every taken numbered name: Nova and Nova (2) taken -> Nova (3), in ONE query")
        void skipsTakenNumberedNamesInOneQuery() {
            activeInOrg("Nova", "Nova (2)");

            assertThat(agentService.allocateAgentName(ORG_ID, "Nova")).isEqualTo("Nova (3)");
            verify(agentRepository, times(1)).findActiveNamesByOrganizationIdStrictAndNamePrefix(ORG_ID, "Nova%");
            verify(agentRepository, never()).findByOrganizationIdStrictAndNameAndIsActiveTrue(any(), any());
        }

        @Test
        @DisplayName("a name already numbered continues its count: 'Nova (2)' taken -> 'Nova (3)', never 'Nova (2) (2)'")
        void numberedNameContinuesItsCount() {
            activeInOrg("Nova", "Nova (2)");
            assertThat(agentService.allocateAgentName(ORG_ID, "Nova (2)")).isEqualTo("Nova (3)");
        }

        @Test
        @DisplayName("a free numbered name is kept as typed, and '(1)' or '(0)' are not treated as a count")
        void freeNumberedNameKept() {
            activeInOrg("Nova (1)");
            assertThat(agentService.allocateAgentName(ORG_ID, "Nova (5)")).isEqualTo("Nova (5)");
            assertThat(agentService.allocateAgentName(ORG_ID, "Nova (1)")).isEqualTo("Nova (1) (2)");
        }

        @Test
        @DisplayName("is case-sensitive like the index: 'nova' taken does not rename 'Nova'")
        void caseSensitiveLikeTheIndex() {
            activeInOrg("nova");
            assertThat(agentService.allocateAgentName(ORG_ID, "Nova")).isEqualTo("Nova");
        }

        @Test
        @DisplayName("LIKE wildcards in the name are escaped: '50%_off' is matched literally")
        void likeWildcardsEscaped() {
            activeInOrg("50%_off");

            assertThat(agentService.allocateAgentName(ORG_ID, "50%_off")).isEqualTo("50%_off (2)");
            verify(agentRepository).findActiveNamesByOrganizationIdStrictAndNamePrefix(ORG_ID, "50\\%\\_off%");
        }

        @Test
        @DisplayName("no organization given: the request-bound one is used, as OrgScopedEntityListener does at persist")
        void requestBoundOrgIsTheScope() {
            activeInOrg("Nova");
            AtomicReference<String> allocated = new AtomicReference<>();

            TenantResolver.runWithOrgScope(ORG_ID, () -> allocated.set(agentService.allocateAgentName(null, "Nova")));

            assertThat(allocated.get()).isEqualTo("Nova (2)");
        }

        @Test
        @DisplayName("no organization at all: nothing is queried (the persist listener refuses such a row loudly)")
        void noOrgAtAllQueriesNothing() {
            assertThat(agentService.allocateAgentName(null, "Nova")).isEqualTo("Nova");
            verify(agentRepository, never()).findActiveNamesByOrganizationIdStrictAndNamePrefix(any(), any());
        }

        @Test
        @DisplayName("a 255-character name keeps its ' (2)' suffix: the STEM is cut, the column bound holds")
        void longNameCutsTheStemNotTheSuffix() {
            String longName = "a".repeat(255);
            activeInOrg(longName);

            String allocated = agentService.allocateAgentName(ORG_ID, longName);

            assertThat(allocated).hasSize(AgentService.AGENT_NAME_MAX_LENGTH).endsWith("a (2)");
        }

        @Test
        @DisplayName("the stem cut never splits a surrogate pair")
        void cutNeverSplitsSurrogatePair() {
            // 250 ASCII chars then an emoji (2 UTF-16 units) straddling the cut at 251.
            String stem = "b".repeat(250) + "😀" + "tail";
            String fitted = AgentService.fitAgentName(stem, " (2)");

            assertThat(fitted).isEqualTo("b".repeat(250) + " (2)");
        }

        @Test
        @DisplayName("null or blank is handed back untouched (the caller's validation reports it)")
        void nullOrBlankUntouched() {
            assertThat(agentService.allocateAgentName(ORG_ID, null)).isNull();
            assertThat(agentService.allocateAgentName(ORG_ID, " ")).isEqualTo(" ");
            verify(agentRepository, never()).findActiveNamesByOrganizationIdStrictAndNamePrefix(any(), any());
        }
    }

    // =====================================================================
    // Clone (name NOT typed by the caller: never fails)
    // =====================================================================

    @Nested
    @DisplayName("cloneAgent")
    class Clone {

        @Test
        @DisplayName("regression: cloning the same agent twice gives the second clone a free name instead of hitting the index")
        void secondCloneGetsAFreeName() {
            UUID sourceId = UUID.randomUUID();
            when(agentRepository.findById(sourceId)).thenReturn(Optional.of(agent(sourceId, "My Agent")));
            // The first clone already exists in the caller's workspace.
            activeInOrg("My Agent", "My Agent (Copy)");

            AgentEntity clone = agentService.cloneAgent(sourceId, TENANT_ID, null, ORG_ID);

            assertThat(clone.getName()).isEqualTo("My Agent (Copy) (2)");
            assertThat(clone.getOrganizationId()).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("first clone keeps the historical 'X (Copy)' name")
        void firstCloneKeepsCopySuffix() {
            UUID sourceId = UUID.randomUUID();
            when(agentRepository.findById(sourceId)).thenReturn(Optional.of(agent(sourceId, "My Agent")));
            activeInOrg("My Agent");

            assertThat(agentService.cloneAgent(sourceId, TENANT_ID, null, ORG_ID).getName())
                    .isEqualTo("My Agent (Copy)");
        }

        @Test
        @DisplayName("a 255-character source cloned twice keeps BOTH ' (Copy)' and ' (2)': the stem is fitted against the whole suffix")
        void longSourceKeepsCopyAndNumber() {
            UUID sourceId = UUID.randomUUID();
            String source = "z".repeat(255);
            when(agentRepository.findById(sourceId)).thenReturn(Optional.of(agent(sourceId, source)));
            activeInOrg(source, AgentService.fitAgentName(source, " (Copy)"));

            String name = agentService.cloneAgent(sourceId, TENANT_ID, null, ORG_ID).getName();

            assertThat(name).hasSize(255).endsWith("z (Copy) (2)");
        }
    }

    // =====================================================================
    // Explicit create (name typed: refused, with the free name)
    // =====================================================================

    @Nested
    @DisplayName("createAgent")
    class Create {

        private AgentEntity create(String name) {
            return agentService.createAgent(
                    TENANT_ID, name, null, null, null, null,
                    null, null, null, null,
                    null, null, null, null, null,
                    null, null, null, ORG_ID,
                    null, null);
        }

        @Test
        @DisplayName("a taken name is refused with the existing id and suggestedName, and nothing is saved")
        void duplicateRefusedWithSuggestion() {
            UUID existingId = UUID.randomUUID();
            activeInOrg("Nova");
            when(agentRepository.findByOrganizationIdStrictAndNameAndIsActiveTrue(ORG_ID, "Nova"))
                    .thenReturn(Optional.of(agent(existingId, "Nova")));

            assertThatThrownBy(() -> create("Nova"))
                    .isInstanceOfSatisfying(AgentNameConflictException.class, e -> {
                        assertThat(e.getName()).isEqualTo("Nova");
                        assertThat(e.getExistingAgentId()).isEqualTo(existingId);
                        assertThat(e.getSuggestedName()).isEqualTo("Nova (2)");
                        // Still an IllegalArgumentException for every older catch site.
                        assertThat(e).isInstanceOf(IllegalArgumentException.class);
                        assertThat(e.getMessage()).contains("already exists")
                                .contains("agent(action='update'").contains(existingId.toString())
                                .contains("'Nova (2)'");
                    });
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a free name is created as typed (no silent renaming) and stamped with the checked workspace")
        void freeNameCreatedAsTyped() {
            activeInOrg("Other");

            AgentEntity created = create("Nova");

            assertThat(created.getName()).isEqualTo("Nova");
            assertThat(created.getOrganizationId()).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("the quota refusal comes first: a create over the plan limit never takes the name lock or reads names")
        void quotaCheckedBeforeTheNameCheck() {
            var guard = org.mockito.Mockito.mock(com.apimarketplace.auth.client.entitlement.EntitlementGuard.class);
            org.springframework.test.util.ReflectionTestUtils.setField(agentService, "entitlementGuard", guard);
            org.mockito.Mockito.doThrow(new IllegalStateException("limit")).when(guard).check(any(), any(), any());

            assertThatThrownBy(() -> create("Nova")).hasMessage("limit");
            verify(agentRepository, never()).findByOrganizationIdStrictAndNameAndIsActiveTrue(any(), any());
        }
    }

    // =====================================================================
    // Rename / re-activation (name typed: refused, with the free name)
    // =====================================================================

    @Nested
    @DisplayName("updateAgent")
    class Rename {

        private AgentEntity update(UUID id, String name, Boolean isActive) {
            return agentService.updateAgent(
                    id, TENANT_ID, name,
                    null, null, null, null,
                    null, null, null, null,
                    null, null, null, null, null, null, null, isActive,
                    null, null, null, ORG_ID);
        }

        @Test
        @DisplayName("regression: renaming onto another active agent's name is refused with a suggestion, not left to the index")
        void renameOntoTakenNameRefused() {
            UUID id = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            when(agentRepository.findById(id)).thenReturn(Optional.of(agent(id, "Scout")));
            activeInOrg("Scout", "Nova");
            when(agentRepository.findByOrganizationIdStrictAndNameAndIsActiveTrue(ORG_ID, "Nova"))
                    .thenReturn(Optional.of(agent(otherId, "Nova")));

            assertThatThrownBy(() -> update(id, "Nova", null))
                    .isInstanceOfSatisfying(AgentNameConflictException.class, e -> {
                        assertThat(e.getExistingAgentId()).isEqualTo(otherId);
                        assertThat(e.getSuggestedName()).isEqualTo("Nova (2)");
                        // Rename wording: the other agent is not the one being edited, so no
                        // "update that agent instead", only the free name.
                        assertThat(e.isRename()).isTrue();
                        assertThat(e.getMessage()).contains("Agent " + otherId + " already uses the name 'Nova'")
                                .contains("Retry with name='Nova (2)'").doesNotContain("agent(action='update'");
                    });
            verify(agentRepository, never()).save(any());
        }

        @Test
        @DisplayName("the rename suggestion does not count the agent's own current name as taken")
        void renameSuggestionMayKeepOwnName() {
            UUID id = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            // "Nova (2)" tries to become "Nova", held by another agent: "Nova (2)" is its own name.
            when(agentRepository.findById(id)).thenReturn(Optional.of(agent(id, "Nova (2)")));
            activeInOrg("Nova", "Nova (2)");
            when(agentRepository.findByOrganizationIdStrictAndNameAndIsActiveTrue(ORG_ID, "Nova"))
                    .thenReturn(Optional.of(agent(otherId, "Nova")));

            assertThatThrownBy(() -> update(id, "Nova", null))
                    .isInstanceOfSatisfying(AgentNameConflictException.class,
                            e -> assertThat(e.getSuggestedName()).isEqualTo("Nova (2)"));
        }

        @Test
        @DisplayName("an update that keeps the name does not even look the name up (no-op for the common case)")
        void sameNameSkipsTheCheck() {
            UUID id = UUID.randomUUID();
            when(agentRepository.findById(id)).thenReturn(Optional.of(agent(id, "Scout")));

            assertThat(update(id, "Scout", null).getName()).isEqualTo("Scout");
            verify(agentRepository, never()).findByOrganizationIdStrictAndNameAndIsActiveTrue(any(), any());
        }

        @Test
        @DisplayName("an agent that stays inactive may take any name (outside the index)")
        void inactiveRenameNotChecked() {
            UUID id = UUID.randomUUID();
            AgentEntity paused = agent(id, "Scout");
            paused.setIsActive(false);
            when(agentRepository.findById(id)).thenReturn(Optional.of(paused));

            assertThat(update(id, "Nova", null).getName()).isEqualTo("Nova");
            verify(agentRepository, never()).findByOrganizationIdStrictAndNameAndIsActiveTrue(any(), any());
        }

        @Test
        @DisplayName("re-activating an agent whose name was taken meanwhile is refused (it would re-enter the index)")
        void reactivationOntoTakenNameRefused() {
            UUID id = UUID.randomUUID();
            AgentEntity paused = agent(id, "Nova");
            paused.setIsActive(false);
            when(agentRepository.findById(id)).thenReturn(Optional.of(paused));
            activeInOrg("Nova");

            assertThatThrownBy(() -> update(id, "Nova", Boolean.TRUE))
                    .isInstanceOf(AgentNameConflictException.class);
        }
    }

    // =====================================================================
    // Insert-time race: the index itself refuses
    // =====================================================================

    @Nested
    @DisplayName("fromIndexViolation")
    class IndexViolation {

        /** What the Postgres driver raises for a 23505, built from the wire fields it parses. */
        private PSQLException psql(String constraint, String detail) {
            String wire = "SERROR\0VERROR\0C23505\0Mduplicate key value violates unique constraint \""
                    + constraint + "\"\0D" + detail + "\0n" + constraint + "\0";
            return new PSQLException(new ServerErrorMessage(wire));
        }

        private DataIntegrityViolationException dive(SQLException cause, String constraint) {
            var hibernate = new org.hibernate.exception.ConstraintViolationException(
                    "could not execute statement", cause, constraint);
            return new DataIntegrityViolationException("could not execute statement", hibernate);
        }

        @Test
        @DisplayName("the V269 violation becomes the same conflict, read from the STRUCTURED error, suggestion from the violated key")
        void structuredViolationBecomesConflict() {
            var e = dive(psql("uq_agents_org_name_active",
                    "Key (organization_id, name)=(" + ORG_ID + ", Nova (2)) already exists."),
                    "uq_agents_org_name_active");

            var conflict = AgentNameConflictException.fromIndexViolation(e, (org, name) -> {
                assertThat(org).isEqualTo(ORG_ID);
                assertThat(name).isEqualTo("Nova (2)");
                return "Nova (3)";
            });

            assertThat(conflict).isPresent();
            assertThat(conflict.get().getName()).isEqualTo("Nova (2)");
            assertThat(conflict.get().getSuggestedName()).isEqualTo("Nova (3)");
            assertThat(conflict.get().getExistingAgentId()).isNull();
        }

        @Test
        @DisplayName("a server speaking another language is still understood: only the untranslated key part is read")
        void localizedDetailStillParsed() {
            var e = dive(psql("uq_agents_org_name_active",
                    "La clé « (organization_id, name)=(" + ORG_ID + ", Nova) » existe déjà."),
                    "uq_agents_org_name_active");

            var conflict = AgentNameConflictException.fromIndexViolation(e, (org, name) -> name + " (2)");

            assertThat(conflict).isPresent();
            assertThat(conflict.get().getSuggestedName()).isEqualTo("Nova (2)");
        }

        @Test
        @DisplayName("another constraint is not claimed, even when its text mentions the index name")
        void otherConstraintNotClaimed() {
            var e = dive(psql("agents_pkey", "mentions uq_agents_org_name_active in passing"), "agents_pkey");
            assertThat(AgentNameConflictException.fromIndexViolation(e, (o, n) -> "x")).isEmpty();
        }

        @Test
        @DisplayName("no structured cause (a wrapper dropped it): falls back to the text and still answers")
        void textFallback() {
            var e = new DataIntegrityViolationException("ERROR: duplicate key value violates unique constraint "
                    + "\"uq_agents_org_name_active\"\n  Detail: Key (organization_id, name)=(" + ORG_ID
                    + ", Nova) already exists.");
            var conflict = AgentNameConflictException.fromIndexViolation(e, (o, n) -> n + " (2)");
            assertThat(conflict).isPresent();
            assertThat(conflict.get().getName()).isEqualTo("Nova");
            assertThat(conflict.get().getSuggestedName()).isEqualTo("Nova (2)");
        }

        @Test
        @DisplayName("a failing suggester still yields the conflict, without a suggestion")
        void failingSuggesterStillConflict() {
            var e = dive(psql("uq_agents_org_name_active",
                    "Key (organization_id, name)=(" + ORG_ID + ", Nova) already exists."),
                    "uq_agents_org_name_active");
            var conflict = AgentNameConflictException.fromIndexViolation(e, (o, n) -> {
                throw new IllegalStateException("db down");
            });
            assertThat(conflict).isPresent();
            assertThat(conflict.get().getSuggestedName()).isNull();
            assertThat(conflict.get().getMessage()).contains("already exists").contains("different name");
        }

        @Test
        @DisplayName("a lost race (no existing id) tells the agent how to find the existing agent, no placeholder")
        void lostRaceMessagePointsToList() {
            var conflict = new AgentNameConflictException("Nova", null, "Nova (2)");
            assertThat(conflict.getMessage()).contains("agent(action='list')").contains("'Nova (2)'")
                    .doesNotContain("<").doesNotContain("agent_id=''");
        }

        @Test
        @DisplayName("an unreadable key still yields the conflict, name unknown, no suggester call")
        void unreadableKey() {
            var e = new DataIntegrityViolationException("violates \"uq_agents_org_name_active\"");
            var conflict = AgentNameConflictException.fromIndexViolation(e, (o, n) -> {
                throw new AssertionError("must not be called without a key");
            });
            assertThat(conflict).isPresent();
            assertThat(conflict.get().getName()).isNull();
        }
    }
}
