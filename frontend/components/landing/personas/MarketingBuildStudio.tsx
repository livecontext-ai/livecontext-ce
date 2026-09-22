'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { Check, Clock, MessageSquare, Search, Share2, Table2, Zap } from 'lucide-react';
import { BrandMark } from '@/components/integrations/BrandMark';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';
import StudioWires, { type Wire } from './StudioWires';
import { HistoryTable, PhoneTile, ScreenTile, STUDIO_PANEL, StudioCard, studioCss } from './studioParts';
import { buildPersonaInterfaceHtml } from './personaInterfaceHtml';
import { buildMarketingSnapshot } from './WorkflowRecapPanel';
import { BUSINESS_DESTINATIONS } from './personas';

/**
 * What a marketing team can build, drawn as the movement marketing actually makes.
 *
 * <p>One brief leaves in three directions and three conversations come back: that is the
 * shape of the section, and it is the opposite of operations, where six tools converge into
 * one screen. The channels card is therefore the widest, and the mentions card answers it
 * from the other side of the page.
 *
 * <p>The channel of each launch asset and the network of each mention are the ones the
 * page's own copy names, paired with the brand marks the integrations directory uses, so
 * nothing here claims a channel the example does not mention.
 */
const RECORDS = ['first', 'second', 'third'] as const;
/**
 * The launch assets and the mentions carry a channel NAME in the copy, which is not a key.
 * The slugs sit in the same order as the records, one row apart from nothing: this is the
 * same positional join the buildable band uses, and the test below pins it.
 */
const CHANNEL_SLUGS = BUSINESS_DESTINATIONS.campaign;
const MENTION_SLUGS = ['reddit', 'linkedin', 'instagram'] as const;

const STAGE = { background: 'linear-gradient(135deg, rgba(236,72,153,.11), rgba(245,158,11,.07) 55%, rgba(99,102,241,.10))' };

const CHANNEL_WIRES: readonly Wire[] = CHANNEL_SLUGS.map((slug) => ({ from: 'brief', to: `channel-${slug}` }));
const SEO_WIRES: readonly Wire[] = [{ from: 'audit', to: 'seo-screen' }];
const MENTION_WIRES: readonly Wire[] = [{ from: 'mention-first', to: 'phone' }];
const TABLE_WIRES: readonly Wire[] = [{ from: 'launch-chip', to: 'history' }];

const integration = (slug: string) => WELL_KNOWN_INTEGRATIONS.find((known) => known.slug === slug);

export default function MarketingBuildStudio() {
  const t = useTranslations('PersonaLanding.personas.marketing.workflowShowcase');
  const preview = useTranslations('PersonaLanding.tablePreview');
  const { theme } = useLandingTheme();
  const html = useMemo(() => buildPersonaInterfaceHtml('marketing', theme, t as never, 'product', 'seo'), [theme, t]);
  const snapshot = useMemo(() => buildMarketingSnapshot('campaign', preview as never), [preview]);
  const example = (key: string) => ({ label: t(`examples.${key}.label`), title: t(`examples.${key}.title`), summary: t(`examples.${key}.summary`) });

  return (
    <div className="flex flex-col gap-5">
      <style>{studioCss('marketing', { tableBody: 430, tableWidth: 900 })}</style>

      <div className="grid gap-5 lg:grid-cols-5">
        {/* One page reviewed, as the result a reader will see. */}
        <div className="min-w-0 lg:col-span-2">
          <StudioCard stage={STAGE} icon={Search} {...example('seo')}>
            <StudioWires wires={SEO_WIRES} className="flex w-full items-center justify-center gap-6">
              <div data-node="audit" className="studio-tile relative z-10 shrink-0 rounded-2xl p-3" style={{ ...STUDIO_PANEL, ['--lift' as string]: '18px', width: 138 }}>
                <p className="flex items-center gap-2 text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                  <Zap className="h-3.5 w-3.5 shrink-0 text-amber-500" aria-hidden="true" />
                  {t('examples.seo.triggerLabel')}
                </p>
                <p className="mt-2 text-[11px] leading-snug" style={{ color: 'var(--text-secondary)' }}>{t('examples.seo.contextValue')}</p>
              </div>
              <ScreenTile node="seo-screen" html={html} width={196} lift="-14px" />
            </StudioWires>
          </StudioCard>
        </div>

        {/* One brief, three channels, each with the asset prepared for it. */}
        <div className="min-w-0 lg:col-span-3">
          <StudioCard stage={STAGE} icon={Share2} {...example('campaign')}>
            <StudioWires wires={CHANNEL_WIRES} className="flex w-full flex-wrap items-center justify-center gap-x-8 gap-y-7 md:flex-nowrap">
              <div data-node="brief" className="studio-tile relative z-10 shrink-0 rounded-2xl p-3" style={{ ...STUDIO_PANEL, ['--lift' as string]: '-10px', width: 158 }}>
                <p className="text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{t('examples.campaign.contextLabel')}</p>
                <p className="mt-2 text-[11px] leading-snug" style={{ color: 'var(--text-secondary)' }}>{t('examples.campaign.contextValue')}</p>
                <p className="mt-2.5 flex items-center gap-1.5 text-[11px] font-semibold text-emerald-600 dark:text-emerald-400">
                  <Check className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  {t('examples.campaign.approvalLabel')}
                </p>
              </div>
              <div className="flex shrink-0 flex-col gap-2.5">
                {RECORDS.map((record, index) => {
                  const slug = CHANNEL_SLUGS[index];
                  const known = integration(slug);
                  return (
                    <div
                      key={record}
                      data-node={`channel-${slug}`}
                      className="studio-tile relative z-10 flex items-center gap-2.5 rounded-xl px-3 py-2.5"
                      style={{ ...STUDIO_PANEL, ['--lift' as string]: `${[-8, 6, -4][index]}px`, marginLeft: [0, 22, 8][index], width: 214 }}
                    >
                      {known && <BrandMark iconSlug={known.iconSlug} size={16} />}
                      <span className="min-w-0">
                        <span className="block truncate text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{t(`examples.campaign.records.${record}.label`)}</span>
                        <span className="block truncate text-[10px]" style={{ color: 'var(--text-muted)' }}>{t(`examples.campaign.records.${record}.value`)}</span>
                      </span>
                    </div>
                  );
                })}
              </div>
            </StudioWires>
          </StudioCard>
        </div>
      </div>

      <div className="grid gap-5 lg:grid-cols-5">
        {/* Every launch leaves a row behind, as the product's own table. */}
        <div className="min-w-0 lg:col-span-3">
          <StudioCard stage={STAGE} icon={Table2} {...{ label: t('studio.cards.table.label'), title: t('studio.cards.table.title'), summary: t('studio.cards.table.summary') }}>
            <StudioWires wires={TABLE_WIRES} className="flex w-full flex-col items-center gap-4">
              <div data-node="launch-chip" className="relative z-10 inline-flex items-center gap-2 rounded-full px-3 py-1.5" style={STUDIO_PANEL}>
                <Share2 className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />
                <span className="text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{t('examples.campaign.subject')}</span>
              </div>
              <HistoryTable node="history" prefix="marketing" snapshot={snapshot} height={262} />
            </StudioWires>
          </StudioCard>
        </div>

        {/* Three mentions come back, one of them wants an answer. */}
        <div className="min-w-0 lg:col-span-2">
          <StudioCard stage={STAGE} icon={MessageSquare} {...example('listening')}>
            <StudioWires wires={MENTION_WIRES} className="flex w-full items-center justify-center gap-6">
              <div className="studio-tile flex shrink-0 flex-col gap-2" style={{ ['--lift' as string]: '-12px' }}>
                {RECORDS.map((record, index) => {
                  const known = integration(MENTION_SLUGS[index]);
                  return (
                    <div
                      key={record}
                      data-node={`mention-${record}`}
                      className="relative z-10 rounded-xl px-2.5 py-2"
                      style={{ ...STUDIO_PANEL, marginLeft: index === 1 ? 18 : 0, width: 176, opacity: index === 0 ? 1 : 0.72 }}
                    >
                      <span className="flex items-center gap-1.5">
                        {known && <BrandMark iconSlug={known.iconSlug} size={12} />}
                        <span className="truncate text-[10.5px] font-semibold" style={{ color: 'var(--text-primary)' }}>{t(`examples.listening.records.${record}.label`)}</span>
                      </span>
                      <span className="mt-1 block text-[10px] leading-snug" style={{ color: 'var(--text-muted)' }}>{t(`examples.listening.records.${record}.detail`)}</span>
                    </div>
                  );
                })}
                <p className="flex items-center gap-1.5 pl-1 text-[10.5px]" style={{ color: 'var(--text-muted)' }}>
                  <Clock className="h-3 w-3 shrink-0" aria-hidden="true" />
                  {t('studio.cards.micro.waiting')}
                </p>
              </div>
              <PhoneTile node="phone" prefix="marketing" persona="marketing" example="listening" lift="10px" />
            </StudioWires>
          </StudioCard>
        </div>
      </div>
    </div>
  );
}
