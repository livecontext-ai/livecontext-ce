'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { Check, Clock, FileText, Smartphone, Table2, TrendingUp, Zap } from 'lucide-react';
import { BrandMark } from '@/components/integrations/BrandMark';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';
import StudioWires, { type Wire } from './StudioWires';
import { HistoryTable, PhoneTile, ScreenTile, STUDIO_PANEL, StudioCard, studioCss } from './studioParts';
import { buildPersonaInterfaceHtml } from './personaInterfaceHtml';
import { buildSalesSnapshot } from './WorkflowRecapPanel';
import { BUSINESS_DESTINATIONS } from './personas';

/**
 * What a sales team can build, in the four things a deal actually leaves behind.
 *
 * <p>Fourth variation of the same idea, and again not the same drawing: creator fans one
 * photograph into five files, operations converges six tools into one screen, support
 * follows one request through four artefacts, and sales follows the MONEY, from the leads
 * that come in to the quote that goes out, the follow-up that is approved and the row that
 * records it.
 *
 * <p>The quote is the card that carries the section, because it is the one artefact here a
 * visitor already knows what to expect from: it is the product's own quote document,
 * rendered from the same HTML the hero's interface node renders, not a picture of one.
 */
const RECORDS = ['first', 'second', 'third'] as const;
/** What the qualification step reads before anyone calls back. */
const SOURCES = ['linkedin', 'hubspot', 'gmail'] as const;

const STAGE = { background: 'linear-gradient(135deg, rgba(245,158,11,.11), rgba(16,185,129,.07) 55%, rgba(20,108,148,.10))' };

const QUOTE_WIRES: readonly Wire[] = [{ from: 'quote-trigger', to: 'quote-screen' }, { from: 'quote-screen', to: 'quote-out' }];
const LEAD_WIRES: readonly Wire[] = SOURCES.map((slug) => ({ from: `source-${slug}`, to: 'leads' }));
const TABLE_WIRES: readonly Wire[] = [{ from: 'quote-chip', to: 'history' }];
const PHONE_WIRES: readonly Wire[] = [{ from: 'phone', to: 'decision' }];

const integration = (slug: string) => WELL_KNOWN_INTEGRATIONS.find((known) => known.slug === slug);

export default function SalesBuildStudio() {
  const t = useTranslations('PersonaLanding.personas.sales.workflowShowcase');
  const preview = useTranslations('PersonaLanding.tablePreview');
  const { theme } = useLandingTheme();
  const html = useMemo(() => buildPersonaInterfaceHtml('sales', theme, t as never, 'product', 'quote'), [theme, t]);
  const snapshot = useMemo(() => buildSalesSnapshot('quote', preview as never), [preview]);
  const example = (key: string) => ({ label: t(`examples.${key}.label`), title: t(`examples.${key}.title`), summary: t(`examples.${key}.summary`) });
  const destinations = (key: keyof typeof BUSINESS_DESTINATIONS) =>
    BUSINESS_DESTINATIONS[key].map(integration).filter((known): known is NonNullable<typeof known> => Boolean(known));

  return (
    <div className="flex flex-col gap-5">
      <style>{studioCss('sales', { tableBody: 430, tableWidth: 980 })}</style>

      <div className="grid gap-5 lg:grid-cols-5">
        {/* The quote itself, as the document the client receives. */}
        <div className="min-w-0 lg:col-span-3">
          <StudioCard stage={STAGE} icon={FileText} {...example('quote')}>
            <StudioWires wires={QUOTE_WIRES} className="flex w-full flex-wrap items-center justify-center gap-x-7 gap-y-7 md:flex-nowrap">
              <div data-node="quote-trigger" className="studio-tile relative z-10 shrink-0 rounded-2xl p-3" style={{ ...STUDIO_PANEL, ['--lift' as string]: '20px', width: 146 }}>
                <p className="flex items-center gap-2 text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                  <Zap className="h-3.5 w-3.5 shrink-0 text-amber-500" aria-hidden="true" />
                  {t('examples.quote.triggerLabel')}
                </p>
                <p className="mt-2 text-[11px] leading-snug" style={{ color: 'var(--text-secondary)' }}>{t('examples.quote.customerMessage')}</p>
              </div>
              <ScreenTile node="quote-screen" html={html} width={252} lift="-18px" />
              <div data-node="quote-out" className="studio-tile relative z-10 shrink-0 rounded-2xl p-3" style={{ ...STUDIO_PANEL, ['--lift' as string]: '26px', width: 146 }}>
                <p className="text-[11px]" style={{ color: 'var(--text-muted)' }}>{t('examples.quote.document.amountDueLabel')}</p>
                <p className="mt-0.5 text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>{t('examples.quote.totalValue')}</p>
                <p className="mt-2 flex items-center gap-1.5 text-[11px] font-semibold text-emerald-600 dark:text-emerald-400">
                  <Check className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  {t('examples.quote.approvalLabel')}
                </p>
                <div className="mt-2.5 flex items-center gap-1.5">
                  {destinations('quote').map((known) => (
                    <span key={known.slug} title={known.name} className="inline-grid place-items-center h-6 w-6 rounded-md" style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)' }}>
                      <BrandMark iconSlug={known.iconSlug} size={13} />
                    </span>
                  ))}
                </div>
              </div>
            </StudioWires>
          </StudioCard>
        </div>

        {/* Who is worth a call back, read out of the tools that already know. */}
        <div className="min-w-0 lg:col-span-2">
          <StudioCard stage={STAGE} icon={TrendingUp} {...example('prospect')}>
            <StudioWires wires={LEAD_WIRES} className="flex w-full items-center justify-center gap-7">
              <div className="studio-tile flex shrink-0 flex-col gap-1.5" style={{ ['--lift' as string]: '12px' }}>
                {SOURCES.map((slug, index) => {
                  const known = integration(slug)!;
                  return (
                    <div key={slug} data-node={`source-${slug}`} className="relative z-10 flex items-center gap-2 rounded-lg px-2.5 py-1.5" style={{ ...STUDIO_PANEL, marginLeft: index === 1 ? 16 : 0 }}>
                      <BrandMark iconSlug={known.iconSlug} size={13} />
                      <span className="text-[10.5px]" style={{ color: 'var(--text-secondary)' }}>{known.name}</span>
                    </div>
                  );
                })}
              </div>
              {/* The three companies the qualification step ranked, with what it found. */}
              <div data-node="leads" className="studio-tile relative z-10 flex shrink-0 flex-col gap-2 rounded-2xl p-3" style={{ ...STUDIO_PANEL, ['--lift' as string]: '-14px', width: 186 }}>
                {RECORDS.map((record, index) => (
                  <div key={record} className="flex items-center gap-2" style={{ marginLeft: index * 6 }}>
                    <span className="inline-grid h-6 w-6 shrink-0 place-items-center rounded-md text-[10px] font-semibold" style={{ background: 'var(--bg-tertiary)', color: 'var(--text-secondary)' }}>
                      {index + 1}
                    </span>
                    <span className="min-w-0">
                      <span className="block truncate text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{t(`examples.prospect.records.${record}.label`)}</span>
                      <span className="block truncate text-[10px]" style={{ color: 'var(--text-muted)' }}>{t(`examples.prospect.records.${record}.value`)}</span>
                    </span>
                  </div>
                ))}
              </div>
            </StudioWires>
          </StudioCard>
        </div>
      </div>

      <div className="grid gap-5 lg:grid-cols-5">
        {/* Where the follow-up is decided, on the thing it is decided on. */}
        <div className="min-w-0 lg:col-span-2">
          <StudioCard stage={STAGE} icon={Smartphone} {...example('followup')}>
            <StudioWires wires={PHONE_WIRES} className="flex w-full items-center justify-center gap-6">
              <PhoneTile node="phone" prefix="sales" persona="sales" example="followup" lift="8px" />
              <div data-node="decision" className="studio-tile relative z-10 shrink-0 rounded-2xl p-3" style={{ ...STUDIO_PANEL, ['--lift' as string]: '-14px', width: 142 }}>
                <p className="flex items-center gap-2 text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                  <Clock className="h-3.5 w-3.5 shrink-0 text-amber-500" aria-hidden="true" />
                  {t('studio.cards.micro.waiting')}
                </p>
                <p className="mt-2.5 flex items-center gap-1.5 text-[11px] font-semibold text-emerald-600 dark:text-emerald-400">
                  <Check className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  {t('examples.followup.approvalLabel')}
                </p>
                <div className="mt-2.5 flex items-center gap-1.5">
                  {destinations('followup').map((known) => (
                    <span key={known.slug} title={known.name} className="inline-grid place-items-center h-6 w-6 rounded-md" style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)' }}>
                      <BrandMark iconSlug={known.iconSlug} size={13} />
                    </span>
                  ))}
                </div>
              </div>
            </StudioWires>
          </StudioCard>
        </div>

        {/* What every quote leaves behind, as the product's own table. */}
        <div className="min-w-0 lg:col-span-3">
          <StudioCard stage={STAGE} icon={Table2} {...{ label: t('studio.cards.table.label'), title: t('studio.cards.table.title'), summary: t('studio.cards.table.summary') }}>
            <StudioWires wires={TABLE_WIRES} className="flex w-full flex-col items-center gap-4">
              <div data-node="quote-chip" className="relative z-10 inline-flex items-center gap-2 rounded-full px-3 py-1.5" style={STUDIO_PANEL}>
                <FileText className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />
                <span className="text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{t('examples.quote.subject')}</span>
              </div>
              <HistoryTable node="history" prefix="sales" snapshot={snapshot} height={262} />
            </StudioWires>
          </StudioCard>
        </div>
      </div>
    </div>
  );
}
