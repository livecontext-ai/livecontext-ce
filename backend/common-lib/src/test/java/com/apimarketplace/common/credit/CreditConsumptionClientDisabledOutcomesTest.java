package com.apimarketplace.common.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this client answers when the deployment does not meter at all.
 *
 * <p>Every method short-circuits before any HTTP when {@code credit.consumption.enabled} is false,
 * and each short-circuit has to pick a word. The commit's word matters more than the others,
 * because a caller now READS it: the catalog decides from that outcome whether an amount may be
 * reported as charged, and a generated asset is stamped with the price it cost. "COMMITTED" would
 * mean a self-hosted install with metering off started printing prices nobody paid - a claim about
 * money, produced by a service that was working exactly as configured.
 */
@DisplayName("CreditConsumptionClient with metering switched off")
class CreditConsumptionClientDisabledOutcomesTest {

    private static final CreditConsumptionClient DISABLED =
            new CreditConsumptionClient("http://auth.invalid", false);

    @Test
    @DisplayName("does not call a commit COMMITTED, because nothing was committed")
    void commitSaysBillingDisabled() {
        String outcome = DISABLED.scopeCommit("platform-markup:RUN:r1:step:s1:call-1",
                new BigDecimal("78"), "Google Gemini", "gemini-2.5-flash-image");

        assertThat(outcome).isEqualTo(CreditConsumptionClient.BILLING_DISABLED);
        // The property the readers depend on, stated as they read it: not any success word.
        assertThat(outcome).isNotEqualTo("COMMITTED").isNotEqualTo("ALREADY_COMMITTED");
    }

    @Test
    @DisplayName("still allows the call, so a deployment without a ledger is not a deployment "
            + "without a catalogue")
    void reserveStillAllows() {
        // The reserve's answer is read for one thing only - may this call go out - and with no
        // ledger the answer is yes. Changing THAT to a refusal would take out every tool call on
        // every install that does not meter.
        CreditConsumptionClient.ScopeReserveResult reserve = DISABLED.scopeReserve(
                1L, "platform-markup:RUN:r1:step:s1:call-1", "Google Gemini",
                "gemini-2.5-flash-image", new BigDecimal("78"), null, 10, "RUN", "r1", false);

        assertThat(reserve.success()).isTrue();
    }

    @Test
    @DisplayName("reports a release as released, which is what a release means with no ledger")
    void releaseSaysReleased() {
        // Nothing is held, so nothing is owed back: unlike the commit, the success word here
        // states no amount and no caller turns it into a number on screen.
        assertThat(DISABLED.scopeRelease("platform-markup:RUN:r1:step:s1:call-1", "any"))
                .isEqualTo("RELEASED");
    }
}
