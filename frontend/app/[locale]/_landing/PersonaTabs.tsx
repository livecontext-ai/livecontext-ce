'use client';

import { useRef, useState } from 'react';
import {
  BarChart3,
  Briefcase,
  FileText,
  Headphones,
  Link2,
  Megaphone,
  MonitorSmartphone,
  Settings2,
  Table2,
} from 'lucide-react';

// Persona-first "what people automate" section: one tab per role, each showing
// named, concrete automations (verb + business object, never node jargon) plus
// the shareable thing each one produces. Data lives in the page (server) and is
// passed down; icons are resolved here by key because component functions are
// not serializable across the server/client boundary.

const OUTPUT_ICONS = {
  app: MonitorSmartphone,
  pdf: FileText,
  link: Link2,
  table: Table2,
} as const;

const PERSONA_ICONS = {
  ops: Settings2,
  marketing: Megaphone,
  sales: BarChart3,
  support: Headphones,
  founders: Briefcase,
} as const;

export type PersonaOutputKind = keyof typeof OUTPUT_ICONS;
export type PersonaIconKey = keyof typeof PERSONA_ICONS;

export type PersonaExample = {
  title: string;
  desc: string;
  /** The shareable artifact this automation produces, e.g. "A tracker app your team opens". */
  output: string;
  outputKind: PersonaOutputKind;
};

export type Persona = {
  key: string;
  icon: PersonaIconKey;
  label: string;
  intro: string;
  examples: PersonaExample[];
};

export default function PersonaTabs({ personas }: { personas: Persona[] }) {
  const [activeKey, setActiveKey] = useState(personas[0]?.key);
  const tabRefs = useRef<Map<string, HTMLButtonElement>>(new Map());
  const active = personas.find((p) => p.key === activeKey) ?? personas[0];

  if (!active) return null;

  // Full ARIA tabs pattern: roving tabindex + arrow-key navigation, and the
  // panel wired to its tab via aria-controls/aria-labelledby.
  const focusAndSelect = (index: number) => {
    const next = personas[(index + personas.length) % personas.length];
    setActiveKey(next.key);
    tabRefs.current.get(next.key)?.focus();
  };

  const onTablistKeyDown = (event: React.KeyboardEvent) => {
    const currentIndex = personas.findIndex((p) => p.key === active.key);
    if (event.key === 'ArrowRight') focusAndSelect(currentIndex + 1);
    else if (event.key === 'ArrowLeft') focusAndSelect(currentIndex - 1);
    else if (event.key === 'Home') focusAndSelect(0);
    else if (event.key === 'End') focusAndSelect(personas.length - 1);
    else return;
    event.preventDefault();
  };

  return (
    <div>
      <div
        className="persona-tab-row"
        role="tablist"
        aria-label="Automations by role"
        onKeyDown={onTablistKeyDown}
      >
        {personas.map((persona) => {
          const Icon = PERSONA_ICONS[persona.icon];
          const isActive = persona.key === active.key;
          return (
            <button
              key={persona.key}
              ref={(el) => {
                if (el) tabRefs.current.set(persona.key, el);
                else tabRefs.current.delete(persona.key);
              }}
              type="button"
              role="tab"
              id={`persona-tab-${persona.key}`}
              aria-selected={isActive}
              aria-controls={`persona-panel-${persona.key}`}
              tabIndex={isActive ? 0 : -1}
              className={`persona-tab${isActive ? ' active' : ''}`}
              onClick={() => setActiveKey(persona.key)}
            >
              <Icon className="w-4 h-4" aria-hidden="true" />
              {persona.label}
            </button>
          );
        })}
      </div>

      <div
        role="tabpanel"
        id={`persona-panel-${active.key}`}
        aria-labelledby={`persona-tab-${active.key}`}
      >
        <p className="mt-6 text-center text-sm max-w-2xl mx-auto" style={{ color: 'var(--text-secondary)' }}>
          {active.intro}
        </p>

        <div className="mt-8 grid grid-cols-1 md:grid-cols-3 gap-4 md:gap-6">
          {active.examples.map((example) => {
            const OutputIcon = OUTPUT_ICONS[example.outputKind];
            return (
              <div
                key={example.title}
                className="persona-card p-6 rounded-2xl flex flex-col"
                style={{
                  background: 'var(--bg-tertiary)',
                  border: '1px solid var(--border-color)',
                  boxShadow: 'var(--landing-card-shadow)',
                }}
              >
                <h3 className="text-base font-semibold" style={{ color: 'var(--text-primary)' }}>
                  {example.title}
                </h3>
                <p className="mt-2 text-sm leading-relaxed flex-1" style={{ color: 'var(--text-secondary)' }}>
                  {example.desc}
                </p>
                <p
                  className="mt-4 pt-4 inline-flex items-center gap-2 text-sm font-medium"
                  style={{ color: 'var(--text-primary)', borderTop: '1px solid var(--border-color)' }}
                >
                  <OutputIcon className="w-4 h-4 shrink-0" aria-hidden="true" />
                  {example.output}
                </p>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
