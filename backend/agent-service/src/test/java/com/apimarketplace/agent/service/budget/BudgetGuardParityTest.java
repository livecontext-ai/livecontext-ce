package com.apimarketplace.agent.service.budget;

import com.apimarketplace.agent.loop.GuardResult;
import com.apimarketplace.agent.loop.IterationContext;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cross-language parity test for the V162 budget guard formula.
 *
 * <p>Reads {@code shared/contracts/budget-guard-fixtures.json} - the same fixture
 * consumed by the JS twin {@code mcp/bridge/lib/__tests__/budgetGuards.parity.test.mjs}.
 * Runs the {@link TenantBudgetGuard} against each fixture case and asserts the expected
 * {@code proceed} / {@code reason_contains} outcome.</p>
 *
 * <p>Pattern mirrors {@code shared/contracts/agent-stop-reason.json}: when the formula
 * changes, update the fixture once and both runners (Java + JS) re-validate. Prevents
 * silent drift between the two budget-guard implementations - the original failure mode
 * that drove the -11305 incident (JS missing the projection that Java had).</p>
 */
@DisplayName("BudgetGuard parity (cross-language fixture)")
class BudgetGuardParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<FixtureCase> cases() throws IOException {
        Path fixturePath = locateFixture();
        JsonNode root = MAPPER.readTree(Files.newBufferedReader(fixturePath));
        JsonNode cases = root.get("cases");
        List<FixtureCase> out = new ArrayList<>();
        for (JsonNode c : cases) {
            out.add(FixtureCase.from(c));
        }
        return out.stream();
    }

    /**
     * Resolve the fixture path. The test is invoked from the agent-service module's
     * working directory; the fixture lives in the repo-root's {@code shared/contracts/}.
     * Walk up until we find it (tolerates being run from different module roots).
     */
    private static Path locateFixture() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path here = cwd;
        for (int i = 0; i < 6; i++) {
            Path candidate = here.resolve("shared/contracts/budget-guard-fixtures.json");
            if (Files.exists(candidate)) return candidate;
            Path parent = here.getParent();
            if (parent == null) break;
            here = parent;
        }
        throw new IllegalStateException(
            "budget-guard-fixtures.json not found from " + cwd
            + " - expected at <repo-root>/shared/contracts/budget-guard-fixtures.json");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void runFixtureCase(FixtureCase fc) {
        // Build the calculator with the fixture's rates.
        ModelCostCalculator calc = new ModelCostCalculator(
            fc.inputRate, fc.outputRate, fc.fixedCost,
            fc.contextWindow, fc.maxOutputTokens);

        // Mock the credit client to return the fixture balance.
        CreditConsumptionClient creditClient = mock(CreditConsumptionClient.class);
        when(creditClient.fetchBalance("tenant-1")).thenReturn(fc.balance);

        TenantBudgetGuard guard = new TenantBudgetGuard(creditClient, calc, fc.requireCtxWindow);

        // Build IterationContext from fixture inputs.
        IterationContext ctx = new IterationContext(
            "tenant-1", "agent-1", fc.provider, fc.model,
            fc.iterations + 1,
            fc.iterations,
            fc.promptTokens,
            fc.completionTokens,
            fc.lastPromptTokens,
            fc.lastCompletionTokens,
            100L);

        GuardResult result = guard.check(ctx);

        assertThat(result.proceed())
            .as("case=%s expected proceed=%s but got proceed=%s (reason=%s)",
                fc.name, fc.expectedProceed, result.proceed(), result.denialReason())
            .isEqualTo(fc.expectedProceed);

        if (fc.expectedReasonContains != null) {
            assertThat(result.denialReason())
                .as("case=%s reason should contain '%s' but was '%s'",
                    fc.name, fc.expectedReasonContains, result.denialReason())
                .containsIgnoringCase(fc.expectedReasonContains);
        }
    }

    /** Holder mapping a JSON case to typed fields. {@code null} for absent JSON nulls. */
    static final class FixtureCase {
        final String name;
        final BigDecimal balance;
        final long promptTokens;
        final long completionTokens;
        final int iterations;
        final long lastPromptTokens;
        final long lastCompletionTokens;
        final String provider;
        final String model;
        final BigDecimal inputRate;
        final BigDecimal outputRate;
        final BigDecimal fixedCost;
        final Integer contextWindow;
        final Integer maxOutputTokens;
        final boolean requireCtxWindow;
        final boolean expectedProceed;
        final String expectedReasonContains;

        private FixtureCase(String name, JsonNode in, JsonNode expected) {
            this.name = name;
            this.balance = new BigDecimal(in.get("balance").asText());
            this.promptTokens = in.get("promptTokens").asLong();
            this.completionTokens = in.get("completionTokens").asLong();
            this.iterations = in.get("iterations").asInt();
            this.lastPromptTokens = in.get("lastPromptTokens").asLong();
            this.lastCompletionTokens = in.get("lastCompletionTokens").asLong();
            this.provider = in.get("provider").asText();
            this.model = in.get("model").asText();
            this.inputRate = new BigDecimal(in.get("inputRate").asText());
            this.outputRate = new BigDecimal(in.get("outputRate").asText());
            this.fixedCost = new BigDecimal(in.get("fixedCost").asText());
            this.contextWindow = in.get("contextWindow").isNull() ? null : in.get("contextWindow").asInt();
            this.maxOutputTokens = in.get("maxOutputTokens").isNull() ? null : in.get("maxOutputTokens").asInt();
            this.requireCtxWindow = in.has("requireCtxWindow") && in.get("requireCtxWindow").asBoolean();
            this.expectedProceed = expected.get("proceed").asBoolean();
            this.expectedReasonContains = expected.has("reason_contains")
                ? expected.get("reason_contains").asText()
                : null;
        }

        static FixtureCase from(JsonNode caseNode) {
            return new FixtureCase(
                caseNode.get("name").asText(),
                caseNode.get("input"),
                caseNode.get("expected"));
        }

        @Override public String toString() { return name; }
    }
}
