'use client';

import { useEffect, useState } from 'react';
import {
  Workflow,
  Layers,
  Bot,
  Monitor,
  Table,
  FileText,
  Check,
  Loader2,
} from 'lucide-react';

interface Item {
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  title: string;
  desc: string;
  /** Per-item duration in milliseconds (varies for realism) */
  duration: number;
}

const ITEMS: Item[] = [
  { icon: Workflow, title: 'The main automation', desc: 'every step, every connection', duration: 2200 },
  { icon: Layers, title: 'Smaller automations inside it', desc: 'and the ones inside those, all the way down', duration: 2800 },
  { icon: Bot, title: 'The agents', desc: 'with their prompts, their tools, their budgets', duration: 2500 },
  { icon: Monitor, title: 'The pages', desc: 'your forms, dashboards, customer apps', duration: 2000 },
  { icon: Table, title: 'The data', desc: 'spreadsheets and example rows', duration: 1700 },
  { icon: FileText, title: 'The files', desc: 'images, PDFs, anything attached', duration: 2400 },
];

const PAUSE_AFTER_DONE = 4500;

export default function CloneTree() {
  const totalDuration = ITEMS.reduce((sum, i) => sum + i.duration, 0);
  const cycleLength = totalDuration + PAUSE_AFTER_DONE;
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    const start = performance.now();
    let frame: number;
    const loop = () => {
      const e = (performance.now() - start) % cycleLength;
      setElapsed(e);
      frame = window.requestAnimationFrame(loop);
    };
    frame = window.requestAnimationFrame(loop);
    return () => window.cancelAnimationFrame(frame);
  }, [cycleLength]);

  const overallProgress = Math.min(elapsed / totalDuration, 1);

  let cumulative = 0;
  const itemStates = ITEMS.map((item) => {
    const start = cumulative;
    const end = cumulative + item.duration;
    cumulative = end;

    let state: 'pending' | 'loading' | 'done';
    let localProgress = 0;
    if (elapsed >= end) {
      state = 'done';
      localProgress = 1;
    } else if (elapsed >= start) {
      state = 'loading';
      localProgress = (elapsed - start) / item.duration;
    } else {
      state = 'pending';
      localProgress = 0;
    }
    return { ...item, state, localProgress };
  });

  return (
    <div
      className="rounded-2xl p-6"
      style={{
        background: 'var(--bg-tertiary)',
        border: '1px solid var(--border-color)',
        boxShadow: 'var(--landing-card-shadow)',
      }}
    >
      <div className="flex items-center justify-between gap-3 mb-4">
        <p className="text-[11px] uppercase tracking-wider" style={{ color: 'var(--text-muted)' }}>
          One click · You get the whole thing
        </p>
        <span
          className="text-[11px] font-mono tabular-nums"
          style={{ color: 'var(--text-secondary)' }}
        >
          {Math.round(overallProgress * 100)}%
        </span>
      </div>

      <div
        className="relative h-1 rounded-full overflow-hidden mb-6"
        style={{ background: 'var(--bg-secondary)' }}
      >
        <div
          className="absolute inset-y-0 left-0 rounded-full transition-[width] duration-100 ease-linear"
          style={{
            width: `${overallProgress * 100}%`,
            background: 'var(--text-primary)',
          }}
        />
      </div>

      <ul className="text-sm space-y-3">
        {itemStates.map((item) => (
          <CloneRow key={item.title} {...item} />
        ))}
      </ul>

      <p className="mt-5 text-xs leading-relaxed" style={{ color: 'var(--text-muted)' }}>
        Your secrets stay yours. Passwords are never copied. Re-install free forever,
        even if you delete it.
      </p>
    </div>
  );
}

function CloneRow({
  icon: Icon,
  title,
  desc,
  state,
  localProgress,
}: Item & { state: 'pending' | 'loading' | 'done'; localProgress: number }) {
  const isPending = state === 'pending';
  const isLoading = state === 'loading';
  const isDone = state === 'done';

  return (
    <li
      className="flex items-start gap-3 transition-opacity duration-300"
      style={{ opacity: isPending ? 0.4 : 1 }}
    >
      <div
        className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 transition-all duration-300"
        style={{
          background: isDone ? 'var(--accent-primary)' : 'var(--bg-secondary)',
          border: '1px solid var(--border-color)',
        }}
      >
        {isLoading ? (
          <Loader2
            className="w-4 h-4 animate-spin"
            style={{ color: 'var(--text-primary)' }}
          />
        ) : isDone ? (
          <Check className="w-4 h-4" style={{ color: 'var(--accent-foreground)' }} />
        ) : (
          <Icon className="w-4 h-4" style={{ color: 'var(--text-muted)' }} />
        )}
      </div>
      <div className="flex-1 pt-0.5 min-w-0">
        <span style={{ color: 'var(--text-primary)', fontWeight: 600 }}>{title}</span>
        <span style={{ color: 'var(--text-secondary)' }}>: {desc}</span>
        {isLoading && (
          <div
            className="relative h-0.5 rounded-full overflow-hidden mt-1.5"
            style={{ background: 'var(--bg-secondary)' }}
          >
            <div
              className="absolute inset-y-0 left-0 rounded-full"
              style={{
                width: `${localProgress * 100}%`,
                background: 'var(--text-primary)',
                transition: 'width 100ms linear',
              }}
            />
          </div>
        )}
      </div>
    </li>
  );
}
