package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PartnerApplication;
import com.apimarketplace.auth.domain.PartnerCommission;
import com.apimarketplace.auth.domain.PartnerTermsAcceptance;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.repository.PartnerApplicationRepository;
import com.apimarketplace.auth.repository.PartnerCommissionRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PartnerProgramAdminService.Amounts;
import com.apimarketplace.auth.service.PartnerProgramAdminService.Defaults;
import com.apimarketplace.auth.service.PartnerProgramAdminService.PartnerCodeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Partner-facing side of the partner program (V549 codes, V553 applications): the public
 * program terms, a partner's own dashboard, and the application flow an admin decides on.
 *
 * <p>Everything money-related is delegated to what V549 already built: approving an
 * application creates the PARTNER code through {@link PartnerProgramAdminService#createPartnerCode},
 * and the dashboard totals use the same bucketing as the admin report
 * ({@link PartnerProgramAdminService#totals}), so a partner and the admin always read the
 * same numbers.
 */
@Service
public class PartnerProgramService {

    private static final Logger log = LoggerFactory.getLogger(PartnerProgramService.class);

    /** How many of the partner's latest commission lines the dashboard lists. */
    static final int DASHBOARD_LINES = 50;
    /** How many calendar months of earnings the dashboard charts, the current one included. */
    static final int DASHBOARD_MONTHS = 12;
    static final int MAX_COMPANY = 120;
    static final int MAX_WEBSITE = 255;
    static final int MAX_AUDIENCE = 500;
    static final int MAX_MESSAGE = 2000;
    static final int MAX_NOTE = 500;
    private static final Pattern WEBSITE = Pattern.compile("^https?://[^\\s/$.?#][^\\s]*$", Pattern.CASE_INSENSITIVE);

    /**
     * The public terms, in the shape the /partners page and the dashboard render.
     * {@code commissionPercent} is the entry (Silver) rate a new partner starts on; {@code tiers}
     * lists every tier with its rate and threshold (V556).
     */
    public record Terms(double commissionPercent, int commissionMonths, int holdDays, int audienceCredits,
                        List<PartnerTierService.TierTerm> tiers, String tierCurrency, int tierSettleDays,
                        Instant founderUntil, boolean founderOpen) {}

    /** {@code termsVersion}: the version of the Partner Program Terms the applicant ticked (V557). */
    public record ApplicationForm(String companyName, String website, String audience, String message,
                                  String termsVersion) {}

    /** Structured outcome: {@code application} on success, a stable {@code error} token otherwise. */
    public record Outcome(PartnerApplication application, String error) {
        static Outcome ok(PartnerApplication a) { return new Outcome(a, null); }
        static Outcome fail(String e) { return new Outcome(null, e); }
        public boolean success() { return error == null; }
    }

    /** One commission line as the partner sees it: no customer identity, only money and state. */
    public record Line(Instant invoicePaidAt, String currency, long baseAmountMinor, long commissionMinor,
                       String status, Instant dueAt, Instant paidAt) {}

    /**
     * What the partner earned in one calendar month (UTC, {@code yyyy-MM}), per currency: the
     * commissions of the invoices paid that month, voided ones excluded, whatever their payout
     * state. A month with nothing earned has an empty map.
     */
    public record Month(String month, Map<String, Long> commissions) {}

    /**
     * What the partner dashboard shows. {@code state} is one of {@code none} (never applied),
     * {@code pending}, {@code rejected}, {@code active} (live code) or {@code inactive} (code
     * disabled or expired). The code block is present only once a code exists, and with it the
     * partner's tier ({@code standing}) and the rate their next commission earns
     * ({@code commissionPercent}: the higher of the code's rate and the tier's). {@code agreement}
     * is where the user stands with the Partner Program Terms (V557), whatever the state.
     */
    public record Dashboard(String state, Terms terms, PartnerApplication application, RewardCode code,
                            long redemptions, long payingCustomers, Amounts commissions, List<Line> lines,
                            PartnerTierService.Standing standing, Double commissionPercent,
                            PartnerTermsService.Status agreement, List<Month> months) {}

    private final PartnerProgramAdminService adminService;
    private final PartnerApplicationRepository applicationRepository;
    private final RewardCodeRepository codeRepository;
    private final PartnerCommissionRepository commissionRepository;
    private final UserRepository userRepository;
    private final PartnerTierService tierService;
    private final PartnerTermsService termsService;

    public PartnerProgramService(PartnerProgramAdminService adminService,
                                 PartnerApplicationRepository applicationRepository,
                                 RewardCodeRepository codeRepository,
                                 PartnerCommissionRepository commissionRepository,
                                 UserRepository userRepository,
                                 PartnerTierService tierService,
                                 PartnerTermsService termsService) {
        this.adminService = adminService;
        this.applicationRepository = applicationRepository;
        this.codeRepository = codeRepository;
        this.commissionRepository = commissionRepository;
        this.userRepository = userRepository;
        this.tierService = tierService;
        this.termsService = termsService;
    }

    /** What a partner link offers a visitor: the code, and the credits a new account gets with it. */
    public record CodeOffer(String code, int credits) {}

    /**
     * The offer behind a partner code, for the page a partner's link opens: only for a PARTNER
     * code that can be redeemed right now, and never who owns it. Empty for anything else
     * (unknown, a creator or referral code, disabled, expired, used up), so the page promises
     * nothing the code would not give.
     */
    @Transactional(readOnly = true)
    public Optional<CodeOffer> codeOffer(String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty() || code.length() > 64) return Optional.empty();
        Instant now = Instant.now();
        return codeRepository.findByCodeIgnoreCase(code)
                .filter(c -> c.isLivePartnerCode(now))
                .map(c -> new CodeOffer(c.getCode(), c.getBenefitAmount()));
    }

    /** The terms every new partner code starts from (the {@code reward.partner.*} defaults) and the tiers above it. */
    public Terms terms() {
        Defaults d = adminService.defaults();
        return new Terms(d.commissionBps() / 100.0, d.commissionMonths(), d.holdDays(), d.audienceCredits(),
                tierService.tiers(), tierService.currency(), tierService.settleDays(), tierService.founderUntil(),
                tierService.founderOpen());
    }

    /** The rate a partner code earns right now: its own rate or its owner's tier rate, whichever is higher. */
    @Transactional(readOnly = true)
    public double effectiveCommissionPercent(RewardCode code) {
        if (code == null) return 0;
        return tierService.effectiveRateBps(code.getPayoutBps(), tierService.tierOf(code.getOwnerUserId())) / 100.0;
    }

    /**
     * Not read-only: looking at the dashboard brings the partner tier up to date
     * ({@link PartnerTierService#refresh}), so a threshold crossed since the last paid invoice
     * shows at once. That write only ever raises the tier.
     */
    @Transactional
    public Dashboard dashboard(Long userId) {
        Terms terms = terms();
        Optional<RewardCode> code = codeRepository.findByOwnerUserIdAndProgram(userId, RewardProgram.PARTNER);
        PartnerApplication application = applicationRepository
                .findFirstByUserIdOrderByCreatedAtDescIdDesc(userId).orElse(null);
        if (code.isEmpty()) {
            String state = application == null ? "none"
                    : application.getStatus() == PartnerApplication.Status.PENDING ? "pending"
                    // An approved application whose code was since deleted reads as "none":
                    // the partner can apply again rather than being stuck on a dead state.
                    : application.getStatus() == PartnerApplication.Status.REJECTED ? "rejected" : "none";
            return new Dashboard(state, terms, application, null, 0, 0, null, List.of(), null, null,
                    termsService.status(userId), List.of());
        }
        RewardCode c = code.get();
        Instant now = Instant.now();
        List<PartnerCommission> all = commissionRepository.findByRewardCodeIdIn(List.of(c.getId()));
        long paying = all.stream().filter(l -> l.getStatus() != PartnerCommission.Status.VOID)
                .map(PartnerCommission::getCustomerUserId).distinct().count();
        List<Line> lines = all.stream()
                .sorted(Comparator.comparing(PartnerCommission::getInvoicePaidAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(DASHBOARD_LINES)
                .map(l -> new Line(l.getInvoicePaidAt(), l.getCurrency(), l.getBaseAmountMinor(),
                        l.getCommissionMinor(), lineStatus(l, now), l.getDueAt(), l.getPaidAt()))
                .toList();
        String state = c.isRedeemableAt(now) ? "active" : "inactive";
        PartnerTierService.Standing standing = tierService.refresh(userId);
        return new Dashboard(state, terms, application, c, c.getCurrentRedemptions(), paying,
                PartnerProgramAdminService.totals(all, now), lines, standing,
                tierService.effectiveRateBps(c.getPayoutBps(), standing.tier()) / 100.0,
                termsService.status(userId), months(all, now));
    }

    /**
     * The last {@link #DASHBOARD_MONTHS} calendar months, oldest first, each with what its paid
     * invoices earned. Built from every line of the code (the line list is capped at the latest
     * 50), so a busy partner's chart is not cut to the last few weeks.
     */
    static List<Month> months(List<PartnerCommission> all, Instant now) {
        YearMonth current = YearMonth.from(now.atZone(ZoneOffset.UTC));
        YearMonth first = current.minusMonths(DASHBOARD_MONTHS - 1L);
        Map<YearMonth, Map<String, Long>> byMonth = new HashMap<>();
        for (PartnerCommission l : all) {
            if (l.getStatus() == PartnerCommission.Status.VOID || l.getInvoicePaidAt() == null) continue;
            YearMonth m = YearMonth.from(l.getInvoicePaidAt().atZone(ZoneOffset.UTC));
            if (m.isBefore(first) || m.isAfter(current)) continue;
            byMonth.computeIfAbsent(m, k -> new HashMap<>()).merge(l.getCurrency(), l.getCommissionMinor(), Long::sum);
        }
        List<Month> out = new ArrayList<>(DASHBOARD_MONTHS);
        for (YearMonth m = first; !m.isAfter(current); m = m.plusMonths(1)) {
            out.add(new Month(m.toString(), Map.copyOf(byMonth.getOrDefault(m, Map.of()))));
        }
        return out;
    }

    /**
     * Submit an application. The applicant accepts the Partner Program Terms with it: the
     * version they ticked must be the current one ({@code terms_not_accepted} when none was
     * sent, {@code terms_outdated} when the text changed since they loaded it), and the
     * acceptance is recorded in the same transaction as the application, with the evidence.
     */
    @Transactional
    public Outcome apply(Long userId, ApplicationForm form, PartnerTermsService.Evidence evidence) {
        if (userId == null) return Outcome.fail("unauthorized");
        if (form == null) return Outcome.fail("missing_body");
        String company = clean(form.companyName());
        if (company == null) return Outcome.fail("missing_company");
        String website = clean(form.website());
        String audience = clean(form.audience());
        String message = clean(form.message());
        if (company.length() > MAX_COMPANY || (website != null && website.length() > MAX_WEBSITE)
                || (audience != null && audience.length() > MAX_AUDIENCE)
                || (message != null && message.length() > MAX_MESSAGE)) {
            return Outcome.fail("too_long");
        }
        if (website != null && !WEBSITE.matcher(website).matches()) return Outcome.fail("invalid_website");
        if (codeRepository.findByOwnerUserIdAndProgram(userId, RewardProgram.PARTNER).isPresent()) {
            return Outcome.fail("already_partner");
        }
        if (applicationRepository.existsByUserIdAndStatus(userId, PartnerApplication.Status.PENDING)) {
            return Outcome.fail("already_pending");
        }
        String termsRefusal = termsService.refusal(form.termsVersion());
        if (termsRefusal != null) return Outcome.fail(termsRefusal);
        PartnerApplication a = new PartnerApplication();
        a.setUserId(userId);
        a.setStatus(PartnerApplication.Status.PENDING);
        a.setCompanyName(company);
        a.setWebsite(website);
        a.setAudience(audience);
        a.setMessage(message);
        // A concurrent double submit loses on uq_partner_application_pending_user: the exception
        // leaves this transaction and the controller answers already_pending.
        PartnerApplication saved = applicationRepository.saveAndFlush(a);
        // created_at is the database's now() (insertable = false), never read back by the insert:
        // stamp the in-memory copy so the response is not dated null. Not written again.
        if (saved.getCreatedAt() == null) saved.setCreatedAt(Instant.now());
        termsService.accept(userId, form.termsVersion(), PartnerTermsAcceptance.Source.APPLICATION, evidence);
        log.info("Partner application {} submitted by user {}", saved.getId(), userId);
        return Outcome.ok(saved);
    }

    /**
     * Accept the current Partner Program Terms from the partner dashboard: for a partner whose
     * code predates the terms, or who applied on an older version. Only a partner (an owner of a
     * PARTNER code) or an applicant has terms to accept; anyone else gets {@code not_partner}.
     * Returns the refusal token, or null once the acceptance is on record.
     */
    @Transactional
    public String acceptTerms(Long userId, String version, PartnerTermsService.Evidence evidence) {
        if (userId == null) return "unauthorized";
        boolean partner = codeRepository.findByOwnerUserIdAndProgram(userId, RewardProgram.PARTNER).isPresent();
        if (!partner && applicationRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId).isEmpty()) {
            return "not_partner";
        }
        return termsService.accept(userId, version, PartnerTermsAcceptance.Source.DASHBOARD, evidence);
    }

    /** The admin queue: pending first-class, everything else newest first (capped). */
    @Transactional(readOnly = true)
    public List<PartnerApplication> applications(boolean pendingOnly) {
        return pendingOnly
                ? applicationRepository.findByStatusOrderByCreatedAtDescIdDesc(PartnerApplication.Status.PENDING)
                : applicationRepository.findTop200ByOrderByCreatedAtDescIdDesc();
    }

    /** Applicant e-mails for the admin list, one lookup per distinct user. */
    @Transactional(readOnly = true)
    public Map<Long, String> applicantEmails(List<PartnerApplication> applications) {
        Set<Long> ids = new HashSet<>();
        applications.forEach(a -> ids.add(a.getUserId()));
        Map<Long, String> emails = new HashMap<>();
        if (ids.isEmpty()) return emails;
        userRepository.findAllById(ids).forEach(u -> emails.put(u.getId(), u.getEmail()));
        return emails;
    }

    /**
     * Approve a pending application: create the applicant's PARTNER code (program defaults
     * unless overridden) and record it on the application, in one transaction. When the
     * applicant already owns a partner code (an admin created one by hand in the meantime),
     * the application is approved onto that code instead of being stuck: nothing new is
     * created and the overrides do not apply. Any other code refusal leaves the application
     * pending and reports the refusal token.
     *
     * <p>{@code founder} also grants the applicant the founder tier (PLATINUM for life), and is
     * refused up front ({@code founder_closed}) once the founder window has closed.
     *
     * <p>Flushed here, not at commit: a concurrent decision on the same application then fails
     * this call with an optimistic-lock error (see {@link PartnerApplication#getVersion()}),
     * which rolls back the code this call created.
     */
    @Transactional
    public Outcome approve(Long applicationId, Long adminUserId, String code, Integer commissionBps,
                           boolean founder) {
        Optional<PartnerApplication> found = applicationRepository.findById(applicationId);
        if (found.isEmpty()) return Outcome.fail("application_not_found");
        PartnerApplication a = found.get();
        if (a.getStatus() != PartnerApplication.Status.PENDING) return Outcome.fail("not_pending");
        // Checked before anything is created: a refusal must leave no code behind.
        if (founder && !tierService.founderOpen()) return Outcome.fail("founder_closed");
        Optional<RewardCode> existing = codeRepository.findByOwnerUserIdAndProgram(a.getUserId(), RewardProgram.PARTNER);
        Long codeId;
        if (existing.isPresent()) {
            // A disabled or expired code would approve a partner with no working link and no
            // badge while the mail says both are live: the admin re-enables it first.
            if (!existing.get().isRedeemableAt(Instant.now())) return Outcome.fail("existing_code_inactive");
            codeId = existing.get().getId();
        } else {
            PartnerProgramAdminService.Result created = adminService.createPartnerCode(new PartnerCodeRequest(
                    a.getUserId(), code, a.getCompanyName(), null, commissionBps, null, null, null, null));
            if (!created.success()) return Outcome.fail(created.error());
            codeId = created.code().getId();
        }
        if (founder) {
            PartnerTierService.Result granted = tierService.grantFounder(a.getUserId(), adminUserId);
            // Only the founder window closing between the check above and here can refuse it:
            // throw, so the code this call created is rolled back with the approval.
            if (!granted.success()) throw new FounderWindowClosedException();
        }
        a.setStatus(PartnerApplication.Status.APPROVED);
        a.setRewardCodeId(codeId);
        a.setReviewedBy(adminUserId);
        a.setReviewedAt(Instant.now());
        return Outcome.ok(applicationRepository.saveAndFlush(a));
    }

    /**
     * Whether the application is still waiting for a decision. Lets the admin endpoint tell a
     * lost race (another admin decided first, so the code index refused this approval) from a
     * real conflict with a code the applicant already owns.
     */
    @Transactional(readOnly = true)
    public boolean isPending(Long applicationId) {
        return applicationRepository.findById(applicationId)
                .map(a -> a.getStatus() == PartnerApplication.Status.PENDING)
                .orElse(false);
    }

    @Transactional
    public Outcome reject(Long applicationId, Long adminUserId, String note) {
        Optional<PartnerApplication> found = applicationRepository.findById(applicationId);
        if (found.isEmpty()) return Outcome.fail("application_not_found");
        PartnerApplication a = found.get();
        if (a.getStatus() != PartnerApplication.Status.PENDING) return Outcome.fail("not_pending");
        String cleaned = clean(note);
        if (cleaned != null && cleaned.length() > MAX_NOTE) return Outcome.fail("too_long");
        a.setStatus(PartnerApplication.Status.REJECTED);
        a.setDecisionNote(cleaned);
        a.setReviewedBy(adminUserId);
        a.setReviewedAt(Instant.now());
        // Flushed for the same reason as approve: a concurrent decision fails here, not later.
        return Outcome.ok(applicationRepository.saveAndFlush(a));
    }

    /** HOLD splits into on-hold and payable exactly like the admin totals. */
    static String lineStatus(PartnerCommission l, Instant now) {
        return switch (l.getStatus()) {
            case PAID -> "paid";
            case VOID -> "void";
            case HOLD -> l.isPayableAt(now) ? "payable" : "on_hold";
        };
    }

    private static String clean(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
