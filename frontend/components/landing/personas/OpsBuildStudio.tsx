'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { BellOff, BellRing, CalendarClock, Check, Clock, Layers, Send, Table2 } from 'lucide-react';
import { BrandMark } from '@/components/integrations/BrandMark';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';
import StudioWires, { type Wire } from './StudioWires';
import { HistoryTable, PhoneTile, ScreenTile, STUDIO_PANEL, StudioCard, studioCss } from './studioParts';
import { buildPersonaInterfaceHtml } from './personaInterfaceHtml';
import { buildOpsSnapshot } from './WorkflowRecapPanel';

/**
 * What an operations team can build, drawn the way the orchestrator actually works.
 *
 * <p>Creator's section fans ONE photograph out into five files; operations is that picture
 * read backwards, six tools converging into one screen. Same wires, opposite movement, so
 * the two pages answer each other rather than repeating each other.
 *
 * <p>Four pain points, and each one is shown with the product's own artefact rather than an
 * illustration of it: the review screen is rendered from the same HTML the hero's interface
 * node renders, the history is the real data table with its photos and progress bars, and
 * the escalation is the phone the hero approves on. For operations there is nothing to
 * photograph, so the screen IS the picture, which is also why the stage takes most of each
 * card and the text sits under it in a single quiet line.
 */
const SOURCES = ['google-sheets', 'stripe', 'hubspot', 'shopify', 'zendesk', 'slack'] as const;
const WEEKS = [
  { label: 'S33', state: 'done' },
  { label: 'S34', state: 'done' },
  { label: 'S35', state: 'done' },
  { label: 'S36', state: 'done' },
  { label: 'S37', state: 'running' },
  { label: 'S38', state: 'next' },
] as const;
/** Five quiet runs and the one that found something: the shape of a useful alert. */
const CHECKS = ['done', 'done', 'done', 'alert', 'done'] as const;

const GATHER_WIRES: readonly Wire[] = [
  ...SOURCES.map((slug) => ({ from: `source-${slug}`, to: 'report' })),
  { from: 'report', to: 'approval' },
];
const ALERT_WIRES: readonly Wire[] = [{ from: 'check-3', to: 'phone' }];
const TABLE_WIRES: readonly Wire[] = [{ from: 'run-chip', to: 'history' }];
const SCHEDULE_WIRES: readonly Wire[] = WEEKS.slice(0, -1).map((week, index) => ({
  from: `week-${week.label}`,
  to: `week-${WEEKS[index + 1].label}`,
}));

const STAGE = { background: 'linear-gradient(135deg, rgba(20,108,148,.10), rgba(16,185,129,.07) 55%, rgba(99,102,241,.10))' };

export default function OpsBuildStudio() {
  const t = useTranslations('PersonaLanding.personas.ops.workflowShowcase');
  const common = useTranslations('PersonaLanding.tablePreview');
  const { theme } = useLandingTheme();
  const html = useMemo(() => buildPersonaInterfaceHtml('ops', theme, t as never, 'product', 'report'), [theme, t]);
  // The history card shows the product's real table, built from the same snapshot the
  // hero's side panel opens on, so the two cannot drift apart.
  const snapshot = useMemo(() => buildOpsSnapshot('report', common as never), [common]);
  const card = (key: string) => ({ label: t(`studio.cards.${key}.label`), title: t(`studio.cards.${key}.title`), summary: t(`studio.cards.${key}.summary`) });

  return (
    <div className="flex flex-col gap-5">
      <style>{studioCss('ops')}</style>

      <div className="grid gap-5 lg:grid-cols-5">
        {/* Six tools in, one screen out. */}
        <div className="min-w-0 lg:col-span-3">
          <StudioCard stage={STAGE} icon={Layers} {...card('gather')}>
            <StudioWires wires={GATHER_WIRES} className="flex w-full flex-wrap items-center justify-center gap-x-5 gap-y-8 md:flex-nowrap">
              <div className="studio-tile grid shrink-0 grid-cols-1 gap-1.5" style={{ ['--lift' as string]: '16px' }}>
                {SOURCES.map((slug) => {
                  const integration = WELL_KNOWN_INTEGRATIONS.find((known) => known.slug === slug)!;
                  return (
                    <div key={slug} data-node={`source-${slug}`} className="relative z-10 flex items-center gap-2 rounded-xl px-2.5 py-2" style={STUDIO_PANEL}>
                      <BrandMark iconSlug={integration.iconSlug} size={14} />
                      <span className="text-[11px] font-medium" style={{ color: 'var(--text-secondary)' }}>{integration.name}</span>
                    </div>
                  );
                })}
              </div>
              <ScreenTile node="report" html={html} width={238} lift="-18px" />
              <div data-node="approval" className="studio-tile relative z-10 shrink-0 rounded-2xl p-3" style={{ ...STUDIO_PANEL, ['--lift' as string]: '26px', width: 136 }}>
                <p className="flex items-center gap-2 text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                  <Clock className="h-3.5 w-3.5 shrink-0 text-amber-500" aria-hidden="true" />
                  {t('examples.report.approvalLabel')}
                </p>
                <p className="mt-2 text-[11px] leading-snug" style={{ color: 'var(--text-secondary)' }}>{t('examples.report.phoneMessage')}</p>
                <p className="mt-3 flex items-center gap-1.5 text-[11px] font-semibold text-emerald-600 dark:text-emerald-400">
                  <Check className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  {t('studio.cards.micro.done')}
                </p>
              </div>
            </StudioWires>
          </StudioCard>
        </div>

        {/* Quiet runs, and the one that reaches a phone. */}
        <div className="min-w-0 lg:col-span-2">
          <StudioCard stage={STAGE} icon={BellRing} {...card('alert')}>
            <StudioWires wires={ALERT_WIRES} className="flex w-full items-center justify-center gap-6">
              <div className="studio-tile flex shrink-0 flex-col gap-2" style={{ ['--lift' as string]: '-14px' }}>
                {CHECKS.map((state, index) => (
                  <div
                    key={index}
                    data-node={`check-${index}`}
                    className="relative z-10 flex items-center gap-2 rounded-lg px-2.5 py-1.5"
                    style={{ ...STUDIO_PANEL, marginLeft: index % 2 ? 18 : 0 }}
                  >
                    {state === 'alert'
                      ? <Send className="h-3.5 w-3.5 shrink-0 text-amber-500" aria-hidden="true" />
                      : <BellOff className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />}
                    <span className="text-[10.5px]" style={{ color: state === 'alert' ? 'var(--text-primary)' : 'var(--text-muted)' }}>
                      {state === 'alert' ? t('studio.cards.micro.escalated') : t('studio.cards.micro.silent')}
                    </span>
                  </div>
                ))}
              </div>
              <PhoneTile node="phone" prefix="ops" persona="ops" example="report" lift="14px" />
            </StudioWires>
          </StudioCard>
        </div>
      </div>

      <div className="grid gap-5 lg:grid-cols-5">
        {/* The history, as the product's own table. The wide card swaps sides on this row
            so the two rows are not the same drawing twice. */}
        <div className="min-w-0 lg:order-2 lg:col-span-3">
          <StudioCard stage={STAGE} icon={Table2} {...card('table')}>
            <StudioWires wires={TABLE_WIRES} className="flex w-full flex-col items-center gap-4">
              <div data-node="run-chip" className="relative z-10 inline-flex items-center gap-2 rounded-full px-3 py-1.5" style={STUDIO_PANEL}>
                <CalendarClock className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />
                <span className="text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{t('examples.report.triggerLabel')}</span>
              </div>
              <HistoryTable node="history" prefix="ops" snapshot={snapshot} height={268} />
            </StudioWires>
          </StudioCard>
        </div>

        {/* The schedule, as the run it fires every week. */}
        <div className="min-w-0 lg:order-1 lg:col-span-2">
          <StudioCard stage={STAGE} icon={CalendarClock} {...card('schedule')}>
            <div className="flex w-full flex-col items-center gap-5">
              <span className="inline-flex items-center gap-2 rounded-full px-3 py-1.5" style={STUDIO_PANEL}>
                <CalendarClock className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />
                <span className="text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{t('examples.report.triggerLabel')}</span>
              </span>
              <StudioWires wires={SCHEDULE_WIRES} className="flex w-full items-center justify-center gap-x-3">
                {WEEKS.map((week) => (
                  <div
                    key={week.label}
                    data-node={`week-${week.label}`}
                    className="studio-tile relative z-10 flex shrink-0 flex-col items-center gap-1 rounded-xl px-2 py-2"
                    style={{ ...STUDIO_PANEL, ['--lift' as string]: `${[-12, 10, -6, 14, -10, 6][WEEKS.indexOf(week)]}px`, opacity: week.state === 'next' ? 0.6 : 1 }}
                  >
                    <span className="text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{week.label}</span>
                    {week.state === 'done' && <Check className="h-3.5 w-3.5 shrink-0 text-emerald-500" aria-hidden="true" />}
                    {week.state === 'running' && <Clock className="h-3.5 w-3.5 shrink-0 text-amber-500" aria-hidden="true" />}
                    {week.state === 'next' && <CalendarClock className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />}
                  </div>
                ))}
              </StudioWires>
              <p className="text-[11px]" style={{ color: 'var(--text-muted)' }}>{t('studio.cards.micro.next')} : {WEEKS[WEEKS.length - 1].label}</p>
            </div>
          </StudioCard>
        </div>
      </div>
    </div>
  );
}
