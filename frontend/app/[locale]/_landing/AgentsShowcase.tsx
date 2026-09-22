'use client';

/**
 * Landing "Agents" showcase: a LIVE, interactive replica of the /app/agent view,
 * built from the real app building blocks (AVATAR_PRESETS svgs, gradient
 * recoloring, tool badges, the real card markup and tab bar geometry) inside a
 * browser window framed like the hero showcase: same left icon rail with the
 * LiveContext logo on top and the account circle at the bottom. Not a
 * screenshot: favorite stars reorder and cards select, like in the product.
 * One deliberate demo deviation: card click toggles SELECTION here (in-app it
 * opens the agent side panel, which has no landing equivalent), so visitors
 * get visible feedback from every click.
 *
 * The homepage keeps its original English demo without requiring intl context.
 * Persona pages reuse the app's translated controls and avatar tool labels.
 */

import React, { useEffect, useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { BarChart3, Bot, CalendarClock, Globe, Lock, Network, Star, Webhook, Zap } from 'lucide-react';
import {
  parsePresetValue,
  getAvatarGradient,
  buildRecoloredPresetDataUri,
  type AvatarCustomColors,
} from '@/components/agents/avatarColors';
import { getAvatarTool } from '@/components/agents/avatarTools';
import { AVATAR_PRESETS } from '@/components/agents/AvatarPicker';
import { favoritesFirst } from '@/lib/utils/listSort';
import type { PersonaKey } from '@/components/landing/personas/personas';
import LandingSidebarRail from './LandingSidebarRail';

// ---------------------------------------------------------------------------
// Demo team: same value format as real agents (preset:<name>?c1=..&tool=..),
// so every avatar renders through the exact production pipeline, including
// the custom-color recolor path (the research avatar) and the tool badges.
//
// Each card is titled by the JOB, not by a first name. A roster of Nova, Atlas and Scout
// reads as a cast of characters, and a visitor had to read the line underneath to learn
// what any of them was for; the title carries that now, and the line adds the detail.
// The `id` keeps the old handles: it is the React key and the avatar lookup, not copy.
// ---------------------------------------------------------------------------

interface DemoAgent {
  id: string;
  name: string;
  description: string;
  avatarUrl: string;
  model: string;
  shared: boolean;
  webhook?: boolean;
  schedule?: boolean;
}

/**
 * The roster the showcase draws when it is not given one.
 *
 * <p>Exported for its TEST only: `demoRosterIds.ts` holds the ids a server component reads,
 * and the suite pins that this array is still in that order, because the translated roster
 * the home page passes is paired with this one by position.
 */
export const DEMO_AGENTS: DemoAgent[] = [
  {
    id: 'nova',
    name: 'Customer support',
    description: 'Support triage, answers or escalates',
    avatarUrl: 'preset:purple?tool=headset',
    model: 'anthropic/claude-sonnet-5',
    shared: true,
    webhook: true,
  },
  {
    id: 'atlas',
    name: 'Lead qualification',
    description: 'Enriches every new lead',
    avatarUrl: 'preset:green?tool=chart',
    model: 'openai/gpt-5.6',
    shared: false,
    schedule: true,
  },
  {
    id: 'scout',
    name: 'Research',
    description: 'Deep research with sources',
    avatarUrl: 'preset:blue?c1=0EA5E9&c2=1E40AF&tool=search',
    model: 'anthropic/claude-opus-4-8',
    shared: true,
  },
  {
    id: 'ember',
    name: 'Content writing',
    description: 'Drafts posts in your voice',
    avatarUrl: 'preset:burgundy?tool=pen',
    model: 'deepseek/deepseek-chat',
    shared: false,
    schedule: true,
  },
  {
    id: 'orion',
    name: 'Code review',
    description: 'Reviews every pull request',
    avatarUrl: 'preset:indigo?tool=code',
    model: 'anthropic/claude-sonnet-5',
    shared: false,
    webhook: true,
  },
  {
    id: 'sol',
    name: 'Social publishing',
    description: 'Schedules and publishes socials',
    avatarUrl: 'preset:sunshine?tool=megaphone',
    model: 'google/gemini-2.5-pro',
    shared: true,
    schedule: true,
  },
  {
    id: 'helix',
    name: 'Software testing',
    description: 'Runs the nightly test suite',
    avatarUrl: 'preset:teal?tool=flask',
    model: 'openai/gpt-5.6',
    shared: false,
    schedule: true,
  },
  {
    id: 'aurora',
    name: 'Shared inbox',
    description: 'Answers the shared inbox',
    avatarUrl: 'preset:emerald?tool=mail',
    model: 'anthropic/claude-haiku-4-5',
    shared: true,
    webhook: true,
  },
  {
    id: 'midas',
    name: 'Invoice follow-up',
    description: 'Chases unpaid invoices',
    avatarUrl: 'preset:gold?tool=dollar',
    model: 'openai/gpt-5.6',
    shared: false,
    schedule: true,
  },
  {
    id: 'drift',
    name: 'Competitive watch',
    description: 'Watches competitor pages',
    avatarUrl: 'preset:cyan?tool=globe',
    model: 'google/gemini-2.5-pro',
    shared: true,
    schedule: true,
  },
  {
    id: 'sensei',
    name: 'Brand review',
    description: 'Guardrails drafts before they ship',
    avatarUrl: 'preset:slate?tool=shield',
    model: 'anthropic/claude-sonnet-5',
    shared: false,
    webhook: true,
  },
  {
    id: 'fizz',
    name: 'Campaign visuals',
    description: 'Generates the campaign visuals',
    avatarUrl: 'preset:bubblegum?tool=palette',
    model: 'deepseek/deepseek-chat',
    shared: true,
  },
];

// Tool badge tooltips: marketing-friendly role descriptions for the landing
// visitor. Deliberately NOT the terse in-app avatarPicker.tools.<id> labels
// ("Headset", "Chart", ...), which name the icon rather than the agent's job.
const TOOL_LABELS: Record<string, string> = {
  headset: 'Customer support',
  chart: 'Analytics',
  search: 'Research',
  pen: 'Writing',
  code: 'Code',
  megaphone: 'Marketing',
  flask: 'Testing',
  mail: 'Email',
  dollar: 'Finance',
  globe: 'Web browsing',
  shield: 'Compliance',
  palette: 'Design',
};

// Twelve role avatars fill the same roster as the homepage. Their order matches
// each persona's translated team; distinct existing presets keep adjacent cards
// recognizable even where the cropped preview truncates their names.
const PERSONA_AVATARS: Record<PersonaKey, readonly string[]> = {
  // Retuned when the ops roster was written: this row predates it, so its tools were paired
  // with nothing and landed on the wrong jobs (a chart on Intake, money on Reports, a pen on
  // Quality), and slot five asked for `tool=table`, which is not a tool the picker has, so
  // that card was the only one in the section with no badge at all.
  ops: [
    'preset:teal?tool=mail', 'preset:blue?tool=git-branch', 'preset:slate?tool=shopping-cart', 'preset:green?tool=truck',
    'preset:indigo?tool=dollar', 'preset:gold?tool=chart', 'preset:purple?tool=book', 'preset:emerald?tool=calendar',
    'preset:cyan?tool=database', 'preset:burgundy?tool=shield', 'preset:sunshine?tool=zap', 'preset:bubblegum?tool=handshake',
  ],
  creator: [
    'preset:burgundy?tool=pen', 'preset:bubblegum?tool=palette', 'preset:blue?tool=film', 'preset:teal?tool=languages',
    'preset:purple?tool=pen', 'preset:indigo?tool=camera', 'preset:green?tool=book', 'preset:emerald?tool=mail',
    'preset:gold?tool=paintbrush', 'preset:cyan?tool=newspaper', 'preset:slate?tool=calendar', 'preset:sunshine?tool=megaphone',
  ],
  support: [
    'preset:purple?tool=headset', 'preset:blue?tool=pen', 'preset:gold?tool=dollar', 'preset:green?tool=shopping-cart',
    'preset:indigo?tool=book', 'preset:burgundy?tool=phone', 'preset:emerald?tool=mail', 'preset:bubblegum?tool=heart',
    'preset:cyan?tool=newspaper', 'preset:slate?tool=shield', 'preset:sunshine?tool=zap', 'preset:teal?tool=chart',
  ],
  sales: [
    'preset:green?tool=target', 'preset:blue?tool=search', 'preset:indigo?tool=briefcase', 'preset:burgundy?tool=pen',
    'preset:emerald?tool=mail', 'preset:purple?tool=calendar', 'preset:gold?tool=newspaper', 'preset:teal?tool=database',
    'preset:slate?tool=handshake', 'preset:sunshine?tool=phone', 'preset:cyan?tool=chart', 'preset:bubblegum?tool=calculator',
  ],
  marketing: [
    'preset:blue?tool=search', 'preset:burgundy?tool=pen', 'preset:sunshine?tool=megaphone', 'preset:emerald?tool=mail',
    'preset:bubblegum?tool=palette', 'preset:indigo?tool=globe', 'preset:teal?tool=film', 'preset:slate?tool=shield',
    'preset:purple?tool=calendar', 'preset:gold?tool=rocket', 'preset:green?tool=compass', 'preset:cyan?tool=chart',
  ],
  recruiting: [
    'preset:emerald?tool=mail', 'preset:blue?tool=newspaper', 'preset:slate?tool=search', 'preset:bubblegum?tool=pen',
    'preset:purple?tool=calendar', 'preset:sunshine?tool=zap', 'preset:cyan?tool=phone', 'preset:burgundy?tool=megaphone',
    'preset:green?tool=target', 'preset:gold?tool=handshake', 'preset:indigo?tool=database', 'preset:teal?tool=chart',
  ],
};

const DEFAULT_COPY = {
  select: (name: string) => `Select ${name}`,
  star: (name: string) => `Star ${name}`,
  unstar: (name: string) => `Unstar ${name}`,
  published: 'Published to the marketplace', private: 'Private',
  webhook: 'Webhook trigger active', scheduled: 'Schedule trigger active',
  tool: (id: string) => TOOL_LABELS[id] ?? id,
  tab: (key: string) => APP_TABS.find((tab) => tab.key === key)!.label as string,
};
type AgentsCopy = typeof DEFAULT_COPY;

// ---------------------------------------------------------------------------
// Avatar renderer: the production AvatarDisplay pipeline (preset svg, optional
// gradient recolor, tool badge) without its next-intl dependency.
// ---------------------------------------------------------------------------

function RecoloredPresetImage({
  presetId,
  image,
  colors,
  alt,
}: {
  presetId: string;
  image: string;
  colors: AvatarCustomColors;
  alt: string;
}) {
  const [dataUri, setDataUri] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    buildRecoloredPresetDataUri(presetId, image, colors).then((uri) => {
      if (!cancelled) setDataUri(uri);
    });
    return () => {
      cancelled = true;
    };
  }, [presetId, image, colors.c1, colors.c2]);

  return <img src={dataUri ?? image} alt={alt} className="w-full h-full object-cover" />;
}

function LandingAgentAvatar({ avatarUrl, name, copy }: { avatarUrl: string; name: string; copy: AgentsCopy }) {
  const parsed = parsePresetValue(avatarUrl);
  const preset = parsed ? AVATAR_PRESETS.find((p) => p.id === parsed.presetId) : null;
  if (!parsed || !preset) return null;

  const tool = getAvatarTool(parsed.tool);
  const gradient = getAvatarGradient(avatarUrl);
  const toolLabel = parsed.tool ? copy.tool(parsed.tool) : undefined;

  return (
    // Sized to the compact card (the grid shows 4 columns of a 12-agent roster,
    // so the avatar is the app's, scaled down, not the app's 80px one).
    <div className="relative w-[52px] h-[52px] flex-shrink-0">
      <div className="w-full h-full rounded-full overflow-hidden">
        {parsed.colors ? (
          <RecoloredPresetImage presetId={preset.id} image={preset.image} colors={parsed.colors} alt={name} />
        ) : (
          <img src={preset.image} alt={name} className="w-full h-full object-cover" />
        )}
      </div>
      {tool && (
        <span
          title={toolLabel}
          aria-label={toolLabel}
          className="absolute -bottom-0.5 -right-0.5 z-10 flex h-[34%] w-[34%] items-center justify-center rounded-full border-2"
          style={{
            backgroundColor: gradient ? gradient[0] : 'var(--accent-primary)',
            borderColor: 'var(--bg-primary)',
          }}
        >
          <tool.Icon className="h-[58%] w-[58%] text-white" strokeWidth={2.5} />
        </span>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// App chrome: the shared icon rail (LandingSidebarRail, Agents active) and the
// real /app/agent tab bar.
// ---------------------------------------------------------------------------

const APP_TABS = [
  { key: 'agents', label: 'Agents', Icon: Bot },
  { key: 'skills', label: 'Skills', Icon: Zap },
  { key: 'fleet', label: 'Fleet', Icon: Network },
  { key: 'metrics', label: 'Metrics', Icon: BarChart3 },
] as const;

// ---------------------------------------------------------------------------
// The app window: sidebar rail + tab bar + interactive card grid, replicating
// the real /app/agent markup with landing theme tokens.
// ---------------------------------------------------------------------------

type ShowcaseTeam = readonly Pick<DemoAgent, 'name' | 'description'>[];

export default function AgentsShowcase({ team, persona, locale }: { team?: ShowcaseTeam; persona?: PersonaKey; locale?: string }) {
  return persona || locale
    ? <LocalizedAgentsShowcase key={`${persona ?? ''}:${locale ?? ''}`} team={team} persona={persona} />
    : <AgentsShowcaseFrame team={team} />;
}

function LocalizedAgentsShowcase({ team, persona }: { team?: ShowcaseTeam; persona?: PersonaKey }) {
  const t = useTranslations('PersonaLanding.agents');
  const common = useTranslations('common');
  const tabs = useTranslations('emptyState.agent');
  const tools = useTranslations('avatarPicker.tools');
  const copy: AgentsCopy = {
    select: (name) => t('select', { name }), star: (name) => t('star', { name }), unstar: (name) => t('unstar', { name }),
    published: t('published'), private: common('visibilityPrivate'), webhook: t('webhook'), scheduled: t('scheduled'),
    tool: (id) => tools(id), tab: (key) => tabs(`tab${key.charAt(0).toUpperCase()}${key.slice(1)}`),
  };
  return <AgentsShowcaseFrame team={team} persona={persona} copy={copy} />;
}

function AgentsShowcaseFrame({ team, persona, copy = DEFAULT_COPY }: { team?: ShowcaseTeam; persona?: PersonaKey; copy?: AgentsCopy }) {
  return (
    // The sim.ai crop: the window sits inside a soft backdrop box, anchored
    // near the top-left and wider than the box, so it bleeds off the right and
    // bottom edges and reads as a glimpse into the real app.
    <div
      // No backdrop of its own: the window floats directly on the section's
      // hero background, the box only provides the sim.ai crop.
      className="relative aspect-square md:aspect-[4/3] lg:aspect-[4/3] overflow-hidden rounded-xl"
      // Overflow-hidden boxes still scroll PROGRAMMATICALLY: focusing a cropped
      // card (Tab key, automation) triggers scroll-into-view and shifts the
      // whole window out of frame. Pin the crop in place.
      onScroll={(e) => {
        e.currentTarget.scrollLeft = 0;
        e.currentTarget.scrollTop = 0;
      }}
    >
      <div className="absolute top-[6%] left-[5%] w-[130%] md:w-[118%]">
        <AgentsAppWindow team={team} persona={persona} copy={copy} />
      </div>
    </div>
  );
}

// Bare app window: no browser chrome (traffic lights / URL bar), just the app
// frame itself. The .browser-frame rule from landingStyles still provides the
// rounded border, surface and frame shadow.
function AgentsAppWindow({ team, persona, copy }: { team?: ShowcaseTeam; persona?: PersonaKey; copy: AgentsCopy }) {
  const localized = copy !== DEFAULT_COPY;
  const [favorites, setFavorites] = useState<Set<string>>(() => new Set([localized && team?.length ? 'member-0' : 'nova']));
  const [selected, setSelected] = useState<Set<string>>(() => new Set());

  // Favorites float to the top through the same helper the real page uses.
  const visibleAgents = useMemo(
    () => favoritesFirst(
      team ? team.map((member, index) => ({
        ...DEMO_AGENTS[index % DEMO_AGENTS.length], ...member, id: `member-${index}`,
        avatarUrl: persona ? PERSONA_AVATARS[persona][index % PERSONA_AVATARS[persona].length] : DEMO_AGENTS[index % DEMO_AGENTS.length].avatarUrl,
      })) : DEMO_AGENTS,
      (a) => a.id,
      favorites,
    ),
    [favorites, team, persona],
  );

  const toggleFavorite = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setFavorites((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelected = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  return (
    // Override the default .browser-frame shadow (0 20px 60px): its wide blur
    // reads as a halo behind the floating window. A tight, faint shadow keeps
    // just enough depth; the 1px frame border does the delimiting.
    <figure className="browser-frame" style={{ boxShadow: '0 2px 12px rgba(28, 26, 23, 0.06)' }}>
      <div className="browser-body flex">
        <LandingSidebarRail activeView="agent" />

        <div className="min-w-0 flex-1">
          {/* Tab bar, same geometry as AgentPageTabBar */}
          <div className="flex items-center gap-1 border-b px-3 overflow-hidden" style={{ borderColor: 'var(--border-color)' }}>
            {APP_TABS.map(({ key, Icon }) => (
              <span
                key={key}
                className="inline-flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 -mb-px whitespace-nowrap flex-shrink-0"
                style={
                  key === 'agents'
                    ? { borderColor: 'var(--accent-primary)', color: 'var(--text-primary)' }
                    : { borderColor: 'transparent', color: 'var(--text-muted)' }
                }
              >
                <Icon className="h-3.5 w-3.5" />
                {copy.tab(key)}
              </span>
            ))}
          </div>

          {/* Card grid: the real /app/agent card markup, compacted (tighter
              gutters, shorter preview, smaller avatar) so a whole roster fits
              in the crop instead of a handful of oversized cards. */}
          <div className="grid grid-cols-3 gap-2.5 p-3 md:grid-cols-4">
            {visibleAgents.map((agent) => {
              const isSelected = selected.has(agent.id);
              const isFavorite = favorites.has(agent.id);
              return (
                <div
                  key={agent.id}
                  role="button"
                  tabIndex={0}
                  aria-pressed={isSelected}
                  onClick={() => toggleSelected(agent.id)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      toggleSelected(agent.id);
                    }
                  }}
                  className={`group cursor-pointer rounded-[14px] border overflow-hidden hover:shadow-md transition-shadow ${
                    isSelected ? 'ring-2 ring-[var(--accent-primary)]' : ''
                  }`}
                  style={{
                    borderColor: 'var(--border-color)',
                    background: 'linear-gradient(to bottom right, var(--bg-secondary), var(--bg-tertiary))',
                  }}
                >
                  {/* Icon preview area */}
                  <div
                    className="relative h-[78px] flex items-center justify-center overflow-hidden"
                    style={{ background: 'var(--bg-primary)' }}
                  >
                    <div className="relative z-10">
                      <LandingAgentAvatar avatarUrl={agent.avatarUrl} name={agent.name} copy={copy} />
                    </div>

                    <div
                      className={`absolute top-1.5 right-1.5 transition-opacity z-10 ${
                        isSelected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={isSelected}
                        onChange={() => {}}
                        onClick={(e) => {
                          e.stopPropagation();
                          toggleSelected(agent.id);
                        }}
                        aria-label={copy.select(agent.name)}
                        className="rounded cursor-pointer w-3.5 h-3.5"
                        style={{ borderColor: 'var(--border-color)' }}
                      />
                    </div>

                    {/* Simplified stand-in for the app's FavoriteStarButton
                        (backdrop-blur pill, focus-within reveal): same position,
                        same amber fill, same hover reveal, fewer moving parts. */}
                    <button
                      type="button"
                      onClick={(e) => toggleFavorite(agent.id, e)}
                      aria-pressed={isFavorite}
                      aria-label={isFavorite ? copy.unstar(agent.name) : copy.star(agent.name)}
                      className={`absolute bottom-1 left-1 z-10 p-1 rounded-md transition-opacity ${
                        isFavorite ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
                      }`}
                    >
                      <Star
                        className="w-3.5 h-3.5"
                        style={
                          isFavorite
                            ? { color: '#f59e0b', fill: '#f59e0b' }
                            : { color: 'var(--text-muted)' }
                        }
                      />
                    </button>
                  </div>

                  {/* Footer */}
                  <div
                    className="border-t px-2.5 py-2 backdrop-blur-sm"
                    style={{ borderColor: 'var(--border-color)', background: 'color-mix(in srgb, var(--bg-primary) 80%, transparent)' }}
                  >
                    <span title={localized ? agent.name : undefined} className="text-sm font-medium truncate block" style={{ color: 'var(--text-primary)' }}>
                      {agent.name}
                    </span>
                    <p title={localized ? agent.description : undefined} className="text-xs truncate mt-0.5" style={{ color: 'var(--text-muted)' }}>
                      {agent.description}
                    </p>
                    <div className="flex items-center gap-1.5 mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
                      <span className="truncate">{agent.model}</span>
                      <span style={{ color: 'var(--border-color)' }}>&middot;</span>
                      {agent.shared ? (
                        <span title={copy.published} className="flex-shrink-0">
                          <Globe className="h-3 w-3" />
                        </span>
                      ) : (
                        <span title={copy.private} className="flex-shrink-0">
                          <Lock className="h-3 w-3" />
                        </span>
                      )}
                      {(agent.webhook || agent.schedule) && <span style={{ color: 'var(--border-color)' }}>&middot;</span>}
                      {agent.webhook && (
                        <span title={copy.webhook} className="flex-shrink-0">
                          <Webhook className="h-3 w-3" />
                        </span>
                      )}
                      {agent.schedule && (
                        <span title={copy.scheduled} className="flex-shrink-0">
                          <CalendarClock className="h-3 w-3" />
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </figure>
  );
}
