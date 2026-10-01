package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.BenefitKind;
import com.apimarketplace.auth.domain.BenefitTrigger;
import com.apimarketplace.auth.domain.CapScope;
import com.apimarketplace.auth.domain.OwnerRewardKind;
import com.apimarketplace.auth.domain.PartnerCommission;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.repository.PartnerCommissionRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Admin side of the partner / influencer program (V549), built on the V366 reward codes.
 *
 * <p>Two kinds of code:
 * <ul>
 *   <li><b>Creator code</b> ({@code PROMO}, no owner): given to a creator so they can try
 *       the product for real. Redeem-time: a timed comp plan (default PRO for 90 days)
 *       plus PAYG credits (default 50,000), single use by default.</li>
 *   <li><b>Partner code</b> ({@code PARTNER}, owned by the creator's account): shared
 *       with their audience as a link. Redeem-time PAYG credits for the new user (default
 *       8,000), and a revenue share for the partner (the Silver rate by default, 30% of every
 *       paid invoice for 12 months, each line held 14 days, raised by the partner's tier: see
 *       {@link PartnerTierService}) recorded by {@link PartnerCommissionService}.</li>
 * </ul>
 * Every default is a property ({@code reward.partner.*}) and every value can be overridden
 * per code at creation time.
 */
@Service
public class PartnerProgramAdminService {

    private static final Logger log = LoggerFactory.getLogger(PartnerProgramAdminService.class);

    /** Stored upper-case; matched case-insensitively (V366 unique index on UPPER(code)). */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{2,31}$");
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int MINT_RETRIES = 5;

    public record Defaults(String creatorPlanCode, int creatorPlanDays, int creatorCredits, int creatorMaxUses,
                           int creatorValidDays, int audienceCredits, int commissionBps, int commissionMonths,
                           int holdDays) {}

    public record CreatorCodeRequest(String code, String label, String planCode, Integer planDays,
                                     Integer credits, Integer maxUses, Integer validDays) {}

    /** {@code maxUses} null = uncapped (every sign-up from the link gets the audience credits). */
    public record PartnerCodeRequest(Long partnerUserId, String code, String label, Integer audienceCredits,
                                     Integer commissionBps, Integer commissionMonths, Integer holdDays,
                                     Integer validDays, Integer maxUses) {}

    /** Structured outcome: {@code code} on success, a stable {@code error} token otherwise. */
    public record Result(RewardCode code, String error) {
        static Result ok(RewardCode c) { return new Result(c, null); }
        static Result fail(String e) { return new Result(null, e); }
        public boolean success() { return error == null; }
    }

    /** Money per currency, minor units. */
    public record Amounts(Map<String, Long> onHold, Map<String, Long> payable, Map<String, Long> paid,
                          Map<String, Long> voided) {}

    /** {@code terms} is the owner's latest acceptance of the Partner Program Terms (V557), null when none. */
    public record CodeReport(RewardCode code, String ownerEmail, long redemptions, long payingCustomers,
                             Amounts commissions, PartnerTermsService.Acceptance terms) {}

    private final RewardCodeRepository codeRepository;
    private final PartnerCommissionRepository commissionRepository;
    private final UserRepository userRepository;
    private final PartnerTermsService termsService;
    private final Defaults defaults;
    private final SecureRandom random = new SecureRandom();

    public PartnerProgramAdminService(RewardCodeRepository codeRepository,
                                      PartnerCommissionRepository commissionRepository,
                                      UserRepository userRepository,
                                      PartnerTermsService termsService,
                                      @Value("${reward.partner.creator.plan-code:PRO}") String creatorPlanCode,
                                      @Value("${reward.partner.creator.plan-days:90}") int creatorPlanDays,
                                      @Value("${reward.partner.creator.credits:50000}") int creatorCredits,
                                      @Value("${reward.partner.creator.max-uses:1}") int creatorMaxUses,
                                      @Value("${reward.partner.creator.valid-days:60}") int creatorValidDays,
                                      @Value("${reward.partner.audience-credits:8000}") int audienceCredits,
                                      @Value("${reward.partner.commission-bps:3000}") int commissionBps,
                                      @Value("${reward.partner.commission-months:12}") int commissionMonths,
                                      @Value("${reward.partner.hold-days:14}") int holdDays) {
        this.codeRepository = codeRepository;
        this.commissionRepository = commissionRepository;
        this.userRepository = userRepository;
        this.termsService = termsService;
        this.defaults = new Defaults(creatorPlanCode, creatorPlanDays, creatorCredits, creatorMaxUses,
                creatorValidDays, audienceCredits, commissionBps, commissionMonths, holdDays);
    }

    public Defaults defaults() {
        return defaults;
    }

    @Transactional
    public Result createCreatorCode(CreatorCodeRequest req) {
        CreatorCodeRequest r = req == null ? new CreatorCodeRequest(null, null, null, null, null, null, null) : req;
        String planCode = r.planCode() == null || r.planCode().isBlank()
                ? defaults.creatorPlanCode() : r.planCode().trim().toUpperCase(Locale.ROOT);
        if (!"NONE".equals(planCode) && !AdminPlanService.ALLOWED_PLAN_CODES.contains(planCode)) {
            return Result.fail("unsupported_plan");
        }
        int planDays = orDefault(r.planDays(), defaults.creatorPlanDays());
        int credits = orDefault(r.credits(), defaults.creatorCredits());
        int maxUses = orDefault(r.maxUses(), defaults.creatorMaxUses());
        int validDays = orDefault(r.validDays(), defaults.creatorValidDays());
        boolean grantsPlan = !"NONE".equals(planCode) && !"FREE".equals(planCode);
        if (credits < 0 || maxUses < 1 || validDays < 0 || (grantsPlan && planDays < 1)) {
            return Result.fail("invalid_values");
        }
        if (!grantsPlan && credits == 0) return Result.fail("empty_benefit");

        RewardCode c = new RewardCode();
        c.setProgram(RewardProgram.PROMO);
        c.setLabel(trimLabel(r.label()));
        c.setBenefitKind(BenefitKind.CREDIT_GRANT);
        c.setBenefitTrigger(BenefitTrigger.REDEEM_TIME);
        c.setBenefitAmount(credits);
        c.setBenefitDurationDays(0);
        c.setBenefitPlanCode(grantsPlan ? planCode : null);
        c.setBenefitPlanDays(grantsPlan ? planDays : 0);
        c.setOwnerRewardKind(OwnerRewardKind.NONE);
        c.setCapScope(CapScope.GLOBAL);
        c.setCapLimit(maxUses);
        applyWindow(c, validDays);
        return save(c, r.code());
    }

    @Transactional
    public Result createPartnerCode(PartnerCodeRequest req) {
        if (req == null || req.partnerUserId() == null) return Result.fail("missing_partner");
        if (userRepository.findById(req.partnerUserId()).isEmpty()) return Result.fail("user_not_found");
        if (codeRepository.findByOwnerUserIdAndProgram(req.partnerUserId(), RewardProgram.PARTNER).isPresent()) {
            return Result.fail("partner_already_has_code");
        }
        int audienceCredits = orDefault(req.audienceCredits(), defaults.audienceCredits());
        int bps = orDefault(req.commissionBps(), defaults.commissionBps());
        int months = orDefault(req.commissionMonths(), defaults.commissionMonths());
        int hold = orDefault(req.holdDays(), defaults.holdDays());
        int validDays = orDefault(req.validDays(), 0);
        if (audienceCredits < 0 || bps < 0 || bps > 10_000 || months < 1 || hold < 0 || validDays < 0
                || (req.maxUses() != null && req.maxUses() < 1)) {
            return Result.fail("invalid_values");
        }

        RewardCode c = new RewardCode();
        c.setProgram(RewardProgram.PARTNER);
        c.setOwnerUserId(req.partnerUserId());
        c.setLabel(trimLabel(req.label()));
        c.setBenefitKind(BenefitKind.CREDIT_GRANT);
        c.setBenefitTrigger(BenefitTrigger.REDEEM_TIME);
        c.setBenefitAmount(audienceCredits);
        c.setBenefitDurationDays(0);
        c.setOwnerRewardKind(OwnerRewardKind.PARTNER_PAYOUT);
        c.setOwnerRewardAmount(0);
        c.setPayoutKind("REVENUE_SHARE");
        c.setPayoutBps(bps);
        c.setPayoutMonths(months);
        c.setHoldDays(hold);
        // An optional hard cap bounds what a scripted sign-up farm on a public link can collect.
        if (req.maxUses() != null) {
            c.setCapScope(CapScope.GLOBAL);
            c.setCapLimit(req.maxUses());
        } else {
            c.setCapScope(CapScope.NONE);
        }
        applyWindow(c, validDays);
        return save(c, req.code());
    }

    /** The owner of a PARTNER code (the account a tier belongs to); empty for any other code. */
    @Transactional(readOnly = true)
    public java.util.Optional<Long> partnerOwnerOf(Long codeId) {
        return codeRepository.findById(codeId)
                .filter(c -> c.getProgram() == RewardProgram.PARTNER && c.getOwnerUserId() != null)
                .map(RewardCode::getOwnerUserId);
    }

    @Transactional
    public boolean setActive(Long codeId, boolean active) {
        return codeRepository.findById(codeId).map(c -> {
            c.setActive(active);
            codeRepository.save(c);
            return true;
        }).orElse(false);
    }

    /**
     * Mark every PAYABLE line of a partner code as PAID (the admin just transferred the
     * money). Lines still in their refund window are left on hold. Returns the lines settled.
     *
     * <p>Refused with {@link PartnerTermsNotAcceptedException}, before any line moves, when the
     * code's owner never accepted the Partner Program Terms (V557): the terms are what make a
     * commission payable, so no money goes to a partner who is not bound by them.
     */
    @Transactional
    public List<PartnerCommission> markPayablePaid(Long codeId, Long adminUserId) {
        Long owner = partnerOwnerOf(codeId).orElse(null);
        List<PartnerCommission> lines = commissionRepository.findByRewardCodeIdIn(List.of(codeId));
        // No owner to check the terms against: nothing with money on it is paid blind.
        boolean bound = owner != null ? termsService.hasAcceptedAny(owner) : lines.isEmpty();
        if (!bound) throw new PartnerTermsNotAcceptedException();
        Instant now = Instant.now();
        List<PartnerCommission> settled = new ArrayList<>();
        for (PartnerCommission c : lines) {
            if (c.isPayableAt(now) && commissionRepository.markPaidIfOnHold(c.getId(), now, adminUserId) == 1) {
                c.setStatus(PartnerCommission.Status.PAID);
                c.setPaidAt(now);
                c.setPaidByUserId(adminUserId);
                settled.add(c);
            }
        }
        log.info("Partner commissions settled: code={} lines={}", codeId, settled.size());
        return settled;
    }

    /** Creator and partner codes, newest first, with their redemption and commission totals. */
    @Transactional(readOnly = true)
    public List<CodeReport> report() {
        List<RewardCode> codes = codeRepository.findPartnerProgramCodes();
        if (codes.isEmpty()) return List.of();
        Map<Long, List<PartnerCommission>> byCode = commissionRepository
                .findByRewardCodeIdIn(codes.stream().map(RewardCode::getId).toList())
                .stream().collect(Collectors.groupingBy(PartnerCommission::getRewardCodeId));
        Map<Long, String> emails = new HashMap<>();
        Map<Long, PartnerTermsService.Acceptance> accepted = termsService.latestFor(codes.stream()
                .map(RewardCode::getOwnerUserId).filter(java.util.Objects::nonNull).distinct().toList());
        Instant now = Instant.now();
        List<CodeReport> out = new ArrayList<>();
        for (RewardCode c : codes) {
            List<PartnerCommission> lines = byCode.getOrDefault(c.getId(), List.of());
            String email = c.getOwnerUserId() == null ? null
                    : emails.computeIfAbsent(c.getOwnerUserId(),
                        id -> userRepository.findById(id).map(u -> u.getEmail()).orElse(null));
            long paying = lines.stream().filter(l -> l.getStatus() != PartnerCommission.Status.VOID)
                    .map(PartnerCommission::getCustomerUserId).distinct().count();
            PartnerTermsService.Acceptance terms = c.getOwnerUserId() == null ? null : accepted.get(c.getOwnerUserId());
            out.add(new CodeReport(c, email, c.getCurrentRedemptions(), paying, totals(lines, now), terms));
        }
        return out;
    }

    static Amounts totals(List<PartnerCommission> lines, Instant now) {
        Map<String, Long> hold = new TreeMap<>();
        Map<String, Long> payable = new TreeMap<>();
        Map<String, Long> paid = new TreeMap<>();
        Map<String, Long> voided = new TreeMap<>();
        for (PartnerCommission l : lines) {
            Map<String, Long> bucket = switch (l.getStatus()) {
                case PAID -> paid;
                case VOID -> voided;
                case HOLD -> l.isPayableAt(now) ? payable : hold;
            };
            bucket.merge(l.getCurrency(), l.getCommissionMinor(), Long::sum);
        }
        return new Amounts(hold, payable, paid, voided);
    }

    private Result save(RewardCode c, String requestedCode) {
        String wanted = requestedCode == null ? "" : requestedCode.trim().toUpperCase(Locale.ROOT);
        if (!wanted.isEmpty()) {
            if (!CODE_PATTERN.matcher(wanted).matches()) return Result.fail("invalid_code_format");
            if (codeRepository.findByCodeIgnoreCase(wanted).isPresent()) return Result.fail("code_taken");
            c.setCode(wanted);
            // A concurrent create of the same code loses on the unique index: the exception
            // leaves this (rolled-back) transaction and the controller answers 409. Catching it
            // here would return inside a doomed transaction and fail the commit instead.
            return Result.ok(codeRepository.saveAndFlush(c));
        }
        for (int attempt = 0; attempt < MINT_RETRIES; attempt++) {
            String candidate = generateCode();
            if (codeRepository.findByCodeIgnoreCase(candidate).isPresent()) continue;
            c.setCode(candidate);
            return Result.ok(codeRepository.saveAndFlush(c));
        }
        return Result.fail("code_generation_failed");
    }

    private void applyWindow(RewardCode c, int validDays) {
        Instant now = Instant.now();
        c.setActive(true);
        c.setValidFrom(now);
        c.setValidUntil(validDays > 0 ? now.plus(validDays, ChronoUnit.DAYS) : null);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder("LC-");
        for (int i = 0; i < 8; i++) sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        return sb.toString();
    }

    private static int orDefault(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private static String trimLabel(String label) {
        if (label == null || label.isBlank()) return null;
        String t = label.trim();
        return t.length() > 128 ? t.substring(0, 128) : t;
    }
}
