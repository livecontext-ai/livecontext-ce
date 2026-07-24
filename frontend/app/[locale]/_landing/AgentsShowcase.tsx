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
 * Hardcoded English like the rest of the landing (see the metadata comment in
 * page.tsx: one English landing on every locale URL). It reuses the app's PURE
 * avatar data modules (avatarColors / avatarTools / AVATAR_PRESETS), which have
 * no i18n dependency, and avoids useTranslations so the component can also
 * render on the intl-context-free public pages that share the landing chrome.
 */

import React, { useEffect, useMemo, useState } from 'react';
import {
  AppWindow,
  BarChart3,
  Bot,
  CalendarClock,
  Columns3,
  Folder,
  Globe,
  Lock,
  Monitor,
  Network,
  Star,
  Store,
  Table,
  User,
  Webhook,
  Workflow,
  Zap,
} from 'lucide-react';
import {
  parsePresetValue,
  getAvatarGradient,
  buildRecoloredPresetDataUri,
  type AvatarCustomColors,
} from '@/components/agents/avatarColors';
import { getAvatarTool } from '@/components/agents/avatarTools';
import { AVATAR_PRESETS } from '@/components/agents/AvatarPicker';
import { favoritesFirst } from '@/lib/utils/listSort';
import LogoAnimate from '@/components/LogoAnimate';

// ---------------------------------------------------------------------------
// Demo team: same value format as real agents (preset:<name>?c1=..&tool=..),
// so every avatar renders through the exact production pipeline, including
// the custom-color recolor path (Scout) and the tool badges.
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

const DEMO_AGENTS: DemoAgent[] = [
  {
    id: 'nova',
    name: 'Nova',
    description: 'Support triage, answers or escalates',
    avatarUrl: 'preset:purple?tool=headset',
    model: 'anthropic/claude-sonnet-5',
    shared: true,
    webhook: true,
  },
  {
    id: 'atlas',
    name: 'Atlas',
    description: 'Enriches every new lead',
    avatarUrl: 'preset:green?tool=chart',
    model: 'openai/gpt-5.6',
    shared: false,
    schedule: true,
  },
  {
    id: 'scout',
    name: 'Scout',
    description: 'Deep research with sources',
    avatarUrl: 'preset:blue?c1=0EA5E9&c2=1E40AF&tool=search',
    model: 'anthropic/claude-opus-4-8',
    shared: true,
  },
  {
    id: 'ember',
    name: 'Ember',
    description: 'Drafts posts in your voice',
    avatarUrl: 'preset:burgundy?tool=pen',
    model: 'deepseek/deepseek-chat',
    shared: false,
    schedule: true,
  },
  {
    id: 'orion',
    name: 'Orion',
    description: 'Reviews every pull request',
    avatarUrl: 'preset:indigo?tool=code',
    model: 'anthropic/claude-sonnet-5',
    shared: false,
    webhook: true,
  },
  {
    id: 'sol',
    name: 'Sol',
    description: 'Schedules and publishes socials',
    avatarUrl: 'preset:sunshine?tool=megaphone',
    model: 'google/gemini-2.5-pro',
    shared: true,
    schedule: true,
  },
  {
    id: 'helix',
    name: 'Helix',
    description: 'Runs the nightly test suite',
    avatarUrl: 'preset:teal?tool=flask',
    model: 'openai/gpt-5.6',
    shared: false,
    schedule: true,
  },
  {
    id: 'aurora',
    name: 'Aurora',
    description: 'Answers the shared inbox',
    avatarUrl: 'preset:emerald?tool=mail',
    model: 'anthropic/claude-haiku-4-5',
    shared: true,
    webhook: true,
  },
  {
    id: 'midas',
    name: 'Midas',
    description: 'Chases unpaid invoices',
    avatarUrl: 'preset:gold?tool=dollar',
    model: 'openai/gpt-5.6',
    shared: false,
    schedule: true,
  },
  {
    id: 'drift',
    name: 'Drift',
    description: 'Watches competitor pages',
    avatarUrl: 'preset:cyan?tool=globe',
    model: 'google/gemini-2.5-pro',
    shared: true,
    schedule: true,
  },
  {
    id: 'sensei',
    name: 'Sensei',
    description: 'Guardrails drafts before they ship',
    avatarUrl: 'preset:slate?tool=shield',
    model: 'anthropic/claude-sonnet-5',
    shared: false,
    webhook: true,
  },
  {
    id: 'fizz',
    name: 'Fizz',
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

function LandingAgentAvatar({ avatarUrl, name }: { avatarUrl: string; name: string }) {
  const parsed = parsePresetValue(avatarUrl);
  const preset = parsed ? AVATAR_PRESETS.find((p) => p.id === parsed.presetId) : null;
  if (!parsed || !preset) return null;

  const tool = getAvatarTool(parsed.tool);
  const gradient = getAvatarGradient(avatarUrl);
  const toolLabel = parsed.tool ? TOOL_LABELS[parsed.tool] ?? parsed.tool : undefined;

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
// App chrome: the hero-showcase icon rail (logo on top, account at the bottom,
// Agents active) and the real /app/agent tab bar.
// ---------------------------------------------------------------------------

// Mirrors the real AppSidebar's chatNavItems (same entries, same order, same
// lucide icons) so the rail matches what a signed-in user actually sees.
const RAIL_ITEMS = [
  { key: 'marketplace', label: 'Marketplace', Icon: Store },
  { key: 'board', label: 'Board', Icon: Columns3 },
  { key: 'agents', label: 'Agents', Icon: Bot, active: true },
  { key: 'applications', label: 'Applications', Icon: AppWindow },
  { key: 'workflows', label: 'Workflows', Icon: Workflow },
  { key: 'interfaces', label: 'Interfaces', Icon: Monitor },
  { key: 'tables', label: 'Tables', Icon: Table },
  { key: 'files', label: 'Files', Icon: Folder },
] as const;

function SidebarRail() {
  return (
    // Purely decorative navigation replica: hidden from assistive tech as a
    // whole (title tooltips stay for sighted visitors).
    <div
      aria-hidden="true"
      // No border-r: the rail's own surface already separates it from the card
      // grid, and the divider read as a hard seam inside the window. The frame
      // border around the whole window (.browser-frame) stays.
      className="flex w-[54px] flex-shrink-0 flex-col items-center gap-1 py-2.5"
      style={{ background: 'var(--bg-secondary)' }}
    >
      <div className="mb-1.5 flex h-8 w-8 items-center justify-center">
        <LogoAnimate size="sm" />
      </div>
      {RAIL_ITEMS.map(({ key, label, Icon, ...rest }) => (
        <div
          key={key}
          title={label}
          data-active={'active' in rest && rest.active ? 'true' : undefined}
          className="flex h-[34px] w-[34px] items-center justify-center rounded-[10px]"
          style={
            'active' in rest && rest.active
              ? { background: 'var(--bg-hover)', color: 'var(--text-primary)' }
              : { color: 'var(--text-muted)' }
          }
        >
          <Icon className="h-[17px] w-[17px]" strokeWidth={1.8} />
        </div>
      ))}
      <div className="flex-1" />
      <div
        title="Account"
        className="flex h-[30px] w-[30px] items-center justify-center rounded-full border"
        style={{
          borderColor: 'var(--border-color)',
          background: 'linear-gradient(135deg, var(--bg-tertiary), var(--bg-hover))',
          color: 'var(--text-secondary)',
        }}
      >
        <User className="h-4 w-4" strokeWidth={1.8} />
      </div>
    </div>
  );
}

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

export default function AgentsShowcase() {
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
        <AgentsAppWindow />
      </div>
    </div>
  );
}

// Bare app window: no browser chrome (traffic lights / URL bar), just the app
// frame itself. The .browser-frame rule from landingStyles still provides the
// rounded border, surface and frame shadow.
function AgentsAppWindow() {
  const [favorites, setFavorites] = useState<Set<string>>(() => new Set(['nova']));
  const [selected, setSelected] = useState<Set<string>>(() => new Set());

  // Favorites float to the top through the same helper the real page uses.
  const visibleAgents = useMemo(
    () => favoritesFirst(DEMO_AGENTS, (a) => a.id, favorites),
    [favorites],
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
        <SidebarRail />

        <div className="min-w-0 flex-1">
          {/* Tab bar, same geometry as AgentPageTabBar */}
          <div className="flex items-center gap-1 border-b px-3 overflow-hidden" style={{ borderColor: 'var(--border-color)' }}>
            {APP_TABS.map(({ key, label, Icon }) => (
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
                {label}
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
                      <LandingAgentAvatar avatarUrl={agent.avatarUrl} name={agent.name} />
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
                        aria-label={`Select ${agent.name}`}
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
                      aria-label={isFavorite ? `Unstar ${agent.name}` : `Star ${agent.name}`}
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
                    <span className="text-sm font-medium truncate block" style={{ color: 'var(--text-primary)' }}>
                      {agent.name}
                    </span>
                    <p className="text-xs truncate mt-0.5" style={{ color: 'var(--text-muted)' }}>
                      {agent.description}
                    </p>
                    <div className="flex items-center gap-1.5 mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
                      <span className="truncate">{agent.model}</span>
                      <span style={{ color: 'var(--border-color)' }}>&middot;</span>
                      {agent.shared ? (
                        <span title="Published to the marketplace" className="flex-shrink-0">
                          <Globe className="h-3 w-3" />
                        </span>
                      ) : (
                        <span title="Private" className="flex-shrink-0">
                          <Lock className="h-3 w-3" />
                        </span>
                      )}
                      {(agent.webhook || agent.schedule) && <span style={{ color: 'var(--border-color)' }}>&middot;</span>}
                      {agent.webhook && (
                        <span title="Webhook trigger active" className="flex-shrink-0">
                          <Webhook className="h-3 w-3" />
                        </span>
                      )}
                      {agent.schedule && (
                        <span title="Schedule trigger active" className="flex-shrink-0">
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
