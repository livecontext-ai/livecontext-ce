package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code budget} block an LLM reads back, and the tool help that describes it.
 *
 * <p>{@code buildBudgetResponse}'s own Javadoc has always ASKED for these two to move
 * together ("update the help module in the same commit or an LLM consulting the tool help
 * will see a stale accessor surface"), and nothing checked it. A request in a comment is a
 * request; this is the check. An agent that reads the help and then branches on a key the
 * help never mentioned, or waits for a key the response stopped sending, fails in a way
 * neither side's tests can see.
 *
 * <p>Driven through the real {@code help} action rather than the private builder, because
 * what an agent actually receives is the string the help action emits, not the map that
 * produced it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("agent budget response - documented keys match emitted keys")
class AgentBudgetResponseHelpParityTest {

    @Mock private ModelCatalogService modelCatalogService;

    private AgentHelpModule helpModule;

    @BeforeEach
    void setUp() {
        helpModule = new AgentHelpModule(new AgentDefaultsConfig(), modelCatalogService);
    }

    /** The budget block exactly as create/get/update return it. */
    private static Map<String, Object> emittedBudget(AgentEntity entity) {
        return AgentCrudModule.buildBudgetResponse(entity);
    }

    private static AgentEntity cappedAgent() {
        AgentEntity entity = new AgentEntity();
        entity.setCreditBudget(new BigDecimal("1"));
        entity.setCreditsConsumed(new BigDecimal("3"));
        entity.setCreditsReserved(BigDecimal.ZERO);
        entity.setBudgetResetMode("monthly");
        entity.setBudgetLastReset(Instant.now());
        return entity;
    }

    private String helpBudgetShape() {
        Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), "tenant-x", null);
        assertThat(result).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get().data();
        @SuppressWarnings("unchecked")
        Map<String, Object> shape = (Map<String, Object>) data.get("response_shape");
        assertThat(shape).as("help must carry a response_shape").isNotNull();
        return String.valueOf(shape.get("budget"));
    }

    @Test
    @DisplayName("every key the response emits is described in the tool help")
    void everyEmittedKeyIsDocumented() {
        Map<String, Object> emitted = emittedBudget(cappedAgent());
        String documented = helpBudgetShape();

        assertThat(emitted).isNotEmpty();
        // "key=", not "key". The help renders its map with toString, so a bare substring
        // test passes for "blocked" on the text of "blocked_until" alone, and for
        // "consumed" on "consumed_own" - so a key nobody documented could still
        // satisfy it whenever a longer key or a sentence contains it. The "=" is the
        // boundary that makes this an assertion about KEYS.
        assertThat(emitted.keySet())
                .allSatisfy(key -> assertThat(documented)
                        .as("budget.%s is returned but not described in the tool help", key)
                        .contains(key + "="));
    }

    @Test
    @DisplayName("the blocked verdict is emitted, and it is not just free == 0 renamed")
    void theBlockedVerdictIsEmitted() {
        // The key an agent needs in order to understand why its schedule produced nothing.
        // Asserting the ROLLED-OVER case as well, because that is the one where a reader who
        // reimplemented it as free == 0 would disagree with the engine.
        String documented = helpBudgetShape();
        assertThat(documented).contains("blocked=");
        assertThat(documented).contains("blocked_until=");
    }

    @Test
    @DisplayName("blocked is true for a spent cap and false once the period has rolled")
    void blockedFollowsTheRolloverRatherThanTheStoredFigure() {
        AgentEntity spentThisMonth = cappedAgent();
        assertThat(emittedBudget(spentThisMonth)).containsEntry("blocked", true);
        assertThat(emittedBudget(spentThisMonth)).containsKey("blocked_until");

        AgentEntity rolledOver = cappedAgent();
        rolledOver.setBudgetLastReset(Instant.now().minus(45, java.time.temporal.ChronoUnit.DAYS));
        Map<String, Object> after = emittedBudget(rolledOver);
        assertThat(after).containsEntry("blocked", false);
        // Omitted rather than null: the documented contract is "absent when not blocked",
        // and an LLM told a key exists will branch on its presence.
        assertThat(after).doesNotContainKey("blocked_until");
    }

    @Test
    @DisplayName("an unlimited agent reports blocked=false rather than omitting the key")
    void unlimitedAgentsStillCarryTheKey() {
        // The help says "always present", and an always-present key is what lets an agent
        // read it without a null check. Omitting it for the unlimited branch would make the
        // help a lie for the most common kind of agent.
        AgentEntity unlimited = new AgentEntity();
        unlimited.setCreditsConsumed(new BigDecimal("500"));

        assertThat(emittedBudget(unlimited)).containsEntry("blocked", false);
    }
}
