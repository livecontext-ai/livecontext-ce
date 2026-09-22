'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { Bot, Check, Clock, Headphones, MonitorPlay, Smartphone, Table2, Zap } from 'lucide-react';
import { parsePresetValue } from '@/components/agents/avatarColors';
import { AVATAR_PRESETS } from '@/components/agents/AvatarPicker';
import { getAvatarTool } from '@/components/agents/avatarTools';
import { BrandMark } from '@/components/integrations/BrandMark';
import { WELL_KNOWN_INTEGRATIONS } from '@/lib/integrations/wellKnownIntegrations';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';
import StudioWires, { type Wire } from './StudioWires';
import { HistoryTable, PhoneTile, ScreenTile, STUDIO_PANEL, StudioCard, studioCss } from './studioParts';
import { buildPersonaInterfaceHtml } from './personaInterfaceHtml';
import { buildSupportSnapshot } from './WorkflowRecapPanel';
import { BUSINESS_DESTINATIONS, HERO_EXAMPLE_KEYS } from './personas';

/**
 * What a support team can build, in four artefacts the product actually produces.
 *
 * <p>Third variation of the same idea, and deliberately not the same drawing: creator fans
 * one photograph into five files, operations converges six tools into one screen, and
 * support follows ONE request through the four things that happen to it. An agent reads it,
 * a screen holds it, a phone decides it, a table remembers it.
 *
 * <p>Each card therefore leads with a different kind of object, which is the point: a
 * roster avatar, a rendered screen, a phone, a data table. A section where every card looks
 * alike is a section a visitor scrolls past after the first one.
 */
const EXAMPLE = HERO_EXAMPLE_KEYS.support;
const AGENT_AVATAR = 'preset:purple?tool=headset';
/** What the agent reads before anyone opens the request. */
const READS = ['zendesk', 'stripe', 'gmail'] as const;

const STAGE = { background: 'linear-gradient(135deg, rgba(139,92,246,.12), rgba(99,102,241,.08) 55%, rgba(14,165,233,.10))' };

const AGENT_WIRES: readonly Wire[] = READS.map((slug) => ({ from: `reads-${slug}`, to: 'agent' }));
const SCREEN_WIRES: readonly Wire[] = [{ from: 'trigger', to: 'screen' }];
const PHONE_WIRES: readonly Wire[] = [{ from: 'phone', to: 'decision' }];
const TABLE_WIRES: readonly Wire[] = [{ from: 'case', to: 'history' }];

/** The roster avatar, drawn the way the agents section draws it, minus its card. */
function AgentFace({ name }: { name: string }) {
  const parsed = parsePresetValue(AGENT_AVATAR);
  const preset = parsed ? AVATAR_PRESETS.find((candidate) => candidate.id === parsed.presetId) : null;
  const tool = parsed?.tool ? getAvatarTool(parsed.tool) : null;
  if (!preset) return null;
  const ToolIcon = tool?.Icon;
  return (
    <div className="relative h-[72px] w-[72px] shrink-0">
      <div className="h-full w-full overflow-hidden rounded-full" style={{ border: '1px solid var(--border-color)' }}>
        <img src={preset.image} alt={name} className="h-full w-full object-cover" />
      </div>
      {ToolIcon && (
        <span className="absolute -bottom-1 -right-1 grid h-6 w-6 place-items-center rounded-full" style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)' }}>
          <ToolIcon className="h-3.5 w-3.5" style={{ color: 'var(--text-secondary)' }} aria-hidden="true" />
        </span>
      )}
    </div>
  );
}

export default function SupportBuildStudio() {
  const t = useTranslations('PersonaLanding.personas.support.workflowShowcase');
  const preview = useTranslations('PersonaLanding.tablePreview');
  const { theme } = useLandingTheme();
  const html = useMemo(() => buildPersonaInterfaceHtml('support', theme, t as never, 'product', EXAMPLE), [theme, t]);
  const snapshot = useMemo(() => buildSupportSnapshot(EXAMPLE, preview as never), [preview]);
  const card = (key: string) => ({ label: t(`studio.cards.${key}.label`), title: t(`studio.cards.${key}.title`), summary: t(`studio.cards.${key}.summary`) });
  const destinations = BUSINESS_DESTINATIONS[EXAMPLE]
    .map((slug) => WELL_KNOWN_INTEGRATIONS.find((known) => known.slug === slug))
    .filter((integration): integration is (typeof WELL_KNOWN_INTEGRATIONS)[number] => Boolean(integration));

  return (
    <div className="flex flex-col gap-5">
      <style>{studioCss('support', { tableBody: 420, tableWidth: 900 })}</style>

      <div className="grid gap-5 lg:grid-cols-5">
        {/* Who reads it first, and what it reads. */}
        <div className="min-w-0 lg:col-span-2">
          <StudioCard stage={STAGE} icon={Bot} {...card('agent')}>
            <StudioWires wires={AGENT_WIRES} className="flex w-full items-center justify-center gap-7">
              <div className="studio-tile flex shrink-0 flex-col gap-1.5" style={{ ['--lift' as string]: '10px' }}>
                {READS.map((slug, index) => {
                  const integration = WELL_KNOWN_INTEGRATIONS.find((known) => known.slug === slug)!;
                  return (
                    <div key={slug} data-node={`reads-${slug}`} className="relative z-10 flex items-center gap-2 rounded-lg px-2.5 py-1.5" style={{ ...STUDIO_PANEL, marginLeft: index === 1 ? 16 : 0 }}>
                      <BrandMark iconSlug={integration.iconSlug} size={13} />
                      <span className="text-[10.5px]" style={{ color: 'var(--text-secondary)' }}>{integration.name}</span>
                    </div>
                  );
                })}
              </div>
              <div data-node="agent" className="studio-tile relative z-10 flex shrink-0 flex-col items-center gap-2.5 rounded-2xl px-4 py-4" style={{ ...STUDIO_PANEL, ['--lift' as string]: '-12px' }}>
                <AgentFace name={t(`examples.${EXAMPLE}.agentLabel`)} />
                <p className="max-w-[140px] text-center text-[11px] font-semibold leading-snug" style={{ color: 'var(--text-primary)' }}>
                  {t(`examples.${EXAMPLE}.agentLabel`)}
                </p>
              </div>
            </StudioWires>
          </StudioCard>
        </div>

        {/* The screen the case lands on. */}
        <div className="min-w-0 lg:col-span-3">
          <StudioCard stage={STAGE} icon={MonitorPlay} {...card('screen')}>
            <StudioWires wires={SCREEN_WIRES} className="flex w-full flex-wrap items-center justify-center gap-x-7 gap-y-6 md:flex-nowrap">
              <div data-node="trigger" className="studio-tile relative z-10 shrink-0 rounded-2xl p-3" style={{ ...STUDIO_PANEL, ['--lift' as string]: '22px', width: 148 }}>
                <p className="flex items-center gap-2 text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                  <Zap className="h-3.5 w-3.5 shrink-0 text-amber-500" aria-hidden="true" />
                  {t(`examples.${EXAMPLE}.triggerLabel`)}
                </p>
                <p className="mt-2 text-[11px] leading-snug" style={{ color: 'var(--text-secondary)' }}>{t(`examples.${EXAMPLE}.customerMessage`)}</p>
              </div>
              <ScreenTile node="screen" html={html} width={286} lift="-16px" />
            </StudioWires>
          </StudioCard>
        </div>
      </div>

      <div className="grid gap-5 lg:grid-cols-5">
        {/* What the case leaves behind, as the product's own table. The wide card leads this
            row while the narrow one led the first, so the section reads as a diagonal rather
            than as the same drawing twice. */}
        <div className="min-w-0 lg:col-span-3">
          <StudioCard stage={STAGE} icon={Table2} {...card('table')}>
            <StudioWires wires={TABLE_WIRES} className="flex w-full flex-col items-center gap-4">
              <div data-node="case" className="relative z-10 inline-flex items-center gap-2 rounded-full px-3 py-1.5" style={STUDIO_PANEL}>
                <Headphones className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />
                <span className="text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{t(`examples.${EXAMPLE}.subject`)}</span>
              </div>
              <HistoryTable node="history" prefix="support" snapshot={snapshot} height={256} />
            </StudioWires>
          </StudioCard>
        </div>

        {/* Where the decision is taken. */}
        <div className="min-w-0 lg:col-span-2">
          <StudioCard stage={STAGE} icon={Smartphone} {...card('phone')}>
            <StudioWires wires={PHONE_WIRES} className="flex w-full items-center justify-center gap-6">
              <PhoneTile node="phone" prefix="support" persona="support" example={EXAMPLE} lift="8px" />
              <div data-node="decision" className="studio-tile relative z-10 shrink-0 rounded-2xl p-3" style={{ ...STUDIO_PANEL, ['--lift' as string]: '-14px', width: 142 }}>
                <p className="flex items-center gap-2 text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                  <Clock className="h-3.5 w-3.5 shrink-0 text-amber-500" aria-hidden="true" />
                  {t('studio.cards.micro.waiting')}
                </p>
                <p className="mt-2.5 flex items-center gap-1.5 text-[11px] font-semibold text-emerald-600 dark:text-emerald-400">
                  <Check className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  {t(`examples.${EXAMPLE}.actionLabel`)}
                </p>
                <div className="mt-2.5 flex items-center gap-1.5">
                  {destinations.map((integration) => (
                    <span key={integration.slug} title={integration.name} className="inline-grid place-items-center h-6 w-6 rounded-md" style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)' }}>
                      <BrandMark iconSlug={integration.iconSlug} size={13} />
                    </span>
                  ))}
                </div>
              </div>
            </StudioWires>
          </StudioCard>
        </div>
      </div>
    </div>
  );
}
