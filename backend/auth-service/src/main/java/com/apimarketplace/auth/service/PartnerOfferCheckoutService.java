package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PartnerOffer;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.repository.PartnerOfferRepository;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.repository.RewardRedemptionRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * The step before a checkout opened from a partner's offer page.
 *
 * <p>The offer must still be live (active, its partner code still able to bring a sign-up). Then the
 * client is attributed to the offer's partner HERE, on the server, before Stripe is involved: the
 * attribution no longer depends on the browser having applied the code first (a closed tab, a
 * blocked storage, a dropped request), and the commission recorded on the first paid invoice finds
 * it. A client the code cannot attribute (already another partner's client, a partner themselves,
 * an account that is not new, an existing subscriber) still pays: the offer simply attributes no
 * one. Only an unverified email stops the checkout, because onboarding settles it and the code then
 * applies.
 *
 * <p>Two checkouts are refused before anything is attributed, because the offer could not keep its
 * promise: an account that already pays (Stripe would change its plan with no checkout, so no
 * payment would ever carry the offer and its apps), and a plan the offer's apps cannot be installed
 * on (the client would pay and the install would be refused).
 *
 * <p>Not transactional on purpose: the redeem runs in its own transaction, so a concurrent redeem of
 * the same code by the browser (a unique-index refusal) is read as "already attributed" instead of
 * poisoning a surrounding one.
 */
@Service
public class PartnerOfferCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(PartnerOfferCheckoutService.class);

    public enum Verdict { PROCEED, OFFER_UNAVAILABLE, EMAIL_NOT_VERIFIED, ALREADY_SUBSCRIBED, PLAN_TOO_LOW }

    private final PartnerOfferRepository offers;
    private final RewardCodeRepository codes;
    private final RewardRedemptionRepository redemptions;
    private final RewardService rewards;
    private final PartnerOfferDeliveryService deliveries;
    private final SubscriptionRepository subscriptions;
    private final PartnerOfferService partnerOffers;

    public PartnerOfferCheckoutService(PartnerOfferRepository offers, RewardCodeRepository codes,
                                       RewardRedemptionRepository redemptions, RewardService rewards,
                                       PartnerOfferDeliveryService deliveries, SubscriptionRepository subscriptions,
                                       PartnerOfferService partnerOffers) {
        this.offers = offers;
        this.codes = codes;
        this.redemptions = redemptions;
        this.rewards = rewards;
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.partnerOffers = partnerOffers;
    }

    /**
     * The checkout opened: the apps the offer gives wait for its payment. Their delivery follows the
     * payment's webhook; this record only lets a payment whose webhook failed be found again. Never
     * throws (see {@link PartnerOfferDeliveryService#expect}).
     */
    public void opened(Long userId, String token) {
        deliveries.expect(token, userId);
    }

    public Verdict prepare(Long userId, String token, String planCode) {
        if (userId == null || token == null || token.isBlank() || token.length() > 16) return Verdict.OFFER_UNAVAILABLE;
        Optional<PartnerOffer> offer = offers.findByToken(token.trim()).filter(PartnerOffer::isActive);
        if (offer.isEmpty()) return Verdict.OFFER_UNAVAILABLE;
        // The same test the personal offer's checkout makes: a Stripe subscription is a live one.
        boolean subscribed = subscriptions.findActiveByUserId(userId)
                .map(s -> s.getProviderSubscriptionId() != null)
                .orElse(false);
        if (subscribed) return Verdict.ALREADY_SUBSCRIBED;
        if (!partnerOffers.appsRunOn(offer.get(), planCode)) return Verdict.PLAN_TOO_LOW;
        // Already this partner's client: the code has done its work. It may have reached its cap
        // with this very client (the page applies it first), which must not stop them paying.
        Optional<com.apimarketplace.auth.domain.RewardRedemption> attributed =
                redemptions.findByRedeemerUserIdAndProgram(userId, RewardProgram.PARTNER);
        if (attributed.isPresent() && java.util.Objects.equals(attributed.get().getOwnerUserId(), offer.get().getPartnerUserId())) {
            return Verdict.PROCEED;
        }
        Optional<RewardCode> code = codes.findById(offer.get().getRewardCodeId())
                .filter(c -> c.isLivePartnerCode(Instant.now()));
        if (code.isEmpty()) return Verdict.OFFER_UNAVAILABLE;

        // Already someone's client (this partner's, through the browser, or another one's: first code
        // wins): nothing to attribute, the checkout goes on.
        if (attributed.isPresent()) return Verdict.PROCEED;
        try {
            RewardService.RedeemStatus status = rewards.redeem(userId, code.get().getCode()).status();
            if (status == RewardService.RedeemStatus.EMAIL_NOT_VERIFIED) return Verdict.EMAIL_NOT_VERIFIED;
            log.info("Partner offer {} checkout: attribution of user {} -> {}", offer.get().getId(), userId, status);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // A concurrent redeem of the same code (the browser's) won the unique index: attributed.
            // Anything else propagates: the checkout fails and is retried, never opened unattributed.
            log.info("Partner offer {} checkout: attribution of user {} raced ({}), checkout goes on",
                    offer.get().getId(), userId, e.getClass().getSimpleName());
        }
        return Verdict.PROCEED;
    }
}
