package com.apimarketplace.auth.service;

import com.apimarketplace.auth.billing.CreditTierConstants;
import com.apimarketplace.auth.domain.PersonalOfferMatrix;
import com.apimarketplace.auth.domain.PersonalOfferPolicy;
import com.apimarketplace.auth.repository.PersonalOfferMatrixRepository;
import com.apimarketplace.auth.repository.PersonalOfferPolicyRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** Edits drafts and publishes immutable offer terms using the existing admin billing surface. */
@Service
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class PersonalOfferAdminService {
    public static final String CAMPAIGN = "free-credit-upgrade";
    private final PersonalOfferPolicyRepository policies;
    private final PersonalOfferMatrixRepository matrix;
    private final JdbcTemplate jdbc;

    public PersonalOfferAdminService(PersonalOfferPolicyRepository policies, PersonalOfferMatrixRepository matrix,
                                    JdbcTemplate jdbc) {
        this.policies = policies;
        this.matrix = matrix;
        this.jdbc = jdbc;
    }

    public record Cell(String planCode, Integer monthlyCredits, Integer bonusCredits) {}
    public record PolicyInput(String campaignKey, String label, Integer waitHours, Integer validityHours,
                              Integer checkoutHoldMinutes, Boolean reminderEnabled, Integer reminderHours,
                              Integer paygCreditsPerUsd, Boolean allowConversionStack, List<Cell> matrix) {}
    public record PolicyView(Long id, String campaignKey, int version, String state, String label, int waitHours,
                             int validityHours, int checkoutHoldMinutes, boolean reminderEnabled, int reminderHours,
                             int paygCreditsPerUsd, boolean allowConversionStack, List<Cell> matrix) {}
    public record CodeView(long id, Long recipientUserId, long policyVersionId, Instant issuedAt, Instant expiresAt,
                           boolean active, String status, Integer bonusCredits) {}

    @Transactional(readOnly = true)
    public List<PolicyView> list() {
        return policies.findByCampaignKeyOrderByVersionDesc(CAMPAIGN).stream().map(this::view).toList();
    }

    @Transactional
    public PolicyView create(PolicyInput input) {
        validate(input);
        List<PersonalOfferPolicy> versions = policies.lockCampaign(CAMPAIGN);
        if (versions.isEmpty()) throw invalid("OFFER_CAMPAIGN_UNAVAILABLE");
        PersonalOfferPolicy policy = new PersonalOfferPolicy();
        policy.setCampaignKey(CAMPAIGN);
        policy.setVersion(versions.stream().mapToInt(PersonalOfferPolicy::getVersion).max().orElseThrow() + 1);
        policy.setState("DRAFT");
        apply(policy, input);
        policies.saveAndFlush(policy);
        writeMatrix(policy.getId(), input.matrix());
        return view(policy);
    }

    @Transactional
    public PolicyView update(Long id, PolicyInput input) {
        validate(input);
        PersonalOfferPolicy policy = locked(id);
        if (!"DRAFT".equals(policy.getState())) throw invalid("OFFER_POLICY_IMMUTABLE");
        apply(policy, input);
        matrix.deleteByPolicyId(id);
        matrix.flush();
        writeMatrix(id, input.matrix());
        return view(policies.save(policy));
    }

    @Transactional
    public PolicyView activate(Long id) {
        List<PersonalOfferPolicy> versions = policies.lockCampaign(CAMPAIGN);
        PersonalOfferPolicy selected = find(versions, id);
        if ("ACTIVE".equals(selected.getState())) return view(selected);
        if (matrix.findByPolicyId(id).stream().noneMatch(cell -> cell.getBonusCredits() > 0))
            throw invalid("OFFER_POLICY_NO_BONUS");
        // Flush the previous ACTIVE state before publishing, respecting the partial unique index.
        for (PersonalOfferPolicy other : versions) {
            if ("ACTIVE".equals(other.getState())) other.setState("PAUSED");
        }
        policies.saveAllAndFlush(versions);
        selected.setState("ACTIVE");
        selected.setUpdatedAt(Instant.now());
        return view(policies.saveAndFlush(selected));
    }

    @Transactional
    public PolicyView pause(Long id) {
        PersonalOfferPolicy policy = locked(id);
        if ("DRAFT".equals(policy.getState())) throw invalid("OFFER_POLICY_NOT_PUBLISHED");
        policy.setState("PAUSED");
        policy.setUpdatedAt(Instant.now());
        // No reward-code mutation: previously promised terms survive the campaign pause.
        return view(policies.save(policy));
    }

    @Transactional(readOnly = true)
    public List<CodeView> codes() {
        return jdbc.query("""
                SELECT c.id,c.recipient_user_id,c.policy_version_id,c.issued_at,c.valid_until,c.active,
                       CASE WHEN r.status='RELEASED' THEN 'GRANTED' WHEN r.status='CLAWED_BACK' THEN 'CLAWED_BACK'
                            WHEN r.status IS NOT NULL THEN 'PROCESSING'
                            WHEN a.status='REVIEW_REQUIRED' THEN 'REVIEW_REQUIRED'
                            WHEN a.status='NO_BONUS' OR (a.status='PAID' AND a.bonus_credits=0) THEN 'NO_BONUS'
                            WHEN a.status='PAID' THEN 'PROCESSING'
                            WHEN a.status='COMPLETED' THEN 'PENDING_PAYMENT'
                            WHEN f.status='PAID' THEN 'ALREADY_USED'
                            WHEN NOT c.active THEN 'DISABLED'
                            WHEN a.status='OPEN' AND a.session_expires_at > now() THEN 'CHECKOUT_OPEN'
                            WHEN c.valid_until <= now() THEN 'EXPIRED' ELSE 'AVAILABLE' END AS offer_status,
                       COALESCE(r.redeemer_reward_amount,a.bonus_credits) AS bonus_credits
                FROM auth.reward_code c LEFT JOIN auth.reward_redemption r ON r.reward_code_id=c.id
                LEFT JOIN auth.personal_offer_first_paid_purchase f ON f.user_id=c.recipient_user_id
                LEFT JOIN LATERAL (SELECT status,bonus_credits,session_expires_at
                  FROM auth.personal_offer_checkout_attempt WHERE reward_code_id=c.id
                  ORDER BY created_at DESC LIMIT 1) a ON TRUE
                WHERE c.program='PERSONAL_UPGRADE' ORDER BY c.id DESC LIMIT 100
                """, (rs, row) -> new CodeView(rs.getLong("id"), rs.getObject("recipient_user_id", Long.class),
                rs.getLong("policy_version_id"), rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("valid_until").toInstant(), rs.getBoolean("active"), rs.getString("offer_status"),
                rs.getObject("bonus_credits", Integer.class)));
    }

    @Transactional
    public void disableCode(Long id) {
        if (jdbc.update("UPDATE auth.reward_code SET active=FALSE WHERE id=? AND program='PERSONAL_UPGRADE'", id) == 0)
            throw invalid("OFFER_UNAVAILABLE");
    }

    private PersonalOfferPolicy locked(Long id) {
        return find(policies.lockCampaign(CAMPAIGN), id);
    }

    private PersonalOfferPolicy find(List<PersonalOfferPolicy> versions, Long id) {
        return versions.stream().filter(policy -> Objects.equals(policy.getId(), id)).findFirst()
                .orElseThrow(() -> invalid("OFFER_POLICY_NOT_FOUND"));
    }

    private PolicyView view(PersonalOfferPolicy policy) {
        List<Cell> cells = matrix.findByPolicyId(policy.getId()).stream()
                .sorted(Comparator.comparing(PersonalOfferMatrix::getMonthlyCredits).thenComparing(PersonalOfferMatrix::getPlanCode))
                .map(row -> new Cell(row.getPlanCode(), row.getMonthlyCredits(), row.getBonusCredits())).toList();
        return new PolicyView(policy.getId(), policy.getCampaignKey(), policy.getVersion(), policy.getState(),
                policy.getLabel(), policy.getWaitHours(), policy.getValidityHours(), policy.getCheckoutHoldMinutes(),
                policy.isReminderEnabled(), policy.getReminderHours(), policy.getPaygCreditsPerUsd(),
                policy.isAllowConversionStack(), cells);
    }

    private void apply(PersonalOfferPolicy policy, PolicyInput input) {
        policy.setLabel(input.label().trim());
        policy.setWaitHours(input.waitHours());
        policy.setValidityHours(input.validityHours());
        policy.setCheckoutHoldMinutes(input.checkoutHoldMinutes());
        policy.setReminderEnabled(Boolean.TRUE.equals(input.reminderEnabled()));
        policy.setReminderHours(input.reminderHours());
        policy.setPaygCreditsPerUsd(input.paygCreditsPerUsd());
        policy.setAllowConversionStack(false);
        policy.setUpdatedAt(Instant.now());
    }

    private void writeMatrix(Long policyId, List<Cell> cells) {
        matrix.saveAll(cells.stream().map(cell -> {
            PersonalOfferMatrix row = new PersonalOfferMatrix();
            row.setPolicyId(policyId);
            row.setPlanCode(cell.planCode());
            row.setMonthlyCredits(cell.monthlyCredits());
            row.setBonusCredits(cell.bonusCredits());
            return row;
        }).toList());
        matrix.flush();
    }

    private void validate(PolicyInput input) {
        if (input == null || !CAMPAIGN.equals(input.campaignKey()) || input.label() == null
                || input.label().isBlank() || input.label().trim().length() > 128)
            throw invalid("OFFER_POLICY_INVALID");
        if (input.waitHours() == null || input.waitHours() < 0 || input.waitHours() > 8760
                || input.validityHours() == null || input.validityHours() < 1 || input.validityHours() > 8760
                || input.checkoutHoldMinutes() == null || input.checkoutHoldMinutes() < 30 || input.checkoutHoldMinutes() > 1440
                || input.reminderHours() == null || input.reminderHours() < 1 || input.reminderHours() >= input.validityHours()
                || input.paygCreditsPerUsd() == null || input.paygCreditsPerUsd() < 1
                || Boolean.TRUE.equals(input.allowConversionStack())) throw invalid("OFFER_POLICY_INVALID");
        if (input.matrix() == null || input.matrix().isEmpty() || input.matrix().size() > 30)
            throw invalid("OFFER_MATRIX_INVALID");
        Set<String> seen = new HashSet<>();
        for (Cell cell : input.matrix()) {
            if (cell == null || cell.planCode() == null || !Set.of("STARTER", "PRO", "TEAM").contains(cell.planCode())
                    || cell.monthlyCredits() == null || cell.bonusCredits() == null || cell.bonusCredits() < 0)
                throw invalid("OFFER_MATRIX_INVALID");
            int tier = -1;
            for (int index = 0; index < CreditTierConstants.CREDIT_TIERS.length; index++)
                if (CreditTierConstants.CREDIT_TIERS[index] == cell.monthlyCredits()) tier = index;
            try { CreditTierConstants.validateTierForPlan(tier, cell.planCode()); }
            catch (IllegalArgumentException invalidTier) { throw invalid("OFFER_MATRIX_INVALID"); }
            if (!seen.add(cell.planCode() + ":" + cell.monthlyCredits())) throw invalid("OFFER_MATRIX_INVALID");
        }
    }

    public static class InvalidPolicy extends RuntimeException {
        public InvalidPolicy(String code) { super(code); }
    }

    private InvalidPolicy invalid(String code) { return new InvalidPolicy(code); }
}
