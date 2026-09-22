'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { CalendarClock, Check, ClipboardCheck, Table2, Users } from 'lucide-react';
import { BrandMark } from '@/components/integrations/BrandMark';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';
import StudioWires, { type Wire } from './StudioWires';
import { HistoryTable, PhoneTile, ScreenTile, STUDIO_PANEL, StudioCard, studioCss } from './studioParts';
import { buildPersonaInterfaceHtml } from './personaInterfaceHtml';
import { buildRecruitingSnapshot, CANDIDATE_KEYS, CANDIDATE_PHOTOS } from './WorkflowRecapPanel';
import { BUSINESS_DESTINATIONS } from './personas';

/**
 * What a recruiting team can build, with the people it is about kept visible.
 *
 * <p>The other four sections show documents, screens and rows. This one has faces, and
 * hiding them behind a generic tile would be the one dishonest choice available here: a
 * shortlist IS a set of people, so the card that opens the section is their portraits,
 * feeding the CV the reviewer actually opens.
 *
 * <p>The portraits and the names come from the same roster the table below uses, so the
 * face beside a name on the card is the face beside that name in the table.
 */
const SHORTLISTED = CANDIDATE_KEYS.slice(0, 3);
const RECORDS = ['first', 'second', 'third'] as const;
/** The slot the scheduling step proposes, which is the one the phone is asked about. */
const CHOSEN_SLOT = 1;

const STAGE = { background: 'linear-gradient(135deg, rgba(59,130,246,.11), rgba(6,182,212,.07) 55%, rgba(16,185,129,.10))' };

const SHORTLIST_WIRES: readonly Wire[] = SHORTLISTED.map((candidate) => ({ from: `candidate-${candidate}`, to: 'cv' }));
const INTERVIEW_WIRES: readonly Wire[] = [{ from: `slot-${RECORDS[CHOSEN_SLOT]}`, to: 'phone' }];
const ONBOARDING_WIRES: readonly Wire[] = RECORDS.map((record) => ({ from: `task-${record}`, to: 'welcome' }));
const TABLE_WIRES: readonly Wire[] = [{ from: 'role-chip', to: 'history' }];

export default function RecruitingBuildStudio() {
  const t = useTranslations('PersonaLanding.personas.recruiting.workflowShowcase');
  const preview = useTranslations('PersonaLanding.tablePreview');
  const { theme } = useLandingTheme();
  const html = useMemo(() => buildPersonaInterfaceHtml('recruiting', theme, t as never, 'product', 'shortlist'), [theme, t]);
  const snapshot = useMemo(() => buildRecruitingSnapshot('shortlist', preview as never), [preview]);
  const example = (key: string) => ({ label: t(`examples.${key}.label`), title: t(`examples.${key}.title`), summary: t(`examples.${key}.summary`) });
  const destinations = BUSINESS_DESTINATIONS.onboarding
    .map((slug) => WELL_KNOWN_INTEGRATIONS.find((known) => known.slug === slug))
    .filter((known): known is NonNullable<typeof known> => Boolean(known));

  return (
    <div className="flex flex-col gap-5">
      <style>{studioCss('recruiting', { tableBody: 430, tableWidth: 900 })}</style>

      <div className="grid gap-5 lg:grid-cols-5">
        {/* The people, then the CV the reviewer opens. */}
        <div className="min-w-0 lg:col-span-3">
          <StudioCard stage={STAGE} icon={Users} {...example('shortlist')}>
            <StudioWires wires={SHORTLIST_WIRES} className="flex w-full flex-wrap items-center justify-center gap-x-8 gap-y-7 md:flex-nowrap">
              <div className="flex shrink-0 flex-col gap-2.5">
                {SHORTLISTED.map((candidate, index) => (
                  <div
                    key={candidate}
                    data-node={`candidate-${candidate}`}
                    className="studio-tile relative z-10 flex items-center gap-2.5 rounded-xl px-3 py-2"
                    style={{ ...STUDIO_PANEL, ['--lift' as string]: `${[-10, 8, -4][index]}px`, marginLeft: [0, 20, 6][index], width: 196 }}
                  >
                    <img
                      src={CANDIDATE_PHOTOS[index]}
                      alt={preview(`recruiting.candidates.${candidate}.name`)}
                      width={36}
                      height={36}
                      loading="lazy"
                      className="h-9 w-9 shrink-0 rounded-full object-cover"
                      style={{ border: '1px solid var(--border-color)' }}
                    />
                    <span className="min-w-0">
                      <span className="block truncate text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{preview(`recruiting.candidates.${candidate}.name`)}</span>
                      <span className="block truncate text-[10px]" style={{ color: 'var(--text-muted)' }}>{preview(`recruiting.candidates.${candidate}.role`)}</span>
                    </span>
                  </div>
                ))}
              </div>
              <ScreenTile node="cv" html={html} width={228} lift="-16px" />
            </StudioWires>
          </StudioCard>
        </div>

        {/* Three times offered, one proposed, approved from a phone. */}
        <div className="min-w-0 lg:col-span-2">
          <StudioCard stage={STAGE} icon={CalendarClock} {...example('interview')}>
            <StudioWires wires={INTERVIEW_WIRES} className="flex w-full items-center justify-center gap-6">
              <div className="studio-tile flex shrink-0 flex-col gap-2" style={{ ['--lift' as string]: '-12px' }}>
                {RECORDS.map((record, index) => (
                  <div
                    key={record}
                    data-node={`slot-${record}`}
                    className="relative z-10 flex items-center gap-2 rounded-xl px-2.5 py-2"
                    style={{
                      ...STUDIO_PANEL,
                      marginLeft: index === 1 ? 18 : 0,
                      width: 168,
                      opacity: index === CHOSEN_SLOT ? 1 : 0.7,
                      borderColor: index === CHOSEN_SLOT ? 'color-mix(in srgb, rgb(16,185,129) 45%, var(--border-color))' : undefined,
                    }}
                  >
                    {index === CHOSEN_SLOT
                      ? <Check className="h-3.5 w-3.5 shrink-0 text-emerald-500" aria-hidden="true" />
                      : <CalendarClock className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />}
                    <span className="min-w-0">
                      <span className="block text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{t(`examples.interview.records.${record}.label`)}</span>
                      {/* Only the proposed slot says more: the other two are times on offer,
                          and the record's second field describes the meeting, not them. */}
                      {index === CHOSEN_SLOT && <span className="block truncate text-[10px]" style={{ color: 'var(--text-muted)' }}>{t('examples.interview.meeting.location')}</span>}
                    </span>
                  </div>
                ))}
              </div>
              <PhoneTile node="phone" prefix="recruiting" persona="recruiting" example="interview" lift="10px" />
            </StudioWires>
          </StudioCard>
        </div>
      </div>

      <div className="grid gap-5 lg:grid-cols-5">
        {/* The first week, prepared before the first day. */}
        <div className="min-w-0 lg:col-span-2">
          <StudioCard stage={STAGE} icon={ClipboardCheck} {...example('onboarding')}>
            <StudioWires wires={ONBOARDING_WIRES} className="flex w-full items-center justify-center gap-6">
              <div className="studio-tile flex shrink-0 flex-col gap-2" style={{ ['--lift' as string]: '10px' }}>
                {RECORDS.map((record, index) => (
                  <div key={record} data-node={`task-${record}`} className="relative z-10 flex items-center gap-2 rounded-lg px-2.5 py-1.5" style={{ ...STUDIO_PANEL, marginLeft: index === 1 ? 16 : 0, width: 168 }}>
                    <Check className="h-3.5 w-3.5 shrink-0 text-emerald-500" aria-hidden="true" />
                    <span className="truncate text-[10.5px]" style={{ color: 'var(--text-secondary)' }}>{t(`examples.onboarding.records.${record}.label`)}</span>
                  </div>
                ))}
              </div>
              <div data-node="welcome" className="studio-tile relative z-10 shrink-0 rounded-2xl p-3" style={{ ...STUDIO_PANEL, ['--lift' as string]: '-14px', width: 138 }}>
                <p className="text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{t('examples.onboarding.plan.startLabel')}</p>
                <p className="mt-0.5 text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>{t('examples.onboarding.plan.startDate')}</p>
                <p className="mt-2 flex items-center gap-1.5 text-[11px] font-semibold text-emerald-600 dark:text-emerald-400">
                  <Check className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  {t('examples.onboarding.approvalLabel')}
                </p>
                <div className="mt-2.5 flex items-center gap-1.5">
                  {destinations.map((known) => (
                    <span key={known.slug} title={known.name} className="inline-grid place-items-center h-6 w-6 rounded-md" style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)' }}>
                      <BrandMark iconSlug={known.iconSlug} size={13} />
                    </span>
                  ))}
                </div>
              </div>
            </StudioWires>
          </StudioCard>
        </div>

        {/* The roster, as the product's own table, portraits included. */}
        <div className="min-w-0 lg:col-span-3">
          <StudioCard stage={STAGE} icon={Table2} {...{ label: t('studio.cards.table.label'), title: t('studio.cards.table.title'), summary: t('studio.cards.table.summary') }}>
            <StudioWires wires={TABLE_WIRES} className="flex w-full flex-col items-center gap-4">
              <div data-node="role-chip" className="relative z-10 inline-flex items-center gap-2 rounded-full px-3 py-1.5" style={STUDIO_PANEL}>
                <Users className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />
                <span className="text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{t('examples.shortlist.subject')}</span>
              </div>
              <HistoryTable node="history" prefix="recruiting" snapshot={snapshot} height={262} />
            </StudioWires>
          </StudioCard>
        </div>
      </div>
    </div>
  );
}
