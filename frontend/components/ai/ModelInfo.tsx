'use client';

/**
 * ModelInfo - user-friendly badges, capability icons, price hint, and detail
 * popover for any LLM model row coming out of {@link useModels}. Designed to be
 * dropped inline into a {@code <SelectItem>} or a chat header trigger.
 *
 * <p>Keeps the inline rendering compact (tier + capability icons + context +
 * price). The full picture (rate limits, batch / cache pricing, max output,
 * deprecation date) lives behind a small {@code (i)} popover so the dropdown
 * stays scannable in narrow inspectors (~280px workflow inspector pane).
 */

import * as React from 'react';
import { useTranslations } from 'next-intl';
import {
  Eye,
  Wrench,
  Brain,
  Zap,
  Globe,
  MonitorCog,
  FileJson,
  Info,
  AlertTriangle,
  Star,
  KeyRound,
  Building2,
} from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import {
  Tooltip,
  TooltipTrigger,
  TooltipContent,
  TooltipProvider,
} from '@/components/ui/tooltip';
import {
  Popover,
  PopoverTrigger,
  PopoverContent,
} from '@/components/ui/popover';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { cn } from '@/lib/utils';
import type { AIModel } from '@/hooks/useModels';
import { getProviderDisplayName } from '@/lib/ai-providers/providerIcons';
import {
  formatCreditAmount,
  formatCreditEstimate,
  ownKeyChargeFor,
  type CostProfileId,
  type ModelCostBasis,
  type ModelRates,
  type OwnKeyCharge,
} from '@/lib/billing/model-cost-estimate';
import { getClientLocale } from '@/lib/utils/locale';
import { renderBoldMarkup } from '@/lib/utils/boldMarkup';
import { UpgradeRequiredBadge } from '@/components/billing/UpgradeRequiredBadge';
import { FreeTierBadge } from '@/components/billing/FreeTierBadge';

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Everything the credit estimate prices a model with. The catalogue serves the
 * cache prices beside `pricing`, not inside it, so they have to be gathered here;
 * passing `model.pricing` alone was what made the badge quote every cached token
 * at the full input rate.
 *
 * `provider` and `supportsPromptCaching` are part of the price, not decoration:
 * the first decides what a cache rate the catalogue omits costs, the second
 * whether a cache rate applies to this model at all.
 */
function creditRatesOf(model: AIModel): ModelRates | undefined {
  if (!model.pricing) return undefined;
  return {
    input: model.pricing.input,
    output: model.pricing.output,
    cacheRead: model.priceCacheRead,
    cacheWrite: model.priceCacheWrite,
    provider: model.provider,
    supportsPromptCaching: model.supportsPromptCaching,
  };
}

/**
 * Which message states an own-key charge. The capped half is the platform price and is marked
 * as the estimate it is; the other half is the flat fee, the most this turn can be billed, and
 * is stated plainly because it does not move with the length of an average turn.
 */
function ownKeyFeeMessage(charge: OwnKeyCharge): 'creditEstimateShort' | 'ownKeyFeeShort' {
  return charge.estimated ? 'creditEstimateShort' : 'ownKeyFeeShort';
}

function ownKeyFeeCredits(charge: OwnKeyCharge): string {
  return formatCreditAmount(charge.credits, getClientLocale());
}

type Tier = 'top' | 'high' | 'mid' | 'budget';

/**
 * Hover tooltips in this file render inside portalled menus that stack far
 * above the app (model menu z-[10000], picker SelectContent z-[100000], the
 * (i) detail card z-[100001]); the tooltip default z-[9999] would paint them
 * BEHIND those hosts, so they take the top slot of the ladder.
 */
const MENU_TOOLTIP_Z = 'z-[100002]';

const TIER_CLASSES: Record<Tier, string> = {
  top: 'bg-violet-500/15 text-violet-700 dark:text-violet-300 border-violet-500/30',
  high: 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/30',
  mid: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/30',
  budget: 'bg-slate-500/15 text-slate-700 dark:text-slate-300 border-slate-500/30',
};

const PROVIDER_KIND_CLASSES: Record<string, string> = {
  cloud: 'bg-sky-500/15 text-sky-700 dark:text-sky-300 border-sky-500/30',
  byok: 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/30',
  bridge: 'bg-fuchsia-500/15 text-fuchsia-700 dark:text-fuchsia-300 border-fuchsia-500/30',
};

function isTier(value: string | undefined): value is Tier {
  return value === 'top' || value === 'high' || value === 'mid' || value === 'budget';
}

/**
 * Compact "128k" / "1M" rendering - rounded to the nearest integer in its
 * scale unit (k or M) so the picker shows tidy values like "200k" instead of
 * "200.5k". Sub-1k values round to the nearest 100.
 */
export function formatContextWindow(tokens: number | undefined): string | null {
  if (!tokens || tokens <= 0) return null;
  if (tokens >= 1_000_000) return `${Math.round(tokens / 1_000_000)}M`;
  if (tokens >= 1_000) return `${Math.round(tokens / 1_000)}k`;
  return String(Math.round(tokens / 100) * 100);
}

/**
 * Format a USD-per-1M-tokens price. Dollar+ rounds to the nearest integer
 * ("$15", "$3"), a tenth or more shows 1 decimal ("$0.3"), and anything below
 * that keeps two significant digits ("$0.042"). Errs on the side of conciseness
 * over precision, but never past the point where a price reads as free.
 *
 * <p><b>Why the smallest band exists.</b> One decimal was enough while the
 * cheapest catalogue row was around $0.3 per 1M. A decision model prices its
 * input at $0.042 and bills no output at all, which the old single rule rendered
 * as "$0.0/$0.0 per 1M": the one row in the picker whose whole argument is that
 * it costs almost nothing was the one row that claimed to cost nothing, and a
 * reader has no way to tell that apart from an unpriced model. Two significant
 * digits hold for anything a provider is plausibly going to charge, without
 * padding the common rates with zeroes they do not have.
 *
 * <p>A true zero is printed "$0" rather than "$0.0": it is free, and a decimal
 * place on it only invites the same confusion from the other side.
 */
function formatPricePerMillion(value: number | undefined | null): string | null {
  if (value === undefined || value === null) return null;
  if (value >= 1) return `$${Math.round(value)}`;
  if (value <= 0) return '$0';
  if (value >= 0.1) return `$${(Math.round(value * 10) / 10).toFixed(1)}`;
  return `$${Number(value.toPrecision(2))}`;
}

// No per-image formatter here: these components render models from
// /v3/chat/models, whose response is mode-filtered as 'chat', so a
// `mode === 'image'` row never reaches them. It could, while the image
// generation tab existed and fed the same picker.

function formatRateLimit(value: number | null | undefined): string | null {
  if (value === undefined || value === null) return null;
  if (value >= 1_000_000) return `${Math.round(value / 1_000_000)}M`;
  if (value >= 1_000) return `${Math.round(value / 1_000)}k`;
  return String(Math.round(value / 10) * 10);
}

// ─────────────────────────────────────────────────────────────────────────────
// Capability icons
// ─────────────────────────────────────────────────────────────────────────────

interface CapabilityDef {
  key: 'vision' | 'tools' | 'reasoning' | 'promptCaching' | 'webSearch' | 'computerUse' | 'responseSchema';
  Icon: typeof Eye;
  enabled: boolean;
}

function collectCapabilities(model: AIModel): CapabilityDef[] {
  // Order matters - most-relevant for the user comes first.
  const list: CapabilityDef[] = [
    { key: 'vision', Icon: Eye, enabled: model.supportsVision === true },
    { key: 'tools', Icon: Wrench, enabled: model.supportsTools === true },
    { key: 'reasoning', Icon: Brain, enabled: model.supportsReasoning === true },
    { key: 'webSearch', Icon: Globe, enabled: model.supportsWebSearch === true },
    { key: 'promptCaching', Icon: Zap, enabled: model.supportsPromptCaching === true },
    { key: 'computerUse', Icon: MonitorCog, enabled: model.supportsComputerUse === true },
    { key: 'responseSchema', Icon: FileJson, enabled: model.supportsResponseSchema === true },
  ];
  return list.filter(c => c.enabled);
}

interface CapabilityIconsProps {
  model: AIModel;
  /** Cap how many icons render inline before truncating (rest go to popover). */
  maxInline?: number;
  className?: string;
}

export function CapabilityIcons({ model, maxInline = 4, className }: CapabilityIconsProps) {
  const t = useTranslations('modelInfo');
  const caps = collectCapabilities(model);
  if (caps.length === 0) return null;
  const shown = caps.slice(0, maxInline);
  return (
    <TooltipProvider delayDuration={150}>
      <span className={cn('inline-flex items-center gap-1', className)}>
        {shown.map(({ key, Icon }) => (
          <Tooltip key={key}>
            <TooltipTrigger asChild>
              <span
                className="inline-flex h-4 w-4 items-center justify-center text-slate-500 dark:text-slate-400"
                aria-label={t(`capability.${key}`)}
              >
                <Icon className="h-3.5 w-3.5" />
              </span>
            </TooltipTrigger>
            <TooltipContent className={MENU_TOOLTIP_Z}>
              <div className="text-xs">
                <div className="font-semibold">{t(`capability.${key}`)}</div>
                <div className="text-slate-500 dark:text-slate-400">
                  {t(`capabilityTooltip.${key}`)}
                </div>
              </div>
            </TooltipContent>
          </Tooltip>
        ))}
      </span>
    </TooltipProvider>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Tier + provider-kind badges
// ─────────────────────────────────────────────────────────────────────────────

interface TierBadgeProps {
  tier: string | undefined;
  className?: string;
}

export function TierBadge({ tier, className }: TierBadgeProps) {
  const t = useTranslations('modelInfo');
  if (!isTier(tier)) return null;
  return (
    <TooltipProvider delayDuration={150}>
      <Tooltip>
        <TooltipTrigger asChild>
          <Badge
            variant="outline"
            className={cn(
              'text-[10px] py-0 px-1.5 leading-tight font-medium',
              TIER_CLASSES[tier],
              className,
            )}
          >
            {t(`tier.${tier}`)}
          </Badge>
        </TooltipTrigger>
        <TooltipContent className={MENU_TOOLTIP_Z}>{t(`tierTooltip.${tier}`)}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

interface ProviderKindBadgeProps {
  providerKind: string | undefined;
  className?: string;
}

/**
 * Surface bridge kind only. Cloud is the assumed default; "byok" is a fallback
 * label from {@code inferProviderKind} for any non-bridge non-cloud provider
 * but does NOT correspond to a real per-user-key chat/agent path - every chat
 * and agent turn debits credits via {@code CreditService.consumeForChat}/
 * {@code consumeForAgent} regardless. Hiding it avoids implying a billing
 * bypass that doesn't exist.
 */
export function ProviderKindBadge({ providerKind, className }: ProviderKindBadgeProps) {
  const t = useTranslations('modelInfo');
  if (!providerKind || providerKind === 'cloud' || providerKind === 'byok') return null;
  return (
    <TooltipProvider delayDuration={150}>
      <Tooltip>
        <TooltipTrigger asChild>
          <Badge
            variant="outline"
            className={cn(
              'text-[10px] py-0 px-1.5 leading-tight font-medium uppercase tracking-wide',
              PROVIDER_KIND_CLASSES[providerKind] ?? '',
              className,
            )}
          >
            {t(`providerKind.${providerKind}` as 'providerKind.cloud')}
          </Badge>
        </TooltipTrigger>
        <TooltipContent className={MENU_TOOLTIP_Z}>
          {t(`providerKindTooltip.${providerKind}` as 'providerKindTooltip.cloud')}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

/**
 * Which key this model's next turn runs on: the caller's own, or the platform's.
 *
 * <p><b>Why a badge and not just the number.</b> An own-key row already stated its charge, but
 * as a bare credit figure shaped exactly like the platform estimate beside it: the same words,
 * the same place, and the only thing marking it as a different route was a hover tooltip. Hover
 * does not exist on a touch device, so on half the app the two routes were indistinguishable -
 * and they bill from different pockets, since on one of them the provider invoices the tokens
 * directly. Which key runs is a fact about the choice, so it is said where the choice is made.
 *
 * <p><b>Why BOTH routes are marked, and only sometimes.</b> A reader who has brought no key of
 * their own has one route and no question to answer: a badge on every row would be pure
 * furniture. The moment they hold one, the SAME list mixes the two - their key serves the
 * providers they keyed, the platform serves the rest - and then the absence of a mark is not an
 * answer, because an absence also looks like a row that forgot to say. So the pair appears
 * together or not at all; {@link ModelOptionDisplay} decides on the caller holding a usable key.
 *
 * <p>The own-key half is drawn from {@link ownKeyChargeFor} being answerable, which is the
 * server's own gate: a key that would actually serve, on a plan that lets it. Never a guess from
 * the provider's name - see {@link ProviderKindBadge}, whose inferred "byok" kind means nothing
 * about billing and is hidden for exactly that reason.
 *
 * <p>The label collapses to the icon alone in {@code compact}, where the row has ~280px for
 * everything: the word is kept for a screen reader and the tooltip carries it for a pointer, so
 * the narrow case loses the width, never the meaning.
 */
function KeyRouteBadge({
  route, compact = false, className,
}: { route: 'own' | 'platform'; compact?: boolean; className?: string }) {
  const t = useTranslations('modelInfo');
  const own = route === 'own';
  return (
    <TooltipProvider delayDuration={150}>
      <Tooltip>
        <TooltipTrigger asChild>
          <Badge
            variant="outline"
            data-testid={own ? 'own-key-badge' : 'platform-key-badge'}
            className={cn(
              'text-[10px] py-0 px-1.5 leading-tight font-medium gap-1',
              // The two halves differ by WEIGHT, not by hue, and that is deliberate. Every free
              // colour was already spoken for on this very line: the tier badge sits immediately
              // to the left in violet / blue / emerald / slate, the provider-kind badge in sky,
              // amber or fuchsia, and the upgrade lock in amber. A tinted own-key pill in any of
              // them produced two near-identical pastel pills side by side (blue "High tier" next
              // to a blue "Your key" was the one that made this obvious), and the reader has to
              // tell them apart at a glance, because one of them says who gets invoiced.
              //
              // So the own-key half is SOLID, in the blue the settings panel marks "Runs on your
              // key" with: the hue still agrees with the other surface that talks about the route,
              // and the fill is what makes it a different object from the tier pill. The platform
              // half is not a pill at all - no ground, no border, just the muted caption it
              // deserves. It is the ordinary case, and it is only drawn to stop an absent mark
              // from reading as a row that forgot to say.
              own
                ? 'border-transparent bg-blue-600 text-white dark:bg-blue-500 dark:text-white'
                : 'border-transparent bg-transparent text-slate-500 dark:text-slate-400',
              className,
            )}
          >
            {own
              ? <KeyRound className="h-3 w-3 flex-shrink-0" aria-hidden />
              : <Building2 className="h-3 w-3 flex-shrink-0" aria-hidden />}
            <span className={compact ? 'sr-only' : undefined}>
              {t(own ? 'ownKeyBadge' : 'platformKeyBadge')}
            </span>
          </Badge>
        </TooltipTrigger>
        <TooltipContent className={MENU_TOOLTIP_Z}>
          {t(own ? 'ownKeyBadgeTooltip' : 'platformKeyBadgeTooltip')}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Inline display - what each option renders
// ─────────────────────────────────────────────────────────────────────────────

interface ModelOptionDisplayProps {
  model: AIModel;
  /**
   * "compact" hides the price and shows just tier + 2 capability icons + ctx,
   * for tight inspector dropdowns (~280px). "default" includes price.
   */
  variant?: 'compact' | 'default';
  /**
   * True when this account's credits cannot pay for what this model does, which
   * marks the row. Passed in rather than read here: the answer belongs to the
   * whole list, and asking per row would put a query observer behind every
   * option and make this presentational row unrenderable without a query
   * client. The caller asks `useMonthlyCreditsCannotPay` once.
   */
  upgradeRequired?: boolean;
  /**
   * True when the reader's free-tier allowance pays for this model right now,
   * which marks the row with a "Free" chip. The mirror image of
   * {@link upgradeRequired}, and passed in for the same reason: the answer
   * belongs to the whole list.
   *
   * <p><b>It must come from `useMonthlyCreditsCannotPay.freeTierForModel`</b>,
   * the verdict that also weighs the allowance balance. That is what keeps this
   * chip and {@link upgradeRequired}'s lock off the same row; the reasoning lives
   * with the verdict.
   */
  freeTier?: boolean;
  /**
   * Multiplier + cost profiles from `useModelCostBasis`, or null to show no
   * estimate (which is what CE gets). Passed in for the same reason as
   * `upgradeRequired`: the answer belongs to the whole list, and a query per
   * row would put an observer behind every option.
   */
  costBasis?: ModelCostBasis | null;
  /**
   * Which shape of work the estimate prices. The surfaces differ by two orders
   * of magnitude, so the picker says which one it is: an agent that calls tools
   * is not a classify step, and quoting one figure for both would mislead on
   * every screen but one.
   */
  costProfile?: CostProfileId;
  className?: string;
}

/**
 * Inline rich row: model name on the first line, then a meta line with tier,
 * capability icons, context window, optional price, an optional credit
 * estimate, and a deprecation flag.
 */
export function ModelOptionDisplay({
  model,
  variant = 'default',
  upgradeRequired = false,
  freeTier = false,
  costBasis = null,
  costProfile = 'chatConversation',
  className,
}: ModelOptionDisplayProps) {
  const t = useTranslations('modelInfo');
  const ctx = formatContextWindow(model.contextWindow);
  const priceIn = formatPricePerMillion(model.pricing?.input);
  const priceOut = formatPricePerMillion(model.pricing?.output);
  const deprecated = !!model.deprecatedAt;
  const showPrice = variant !== 'compact' && priceIn !== null && priceOut !== null;
  const maxInline = variant === 'compact' ? 3 : 4;
  // What the choice will actually cost, in the same credits the ledger debits,
  // said BEFORE the model is picked. Null on CE, on an unpriced row, and on a
  // catalogue sentinel rate.
  const creditEstimate = formatCreditEstimate(creditRatesOf(model), costBasis, costProfile, getClientLocale(), model.provider, model.id);
  // On the caller's own key the ledger takes a flat fee per turn, capped at what the
  // platform route would have charged, so the token-rate estimate above is a number that
  // route does not pay. State what it does pay instead.
  const ownKeyCharge = ownKeyChargeFor(model, creditRatesOf(model), costBasis, costProfile);

  return (
    <div className={cn('flex flex-col gap-0.5 min-w-0 w-full', className)}>
      <div className="flex items-center gap-1.5 min-w-0">
        {/* Greyed when the balance cannot pay for it, which is how a row reads as
            unavailable at a glance rather than only through the lock. The NAME
            carries it and the row does not: the name has contrast to spare, while
            the 11px meta line beneath it is already close to the floor and fading
            the subtree would take it under.

            slate-600, not slate-500, and the difference is not cosmetic: a blocked
            row is read on three backgrounds, and 14px/500 is normal text, so the
            bar is 4.5:1. slate-500 gives 4.76 on white but 4.32 on the menu's
            hover/selected `bg-gray-100` and 4.13 on the pickers' `SelectItem`
            hover, i.e. it fails in exactly the states a row is in while being
            considered. slate-600 gives 7.58 / 6.89 / 6.57. Dark side:
            slate-400 on gray-800 is 5.72. */}
        <span className={cn(
          'truncate text-sm font-medium',
          upgradeRequired && 'text-slate-600 dark:text-slate-400',
        )}>{model.name}</span>
        {model.recommended && (
          <TooltipProvider delayDuration={150}>
            <Tooltip>
              <TooltipTrigger asChild>
                <Star
                  className="h-3 w-3 flex-shrink-0 fill-amber-400 stroke-amber-500"
                  aria-label={t('recommended')}
                />
              </TooltipTrigger>
              <TooltipContent className={MENU_TOOLTIP_Z}>{t('recommended')}</TooltipContent>
            </Tooltip>
          </TooltipProvider>
        )}
        {deprecated && (
          <TooltipProvider delayDuration={150}>
            <Tooltip>
              <TooltipTrigger asChild>
                <AlertTriangle
                  className="h-3 w-3 flex-shrink-0 text-amber-600"
                  aria-label={t('deprecatedBadge')}
                />
              </TooltipTrigger>
              <TooltipContent className={MENU_TOOLTIP_Z}>{t('deprecatedBadge')}</TooltipContent>
            </Tooltip>
          </TooltipProvider>
        )}
      </div>
      {/* Meta line - single font (no mono) so the row reads as one consistent
          typeface; tier/provider badges and capability icons first, then the
          numeric facts (context · price) separated by a subtle middot. */}
      <div className="flex items-center gap-x-2 gap-y-0.5 text-[11px] text-slate-500 dark:text-slate-400 min-w-0 flex-wrap">
        {/* First on the meta line, ahead of tier and provider. It and the lock two
            badges along are the pair that answers "can I run this?", which is what
            a reader on a workflow-scoped plan is reading the row for; they are
            never both present, so the line never carries two markers however far
            apart they sit. The figure this chip could quote is left out here - see
            {@code FreeTierBadge.credits} for why a row must not ask for it. */}
        <FreeTierBadge covered={freeTier} />
        <TierBadge tier={model.tier} />
        {/* Drawn only for a caller who HAS a key of their own, where the list genuinely mixes
            the two routes. For everyone else there is one route and nothing to disambiguate. */}
        {costBasis?.ownKey && (
          <KeyRouteBadge
            route={ownKeyCharge !== null ? 'own' : 'platform'}
            compact={variant === 'compact'}
          />
        )}
        <ProviderKindBadge providerKind={model.providerKind} />
        {/* Said where the choice is made rather than after it. Every picker
            that renders this row spends from the pay-as-you-go bucket (a chat
            turn, an agent's tokens, a classify or guardrail call), which the
            Free plan's monthly credits may not fund. A label only, and a lock
            without the word in every variant: the row has no width to spare and
            the word is there for a screen reader. What to DO about it sits
            under the control, where a click works. */}
        <UpgradeRequiredBadge blocked={upgradeRequired} />
        <CapabilityIcons model={model} maxInline={maxInline} />
        {ctx && <span>{t('context', { tokens: ctx })}</span>}
        {ctx && showPrice && (
          <span aria-hidden className="text-slate-300 dark:text-slate-600">·</span>
        )}
        {showPrice && (
          <span>{t('priceShort', { input: priceIn, output: priceOut })}</span>
        )}
        {(ownKeyCharge !== null || creditEstimate) && (ctx || showPrice) && (
          <span aria-hidden className="text-slate-300 dark:text-slate-600">·</span>
        )}
        {ownKeyCharge !== null ? (
          <TooltipProvider delayDuration={150}>
            <Tooltip>
              <TooltipTrigger asChild>
                <span className="whitespace-nowrap" data-testid="own-key-fee">
                  {t(ownKeyFeeMessage(ownKeyCharge), { credits: ownKeyFeeCredits(ownKeyCharge) })}
                </span>
              </TooltipTrigger>
              <TooltipContent className={MENU_TOOLTIP_Z}>
                <div className="text-xs max-w-[15rem]">{t('ownKeyFeeTooltip')}</div>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        ) : creditEstimate ? (
          <TooltipProvider delayDuration={150}>
            <Tooltip>
              <TooltipTrigger asChild>
                <span className="whitespace-nowrap">
                  {t('creditEstimateShort', { credits: creditEstimate })}
                </span>
              </TooltipTrigger>
              <TooltipContent className={MENU_TOOLTIP_Z}>
                <div className="text-xs max-w-[15rem]">
                  {/* The figure is bolded inside the message (see
                      renderBoldMarkup): it is the one thing the reader opened
                      the tooltip for, and the sentence around it is what says
                      the figure is an estimate rather than a cap. */}
                  {renderBoldMarkup(t(`creditEstimateTooltip.${costProfile}`, { credits: creditEstimate }))}
                </div>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        ) : null}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Detail popover - full info card
// ─────────────────────────────────────────────────────────────────────────────

interface ModelInfoPopoverProps {
  model: AIModel;
  /**
   * See {@link ModelOptionDisplayProps.freeTier}, from the same verdict and the
   * same caller. Repeated on this card because the row's chip explains itself
   * through a hover tooltip, which a touch device can never open - the same
   * reason the credit estimate is repeated here. On the composer menu this card
   * is on every row; on the pickers it is beside the chosen model, so there the
   * sentence arrives once a model is selected.
   */
  freeTier?: boolean;
  /** Optional custom trigger; defaults to a small (i) icon button. */
  trigger?: React.ReactNode;
  /** See {@link ModelOptionDisplayProps.costBasis}. Passed by the same caller. */
  costBasis?: ModelCostBasis | null;
  /** See {@link ModelOptionDisplayProps.costProfile}. */
  costProfile?: CostProfileId;
  className?: string;
}

/**
 * Click-target ⓘ that pops a detailed card with rate limits, batch / cache
 * pricing, and deprecation info. Use this next to inline displays where the
 * user might want the full picture without leaving the picker.
 */
export function ModelInfoPopover({
  model,
  freeTier = false,
  trigger,
  costBasis = null,
  costProfile = 'chatConversation',
  className,
}: ModelInfoPopoverProps) {
  const t = useTranslations('modelInfo');
  // The chip's own namespace, so the card and the chip cannot drift into two
  // different explanations of one allowance.
  const tFreeTier = useTranslations('billing.freeTier');
  const [open, setOpen] = React.useState(false);
  // The row's estimate lives behind a hover tooltip, which a touch device can
  // never open. This card IS the touch path (it opens on tap), so the figure and
  // its explanation are repeated here rather than being pointer-only.
  const creditEstimate = formatCreditEstimate(creditRatesOf(model), costBasis, costProfile, getClientLocale(), model.provider, model.id);
  // Same substitution as the row: on the caller's own key the token-rate estimate is not
  // what gets debited, and this card is the only path a touch device has to either figure.
  const ownKeyCharge = ownKeyChargeFor(model, creditRatesOf(model), costBasis, costProfile);

  const priceIn = formatPricePerMillion(model.pricing?.input);
  const priceOut = formatPricePerMillion(model.pricing?.output);
  const priceCacheRead = formatPricePerMillion(model.priceCacheRead);
  const priceBatchIn = formatPricePerMillion(model.priceInputBatch);
  const ctx = formatContextWindow(model.contextWindow);
  const maxOut = formatContextWindow(model.maxOutputTokens);
  const tpm = formatRateLimit(model.rateLimitTpm);
  const rpm = formatRateLimit(model.rateLimitRpm);
  const allCaps = collectCapabilities(model);

  return (
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          {trigger ?? (
            <button
              type="button"
              aria-label={t('infoTooltip')}
              title={t('infoTooltip')}
              data-model-selector-keep-open
              className={cn(
                'inline-flex h-5 w-5 items-center justify-center rounded-lg text-slate-400 hover:text-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800 dark:hover:text-slate-200 transition-colors',
                className,
              )}
              onPointerDown={e => e.stopPropagation()}
              onMouseDown={e => e.stopPropagation()}
              onClick={e => {
                e.stopPropagation();
                setOpen(o => !o);
              }}
            >
              <Info className="h-3.5 w-3.5" />
            </button>
          )}
        </PopoverTrigger>
        {/* The default PopoverContent z-50 paints this card BEHIND every host that
            opens it: the composer model menu (z-[10000], ModelSelectorDropdown), the
            composer Options popover (z-[99999]) and the pickers' SelectContent
            (z-[100000]) - so it must sit above them all. */}
        <PopoverContent
          align="end"
          className="w-80 p-4 text-sm bg-theme-primary border-theme z-[100001]"
          data-model-selector-keep-open
          onPointerDown={e => e.stopPropagation()}
          onMouseDown={e => e.stopPropagation()}
          onClick={e => e.stopPropagation()}
        >
        <div className="space-y-3">
          <div>
            <div className="flex items-center gap-1.5 flex-wrap">
              <span className="font-semibold text-base">{model.name}</span>
              <TierBadge tier={model.tier} />
              {/* The card is the touch path to everything the row says on hover, the route
                  included: without it a tablet reader sees the fee and never learns whose key
                  it belongs to. */}
              {costBasis?.ownKey && (
                <KeyRouteBadge route={ownKeyCharge !== null ? 'own' : 'platform'} />
              )}
              <ProviderKindBadge providerKind={model.providerKind} />
            </div>
            <div className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
              {getProviderDisplayName(model.provider)} / <span className="font-mono">{model.id}</span>
            </div>
          </div>

          {/* The touch path for the row's "Free" chip: its own explanation is a
              hover tooltip, and this card opens on tap. Stated in full here,
              where there is width for it, rather than abbreviated. */}
          {freeTier && (
            <div
              data-testid="free-tier-detail"
              className="rounded-md border border-sky-300 bg-sky-50 px-2 py-1.5 text-xs text-sky-800 dark:border-sky-700 dark:bg-sky-900/20 dark:text-sky-300"
            >
              {tFreeTier('tooltip')}
            </div>
          )}

          {model.deprecatedAt && (
            <div className="flex items-start gap-1.5 rounded-md bg-amber-500/10 border border-amber-500/30 px-2 py-1.5 text-xs text-amber-700 dark:text-amber-300">
              <AlertTriangle className="h-3.5 w-3.5 flex-shrink-0 mt-0.5" />
              <div>
                {model.deprecationDate
                  ? t('deprecationDate', { date: model.deprecationDate })
                  : t('deprecatedAt', {
                      date: formatUtcDate(model.deprecatedAt),
                    })}
              </div>
            </div>
          )}

          {allCaps.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {allCaps.map(({ key, Icon }) => (
                <Badge
                  key={key}
                  variant="outline"
                  className="text-[10px] py-0 px-1.5 leading-tight gap-1"
                >
                  <Icon className="h-3 w-3" />
                  {t(`capability.${key}`)}
                </Badge>
              ))}
            </div>
          )}

          {(ctx || maxOut) && (
            <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
              {ctx && (
                <>
                  <dt className="text-slate-500 dark:text-slate-400">{t('contextLabel')}</dt>
                  <dd className="font-mono text-right">{ctx}</dd>
                </>
              )}
              {maxOut && (
                <>
                  <dt className="text-slate-500 dark:text-slate-400">{t('maxOutputLabel')}</dt>
                  <dd className="font-mono text-right">{maxOut}</dd>
                </>
              )}
            </dl>
          )}

          {ownKeyCharge !== null ? (
            <div className="rounded-md border border-slate-200 dark:border-slate-700 p-2" data-testid="own-key-fee-card">
              <div className="text-[11px] uppercase tracking-wide text-slate-500 dark:text-slate-400 mb-1">
                {t('ownKeyFeeLabel')}
              </div>
              <div className="text-sm font-semibold text-theme-primary">
                {t(ownKeyFeeMessage(ownKeyCharge), { credits: ownKeyFeeCredits(ownKeyCharge) })}
              </div>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                {t('ownKeyFeeTooltip')}
              </p>
            </div>
          ) : creditEstimate ? (
            <div className="rounded-md border border-slate-200 dark:border-slate-700 p-2">
              <div className="text-[11px] uppercase tracking-wide text-slate-500 dark:text-slate-400 mb-1">
                {t('creditEstimateLabel')}
              </div>
              <div className="text-sm font-semibold text-theme-primary">
                {t('creditEstimateShort', { credits: creditEstimate })}
              </div>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                {renderBoldMarkup(t(`creditEstimateTooltip.${costProfile}`, { credits: creditEstimate }))}
              </p>
            </div>
          ) : null}

          {(priceIn || priceOut || priceCacheRead || priceBatchIn) && (
            <div className="rounded-md border border-slate-200 dark:border-slate-700 p-2">
              <div className="text-[11px] uppercase tracking-wide text-slate-500 dark:text-slate-400 mb-1">
                {t('perMillion')}
              </div>
              <dl className="grid grid-cols-2 gap-x-3 gap-y-0.5 text-xs">
                {priceIn && (
                  <>
                    <dt className="text-slate-500 dark:text-slate-400">
                      {t('priceInputLabel')}
                    </dt>
                    <dd className="font-mono text-right">{priceIn}</dd>
                  </>
                )}
                {priceOut && (
                  <>
                    <dt className="text-slate-500 dark:text-slate-400">
                      {t('priceOutputLabel')}
                    </dt>
                    <dd className="font-mono text-right">{priceOut}</dd>
                  </>
                )}
                {priceCacheRead && (
                  <>
                    <dt className="text-slate-500 dark:text-slate-400">
                      {t('priceCacheLabel')}
                    </dt>
                    <dd className="font-mono text-right">{priceCacheRead}</dd>
                  </>
                )}
                {priceBatchIn && (
                  <>
                    <dt className="text-slate-500 dark:text-slate-400">
                      {t('priceBatchLabel')}
                    </dt>
                    <dd className="font-mono text-right">{priceBatchIn}</dd>
                  </>
                )}
              </dl>
            </div>
          )}

          {(tpm || rpm) && (
            <div className="rounded-md border border-slate-200 dark:border-slate-700 p-2">
              <div className="text-[11px] uppercase tracking-wide text-slate-500 dark:text-slate-400 mb-1">
                {t('rateLimitsTitle')}
              </div>
              <dl className="grid grid-cols-2 gap-x-3 gap-y-0.5 text-xs">
                {rpm && (
                  <>
                    <dt className="text-slate-500 dark:text-slate-400">{t('rpmLabel')}</dt>
                    <dd className="font-mono text-right">{rpm}</dd>
                  </>
                )}
                {tpm && (
                  <>
                    <dt className="text-slate-500 dark:text-slate-400">{t('tpmLabel')}</dt>
                    <dd className="font-mono text-right">{tpm}</dd>
                  </>
                )}
              </dl>
            </div>
          )}
        </div>
      </PopoverContent>
      </Popover>
  );
}
