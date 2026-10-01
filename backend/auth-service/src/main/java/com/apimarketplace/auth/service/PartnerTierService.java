package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PartnerStanding;
import com.apimarketplace.auth.domain.PartnerTier;
import com.apimarketplace.auth.repository.PartnerCommissionRepository;
import com.apimarketplace.auth.repository.PartnerStandingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Partner tiers (V556): Silver, Gold and Platinum, each with its commission rate.
 *
 * <ul>
 *   <li>Every partner starts SILVER.</li>
 *   <li>GOLD and PLATINUM are reached automatically on settled revenue: what the partner's
 *       customers paid (excluding tax) on the invoices that earn them a commission, counted once
 *       an invoice is {@code reward.partner.tier.settle-days} old (60 by default, well past the
 *       14-day payout hold, since a card dispute can come weeks later) and never voided
 *       ({@link PartnerCommissionRepository#sumSettledRevenue}).</li>
 *   <li>A founding partner is granted PLATINUM by an admin, only while the founder window is
 *       open ({@code reward.partner.founder-until}).</li>
 *   <li>A tier never goes down: {@link PartnerStandingRepository#raise} only ever raises it.</li>
 * </ul>
 *
 * <p>The stored tier is brought up to date lazily, whenever it matters: before a commission
 * is computed, and when the partner or an admin looks ({@link #refresh}). The commission a
 * line earns is the higher of its code's own rate (a custom deal) and the tier's rate.
 *
 * <p>Rates, thresholds and the founder deadline are configuration ({@code reward.partner.*}),
 * not data. The Silver rate is {@code reward.partner.commission-bps}, the rate a new partner
 * code is created with.
 */
@Service
public class PartnerTierService {

    private static final Logger log = LoggerFactory.getLogger(PartnerTierService.class);

    /** What a partner sees of their tier: where they are and how far the next one is. */
    public record Standing(PartnerTier tier, boolean founder, long revenueMinor, String currency,
                           PartnerTier nextTier, Long nextThresholdMinor, int rateBps) {}

    /** One tier as the public terms describe it: its rate and the revenue that reaches it. */
    public record TierTerm(PartnerTier tier, int rateBps, long thresholdMinor) {}

    /** A partner code as the admin list shows it: its owner's tier and the rate it earns now. */
    public record CodeStanding(Standing standing, int effectiveRateBps) {}

    /** Structured outcome: {@code standing} on success, a stable {@code error} token otherwise. */
    public record Result(Standing standing, String error) {
        static Result ok(Standing s) { return new Result(s, null); }
        static Result fail(String e) { return new Result(null, e); }
        public boolean success() { return error == null; }
    }

    private final PartnerStandingRepository standingRepository;
    private final PartnerCommissionRepository commissionRepository;
    private final Map<PartnerTier, Integer> rates = new EnumMap<>(PartnerTier.class);
    private final Map<PartnerTier, Long> thresholds = new EnumMap<>(PartnerTier.class);
    private final String currency;
    private final int settleDays;
    private final Instant founderUntil;
    private final Clock clock;

    @Autowired
    public PartnerTierService(PartnerStandingRepository standingRepository,
                              PartnerCommissionRepository commissionRepository,
                              @Value("${reward.partner.commission-bps:3000}") int silverBps,
                              @Value("${reward.partner.tier.gold-bps:4000}") int goldBps,
                              @Value("${reward.partner.tier.platinum-bps:5000}") int platinumBps,
                              @Value("${reward.partner.tier.gold-threshold-minor:500000}") long goldThresholdMinor,
                              @Value("${reward.partner.tier.platinum-threshold-minor:2500000}") long platinumThresholdMinor,
                              @Value("${reward.partner.tier.currency:usd}") String currency,
                              @Value("${reward.partner.tier.settle-days:60}") int settleDays,
                              @Value("${reward.partner.founder-until:2027-01-01T00:00:00Z}") String founderUntil) {
        this(standingRepository, commissionRepository, silverBps, goldBps, platinumBps, goldThresholdMinor,
                platinumThresholdMinor, currency, settleDays, Instant.parse(founderUntil), Clock.systemUTC());
    }

    PartnerTierService(PartnerStandingRepository standingRepository,
                       PartnerCommissionRepository commissionRepository,
                       int silverBps, int goldBps, int platinumBps,
                       long goldThresholdMinor, long platinumThresholdMinor,
                       String currency, int settleDays, Instant founderUntil, Clock clock) {
        // A higher tier paying less, or reached before a lower one, is a configuration mistake
        // that would silently shortchange partners: refuse to start rather than run with it.
        if (silverBps < 0 || platinumBps > 10_000 || silverBps > goldBps || goldBps > platinumBps) {
            throw new IllegalArgumentException("reward.partner tier rates must satisfy 0 <= silver <= gold <= platinum <= 10000");
        }
        if (goldThresholdMinor <= 0 || platinumThresholdMinor <= goldThresholdMinor) {
            throw new IllegalArgumentException("reward.partner tier thresholds must satisfy 0 < gold < platinum");
        }
        if (settleDays < 0) {
            throw new IllegalArgumentException("reward.partner.tier.settle-days must not be negative");
        }
        this.settleDays = settleDays;
        this.standingRepository = standingRepository;
        this.commissionRepository = commissionRepository;
        rates.put(PartnerTier.SILVER, silverBps);
        rates.put(PartnerTier.GOLD, goldBps);
        rates.put(PartnerTier.PLATINUM, platinumBps);
        thresholds.put(PartnerTier.SILVER, 0L);
        thresholds.put(PartnerTier.GOLD, goldThresholdMinor);
        thresholds.put(PartnerTier.PLATINUM, platinumThresholdMinor);
        this.currency = currency == null ? "usd" : currency.toLowerCase(Locale.ROOT);
        this.founderUntil = founderUntil;
        this.clock = clock;
    }

    public int rateBps(PartnerTier tier) {
        return rates.get(tier == null ? PartnerTier.SILVER : tier);
    }

    /** The three tiers in ascending order, for the public terms and the admin page. */
    public List<TierTerm> tiers() {
        return List.of(PartnerTier.values()).stream()
                .map(t -> new TierTerm(t, rates.get(t), thresholds.get(t)))
                .toList();
    }

    /** The currency the thresholds and the settled revenue are counted in (lower case). */
    public String currency() {
        return currency;
    }

    public Instant founderUntil() {
        return founderUntil;
    }

    /** How old an invoice must be (and not voided) before it counts toward a tier. */
    public int settleDays() {
        return settleDays;
    }

    /** Whether an admin may still grant the founder tier. */
    public boolean founderOpen() {
        return clock.instant().isBefore(founderUntil);
    }

    /** The tier a settled revenue reaches on its own (no founder grant, no history). */
    PartnerTier earnedTier(long revenueMinor) {
        if (revenueMinor >= thresholds.get(PartnerTier.PLATINUM)) return PartnerTier.PLATINUM;
        if (revenueMinor >= thresholds.get(PartnerTier.GOLD)) return PartnerTier.GOLD;
        return PartnerTier.SILVER;
    }

    /**
     * Bring a partner's stored tier up to what their settled revenue has earned, and return
     * where they stand. Never lowers the tier. Writes only when a higher tier was earned.
     */
    @Transactional
    public Standing refresh(Long userId) {
        Instant now = clock.instant();
        long revenue = commissionRepository.sumSettledRevenue(userId, currency,
                now.minus(settleDays, java.time.temporal.ChronoUnit.DAYS));
        Optional<PartnerStanding> stored = standingRepository.findById(userId);
        PartnerTier current = stored.map(PartnerStanding::getTier).orElse(PartnerTier.SILVER);
        boolean founder = stored.map(PartnerStanding::isFounder).orElse(false);
        PartnerTier earned = earnedTier(revenue);
        if (earned.isAbove(current)) {
            standingRepository.raise(userId, earned.name(), false, null, now);
            log.info("Partner {} reached {} (settled revenue {} {})", userId, earned, revenue, currency);
            current = earned;
        }
        return standing(current, founder, revenue);
    }

    /** Each partner's tier after {@link #refresh}: the admin partner list. */
    @Transactional
    public Map<Long, Standing> refreshAll(Collection<Long> userIds) {
        Map<Long, Standing> out = new HashMap<>();
        for (Long id : userIds) {
            if (id != null && !out.containsKey(id)) out.put(id, refresh(id));
        }
        return out;
    }

    /** The stored tier, without recomputing it (a public display). No row reads as SILVER. */
    @Transactional(readOnly = true)
    public PartnerTier tierOf(Long userId) {
        if (userId == null) return PartnerTier.SILVER;
        return standingRepository.findById(userId).map(PartnerStanding::getTier).orElse(PartnerTier.SILVER);
    }

    /**
     * Grant a partner the founder tier: PLATINUM, for life. Refused once the founder window
     * has closed ({@code founder_closed}); granting it twice changes nothing.
     */
    @Transactional
    public Result grantFounder(Long userId, Long adminUserId) {
        if (userId == null) return Result.fail("missing_partner");
        if (!founderOpen()) return Result.fail("founder_closed");
        standingRepository.raise(userId, PartnerTier.PLATINUM.name(), true, adminUserId, clock.instant());
        log.info("Admin {} granted the founder tier to partner {}", adminUserId, userId);
        return Result.ok(refresh(userId));
    }

    /**
     * End a partner's founder status (Partner Program Terms, clause 7.5): they return to the tier
     * their settled revenue has earned, which may be lower than Platinum. Refused with
     * {@code not_founder} for a partner who is not one. Open at any time, unlike the grant: the
     * founder window only limits who can be named.
     */
    @Transactional
    public Result endFounder(Long userId, Long adminUserId) {
        if (userId == null) return Result.fail("missing_partner");
        Instant now = clock.instant();
        long revenue = commissionRepository.sumSettledRevenue(userId, currency,
                now.minus(settleDays, java.time.temporal.ChronoUnit.DAYS));
        PartnerTier earned = earnedTier(revenue);
        if (standingRepository.endFounder(userId, earned.name(), adminUserId, now) == 0) {
            return Result.fail("not_founder");
        }
        log.info("Admin {} ended the founder status of partner {}: back to {} (settled revenue {} {})",
                adminUserId, userId, earned, revenue, currency);
        return Result.ok(standing(earned, false, revenue));
    }

    /**
     * The tier of each PARTNER code's owner, brought up to date, with the rate the code earns
     * now: the admin partner list. Other codes (creator codes, codes with no owner) are absent.
     */
    @Transactional
    public Map<Long, CodeStanding> forCodes(Collection<com.apimarketplace.auth.domain.RewardCode> codes) {
        Map<Long, CodeStanding> out = new HashMap<>();
        if (codes == null || codes.isEmpty()) return out;
        List<com.apimarketplace.auth.domain.RewardCode> partnerCodes = codes.stream()
                .filter(c -> c != null && c.getProgram() == com.apimarketplace.auth.domain.RewardProgram.PARTNER
                        && c.getOwnerUserId() != null)
                .toList();
        Map<Long, Standing> byOwner = refreshAll(partnerCodes.stream()
                .map(com.apimarketplace.auth.domain.RewardCode::getOwnerUserId).toList());
        for (var c : partnerCodes) {
            Standing s = byOwner.get(c.getOwnerUserId());
            out.put(c.getId(), new CodeStanding(s, effectiveRateBps(c.getPayoutBps(), s.tier())));
        }
        return out;
    }

    /** The rate a commission line earns: the higher of its code's own rate and the tier's. */
    public int effectiveRateBps(Integer codeBps, PartnerTier tier) {
        int tierBps = rateBps(tier);
        return codeBps == null ? tierBps : Math.max(codeBps, tierBps);
    }

    private Standing standing(PartnerTier tier, boolean founder, long revenue) {
        PartnerTier next = tier.next();
        return new Standing(tier, founder, revenue, currency, next,
                next == null ? null : thresholds.get(next), rates.get(tier));
    }
}
