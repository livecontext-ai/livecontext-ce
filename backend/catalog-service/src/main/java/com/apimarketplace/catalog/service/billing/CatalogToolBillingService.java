package com.apimarketplace.catalog.service.billing;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.SourceIdBuilder;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.ResolvedScopeMarkupDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * V148+ single billing point for catalog tool calls.
 *
 * <p>Replaces the {@code CatalogBillingDispatcher} + per-tool
 * {@code CatalogBillingStrategy} fan-out. Catalog tool execution flow:
 *
 * <pre>
 *   ToolExecutionManager.executeTool() {
 *     decision = billing.preflightReserve(scope);          // BEFORE upstream HTTP
 *     if (!decision.allowed()) throw InsufficientCredits;  // 402, upstream NOT called
 *     callUpstream();
 *     billing.commitOnSuccess(sourceId, amount, ...);      // success on the platform key
 *     // or billing.releaseOnFailure(sourceId, reason) on failure / BYOK / abort
 *   }
 * </pre>
 *
 * <p>The order is the feature. Reserving after the call can only record that
 * the customer could not pay for something already delivered: a resold
 * generation is produced, stored and handed over, and the platform owner pays
 * the provider for it.</p>
 *
 * <p><b>Decision tree</b> (in {@link #shouldBill}):
 * <ul>
 *   <li>{@code credentialSource = USER} (BYOK) → no-op, user paid upstream directly.</li>
 *   <li>{@code credential.providerKind = bridge} → no-op, CE bridge does its own internal accounting.</li>
 *   <li>{@code billingOwnedByCaller} → no-op, the CE relay reserved this call before dispatching it
 *       and settles it itself. Not billed here and never refused here: it is already paid for.</li>
 *   <li>{@code scopeKind == null || scopeId == null} → a scope is never invented. What happens
 *       next depends on the endpoint: an ordinary tool proceeds unbilled (WARN), a RESOLD
 *       generation is REFUSED, because it would otherwise spend the owner's paid provider
 *       quota with no ledger row at all. See {@link #requiresBillingToProceed}.</li>
 *   <li>{@code credentialSource = PLATFORM} + scope present → reserve / commit / release. A RESOLD
 *       generation is additionally REFUSED when the resolved row charges per unit and the call
 *       carries no size: the amount that comes back is then the one-unit floor, which for a ten
 *       second video is a tenth of its price. See {@link #unitPricedWithoutASize}.</li>
 * </ul>
 *
 * <p><b>Scope precedence</b> (RUN-priority): if both {@code runId} and
 * {@code streamId} are present (embedded agent inside workflow), use RUN.
 * STREAM is suppressed when {@code runId != null}. Workflow atomicity is
 * preferred over enforcing chat delinquent gate.
 *
 * <p><b>Slug → UUID resolution</b> happens locally via {@link ApiToolRepository}
 * (catalog owns the tool table). Auth-side endpoint takes the UUID; never
 * needs to know the catalog slug. Avoids a circular auth → catalog dependency.
 */
@Slf4j
@Service
public class CatalogToolBillingService {

    private final CreditConsumptionClient creditClient;
    private final CredentialClient credentialClient;
    private final ApiToolRepository apiToolRepository;
    private final ApiRepository apiRepository;
    private final boolean markupEnabled;

    /**
     * Refusal returned when a resold generation cannot be billed for THIS call
     * (no billing scope on it). Same code as the unpriced refusal below and as
     * the CE relay, so a caller handles one shape for every "the platform key
     * is not available for this generation" outcome.
     *
     * <p>Distinct SENTENCE from {@link #UNBILLABLE_GENERATION_UNIDENTIFIED_CALL},
     * which is thrown one layer up for a different reason. Both are correct
     * outcomes and a caller treats them alike, but a test that can only see the
     * shared {@code PLATFORM_NOT_AVAILABLE} code cannot tell which guard fired,
     * so deleting either one left the whole suite green.
     */
    public static final String UNBILLABLE_GENERATION =
            "PLATFORM_NOT_AVAILABLE: this generation carries no billing scope on this call, so it cannot be "
                    + "charged and is not available on the platform key. Pass credential_source='user' to run "
                    + "it on a provider key you configured yourself, which the platform never charges for. "
                    + "Nothing else you send changes this.";

    /**
     * Refusal for a resold generation whose call could not be described well
     * enough to bill it AT ALL: no billing context could be built, because the
     * endpoint or the account behind the call did not resolve.
     *
     * <p>A different failure from {@link #UNBILLABLE_GENERATION}, which is
     * reached with a context in hand that simply carries no run and no chat to
     * charge against. The remedy a caller can apply is the same, so the code is
     * the same; the sentence differs because the two are separate guards, in
     * separate classes, and each has to be provable on its own.
     */
    public static final String UNBILLABLE_GENERATION_UNIDENTIFIED_CALL =
            "PLATFORM_NOT_AVAILABLE: this generation could not be identified well enough to charge it (the "
                    + "endpoint or the account behind this call did not resolve), so it is not available on "
                    + "the platform key. Pass credential_source='user' to run it on a provider key you "
                    + "configured yourself, which the platform never charges for. Sending the same call "
                    + "again does not change this.";

    static final String UNPRICEABLE_GENERATION =
            "PLATFORM_NOT_AVAILABLE: this generation could not be priced on this call, so it is not "
                    + "available on the platform key. This is usually temporary, so running it again may "
                    + "succeed. Pass credential_source='user' to run it now on a provider key you configured "
                    + "yourself, which the platform never charges for.";

    public CatalogToolBillingService(CreditConsumptionClient creditClient,
                                      CredentialClient credentialClient,
                                      ApiToolRepository apiToolRepository,
                                      ApiRepository apiRepository,
                                      @Value("${credentials.platform.markup.enabled:true}") boolean markupEnabled) {
        this.creditClient = creditClient;
        this.credentialClient = credentialClient;
        this.apiToolRepository = apiToolRepository;
        this.apiRepository = apiRepository;
        this.markupEnabled = markupEnabled;
    }

    /**
     * Pre-flight reservation. Called BEFORE the upstream HTTP call. Returns
     * a {@link PreflightDecision} carrying:
     * <ul>
     *   <li>{@code allowed=true, sourceId, finalCharge=projected}: catalog
     *       proceeds to upstream call. Catalog MUST call {@link #commitOnSuccess}
     *       or {@link #releaseOnFailure} after the call.</li>
     *   <li>{@code allowed=true, sourceId=null}: no billing applies (BYOK,
     *       bridge, markup off, or an ORDINARY endpoint with no billing scope).
     *       Catalog proceeds without a tracked reservation.</li>
     *   <li>{@code allowed=false}: refuse the call. Catalog returns 402 to
     *       caller with the {@code error} message ("account delinquent - top up
     *       to resume" / "Insufficient credits") and {@code delinquent} flag
     *       so the chat UI can display the right banner.</li>
     * </ul>
     */
    public PreflightDecision preflightReserve(BillingScope scope) {
        if (!markupEnabled) {
            // Markup off is the CE (self-hosted) posture, and it is a
            // deliberate one: a CE install's "platform" credential is the
            // operator's OWN provider key, there is no reseller margin and no
            // credit ledger to reserve against, so nothing is being resold and
            // there is nothing to fail closed on. Refusing here would break
            // every generation on every self-hosted install. A CE install that
            // consumes the CLOUD catalog does not reach this branch at all: the
            // whole execution is relayed to the cloud, which runs with markup
            // ON and bills the linked account there.
            return PreflightDecision.allowedWithoutBilling();
        }
        if (!shouldBill(scope)) {
            // Nothing to bill on this call. For an ordinary endpoint that is
            // the end of it, and that is the behaviour of the 1000+ catalog
            // tools that carry no published price.
            //
            // For a RESOLD generation it is the hole this guard closes. The
            // way in is not exotic: a caller with no run and no chat stream
            // (an `lc_live_` API key on the MCP surface) produces a scope with
            // no coordinates, every billing branch below is skipped, and the
            // owner's paid provider quota is spent with no ledger row at all.
            // The provider charges the owner for that video either way, so the
            // only safe answer is to refuse before it is produced.
            if (requiresBillingToProceed(scope)) {
                log.warn("Refusing generation tool {} under platform credential {}: the call carries no "
                                + "billing scope (kind={}, id={}, user={}), so it cannot be charged.",
                        scope.toolSlug(), scope.platformCredentialId(),
                        scope.scopeKind(), scope.scopeId(), scope.userId());
                return PreflightDecision.refused(UNBILLABLE_GENERATION, false);
            }
            return PreflightDecision.allowedWithoutBilling();
        }

        Optional<UUID> apiToolUuid = resolveApiToolUuid(scope.toolSlug());
        if (apiToolUuid.isEmpty()) {
            // Not knowing which endpoint this is means not knowing whether it is
            // a generation, so every guard below is skipped and the call goes
            // out free. For an ordinary endpoint that is correct and is what the
            // 1000+ unpriced catalog tools do. For a generation it is the whole
            // hole again, reached by an ordinary accident rather than an exotic
            // one: resolveApiToolUuid swallows its own exceptions, so one
            // transient catalog-database fault dispatches a video on the owner's
            // provider key with no reservation and no ledger row.
            //
            // The call itself already proves what it is. Naming a generation
            // model is something only the generation surface does, and it needs
            // no lookup to read, so it stands in for the descriptor exactly when
            // the descriptor cannot be reached.
            if (scope.generationModelId() != null && chargesThePlatform(scope)) {
                log.warn("Refusing generation model {} on tool {}: its catalog endpoint could not be "
                                + "resolved, so the call cannot be priced. Refusing rather than "
                                + "dispatching it free on the platform provider key.",
                        scope.generationModelId(), scope.toolSlug());
                return PreflightDecision.refused(UNPRICEABLE_GENERATION, false);
            }
            log.debug("No catalog UUID for slug={} - skipping billing", scope.toolSlug());
            return PreflightDecision.allowedWithoutBilling();
        }

        // V428: pass the generation model and the size of the call so auth
        // resolves the price of THIS request. One endpoint can back several
        // models at different rates, and a generation's price usually scales
        // with the call, so quoting the endpoint alone would reserve an amount
        // the customer is never charged.
        Optional<ResolvedScopeMarkupDto> resolved = credentialClient.resolveScopeMarkupRate(
                scope.scopeKind(), scope.scopeId(), scope.userId(),
                scope.platformCredentialId(), apiToolUuid.get(),
                scope.generationModelId(), scope.generationQuantity(),
                // What this call's own choices do to the published rate. Null
                // for every ordinary endpoint and for a generation whose model
                // declares no modifiers, and auth then resolves exactly the
                // amount it did before.
                scope.generationPriceMultiplier());
        if (resolved.isEmpty() || resolved.get().getEffectiveMarkup() == null
                || resolved.get().getEffectiveMarkup().signum() <= 0) {
            // An ordinary tool priced at zero is a deliberate admin choice, so
            // it proceeds free.
            //
            // A GENERATION endpoint priced at zero is not a choice, it is a
            // hole: every call spends the platform owner's own provider quota
            // on a paid third-party API. The two ways to get here are both
            // ordinary mistakes rather than exotic ones - the platform key was
            // provisioned after the catalog import so no version was ever
            // published, or the endpoint carries per-model rows only and is
            // reached without naming a model (an mcp: node pointed straight at
            // the submit endpoint). Either way, giving it away silently is the
            // single outcome this feature exists to prevent, so it fails
            // CLOSED. Same posture, and the same error code, as the CE relay.
            if (isResoldGeneration(scope, apiToolUuid.get())) {
                // The size branch requires a price to have RESOLVED. It reads
                // that price's unit to name what the caller must state, so with
                // nothing published it threw NoSuchElementException: a crash
                // where a refusal belongs, on a fail-closed path whose whole
                // job is to answer. Both facts can be true at once (a per-model
                // endpoint reached without a model, on a credential that has no
                // version), and when they are, the missing PRICE is the one the
                // caller cannot do anything about, so it is the one to report.
                if (resolved.isPresent() && sizeWasNeverStated(scope)) {
                    // A DIFFERENT problem with a different fix, and the one that
                    // used to be reported as the administrator's. The price is
                    // published and correct; what is missing is how big the call
                    // is, which is the caller's to supply and nobody else's.
                    log.warn("Refusing generation model {} on tool {}: the call carries no size, so a "
                                    + "per-unit price resolves to nothing. The caller has to state it.",
                            scope.generationModelId(), scope.toolSlug());
                    return PreflightDecision.refused(
                            generationSizeUnknown(scope, resolved.get().getPriceUnit()), false);
                }
                log.warn("Refusing generation tool {} under platform credential {}: no positive price "
                                + "resolved (model={}). Publish a price for this endpoint, or for the "
                                + "model being called, before it can be sold.",
                        scope.toolSlug(), scope.platformCredentialId(), scope.generationModelId());
                return PreflightDecision.refused(
                        "PLATFORM_NOT_AVAILABLE: this generation is not sold on the platform key, "
                                + "because no price is published for it"
                                + (scope.generationModelId() == null
                                        ? ". Name a generation model when you run it, so the model's own "
                                          + "price applies"
                                        : " (model '" + scope.generationModelId() + "')")
                                + ". Pass credential_source='user' to run it on a provider key you "
                                + "configured yourself, which the platform never charges for. "
                                + "Publishing a price is the platform owner's decision and nothing you "
                                + "send can trigger it.",
                        false);
            }
            log.debug("No effective markup for scope={}/{} cred={} tool={} - proceeding free",
                    scope.scopeKind(), scope.scopeId(), scope.platformCredentialId(), scope.toolSlug());
            return PreflightDecision.allowedWithoutBilling();
        }
        // A POSITIVE price is not by itself proof the call can be priced. When
        // the row charges per unit and the call never said how many, auth bills
        // ONE unit: a floor that is right for the ordinary endpoints it was
        // written for (they have no notion of a size and would otherwise bill
        // the base alone, which for a pure per-unit rate is zero), and wrong for
        // a resold generation, where a size exists and simply did not travel.
        // The realistic way in is an mcp: node pointed straight at the submit
        // endpoint of a generation that carries an endpoint-wide per-second row:
        // ten seconds of video resolve to one second of price, positively, so
        // the fail-closed rule above never fires and the platform owner eats the
        // other nine. Undercharging silently is worse than the refusal that rule
        // was written to produce, so this is refused too, naming the missing
        // parameter rather than blaming a price that is published and correct.
        // A positive price is also not proof the owner priced THIS endpoint. A
        // credential carries a version-wide default for the ordinary calls of
        // an API, where one call costs about what the next one does. A
        // generation on that same key can cost dollars of provider money per
        // call, so inheriting the catch-all sells a video at the price of a
        // weather lookup, and does it silently because the amount is positive
        // and every guard above is satisfied. The owner never made that choice;
        // they made a choice about the rest of the API. Refuse for the same
        // reason an unpriced generation is refused, and say which decision is
        // missing so it is actionable rather than mysterious.
        if (isResoldGeneration(scope, apiToolUuid.get()) && resolved.get().isKnownToBeVersionDefault()) {
            log.warn("Refusing generation tool {} (model={}): its amount came from the credential-wide "
                            + "default on platform credential {}, not from a price published for this "
                            + "endpoint. A generation is not sold on a catch-all.",
                    scope.toolSlug(), scope.generationModelId(), scope.platformCredentialId());
            return PreflightDecision.refused(
                    "PLATFORM_NOT_AVAILABLE: this generation is not sold on the platform key, because "
                            + "the only price that covers it is the credential-wide default rather than "
                            + "a price published for this generation"
                            + (scope.generationModelId() == null
                                    ? ". Name a generation model when you run it, so the model's own "
                                      + "price applies"
                                    : " (model '" + scope.generationModelId() + "')")
                            + ". Pass credential_source='user' to run it on a provider key you "
                            + "configured yourself, which the platform never charges for. Publishing a "
                            + "price is the platform owner's decision and nothing you send can trigger it.",
                    false);
        }
        // A positive price is not proof the price and the call are talking about
        // the same thing. The dimension guard on publishing only arms on a
        // RE-publish (there is nothing to compare a first row against), so an
        // administrator can publish "per image" on a model measured in seconds.
        // Both halves then look ordinary on their own: a rate, and a bare 10.
        // Multiplied, a ten second clip is charged ten times the per-image rate,
        // and the run reports billed_unit "second" next to a per-image price.
        // This is the only place both units exist, so it is the only place the
        // contradiction can be seen.
        if (isResoldGeneration(scope, apiToolUuid.get())
                && dimensionsDisagree(resolved.get(), scope.generationQuantityUnit())) {
            log.warn("Refusing generation tool {} (model={}): published per {} but this call is measured "
                            + "in {}, so the two cannot be multiplied.",
                    scope.toolSlug(), scope.generationModelId(),
                    resolved.get().getPriceUnit(), scope.generationQuantityUnit());
            return PreflightDecision.refused(
                    "PLATFORM_NOT_AVAILABLE: this generation is priced per "
                            + resolved.get().getPriceUnit() + ", but a call on this model is measured in "
                            + scope.generationQuantityUnit() + ", so the published price cannot be applied "
                            + "to it. Pass credential_source='user' to run it on a provider key you "
                            + "configured yourself, which the platform never charges for. Correcting the "
                            + "published price is the platform owner's decision and nothing you send can "
                            + "trigger it.",
                    false);
        }
        if (unitPricedWithoutASize(resolved.get(), scope) && isResoldGeneration(scope, apiToolUuid.get())) {
            log.warn("Refusing generation tool {} (model={}): the published price is per {} and this call "
                            + "carries no size, so it would be billed a single unit instead of what it "
                            + "actually asks the provider for.",
                    scope.toolSlug(), scope.generationModelId(), resolved.get().getPriceUnit());
            return PreflightDecision.refused(
                    generationSizeUnknown(scope, resolved.get().getPriceUnit()), false);
        }

        BigDecimal projected = resolved.get().getEffectiveMarkup();

        Long pinId = resolved.get().getPinId();
        // scope.callRef() is what makes THIS call its own ledger row. The ledger
        // is keyed on sourceId and answers "already reserved, zero debit" for a
        // key it has seen, so a sourceId that repeats inside a scope is not a
        // duplicate-protection, it is a free call. See BillingScope.callRef().
        String sourceId = SourceIdBuilder.markupDebitChat(
                scope.scopeId(), scope.toolSlug(), scope.callRef());
        // For RUN scope with stepId info, prefer the workflow per-call key so
        // analytics group correctly. Fall back to chat shape for STREAM.
        if ("RUN".equals(scope.scopeKind()) && scope.stepId() != null) {
            sourceId = SourceIdBuilder.markupDebitWithCall(
                    scope.scopeId(), scope.stepId(), scope.callRef());
        }

        // hasExistingPin tells the auth-side delinquent gate "the user already
        // has a live pin for this scope+credential - this is an in-flight call,
        // not a fresh request". For RUN: workflow run-init creates the pin
        // eagerly, so a pin nearly always exists by the time per-step calls
        // arrive. For STREAM: chat call #1 creates the pin lazily, so call #1
        // is "fresh" (no pin yet) and would be refused while delinquent.
        // We do an honest existence check rather than hardcoding by scopeKind
        // so the auth-side gate gets accurate info - its policy then decides
        // whether to bypass (RUN+pin) or refuse (anything else when delinquent).
        boolean hasExistingPin;
        try {
            hasExistingPin = credentialClient.existsScopePin(
                    scope.scopeKind(), scope.scopeId(), scope.platformCredentialId());
        } catch (Exception e) {
            log.warn("existsScopePin failed for scope={}/{} cred={}: {} - fail-open false",
                    scope.scopeKind(), scope.scopeId(), scope.platformCredentialId(), e.getMessage());
            hasExistingPin = false;
        }

        CreditConsumptionClient.ScopeReserveResult result = creditClient.scopeReserve(
                scope.userId(), sourceId, scope.provider(), scope.model(),
                projected, pinId, scope.ttlMinutes(),
                scope.scopeKind(), scope.scopeId(), hasExistingPin);

        if (!result.success()) {
            if (callerHasTheirOwnKey(scope)) {
                // This caller was never going to be charged for this call. The
                // reservation is taken on the ASSUMPTION that the platform key
                // will answer, because the agentic path cannot know before the
                // call which pool answers; but that path tries the caller's own
                // credential FIRST, and this caller has one. Turning "your
                // balance is empty" into a 402 here would block a user who is
                // paying their own provider directly, on every one of the 1000+
                // endpoints that carry a published markup.
                //
                // The relaxation is only sound while its own premise holds -
                // "the platform key is merely a fallback here" - and BEFORE the
                // call that premise is a hope, not a fact. The agentic path
                // falls back to the platform credential whenever the caller's
                // own one fails (a revoked key answering 401 is the ordinary
                // case, not an exotic one), and it stamps the response
                // credentialSource="platform". A caller with zero credits and a
                // dead key of their own would therefore have had a resold
                // generation produced on the platform's provider quota, with no
                // reservation to commit and no ledger row at all - repeatable
                // for as long as they cared to repeat it.
                //
                // So the decision is not left to which keys EXIST: the call is
                // pinned to the caller's own pool for the rest of its life
                // (restrictToCallersOwnKey), which removes the platform
                // fallback entirely. Only the caller's key can answer, so
                // "unbilled" is decided by which key ANSWERED. When their key
                // fails the call now fails, which is the correct outcome: no
                // asset is produced, and no third party bills the platform
                // owner for one. Every call where their key works behaves
                // exactly as it did before - that pool was tried first anyway.
                log.info("Reservation refused for scope={}/{} tool={} ({}), but the caller has their own "
                                + "credential for '{}' - pinning the call to their key (no platform "
                                + "fallback) and proceeding unbilled",
                        scope.scopeKind(), scope.scopeId(), scope.toolSlug(), result.error(),
                        integrationOf(scope));
                return PreflightDecision.allowedOnCallersOwnKey();
            }
            log.warn("Reservation refused for scope={}/{} cred={} tool={} - error={} delinquent={} "
                            + "- the call is NOT dispatched",
                    scope.scopeKind(), scope.scopeId(), scope.platformCredentialId(), scope.toolSlug(),
                    result.error(), result.delinquent());
            return PreflightDecision.refused(withOwnKeyRemedy(result.error()), result.delinquent());
        }
        return PreflightDecision.allowedWithReservation(sourceId, projected);
    }

    /**
     * True when the size of a per-unit generation call was never stated, which
     * is why its price resolved to nothing.
     *
     * <p>This is a different failure from "the platform does not sell this", and
     * it has a different fix: the caller supplies the size, nobody publishes
     * anything. Distinguished by the two facts this service actually has - a
     * model WAS named (so a per-model price row was looked up) and the call
     * carries no positive quantity for it.
     */
    private static boolean sizeWasNeverStated(BillingScope scope) {
        if (scope.generationModelId() == null) return false;
        return sizeMissing(scope);
    }

    /**
     * True when the resolved price row charges per unit and this call carries no
     * size for it, so the amount that came back is the one-unit floor rather
     * than the price of the call.
     *
     * <p>Read from the row the amount actually came from, not from the seed
     * descriptor: an administrator can republish an endpoint from flat to
     * per-unit and back, and only the published row knows which it is today.
     */
    /**
     * Whether the published rate and the measurement describe different things.
     *
     * <p>Answers FALSE whenever it cannot tell, which is every case that is not
     * a real contradiction: no measured unit (an ordinary endpoint, or a caller
     * older than this field), no published unit, a flat price (a per-call rate
     * applies to a call of any size, which is what flat means), or a unit
     * neither side recognises. A guard that refuses on ignorance would take out
     * traffic that was fine, and the amount is checked by three other rules
     * either side of this one.
     */
    private static boolean dimensionsDisagree(ResolvedScopeMarkupDto resolved, String measuredUnit) {
        return !resolved.canPriceMeasurementIn(measuredUnit);
    }

    private static boolean unitPricedWithoutASize(ResolvedScopeMarkupDto resolved, BillingScope scope) {
        BigDecimal unitRate = resolved.getUnitCredits();
        if (unitRate == null || unitRate.signum() <= 0) return false;
        return sizeMissing(scope);
    }

    /**
     * The size of this call, as far as billing is concerned, was not stated.
     *
     * <p>Null and non-positive are the same statement here even though they are
     * different statements in the price resolver: a generation of zero seconds
     * or zero images is not a call anyone meant to make, and the generation
     * surface never produces one (it refuses first, naming the parameter). So
     * both mean "nobody said how big this is".
     */
    private static boolean sizeMissing(BillingScope scope) {
        BigDecimal quantity = scope.generationQuantity();
        return quantity == null || quantity.signum() <= 0;
    }

    /**
     * The one refusal text for "this generation is priced by its size and the
     * size never arrived", used by both places that can detect it, so they
     * cannot drift into saying different things about the same problem.
     *
     * <p>Names the model when one was given. When none was, the call came from
     * outside the generation surface, so the remedy is to use that surface
     * rather than to add a parameter to the raw endpoint.
     */
    private static String generationSizeUnknown(BillingScope scope, String priceUnit) {
        String unit = priceUnit == null || priceUnit.isBlank() ? "unit" : priceUnit;
        if (scope.generationModelId() == null) {
            return "GENERATION_SIZE_UNKNOWN: this generation is priced per " + unit + ", and this call "
                    + "did not say how big it is, so it cannot be priced. Name the model and the size: "
                    + "on a workflow node, set 'model' plus duration_seconds (video, music, sound) or n "
                    + "(images); in a chat, generation(action='create', model=...) measures the call for "
                    + "you. The model ids and what each costs are in workflow(action='help', "
                    + "topics=['generate']), which is free to read and needs no tool you may not hold. "
                    + "Nothing was charged and nothing was generated.";
        }
        return "GENERATION_SIZE_UNKNOWN: model '" + scope.generationModelId()
                + "' is priced by the size of each call, and this call did not say "
                + "how big it is, so it cannot be priced. State the size and run it "
                + "again: duration_seconds for video, music and sound, n for a number "
                + "of images. What this model accepts is in workflow(action='help', "
                + "topics=['generate']), a free read; the generation tool's "
                + "action='models' says the same with its limits, if you hold it.";
    }

    /**
     * True when the caller has their OWN credential for this integration, so a
     * call they cannot pay for on the platform key is not a call they cannot
     * make.
     *
     * <p>Only the agentic path qualifies. It resolves the caller's credential
     * first and only falls back to the platform's, so a caller who has one is
     * not spending platform money. A caller who explicitly asked for the
     * platform key ({@code credentialSource="PLATFORM"}) is a different
     * statement and is still refused: they asked for the pool they cannot pay
     * for, and silently running them on another one would hide it.
     *
     * <p>Keyed on the API's icon slug, which is the same key the catalog's own
     * credential pre-flight uses to ask "has this user connected this service",
     * and fails CLOSED: a lookup error answers "no own key", so an auth-service
     * blip refuses rather than handing out the platform key unbilled.
     */
    private boolean callerHasTheirOwnKey(BillingScope scope) {
        if (scope.credentialSource() != null) return false;
        String integration = integrationOf(scope);
        if (integration == null || scope.userId() == null) return false;
        try {
            return credentialClient.getConfiguredIntegrations(String.valueOf(scope.userId()))
                    .stream()
                    .anyMatch(integration::equalsIgnoreCase);
        } catch (Exception e) {
            log.warn("Could not check whether user {} has their own credential for '{}': {} - refusing",
                    scope.userId(), integration, e.getMessage());
            return false;
        }
    }

    /** Integration a caller's own credential for this endpoint would be filed under. */
    private String integrationOf(BillingScope scope) {
        String toolSlug = scope.toolSlug();
        if (toolSlug == null || !toolSlug.contains("/")) return null;
        try {
            return apiRepository.findByApiSlug(toolSlug.substring(0, toolSlug.indexOf('/')))
                    .map(ApiEntity::getIconSlug)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Could not resolve the integration of {}: {}", toolSlug, e.getMessage());
            return null;
        }
    }

    /**
     * Add the one remedy a caller can apply themselves to a refusal about their
     * balance. "Top up" is the account owner's move and can be days away; using
     * a provider key of their own takes effect on the next call, and the
     * platform charges nothing for it.
     */
    private static String withOwnKeyRemedy(String error) {
        String message = error == null || error.isBlank() ? "Insufficient credits" : error;
        return message + " If you have your own key for this provider, run the call with "
                + "credential_source='user': the platform bills nothing for it.";
    }

    /** Outcome of a commit for a call that reserved nothing: no row was written, none was charged. */
    public static final String NOTHING_RESERVED = "NOTHING_RESERVED";

    /**
     * Post-flight commit. Catalog calls after a successful upstream response.
     * Idempotent - duplicate calls return {@code ALREADY_COMMITTED}.
     *
     * @param sourceId    the value from {@link PreflightDecision#sourceId()}
     * @param actualAmount actual credits consumed; pass the projected from
     *                     preflight when there's no per-call adjustment, or a
     *                     smaller amount when partial result reduces cost
     */
    public String commitOnSuccess(String sourceId, BigDecimal actualAmount, String provider, String model) {
        // Nothing was reserved, so nothing was committed - which is its own answer, and neither a
        // success nor "this deployment does not meter". It gets its own word for the reason this
        // whole area was reworked: commitTookTheWholeAmount reads this string to decide whether an
        // amount may be shown as CHARGED, and every state that did not debit a ledger row has to be
        // distinguishable from the one that did. Unreachable today (the caller short-circuits on a
        // null source id first) and left correct anyway, because the reason it is unreachable lives
        // in another class.
        if (sourceId == null) return NOTHING_RESERVED;
        return creditClient.scopeCommit(sourceId, actualAmount, provider, model);
    }

    /**
     * Whether THIS commit took the whole amount that was reserved.
     *
     * <p>Two outcomes answer true, and the line between them and the rest is drawn on what the
     * ledger row ends up holding, not on how alarming the outcome sounds. A COMMITTED commit
     * charges the amount it was handed. A FLOORED one is louder - the balance had already gone
     * negative through a concurrent debit, and the account is marked delinquent - but it charges
     * {@code reserved} exactly, which is the figure in hand, so refusing to state it would drop a
     * price that was really paid. A PARTIAL commit is the one that charges LESS (the balance could
     * not cover the whole reservation) and the reduced figure never comes back here, so the
     * reserved amount would overstate it. An expired reservation charged nothing at all,
     * and {@code BILLING_DISABLED} means no ledger was even reached: both the credit client and the
     * auth service answer with that word rather than a success one precisely so that this method can
     * tell "charged" from "not metered" without knowing either deployment's configuration.
     *
     * <p><b>{@code ALREADY_COMMITTED} answers false, and that is deliberate.</b> It is the honest
     * answer to "was the whole amount taken", because this caller cannot tell WHICH commit it is
     * repeating: a partial or floored commit leaves the row as an ordinary committed one, so a
     * retry over a charge that took less than was reserved comes back as ALREADY_COMMITTED, and
     * reporting the reserved figure then re-opens the overstatement the partial case is refused
     * for. The cost of the narrow reading is that a repeated commit shows no price on the asset,
     * which is the same outcome as a generation on the reader's own key and states nothing untrue.
     * A source id is minted per dispatch, so this is a retry of one call, never a second purchase.
     *
     * <p>Lives here, next to the commit it reads, because two callers need the same answer: the
     * ordinary execution path and the CE relay, which reserves and commits on its own. Written
     * twice, one of them would eventually report the reserved figure for a partial charge, and a
     * price that is wrong on the high side is the one a reader disputes.
     */
    public static boolean commitTookTheWholeAmount(String commitOutcome) {
        return "COMMITTED".equals(commitOutcome) || "COMMITTED_FLOORED".equals(commitOutcome);
    }

    /**
     * Failure / partial-result release. Catalog calls when the upstream call
     * fails or yields fewer items than projected. Idempotent.
     */
    public String releaseOnFailure(String sourceId, String reason) {
        if (sourceId == null) return "RELEASED";
        return creditClient.scopeRelease(sourceId, reason);
    }

    /**
     * True when this endpoint is something the platform RESELLS: it carries a
     * generation descriptor, so every call spends the owner's own quota on a
     * paid third-party API.
     *
     * <p>Read from the catalog row rather than inferred from the presence of a
     * generation model on the call, because the dangerous case is precisely the
     * one where no model was named.
     *
     * <p>Fails OPEN on a lookup error. This runs before every catalog call, and
     * a database blip must not refuse ordinary traffic; the audit trail is the
     * warn below plus the reservation that does not happen.
     */
    /**
     * Whether this call is a resold generation, asked in the way that survives
     * the catalog being briefly unreadable.
     *
     * <p>The descriptor is the definition, but reading it takes a lookup, and
     * that lookup fails open by design so a database blip cannot stop the 1000+
     * ordinary endpoints. Failing open there also lets a generation through
     * free, which is the one outcome this whole feature exists to prevent.
     *
     * <p>Naming a generation model settles it without any lookup at all: only
     * the generation surface sends one. So a call that names a model is a
     * generation even when nothing can be read, and a call that does not still
     * gets the lenient descriptor answer.
     */
    private boolean isResoldGeneration(BillingScope scope, UUID apiToolId) {
        if (scope != null && scope.generationModelId() != null) {
            return true;
        }
        return isResoldGeneration(apiToolId);
    }

    private boolean isResoldGeneration(UUID apiToolId) {
        try {
            return apiToolRepository.findById(apiToolId)
                    .map(ApiToolEntity::isGeneration)
                    .orElse(false);
        } catch (Exception e) {
            log.warn("Could not determine whether tool {} is a generation endpoint: {} - not refusing",
                    apiToolId, e.getMessage());
            return false;
        }
    }

    /**
     * True when this call MUST be billed in order to go out at all: it targets
     * an endpoint the platform RESELLS (it carries a generation descriptor), it
     * runs on the platform's own provider key, and this deployment resells
     * (markup enabled).
     *
     * <p>For such a call "billing could not be established" is not a reason to
     * proceed free, it is a reason to refuse: the third-party provider charges
     * the platform owner for the asset whether or not a ledger row was ever
     * written. Every OTHER call answers false here, which is what keeps the
     * 1000+ ordinary catalog endpoints on exactly the behaviour they have today.
     *
     * <p>Public because the same question has to be asked from the one place
     * that can see a failure this service never returns: an exception thrown
     * out of {@link #preflightReserve} itself. Both callers must reach the same
     * verdict from the same rule, hence one method rather than two notions of
     * "is this a generation".
     *
     * <p>False when the caller owns the charge. The rule refuses a call that
     * NOBODY can bill; a relayed generation is billed by the relay, which
     * reserved before dispatching it and refuses outright without a strictly
     * positive markup. Answering true there would refuse a call that has already
     * been paid for, which is what a cloud-linked CE install saw as a bare
     * "Tool execution failed" on every relayed generation.
     */
    public boolean requiresBillingToProceed(BillingScope scope) {
        if (!chargesThePlatform(scope)) return false;
        // Asked BEFORE the lookup, not inside it. A named model settles the
        // question on its own, and putting the shortcut inside the mapping made
        // it unreachable in the one case it exists for: when the endpoint
        // cannot be resolved, an empty Optional maps to nothing and this
        // answered false, so a generation with no billing scope went out free
        // on the platform key. The sibling guard on the reserve path already
        // asked it this way round; this one did not.
        if (scope.generationModelId() != null) return true;
        return resolveApiToolUuid(scope.toolSlug())
                .map(uuid -> isResoldGeneration(scope, uuid))
                .orElse(false);
    }

    /**
     * Whether a generation must be REFUSED when no billing scope could be built
     * for it at all.
     *
     * <p>Every guard in this class reads a {@link BillingScope}. When one
     * cannot be built (an unresolvable composite slug, a user id that is not a
     * number) the call currently proceeds unbilled, which is right for the
     * ordinary catalog endpoints that carry no price and wrong for a
     * generation, where it dispatches the owner's paid provider key with no
     * reservation and no ledger row. This answers the same question from the
     * few facts that are available without a scope.
     *
     * <p>Naming a generation model is the fact that carries it: only the
     * generation surface sends one, and it needs no lookup to read.
     *
     * @param credentialSource what the caller asked to run on
     * @param generationModelId the model being priced, null for an ordinary tool
     * @param billingOwnedByCaller true when the CE relay already reserved this call
     */
    public boolean generationRequiresAScope(String credentialSource, boolean isGeneration,
                                             boolean billingOwnedByCaller) {
        if (!markupEnabled) return false;
        // Takes the ANSWER, not one of the two facts it can be derived from.
        // This used to accept a model id, which only the generation surface
        // sends, so the same endpoint reached by raw slug walked past it: the
        // identical half-a-signal mistake as its two siblings above.
        if (!isGeneration) return false;
        if (billingOwnedByCaller) return false;
        return !"USER".equalsIgnoreCase(credentialSource);
    }

    /**
     * Whether the PLATFORM is the one paying the provider for this call.
     *
     * <p>Everything that fails closed here fails closed only in that case. On
     * the user's own key the platform buys nothing and charges nothing, and
     * with markup off (CE) the operator's key is their own, so refusing would
     * take away a call that costs nobody anything.
     *
     * <p>Deliberately answerable WITHOUT resolving the endpoint, so a guard
     * that runs because the endpoint could not be resolved can still ask it.
     */
    private boolean chargesThePlatform(BillingScope scope) {
        if (!markupEnabled) return false;
        if (!sellableOnPlatformKey(scope)) return false;
        return !scope.billingOwnedByCaller();
    }

    /**
     * Whether this call can spend the platform owner's provider quota at all.
     * BYOK (the user's own key) and the CE bridge (which does its own internal
     * accounting) never can, so neither is billed and neither is ever refused.
     */
    private static boolean sellableOnPlatformKey(BillingScope scope) {
        if (scope == null) return false;
        if ("USER".equalsIgnoreCase(scope.credentialSource())) return false;
        return !"bridge".equalsIgnoreCase(scope.providerKind());
    }

    private boolean shouldBill(BillingScope scope) {
        if (!sellableOnPlatformKey(scope)) return false;
        if (scope.billingOwnedByCaller()) {
            // Not "nothing is charged for this call": something already is, one
            // frame up, by the caller that reserved before dispatching it. A
            // second reservation here would bill the same generation twice.
            log.debug("Catalog billing skipped for tool {}: the caller owns the charge for this call",
                    scope.toolSlug());
            return false;
        }
        if (scope.scopeKind() == null || scope.scopeId() == null) {
            // Fail-OPEN for an ordinary endpoint, which is the honest word for
            // it: with no run and no chat stream there is nothing to reserve
            // against, and refusing every scope-less catalog call would take
            // the MCP surface down. A resold generation is the exception and is
            // refused by the caller above, which is where the fail-CLOSED
            // decision actually lives.
            log.warn("Catalog billing skipped: the call carries no billing scope (toolSlug={}, userId={}) "
                            + "- an ordinary endpoint proceeds unbilled, a resold generation is refused",
                    scope.toolSlug(), scope.userId());
            return false;
        }
        if (scope.platformCredentialId() == null || scope.userId() == null) {
            return false;
        }
        return true;
    }

    /**
     * Resolve a composite slug like {@code "openai/openai-create-image"} to the
     * tool UUID. Splits on the first slash; left part is the API slug, right
     * part is the tool slug. Lookups are local to catalog-service so this
     * stays a fast O(2) DB hit (both indexed by slug).
     *
     * <p>Returns empty for slugs that don't fit the pattern (already-UUID,
     * non-API tools like {@code crud/*}, etc.) - caller treats as
     * "not billable" and skips.
     */
    private Optional<UUID> resolveApiToolUuid(String toolSlug) {
        if (toolSlug == null || !toolSlug.contains("/")) return Optional.empty();
        try {
            int slash = toolSlug.indexOf('/');
            String apiSlug = toolSlug.substring(0, slash);
            String toolSlugTail = toolSlug.substring(slash + 1);
            Optional<ApiEntity> api = apiRepository.findByApiSlug(apiSlug);
            if (api.isEmpty()) return Optional.empty();
            return apiToolRepository.findByApiIdAndToolSlug(api.get().getId(), toolSlugTail)
                    .map(ApiToolEntity::getId);
        } catch (Exception e) {
            log.warn("Slug → UUID resolution failed for slug={}: {}", toolSlug, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Catalog billing context - built by the caller from the catalog request +
     * resolved credential metadata. Single record passed end-to-end so the
     * billing service has everything it needs without reaching into Spring
     * security context or thread-locals.
     *
     * <p>{@code ttlMinutes} convention:
     * <ul>
     *   <li>15 - per-step short LLM call (configurable via
     *       {@code markup.reserve.default-ttl-minutes})</li>
     *   <li>30 - catalog tool call ({@code ToolExecutionManager.RESERVE_TTL_MINUTES}):
     *       10 min upstream read timeout + 10 min async-poll ceiling + slack</li>
     *   <li>60 - long-running step (browser-agent, classify)</li>
     *   <li>1440 - workflow run-init reserve (24h)</li>
     * </ul>
     * TTL must be ≥ upstream hard timeout + safety. Validation enforced in
     * auth-side {@code tryReserveMarkup}.
     */
    public record BillingScope(
            Long userId,
            String credentialSource,    // "USER" | "PLATFORM" | null
            Long platformCredentialId,
            String providerKind,        // "cloud" | "bridge"
            String provider,
            String model,
            String toolSlug,            // catalog kebab slug, e.g. "openai/openai-create-image"
            String scopeKind,           // "RUN" | "STREAM" | null
            String scopeId,             // runId or streamId
            String stepId,              // workflow step id (RUN only)
            /**
             * SERVER-GENERATED reference for THIS dispatched call, and the only
             * thing that separates the second generation in a scope from the
             * first. The ledger's unique index on {@code source_id} turns a
             * repeated key into "already reserved, zero debit", so a callRef
             * that repeats within a run or a chat turn does not deduplicate a
             * retry, it hands out a free generation. Minted by the producer
             * ({@code ToolExecutionManager}) once per dispatched call; it must
             * never become a value a caller can send, because it decides
             * whether the call is charged.
             */
            String callRef,
            int ttlMinutes,
            /** V428: generation model priced for this call, null for an ordinary tool. */
            String generationModelId,
            /** V428: billable quantity in the model's price unit, null when the price is flat. */
            java.math.BigDecimal generationQuantity,
            /**
             * The PLATFORM unit {@code generationQuantity} is counted in.
             * Carried so the published rate can be checked against it: without
             * it a row priced per image and a call measured in seconds are
             * indistinguishable, both being a bare number.
             */
            String generationQuantityUnit,
            /**
             * What the CHOICES in this call do to its price: 2 for a render sold
             * at twice the model's published rate, 1.2 for one carrying two
             * reference images priced at a tenth each. 1 (or null, read as 1)
             * for a call at the published rate, which is every ordinary tool and
             * every generation whose model declares no modifiers.
             *
             * <p>Separate from {@link #generationQuantity} because it answers a
             * different question. The quantity is how BIG the call is and is
             * reported back to the customer as such; this is what that size
             * costs. Folding one into the other would have a ten second clip
             * report twenty seconds of video because it was rendered at 1080p.
             *
             * <p>Derived server-side from parameters already validated against
             * the model, exactly like the quantity, and for the same reason: a
             * factor a caller could state is a factor a caller would state as
             * zero.
             */
            java.math.BigDecimal generationPriceMultiplier,
            /**
             * The caller around this execution has already reserved the charge
             * and will settle it itself, so this layer must neither charge again
             * nor refuse for lack of a scope. Set only by
             * {@code CeCatalogRelayService}; see
             * {@code ToolExecutionRequest.billingOwnedByCaller} for why it can
             * never arrive from the wire.
             */
            boolean billingOwnedByCaller
    ) {
        /** Apply RUN-priority: if runId is present, force scopeKind=RUN and clear streamId. */
        public static BillingScope of(Long userId, String credentialSource, Long platformCredentialId,
                                       String providerKind, String provider, String model, String toolSlug,
                                       String runId, String streamId, String stepId,
                                       String callRef,
                                       int ttlMinutes) {
            return of(userId, credentialSource, platformCredentialId, providerKind, provider, model,
                    toolSlug, runId, streamId, stepId, callRef, ttlMinutes, null, null);
        }

        /** V428 variant carrying the generation model and the size of the call. */
        public static BillingScope of(Long userId, String credentialSource, Long platformCredentialId,
                                       String providerKind, String provider, String model, String toolSlug,
                                       String runId, String streamId, String stepId,
                                       String callRef,
                                       int ttlMinutes,
                                       String generationModelId,
                                       java.math.BigDecimal generationQuantity) {
            return of(userId, credentialSource, platformCredentialId, providerKind, provider, model,
                    toolSlug, runId, streamId, stepId, callRef, ttlMinutes,
                    generationModelId, generationQuantity, false);
        }

        /**
         * Variant for a call whose charge is owned by the caller around this
         * execution (the CE relay). Everything else is identical: the flag says
         * who bills, not what the call is.
         */
        public static BillingScope of(Long userId, String credentialSource, Long platformCredentialId,
                                       String providerKind, String provider, String model, String toolSlug,
                                       String runId, String streamId, String stepId,
                                       String callRef,
                                       int ttlMinutes,
                                       String generationModelId,
                                       java.math.BigDecimal generationQuantity,
                                       boolean billingOwnedByCaller) {
            return of(userId, credentialSource, platformCredentialId, providerKind, provider, model,
                    toolSlug, runId, streamId, stepId, callRef, ttlMinutes,
                    generationModelId, generationQuantity, null, billingOwnedByCaller);
        }

        /**
         * The form that carries the unit but not yet what the call's choices do
         * to its price, kept for the callers that have no notion of one.
         */
        public static BillingScope of(Long userId, String credentialSource, Long platformCredentialId,
                                       String providerKind, String provider, String model, String toolSlug,
                                       String runId, String streamId, String stepId,
                                       String callRef,
                                       int ttlMinutes,
                                       String generationModelId,
                                       java.math.BigDecimal generationQuantity,
                                       String generationQuantityUnit,
                                       boolean billingOwnedByCaller) {
            return of(userId, credentialSource, platformCredentialId, providerKind, provider, model,
                    toolSlug, runId, streamId, stepId, callRef, ttlMinutes,
                    generationModelId, generationQuantity, generationQuantityUnit, null,
                    billingOwnedByCaller);
        }

        /** Full form, carrying the unit AND what this call's own choices cost. */
        public static BillingScope of(Long userId, String credentialSource, Long platformCredentialId,
                                       String providerKind, String provider, String model, String toolSlug,
                                       String runId, String streamId, String stepId,
                                       String callRef,
                                       int ttlMinutes,
                                       String generationModelId,
                                       java.math.BigDecimal generationQuantity,
                                       String generationQuantityUnit,
                                       java.math.BigDecimal generationPriceMultiplier,
                                       boolean billingOwnedByCaller) {
            // Required rather than defaulted: a missing callRef does not degrade
            // the billing, it silently rebuilds the "one charge per scope" hole
            // (see the field javadoc). Better to fail the call than to give the
            // generation away.
            if (callRef == null || callRef.isBlank()) {
                throw new IllegalArgumentException(
                        "callRef is required on a BillingScope: without it every call in the scope "
                                + "reserves the same ledger row and only the first one is charged");
            }
            String scopeKind;
            String scopeId;
            if (runId != null && !runId.isBlank()) {
                scopeKind = "RUN";
                scopeId = runId;
            } else if (streamId != null && !streamId.isBlank()) {
                scopeKind = "STREAM";
                scopeId = streamId;
            } else {
                scopeKind = null;
                scopeId = null;
            }
            // An absurd factor is read as "no factor", never as a price of zero and never as a
            // thousandfold charge. Belt to the controller's braces, and not redundant: a scope can
            // be built by any caller inside this service, and the ceiling is the only thing between
            // a bad number and a reservation.
            java.math.BigDecimal multiplier = com.apimarketplace.common.web.BillingContextHeaders
                    .sanitizeGenerationMultiplier(generationPriceMultiplier);
            return new BillingScope(userId, credentialSource, platformCredentialId,
                    providerKind, provider, model, toolSlug, scopeKind, scopeId, stepId,
                    callRef, ttlMinutes, generationModelId, generationQuantity,
                    generationQuantityUnit, multiplier, billingOwnedByCaller);
        }
    }

    /**
     * Outcome of {@link #preflightReserve}. Catalog reads {@link #allowed} to
     * decide whether to proceed; on refusal returns 402 to the caller with the
     * provided {@code error} and {@code delinquent} flag.
     */
    public record PreflightDecision(
            boolean allowed,
            String sourceId,           // non-null only when a reservation row was written
            BigDecimal reservedAmount, // for downstream commit/release
            String error,              // populated on refusal
            boolean delinquent,        // populated on refusal - UI shows distinct banner
            /**
             * True when the call is allowed ONLY because the caller has a
             * provider key of their own: the platform key must not be allowed to
             * answer it. The executor turns this into a hard pin on the caller's
             * credential pool, so the "nothing to bill" verdict is decided by
             * which key answered rather than by which keys exist. Without the
             * pin, the agentic path's platform fallback would produce a resold
             * asset with no reservation to commit.
             */
            boolean restrictToCallersOwnKey
    ) {
        public static PreflightDecision allowedWithoutBilling() {
            return new PreflightDecision(true, null, null, null, false, false);
        }
        /**
         * Allowed, unbilled, and ONLY on the caller's own credential. See
         * {@link #restrictToCallersOwnKey()}.
         */
        public static PreflightDecision allowedOnCallersOwnKey() {
            return new PreflightDecision(true, null, null, null, false, true);
        }
        public static PreflightDecision allowedWithReservation(String sourceId, BigDecimal projected) {
            return new PreflightDecision(true, sourceId, projected, null, false, false);
        }
        public static PreflightDecision refused(String error, boolean delinquent) {
            return new PreflightDecision(false, null, null, error, delinquent, false);
        }
    }

    /** Map a non-Map metadata cell back to {@code Map} or null. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
