package com.apimarketplace.auth.service;

import com.apimarketplace.auth.billing.CreditTierConstants;
import com.apimarketplace.auth.domain.PartnerOffer;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.repository.PartnerOfferRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A partner's offers: one link per client, carrying the plan, monthly credits and billing cycle
 * the partner recommends (V559), and the partner's own applications it gives (V560). The partner
 * creates, lists and deactivates their own; the offer page reads one by its token, anonymously,
 * and gets only what a client may see: the plan, the credits the partner's code gives a new
 * account, the applications as marketplace cards, and the partner's public identity (the one their
 * public profile already shows), never their earnings or note. The app cards name their publisher
 * (the partner's account id), as those apps' own marketplace cards already do: an offer gives
 * public or unlisted apps only.
 */
@Service
public class PartnerOfferService {

    private static final Logger log = LoggerFactory.getLogger(PartnerOfferService.class);

    /** How many live offers one partner may hold: generous for an agency, bounded for abuse. */
    static final int MAX_ACTIVE_OFFERS = 200;
    /** How many applications one offer gives (publication-service applies the same bound). */
    static final int MAX_APPS = 10;
    static final int TOKEN_LENGTH = 10;
    static final int MAX_LABEL = 120;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final Set<String> PLANS = Set.of("STARTER", "PRO", "TEAM");
    private static final Set<String> CYCLES = Set.of("monthly", "yearly");

    /** Result of a create: the offer, or the reason it was refused (a stable token for the UI). */
    public record Outcome(PartnerOffer offer, String error) {
        static Outcome ok(PartnerOffer offer) { return new Outcome(offer, null); }
        static Outcome fail(String error) { return new Outcome(null, error); }
        public boolean succeeded() { return error == null; }
    }

    /**
     * What the offer page shows. {@code partner} is null when the partner's profile is private;
     * {@code apps} holds the applications the offer gives that can still be given, as cards;
     * {@code appsPlan} is the smallest plan they can be installed on (null: any plan), since a
     * checkout through the offer on a smaller plan is refused.
     */
    public record PublicOffer(String token, String code, int credits, String planCode, int creditTierIndex,
                              String billingCycle, PublicProfileDto partner, List<Map<String, Object>> apps,
                              String appsPlan) {}

    private final PartnerOfferRepository offers;
    private final RewardCodeRepository codes;
    private final UserRepository users;
    private final UserService userService;
    private final PartnerOfferAppsClient apps;
    private final PlanFeatureRequirementService planFeatures;
    private final RewardRedemptionRepository redemptions;
    private final PlanRepository plans;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public PartnerOfferService(PartnerOfferRepository offers, RewardCodeRepository codes, UserRepository users,
                               UserService userService, PartnerOfferAppsClient apps,
                               PlanFeatureRequirementService planFeatures, RewardRedemptionRepository redemptions,
                               PlanRepository plans, PlatformTransactionManager transactionManager) {
        this(offers, codes, users, userService, apps, planFeatures, redemptions, plans, new TransactionTemplate(transactionManager));
    }

    PartnerOfferService(PartnerOfferRepository offers, RewardCodeRepository codes, UserRepository users,
                        UserService userService, PartnerOfferAppsClient apps,
                        PlanFeatureRequirementService planFeatures, RewardRedemptionRepository redemptions,
                        PlanRepository plans, TransactionTemplate tx) {
        this.offers = offers;
        this.codes = codes;
        this.users = users;
        this.userService = userService;
        this.apps = apps;
        this.planFeatures = planFeatures;
        this.redemptions = redemptions;
        this.plans = plans;
        this.tx = tx;
    }

    /**
     * A new offer on the partner's own code. Refused when the partner has no code that can bring
     * a sign-up right now (inactive, expired or used up: the link would lead nowhere), on a plan,
     * credit tier or cycle the price list does not sell, past {@link #MAX_ACTIVE_OFFERS}, or with
     * applications that are not the partner's to give (more than {@link #MAX_APPS}, or one that is
     * not their active public or unlisted application), or one the recommended plan could not run
     * (it uses a capability the plan does not include, such as vector search below Pro).
     *
     * <p>Not one transaction: the applications are checked with publication-service first, and a
     * database transaction should not wait on a remote call. The steps after it are a read and an
     * insert, which need none.
     */
    public Outcome create(Long partnerUserId, String planCode, Integer creditTierIndex, String billingCycle, String label,
                          List<String> appIds) {
        if (partnerUserId == null) return Outcome.fail("unauthorized");
        Optional<RewardCode> code = codes.findByOwnerUserIdAndProgram(partnerUserId, RewardProgram.PARTNER);
        if (code.isEmpty()) return Outcome.fail("not_partner");
        RewardCode c = code.get();
        if (!c.isLivePartnerCode(Instant.now())) return Outcome.fail("code_inactive");

        String plan = planCode == null ? "" : planCode.trim().toUpperCase();
        if (!PLANS.contains(plan)) return Outcome.fail("invalid_plan");
        if (creditTierIndex == null || creditTierIndex < 0 || creditTierIndex >= CreditTierConstants.CREDIT_TIERS.length
                || ("STARTER".equals(plan) && creditTierIndex > CreditTierConstants.STARTER_MAX_TIER_INDEX)) {
            return Outcome.fail("invalid_credits");
        }
        String cycle = billingCycle == null ? "" : billingCycle.trim().toLowerCase();
        if (!CYCLES.contains(cycle)) return Outcome.fail("invalid_cycle");
        String note = label == null ? null : label.trim();
        if (note != null && note.isEmpty()) note = null;
        if (note != null && note.length() > MAX_LABEL) return Outcome.fail("too_long");

        List<String> wanted = new ArrayList<>();
        if (appIds != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (String id : appIds) {
                UUID uuid = parseUuid(id);
                if (uuid == null) return Outcome.fail("invalid_apps");
                seen.add(uuid.toString());
            }
            wanted.addAll(seen);
        }
        if (wanted.size() > MAX_APPS) return Outcome.fail("too_many_apps");
        if (!wanted.isEmpty()) {
            List<Map<String, Object>> offerable;
            try {
                offerable = apps.offerable(partnerUserId, wanted);
            } catch (Exception e) {
                log.warn("Partner {}: applications could not be checked for a new offer: {}", partnerUserId, e.getMessage());
                return Outcome.fail("apps_unavailable");
            }
            Set<String> ok = new LinkedHashSet<>();
            for (Map<String, Object> app : offerable) ok.add(String.valueOf(app.get("id")));
            // Every one, or the offer would promise an app it cannot give.
            if (!ok.containsAll(wanted)) return Outcome.fail("invalid_apps");
            // An app the plan cannot run would fail its install after the client paid.
            for (Map<String, Object> app : offerable) {
                if (!planRuns(plan, app.get("features"))) return Outcome.fail("apps_need_higher_plan");
            }
            // Nor can a plan hold more apps or interfaces than its quotas: the install would be
            // refused, or cloned without the interfaces past the quota.
            if (!fits(plan, offerable)) return Outcome.fail("apps_exceed_plan");
        }

        // A soft cap against runaway scripts, read then written: two creates racing at the limit can
        // pass it by one, which costs nothing (the cap guards volume, not money).
        if (offers.countByPartnerUserIdAndActiveTrue(partnerUserId) >= MAX_ACTIVE_OFFERS) return Outcome.fail("too_many_offers");

        PartnerOffer offer = new PartnerOffer();
        offer.setToken(newToken());
        offer.setPartnerUserId(partnerUserId);
        offer.setRewardCodeId(c.getId());
        offer.setPlanCode(plan);
        offer.setCreditTierIndex(creditTierIndex);
        offer.setBillingCycle(cycle);
        offer.setLabel(note);
        offer.setAppPublicationIds(wanted);
        return Outcome.ok(offers.save(offer));
    }

    /** The partner's live offers, newest first. */
    @Transactional(readOnly = true)
    public List<PartnerOffer> list(Long partnerUserId) {
        if (partnerUserId == null) return List.of();
        return offers.findByPartnerUserIdAndActiveTrueOrderByCreatedAtDescIdDesc(partnerUserId);
    }

    /** Deactivate one of the partner's own offers; false when it is not theirs or not found. */
    @Transactional
    public boolean deactivate(Long partnerUserId, String token) {
        if (partnerUserId == null || token == null) return false;
        return offers.findByToken(token.trim())
                .filter(o -> partnerUserId.equals(o.getPartnerUserId()))
                .map(o -> {
                    o.setActive(false);
                    offers.save(o);
                    return true;
                })
                .orElse(false);
    }

    /**
     * The offer behind a token, for the anonymous offer page: empty when the offer is unknown or
     * deactivated, or when the partner's code can no longer bring a sign-up (so the page never
     * promises credits the code would refuse). The applications are the ones it gives that can
     * still be given (one the partner withdrew since is not promised); when publication-service
     * cannot say, the page shows none rather than failing.
     */
    public Optional<PublicOffer> publicOffer(String token) {
        return publicOffer(token, null);
    }

    /**
     * The offer as {@code viewerUserId} sees it. A client already attributed to the offer's partner
     * still reads it once the partner's code can no longer bring a sign-up (used up, expired): they
     * may still pay through it (back from a cancelled payment, say), and its apps are still theirs
     * to get. The credits the code gives a new account are then not promised (0). Anyone else gets
     * what {@link #publicOffer(String)} gives.
     */
    public Optional<PublicOffer> publicOffer(String token, Long viewerUserId) {
        if (token == null || token.isBlank() || token.length() > 16) return Optional.empty();
        // Writable, not read-only: the partner's public profile generates its @handle lazily on first read.
        record Read(PartnerOffer offer, RewardCode code, boolean live, PublicProfileDto partner) {}
        Read read = tx.execute(status -> {
            PartnerOffer offer = offers.findByToken(token.trim()).filter(PartnerOffer::isActive).orElse(null);
            if (offer == null) return null;
            RewardCode code = codes.findById(offer.getRewardCodeId())
                    .filter(c -> c.getProgram() == RewardProgram.PARTNER)
                    .orElse(null);
            if (code == null) return null;
            boolean live = code.isLivePartnerCode(Instant.now());
            if (!live && !attributedTo(viewerUserId, offer.getPartnerUserId())) return null;
            PublicProfileDto partner = users.findById(offer.getPartnerUserId())
                    .flatMap(userService::getPublicProfile)
                    .orElse(null);
            return new Read(offer, code, live, partner);
        });
        if (read == null) return Optional.empty();
        PartnerOffer offer = read.offer();
        List<Map<String, Object>> cards = List.of();
        if (!offer.getAppPublicationIds().isEmpty()) {
            try {
                cards = apps.offerable(offer.getPartnerUserId(), offer.getAppPublicationIds());
            } catch (Exception e) {
                log.warn("Partner offer {}: application cards unavailable: {}", offer.getToken(), e.getMessage());
            }
        }
        String appsPlan = appsPlan(cards);
        // Apps no plan sold here can run (an admin set their feature above Team): the offer could
        // never be paid for, so it is not offered.
        if (appsPlan != null && !PLANS.contains(appsPlan)) {
            log.warn("Partner offer {}: its apps need {}, which an offer does not sell", offer.getToken(), appsPlan);
            return Optional.empty();
        }
        return Optional.of(new PublicOffer(offer.getToken(), read.code().getCode(), read.live() ? read.code().getBenefitAmount() : 0,
                offer.getPlanCode(), offer.getCreditTierIndex(), offer.getBillingCycle(), read.partner(), cards, appsPlan));
    }

    private boolean attributedTo(Long viewerUserId, Long partnerUserId) {
        return viewerUserId != null && redemptions.findByRedeemerUserIdAndProgram(viewerUserId, RewardProgram.PARTNER)
                .map(r -> java.util.Objects.equals(r.getOwnerUserId(), partnerUserId))
                .orElse(false);
    }

    /**
     * Whether every application {@code offer} gives can be installed on {@code planCode}, read from
     * publication-service now (the app may have changed, the plan an admin set may have too). Opens
     * when they cannot be read: the checkout is not blocked on a lookup, and the install then
     * retries and says what it could not do.
     */
    public boolean appsRunOn(PartnerOffer offer, String planCode) {
        if (offer.getAppPublicationIds().isEmpty()) return true;
        try {
            List<Map<String, Object>> cards = apps.offerable(offer.getPartnerUserId(), offer.getAppPublicationIds());
            for (Map<String, Object> card : cards) {
                if (!planRuns(planCode, card.get("features"))) return false;
            }
            if (!fits(planCode, cards)) return false;
        } catch (Exception e) {
            log.warn("Partner offer {}: applications not checked against plan {}: {}", offer.getToken(), planCode, e.getMessage());
        }
        return true;
    }

    /**
     * The smallest plan an offer sells that installs every one of these apps (their priced
     * features, and their count and interfaces within its quotas): null when Starter does, and a
     * code above the sold plans when none does.
     */
    String appsPlan(List<Map<String, Object>> cards) {
        for (String plan : SOLD_PLANS) {
            boolean runs = cards.stream().allMatch(card -> planRuns(plan, card.get("features")));
            if (runs && fits(plan, cards)) return "STARTER".equals(plan) ? null : plan;
        }
        return BEYOND_SOLD_PLANS;
    }

    /** The plans an offer sells, smallest first. */
    private static final List<String> SOLD_PLANS = List.of("STARTER", "PRO", "TEAM");
    /** What {@link #appsPlan} answers when no sold plan installs the apps. */
    static final String BEYOND_SOLD_PLANS = "ENTERPRISE";

    /**
     * Whether {@code plan}'s quotas hold these apps: one application each, and the interfaces they
     * bring. The client's own apps and interfaces count too, and are not known here: this is the
     * least the plan must hold. An unknown plan or an unlimited quota holds them.
     */
    private boolean fits(String plan, List<Map<String, Object>> cards) {
        com.apimarketplace.auth.domain.Plan p = plans.findByCode(plan).orElse(null);
        if (p == null) return true;
        Integer maxApps = p.getResourceLimit("APPLICATION");
        if (maxApps != null && cards.size() > maxApps) return false;
        Integer maxInterfaces = p.getResourceLimit("INTERFACE");
        int interfaces = cards.stream().mapToInt(card -> card.get("interfaceCount") instanceof Number n ? n.intValue() : 0).sum();
        return maxInterfaces == null || interfaces <= maxInterfaces;
    }

    /**
     * A short, unguessable token without look-alike characters (no 0/O, 1/l/I). Checked then
     * inserted: two draws of the same token at the same instant (one chance in 57^10) would meet the
     * unique index and fail that one create, never hand out a shared link.
     */
    String newToken() {
        for (int attempt = 0; attempt < 8; attempt++) {
            StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
            for (int i = 0; i < TOKEN_LENGTH; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            String token = sb.toString();
            if (!offers.existsByToken(token)) return token;
        }
        throw new IllegalStateException("could not draw a free offer token");
    }

    /**
     * Whether {@code plan} may install an app with these features (as publication-service names
     * them). The install refuses one priced capability: vector search, below the plan an admin
     * set on {@code feature:vector_search}. The same question is asked here, so an offer never
     * promises an app its own plan would be refused.
     */
    private boolean planRuns(String plan, Object features) {
        return !(features instanceof List<?> names && names.contains(VECTOR_SEARCH))
                || planFeatures.allows(plan, VECTOR_SEARCH_KEY);
    }

    /** publication-service's name for the capability, and the key its plan requirement is stored under. */
    static final String VECTOR_SEARCH = "VECTOR_SEARCH";
    static final String VECTOR_SEARCH_KEY = "feature:vector_search";

    private static UUID parseUuid(String id) {
        try {
            return id == null ? null : UUID.fromString(id.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
