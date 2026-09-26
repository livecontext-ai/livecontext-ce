package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.service.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Usage page's "by model" breakdown and its previous-period comparison, on real Postgres.
 *
 * <p>The unit test pins which windows the service asks for; only a real database can prove the
 * JPQL groups the rows it should, applies the nullable filters, excludes what is not a spend, and
 * puts a row in exactly one of the two windows.
 */
@SpringBootTest
@DisplayName("Usage analytics by model - real JPQL on real Postgres")
class ModelUsageAnalyticsPersistenceTest extends AuthPostgresIntegrationTest {

    @Autowired private CreditService creditService;
    @Autowired private CreditLedgerRepository ledgerRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final Long USER = 8101L;
    private static final String ORG = "00000000-0000-4000-8000-0000000000c1";

    private void seed(String sourceId, String sourceType, String provider, String model,
                      String amount, Integer prompt, Integer completion, int daysAgo) {
        CreditLedgerEntry e = new CreditLedgerEntry();
        e.setUserId(USER);
        e.setExecutorUserId(USER);
        e.setOrganizationId(ORG);
        e.setAmount(new BigDecimal(amount));
        e.setBalanceAfter(BigDecimal.ZERO);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setProvider(provider);
        e.setModel(model);
        e.setPromptTokens(prompt);
        e.setCompletionTokens(completion);
        ledgerRepository.save(e);
        // @PrePersist stamps now(); back-date the row to place it in a window.
        jdbcTemplate.update("UPDATE auth.credit_ledger SET created_at = now() - make_interval(days => ?) WHERE source_id = ?",
                daysAgo, sourceId);
    }

    @BeforeEach
    void seedLedger() {
        ledgerRepository.deleteAll();
        // Current 7-day window.
        seed("m-1", "AGENT_EXECUTION", "anthropic", "claude-sonnet-5", "-10.0000", 100, 50, 1);
        seed("m-2", "AGENT_EXECUTION", "anthropic", "claude-sonnet-5", "-5.0000", 40, 10, 2);
        seed("m-3", "CHAT_CONVERSATION", "openai", "gpt-5", "-2.0000", 20, 5, 3);
        seed("m-4", "PLATFORM_MARKUP", null, null, "-1.0000", null, null, 1);
        // Not spend: a grant and a reservation never count.
        seed("m-5", "GRANT", null, null, "50.0000", null, null, 1);
        seed("m-6", "PLATFORM_MARKUP_RESERVE", null, null, "-9.0000", null, null, 1);
        // Previous 7-day window only.
        seed("p-1", "AGENT_EXECUTION", "anthropic", "claude-sonnet-5", "-3.0000", 10, 10, 10);
        // Older than both windows.
        seed("o-1", "AGENT_EXECUTION", "anthropic", "claude-sonnet-5", "-99.0000", 1, 1, 30);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    private static Map<String, Object> find(List<Map<String, Object>> rows, String model, String sourceType) {
        return rows.stream()
                .filter(r -> java.util.Objects.equals(r.get("model"), model) && sourceType.equals(r.get("sourceType")))
                .findFirst().orElseThrow(() -> new AssertionError("no row for " + model + "/" + sourceType + " in " + rows));
    }

    @Test
    @DisplayName("groups the current window by provider, model and type, summing spend, calls and tokens")
    void groupsTheCurrentWindow() {
        Map<String, Object> result = creditService.getUsageAnalytics(USER, 7, null, null, null, ORG);
        List<Map<String, Object>> current = rows(result, "modelUsage");

        assertThat(current).hasSize(3);
        Map<String, Object> sonnet = find(current, "claude-sonnet-5", "AGENT_EXECUTION");
        assertThat(sonnet).containsEntry("provider", "anthropic");
        assertThat(((Number) sonnet.get("count")).longValue()).isEqualTo(2);
        assertThat(new BigDecimal(sonnet.get("credits").toString())).isEqualByComparingTo("15");
        assertThat(((Number) sonnet.get("tokens")).longValue()).isEqualTo(200);
        // A platform call has no model: it is still a spend, grouped under null.
        Map<String, Object> platform = find(current, null, "PLATFORM_MARKUP");
        assertThat(new BigDecimal(platform.get("credits").toString())).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("the previous window holds only the rows of the period just before, none of the current one")
    void previousWindowIsDisjoint() {
        List<Map<String, Object>> previous = rows(
                creditService.getUsageAnalytics(USER, 7, null, null, null, ORG), "previousModelUsage");

        assertThat(previous).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("model", "claude-sonnet-5");
            assertThat(((Number) r.get("count")).longValue()).isEqualTo(1);
            assertThat(new BigDecimal(r.get("credits").toString())).isEqualByComparingTo("3");
        });
    }

    @Test
    @DisplayName("the workspace filter keeps another workspace's spend out of the breakdown")
    void workspaceFilterApplies() {
        CreditLedgerEntry other = new CreditLedgerEntry();
        other.setUserId(USER);
        other.setExecutorUserId(USER);
        other.setOrganizationId("00000000-0000-4000-8000-0000000000c2");
        other.setAmount(new BigDecimal("-50.0000"));
        other.setBalanceAfter(BigDecimal.ZERO);
        other.setSourceType("AGENT_EXECUTION");
        other.setSourceId("other-org-1");
        other.setProvider("openai");
        other.setModel("gpt-5");
        ledgerRepository.save(other);

        List<Map<String, Object>> current = rows(
                creditService.getUsageAnalytics(USER, 7, null, null, null, ORG), "modelUsage");

        // Still the 3 rows of ORG, and gpt-5 at its ORG spend only (2), not 52.
        assertThat(current).hasSize(3);
        assertThat(new BigDecimal(find(current, "gpt-5", "CHAT_CONVERSATION").get("credits").toString()))
                .isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("the member query returns only the executor's own rows in the payer's wallet")
    void memberQueryIsTheIntersection() {
        // A colleague's call in the same wallet and workspace: a member must not see it.
        CreditLedgerEntry colleague = new CreditLedgerEntry();
        colleague.setUserId(USER);
        colleague.setExecutorUserId(9999L);
        colleague.setOrganizationId(ORG);
        colleague.setAmount(new BigDecimal("-7.0000"));
        colleague.setBalanceAfter(BigDecimal.ZERO);
        colleague.setSourceType("AGENT_EXECUTION");
        colleague.setSourceId("colleague-1");
        colleague.setProvider("anthropic");
        colleague.setModel("claude-sonnet-5");
        ledgerRepository.save(colleague);

        java.time.LocalDateTime now = java.time.LocalDateTime.now().plusMinutes(1);
        List<Object[]> colleagueRows = ledgerRepository.getModelUsageForPayerAndExecutor(
                USER, 9999L, now.minusDays(7), now, null, null, null, ORG);
        List<Object[]> ownRows = ledgerRepository.getModelUsageForPayerAndExecutor(
                USER, USER, now.minusDays(7), now, null, null, null, ORG);

        assertThat(colleagueRows).singleElement().satisfies(r ->
                assertThat(new BigDecimal(r[4].toString())).isEqualByComparingTo("7"));
        // The executor's own sonnet rows (10 + 5), without the colleague's 7.
        Object[] ownSonnet = ownRows.stream().filter(r -> "claude-sonnet-5".equals(r[1])).findFirst().orElseThrow();
        assertThat(new BigDecimal(ownSonnet[4].toString())).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("a row exactly on the boundary between the two windows is counted once, in the later one")
    void boundaryRowCountedOnce() {
        java.time.LocalDateTime boundary = java.time.LocalDateTime.now().minusDays(7).withNano(0);
        seed("b-1", "CHAT_CONVERSATION", "openai", "gpt-5", "-4.0000", 1, 1, 0);
        jdbcTemplate.update("UPDATE auth.credit_ledger SET created_at = ? WHERE source_id = 'b-1'", boundary);

        List<Object[]> later = ledgerRepository.getModelUsage(USER, boundary, boundary.plusDays(7), null, null, "gpt-5", ORG);
        List<Object[]> earlier = ledgerRepository.getModelUsage(USER, boundary.minusDays(7), boundary, null, null, "gpt-5", ORG);

        assertThat(later.stream().mapToLong(r -> ((Number) r[3]).longValue()).sum()).isEqualTo(2); // m-3 + b-1
        assertThat(earlier).isEmpty();
    }

    @Test
    @DisplayName("the chart filters apply to the model breakdown too")
    void filtersApply() {
        List<Map<String, Object>> current = rows(
                creditService.getUsageAnalytics(USER, 7, null, "openai", null, ORG), "modelUsage");

        assertThat(current).singleElement().satisfies(r -> assertThat(r).containsEntry("model", "gpt-5"));
    }
}
