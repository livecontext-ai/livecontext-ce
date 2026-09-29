package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.RewardRedemption;
import com.apimarketplace.auth.domain.RewardStatus;
import com.apimarketplace.auth.service.RewardService;
import com.apimarketplace.auth.service.RewardService.RedeemResult;
import com.apimarketplace.auth.service.RewardService.RedeemStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The status-to-HTTP mapping of POST /api/billing/redeem. The redeem logic lives
 * in RewardService (tested separately); here we pin that each RedeemStatus maps to
 * the right HTTP status + typed body code, and that a concurrent double-redeem
 * (DataIntegrityViolationException) becomes 409 ALREADY_REDEEMED.
 */
@DisplayName("BillingController - reward redeem mapping")
class BillingControllerRewardRedeemTest {

    private RewardService rewardService;
    private BillingController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        rewardService = mock(RewardService.class);
        controller = new BillingController();
        ReflectionTestUtils.setField(controller, "rewardService", rewardService);
        request = mock(HttpServletRequest.class);
        lenient().when(request.getHeader("X-User-ID")).thenReturn("7");
    }

    private ResponseEntity<Map<String, Object>> redeemReturning(RedeemStatus status, RewardRedemption r) {
        when(rewardService.redeem(7L, "CODE")).thenReturn(new RedeemResult(status, r));
        return controller.redeemRewardCode(Map.of("code", "CODE"), request);
    }

    private RewardRedemption granted() {
        RewardRedemption r = new RewardRedemption();
        r.setStatus(RewardStatus.GRANTED);
        r.setBenefitType("WORKFLOW_NODE_FREE");
        r.setBenefitUntil(Instant.now());
        r.setFreeCreditsCap(20000);
        return r;
    }

    private RewardRedemption withStatus(RewardStatus s) {
        RewardRedemption r = new RewardRedemption();
        r.setStatus(s);
        return r;
    }

    @Test
    @DisplayName("SUCCESS -> 200 REDEEMED")
    void success() {
        var resp = redeemReturning(RedeemStatus.SUCCESS, granted());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("code")).isEqualTo("REDEEMED");
        assertThat(resp.getBody().get("success")).isEqualTo(true);
    }

    @Test
    @DisplayName("V549: SUCCESS carries what a creator code gave (credits, plan, plan end) for the UI to say it")
    void successCarriesGrantedCreditsAndPlan() {
        RewardRedemption r = withStatus(RewardStatus.GRANTED);
        java.time.LocalDateTime endsAt = java.time.LocalDateTime.of(2026, 12, 27, 10, 0);
        when(rewardService.redeem(7L, "CODE"))
                .thenReturn(new RedeemResult(RedeemStatus.SUCCESS, r, 50000, "PRO", endsAt));

        var resp = controller.redeemRewardCode(Map.of("code", "CODE"), request);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("grantedCredits")).isEqualTo(50000);
        assertThat(resp.getBody().get("grantedPlan")).isEqualTo("PRO");
        // An instant with its offset: the browser shows the right day in any zone.
        assertThat(java.time.Instant.parse((String) resp.getBody().get("planEndsAt")))
                .isEqualTo(endsAt.atZone(java.time.ZoneId.systemDefault()).toInstant());
    }

    @Test
    @DisplayName("V549: ALREADY_ATTRIBUTED -> 409 with its own typed code (the UI names the reason)")
    void alreadyAttributed() {
        var resp = redeemReturning(RedeemStatus.ALREADY_ATTRIBUTED, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("code")).isEqualTo("ALREADY_ATTRIBUTED");
    }

    @Test
    @DisplayName("PENDING_CONVERSION -> 202")
    void pending() {
        var resp = redeemReturning(RedeemStatus.PENDING_CONVERSION, withStatus(RewardStatus.PENDING));
        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        assertThat(resp.getBody().get("code")).isEqualTo("PENDING_CONVERSION");
    }

    @Test
    @DisplayName("TRACK_ONLY -> 202")
    void trackOnly() {
        var resp = redeemReturning(RedeemStatus.TRACK_ONLY, withStatus(RewardStatus.TRACK_ONLY));
        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        assertThat(resp.getBody().get("code")).isEqualTo("TRACK_ONLY");
    }

    @Test
    @DisplayName("UNKNOWN_CODE -> 404 INVALID_CODE")
    void unknown() {
        var resp = redeemReturning(RedeemStatus.UNKNOWN_CODE, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody().get("code")).isEqualTo("INVALID_CODE");
        assertThat(resp.getBody().get("success")).isEqualTo(false);
    }

    @Test
    @DisplayName("NOT_REDEEMABLE / EXHAUSTED -> 409 with matching code")
    void conflict409() {
        var nr = redeemReturning(RedeemStatus.NOT_REDEEMABLE, null);
        assertThat(nr.getStatusCode().value()).isEqualTo(409);
        assertThat(nr.getBody().get("code")).isEqualTo("NOT_REDEEMABLE");

        var ex = redeemReturning(RedeemStatus.EXHAUSTED, null);
        assertThat(ex.getStatusCode().value()).isEqualTo(409);
        assertThat(ex.getBody().get("code")).isEqualTo("EXHAUSTED");
    }

    @Test
    @DisplayName("SELF_REFERRAL -> 409 SELF_REFERRAL")
    void selfReferral() {
        var resp = redeemReturning(RedeemStatus.SELF_REFERRAL, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("code")).isEqualTo("SELF_REFERRAL");
    }

    @Test
    @DisplayName("ALREADY_PAID -> 409 ALREADY_PAID")
    void alreadyPaid() {
        var resp = redeemReturning(RedeemStatus.ALREADY_PAID, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("code")).isEqualTo("ALREADY_PAID");
    }

    @Test
    @DisplayName("a concurrent double-redeem (the per-user-per-code unique constraint) -> 409 ALREADY_REDEEMED")
    void duplicateRace() {
        when(rewardService.redeem(7L, "CODE")).thenThrow(new DataIntegrityViolationException("insert",
                new RuntimeException("duplicate key value violates unique constraint \"uq_reward_redemption_user_code\"")));
        var resp = controller.redeemRewardCode(Map.of("code", "CODE"), request);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("code")).isEqualTo("ALREADY_REDEEMED");
    }

    @Test
    @DisplayName("missing X-User-ID -> 401")
    void noUser() {
        when(request.getHeader("X-User-ID")).thenReturn(null);
        var resp = controller.redeemRewardCode(Map.of("code", "CODE"), request);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("V549: EMAIL_NOT_VERIFIED -> 403 (not final: the auto-redeem keeps the code and retries)")
    void emailNotVerifiedIs403() {
        var resp = redeemReturning(RedeemStatus.EMAIL_NOT_VERIFIED, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(resp.getBody().get("code")).isEqualTo("EMAIL_NOT_VERIFIED");
    }

    @Test
    @DisplayName("V549: NOT_NEW_ACCOUNT and NOTHING_TO_GRANT -> 409 with their own codes")
    void newAccountAndNothingToGrant() {
        assertThat(redeemReturning(RedeemStatus.NOT_NEW_ACCOUNT, null).getBody().get("code")).isEqualTo("NOT_NEW_ACCOUNT");
        assertThat(redeemReturning(RedeemStatus.NOTHING_TO_GRANT, null).getBody().get("code")).isEqualTo("NOTHING_TO_GRANT");
    }

    @Test
    @DisplayName("V549: an unexpected constraint (rolled back, nothing consumed) is 503 REDEEM_RETRY, never 'already redeemed'")
    void unexpectedConstraintIsRetryable() {
        when(rewardService.redeem(7L, "CODE")).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "insert", new RuntimeException("duplicate key value violates unique constraint \"uq_subscription_one_active\"")));

        var resp = controller.redeemRewardCode(Map.of("code", "CODE"), request);

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertThat(resp.getBody().get("code")).isEqualTo("REDEEM_RETRY");
    }

    @Test
    @DisplayName("V549: two partner codes redeemed at once lose on the one-partner index -> ALREADY_ATTRIBUTED, not ALREADY_REDEEMED")
    void partnerIndexRaceMapsToAlreadyAttributed() {
        when(rewardService.redeem(7L, "CODE")).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "insert", new RuntimeException("duplicate key value violates unique constraint \"uq_reward_redemption_partner_redeemer\"")));

        var resp = controller.redeemRewardCode(Map.of("code", "CODE"), request);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("code")).isEqualTo("ALREADY_ATTRIBUTED");
    }
}
