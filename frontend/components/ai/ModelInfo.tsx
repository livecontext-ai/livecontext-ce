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

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

type Tier = 'top' | 'high' | 'mid' | 'budget';

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
 * Format a USD-per-1M-tokens price. Sub-dollar shows 1 decimal (e.g. "$0.3"),
 * dollar+ rounds to the nearest integer ("$15", "$3"). Errs on the side of
 * conciseness over precision - full breakdown lives in the billing panel.
 */
function formatPricePerMillion(value: number | undefined | null): string | null {
  if (value === undefined || value === null) return null;
  if (value < 1) return `$${(Math.round(value * 10) / 10).toFixed(1)}`;
  return `$${Math.round(value)}`;
}

/**
 * Format a USD-per-image price. Image-gen rates sit in $0.005-$0.211 - render
 * up to 3 decimals and trim trailing zeros (0.039 → "$0.039", 0.150 → "$0.15",
 * 0.200 → "$0.2") so the value reads naturally in cells and tooltips.
 */
function formatPricePerImage(value: number | undefined | null): string | null {
  if (value === undefined || value === null) return null;
  const fixed = value.toFixed(3);
  const trimmed = fixed.replace(/\.?0+$/, '');
  return `$${trimmed || '0'}`;
}

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
            <TooltipContent>
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
        <TooltipContent>{t(`tierTooltip.${tier}`)}</TooltipContent>
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
        <TooltipContent>
          {t(`providerKindTooltip.${providerKind}` as 'providerKindTooltip.cloud')}
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
  className?: string;
}

/**
 * Inline rich row: model name on the first line, then a meta line with tier,
 * capability icons, context window, optional price, and a deprecation flag.
 */
export function ModelOptionDisplay({
  model,
  variant = 'default',
  className,
}: ModelOptionDisplayProps) {
  const t = useTranslations('modelInfo');
  const ctx = formatContextWindow(model.contextWindow);
  const isImage = model.mode === 'image';
  const priceIn = isImage
    ? formatPricePerImage(model.pricing?.input)
    : formatPricePerMillion(model.pricing?.input);
  const priceOut = formatPricePerMillion(model.pricing?.output);
  const deprecated = !!model.deprecatedAt;
  const showPrice = variant !== 'compact' && (
    isImage ? priceIn !== null : (priceIn !== null && priceOut !== null)
  );
  const maxInline = variant === 'compact' ? 3 : 4;

  return (
    <div className={cn('flex flex-col gap-0.5 min-w-0 w-full', className)}>
      <div className="flex items-center gap-1.5 min-w-0">
        <span className="truncate text-sm font-medium">{model.name}</span>
        {model.recommended && (
          <TooltipProvider delayDuration={150}>
            <Tooltip>
              <TooltipTrigger asChild>
                <Star
                  className="h-3 w-3 flex-shrink-0 fill-amber-400 stroke-amber-500"
                  aria-label={t('recommended')}
                />
              </TooltipTrigger>
              <TooltipContent>{t('recommended')}</TooltipContent>
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
              <TooltipContent>{t('deprecatedBadge')}</TooltipContent>
            </Tooltip>
          </TooltipProvider>
        )}
      </div>
      {/* Meta line - single font (no mono) so the row reads as one consistent
          typeface; tier/provider badges and capability icons first, then the
          numeric facts (context · price) separated by a subtle middot. */}
      <div className="flex items-center gap-x-2 gap-y-0.5 text-[11px] text-slate-500 dark:text-slate-400 min-w-0 flex-wrap">
        <TierBadge tier={model.tier} />
        <ProviderKindBadge providerKind={model.providerKind} />
        <CapabilityIcons model={model} maxInline={maxInline} />
        {ctx && <span>{t('context', { tokens: ctx })}</span>}
        {ctx && showPrice && (
          <span aria-hidden className="text-slate-300 dark:text-slate-600">·</span>
        )}
        {showPrice && (
          <span>
            {isImage
              ? t('priceShortPerImage', { input: priceIn })
              : t('priceShort', { input: priceIn, output: priceOut })}
          </span>
        )}
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Detail popover - full info card
// ─────────────────────────────────────────────────────────────────────────────

interface ModelInfoPopoverProps {
  model: AIModel;
  /** Optional custom trigger; defaults to a small (i) icon button. */
  trigger?: React.ReactNode;
  className?: string;
}

/**
 * Click-target ⓘ that pops a detailed card with rate limits, batch / cache
 * pricing, and deprecation info. Use this next to inline displays where the
 * user might want the full picture without leaving the picker.
 */
export function ModelInfoPopover({ model, trigger, className }: ModelInfoPopoverProps) {
  const t = useTranslations('modelInfo');
  const [open, setOpen] = React.useState(false);

  const isImage = model.mode === 'image';
  const priceIn = isImage
    ? formatPricePerImage(model.pricing?.input)
    : formatPricePerMillion(model.pricing?.input);
  const priceOut = isImage ? null : formatPricePerMillion(model.pricing?.output);
  const priceCacheRead = isImage ? null : formatPricePerMillion(model.priceCacheRead);
  const priceBatchIn = isImage ? null : formatPricePerMillion(model.priceInputBatch);
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
                'inline-flex h-5 w-5 items-center justify-center rounded-full text-slate-400 hover:text-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800 dark:hover:text-slate-200 transition-colors',
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
        <PopoverContent
          align="end"
          className="w-80 p-4 text-sm bg-theme-primary border-theme"
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
              <ProviderKindBadge providerKind={model.providerKind} />
            </div>
            <div className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
              {getProviderDisplayName(model.provider)} / <span className="font-mono">{model.id}</span>
            </div>
          </div>

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

          {(priceIn || priceOut || priceCacheRead || priceBatchIn) && (
            <div className="rounded-md border border-slate-200 dark:border-slate-700 p-2">
              <div className="text-[11px] uppercase tracking-wide text-slate-500 dark:text-slate-400 mb-1">
                {isImage ? t('perImage') : t('perMillion')}
              </div>
              <dl className="grid grid-cols-2 gap-x-3 gap-y-0.5 text-xs">
                {priceIn && (
                  <>
                    <dt className="text-slate-500 dark:text-slate-400">
                      {isImage ? t('priceImageLabel') : t('priceInputLabel')}
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
