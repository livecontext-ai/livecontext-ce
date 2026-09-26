"use client";

import React from "react";
import { useTranslations } from "next-intl";
import { InfoPopover } from "@/components/ui/info-popover";
import { cn } from "@/lib/utils";
import { TierBadge } from "@/components/ai/ModelInfo";
import { useModelCostBasis } from "@/lib/hooks/useModelCostBasis";
import { formatCreditAmount } from "@/lib/billing/model-cost-estimate";
import { getClientLocale } from "@/lib/utils/locale";

/**
 * The four price bands, in the order a reader climbs them. {@code unknown} is deliberately
 * absent: it is the server's fallback for a model with no published price, not a band anyone
 * can choose, and listing it as a fifth row would invite the question of which models are in it.
 */
const TIERS = ["budget", "mid", "high", "top"] as const;

/**
 * The (i) beside the own-keys panel: what a turn on your own key actually costs, per price band.
 *
 * <p><b>Why it exists.</b> The panel said a flat fee is charged per agent turn and never said
 * how much. The figure was reachable only from a model row, and only AFTER a key was saved -
 * so the one reader who needs it, the one deciding whether to bring a key at all, could not
 * see it anywhere. The four numbers are the offer.
 *
 * <p><b>Where the numbers come from.</b> {@code GET /api/credits/estimate-basis}, the same
 * response that prices every model row, through the same hook and therefore the same cached
 * request. They are never restated here: the ladder is an operator lever and a copy in the
 * frontend would advertise a price the ledger stopped charging the day it moved.
 *
 * <p>Renders NOTHING when the install publishes no ladder (self-hosted, which meters no
 * credits, answers `enabled:false` and the hook does not even fire there). An (i) that opens
 * onto an empty table is worse than no (i).
 */
export default function OwnKeyFeeInfo({ className }: { className?: string }) {
  const t = useTranslations("aiProviders");
  const { basis } = useModelCostBasis();
  const [open, setOpen] = React.useState(false);

  const fees = basis?.ownKeyFeeByTier;
  // Only the bands this install actually priced. A missing one is dropped rather than drawn
  // as a zero: free is a promise, and an absent number is not one.
  const rows = TIERS
    .map((tier) => ({ tier, credits: fees?.[tier] }))
    .filter((row): row is { tier: (typeof TIERS)[number]; credits: number } =>
      typeof row.credits === "number" && Number.isFinite(row.credits));

  if (rows.length === 0) return null;

  return (
    <InfoPopover
      label={t("yourKeys.fees.open")}
      accessibleName={t("yourKeys.fees.open")}
      open={open}
      onOpenChange={setOpen}
      align="start"
      // The 20px hit area the old button had; the shared trigger is only padded.
      triggerClassName={cn("h-5 w-5 rounded-lg", className)}
      contentClassName="p-4"
    >
      <p className="mb-1 font-medium text-theme-primary">{t("yourKeys.fees.title")}</p>
      <p className="mb-3 text-xs leading-relaxed text-theme-secondary">{t("yourKeys.fees.intro")}</p>
      {/* The unit belongs to the COLUMN, not to each row. Repeating it per line gave "1
          credits" on the budget band, and the obvious repair - a plural rule - cannot apply to
          a value that is already a formatted string ("1,000", "<0.1"). Said once above the
          numbers, the grammar problem disappears and the list reads as the price table it is. */}
      <div className="mb-1 border-b border-theme pb-1 text-right text-[11px] uppercase tracking-wide text-theme-secondary">
        {t("yourKeys.fees.unit")}
      </div>
      <dl className="space-y-1.5">
        {rows.map(({ tier, credits }) => (
          <div key={tier} className="flex items-center justify-between gap-3">
            {/* The SAME badge the model pickers draw, so a band named here is recognisable
                on the row where it is chosen - one colour and one word per tier, app-wide. */}
            <dt className="min-w-0">
              <TierBadge tier={tier} />
            </dt>
            <dd className="whitespace-nowrap text-sm font-medium tabular-nums text-theme-primary" data-testid="own-key-fee-row">
              {/* Rounded and grouped exactly like every other credit figure in the app. */}
              {formatCreditAmount(credits, getClientLocale())}
            </dd>
          </div>
        ))}
      </dl>
    </InfoPopover>
  );
}
