'use client';

import { useState, useCallback, useEffect, useMemo } from 'react';
import { ChevronLeft, ChevronRight, Maximize2, Minimize2 } from 'lucide-react';

// ==================== Types ====================

export interface SlideTheme {
  primaryColor?: string;
  secondaryColor?: string;
  accentColor?: string;
  fontFamily?: string;
}

export interface SlideContent {
  title?: string;
  subtitle?: string;
  body?: string;
  image_url?: string;
  image_position?: 'left' | 'right';
  caption?: string;
  overlay_text?: string;
  left_content?: string;
  right_content?: string;
  columns?: Array<{ title?: string; content?: string }>;
  chart_type?: string;
  chart_data?: { labels?: string[]; datasets?: Array<{ label?: string; data?: number[]; color?: string }> };
  items?: Array<{ name?: string; features?: string[]; highlighted?: boolean }>;
  events?: Array<{ date?: string; title?: string; description?: string }>;
  quote?: string;
  author?: string;
  role?: string;
  members?: Array<{ name?: string; role?: string; image_url?: string; description?: string }>;
  metrics?: Array<{ label?: string; value?: string; change?: string; trend?: string }>;
  contact_info?: string;
  content?: string; // for blank layout
}

export interface Slide {
  layout: string;
  content: SlideContent;
  notes?: string;
}

export interface SlideData {
  slides: Slide[];
  theme?: SlideTheme;
}

interface SlideRendererProps {
  slideData: SlideData;
  className?: string;
  /** Compact mode for card previews (no navigation, smaller text) */
  compact?: boolean;
  /** Start on this slide index */
  initialSlide?: number;
}

// ==================== Theme Defaults ====================

const DEFAULT_THEME: Required<SlideTheme> = {
  primaryColor: '#0f172a',
  secondaryColor: '#1e293b',
  accentColor: '#3b82f6',
  fontFamily: 'Inter, system-ui, sans-serif',
};

function mergeTheme(theme?: SlideTheme): Required<SlideTheme> {
  return { ...DEFAULT_THEME, ...theme };
}

// ==================== Main Component ====================

export function SlideRenderer({ slideData, className = '', compact = false, initialSlide = 0 }: SlideRendererProps) {
  const [currentIndex, setCurrentIndex] = useState(initialSlide);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const theme = useMemo(() => mergeTheme(slideData.theme), [slideData.theme]);
  const slides = slideData.slides || [];
  const total = slides.length;

  // Clamp index if slides change
  useEffect(() => {
    if (currentIndex >= total && total > 0) {
      setCurrentIndex(total - 1);
    }
  }, [total, currentIndex]);

  const goNext = useCallback(() => {
    setCurrentIndex(prev => Math.min(prev + 1, total - 1));
  }, [total]);

  const goPrev = useCallback(() => {
    setCurrentIndex(prev => Math.max(prev - 1, 0));
  }, []);

  // Keyboard navigation
  useEffect(() => {
    if (compact) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'ArrowRight' || e.key === ' ') { e.preventDefault(); goNext(); }
      if (e.key === 'ArrowLeft') { e.preventDefault(); goPrev(); }
      if (e.key === 'Escape' && isFullscreen) { setIsFullscreen(false); }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [compact, goNext, goPrev, isFullscreen]);

  if (total === 0) {
    return (
      <div className={`flex items-center justify-center text-theme-muted p-8 ${className}`}>
        <p className="text-sm">No slides</p>
      </div>
    );
  }

  const slide = slides[currentIndex];

  const containerClass = isFullscreen
    ? 'fixed inset-0 z-50 bg-black flex flex-col'
    : `relative flex flex-col ${className}`;

  return (
    <div className={containerClass}>
      {/* Slide area */}
      <div
        className={`flex-1 flex items-center justify-center overflow-hidden ${compact ? '' : 'min-h-0'}`}
        style={{
          backgroundColor: theme.primaryColor,
          fontFamily: theme.fontFamily,
        }}
      >
        <div className={`w-full h-full flex items-center justify-center ${compact ? 'p-3' : 'p-6 md:p-12'}`}>
          <SlideLayout slide={slide} theme={theme} compact={compact} />
        </div>
      </div>

      {/* Navigation bar (hidden in compact mode) */}
      {!compact && (
        <div
          className="flex items-center justify-between px-4 py-2 border-t"
          style={{
            backgroundColor: theme.secondaryColor,
            borderColor: `${theme.accentColor}33`,
          }}
        >
          <button
            onClick={goPrev}
            disabled={currentIndex === 0}
            className="p-1.5 rounded-lg transition-colors disabled:opacity-30"
            style={{ color: '#fff' }}
          >
            <ChevronLeft className="w-5 h-5" />
          </button>

          <span className="text-sm font-medium" style={{ color: '#94a3b8' }}>
            {currentIndex + 1} / {total}
          </span>

          <div className="flex items-center gap-2">
            <button
              onClick={() => setIsFullscreen(f => !f)}
              className="p-1.5 rounded-lg transition-colors"
              style={{ color: '#94a3b8' }}
            >
              {isFullscreen ? <Minimize2 className="w-4 h-4" /> : <Maximize2 className="w-4 h-4" />}
            </button>
            <button
              onClick={goNext}
              disabled={currentIndex >= total - 1}
              className="p-1.5 rounded-lg transition-colors disabled:opacity-30"
              style={{ color: '#fff' }}
            >
              <ChevronRight className="w-5 h-5" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

// ==================== Slide Layout Dispatcher ====================

function SlideLayout({ slide, theme, compact }: { slide: Slide; theme: Required<SlideTheme>; compact: boolean }) {
  const c = slide.content || {};
  const h1Size = compact ? 'text-lg' : 'text-3xl md:text-5xl';
  const h2Size = compact ? 'text-base' : 'text-2xl md:text-3xl';
  const bodySize = compact ? 'text-xs' : 'text-base md:text-lg';
  const subSize = compact ? 'text-xs' : 'text-lg md:text-xl';
  const accent = theme.accentColor;

  switch (slide.layout) {
    case 'title':
    case 'section_header':
      return (
        <div className="text-center max-w-4xl">
          <h1 className={`${h1Size} font-bold mb-4`} style={{ color: '#fff' }}>
            {c.title}
          </h1>
          {c.subtitle && (
            <p className={`${subSize}`} style={{ color: accent }}>
              {c.subtitle}
            </p>
          )}
        </div>
      );

    case 'content':
      return (
        <div className="w-full max-w-4xl">
          <h2 className={`${h2Size} font-bold mb-6`} style={{ color: '#fff' }}>{c.title}</h2>
          <div className={`${bodySize} leading-relaxed space-y-2`} style={{ color: '#cbd5e1' }}>
            {renderBody(c.body)}
          </div>
        </div>
      );

    case 'content_with_image':
      return (
        <div className={`w-full max-w-5xl flex ${c.image_position === 'left' ? 'flex-row-reverse' : 'flex-row'} gap-8 items-center`}>
          <div className="flex-1">
            <h2 className={`${h2Size} font-bold mb-4`} style={{ color: '#fff' }}>{c.title}</h2>
            <div className={`${bodySize} leading-relaxed`} style={{ color: '#cbd5e1' }}>
              {renderBody(c.body)}
            </div>
          </div>
          {c.image_url && (
            <div className="flex-1">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={c.image_url} alt={c.title || ''} className="rounded-xl w-full object-cover max-h-[400px]" />
            </div>
          )}
        </div>
      );

    case 'two_columns':
      return (
        <div className="w-full max-w-5xl">
          <h2 className={`${h2Size} font-bold mb-6`} style={{ color: '#fff' }}>{c.title}</h2>
          <div className="grid grid-cols-2 gap-8">
            <div className={`${bodySize} leading-relaxed`} style={{ color: '#cbd5e1' }}>
              {renderBody(c.left_content)}
            </div>
            <div className={`${bodySize} leading-relaxed`} style={{ color: '#cbd5e1' }}>
              {renderBody(c.right_content)}
            </div>
          </div>
        </div>
      );

    case 'three_columns':
      return (
        <div className="w-full max-w-5xl">
          <h2 className={`${h2Size} font-bold mb-6`} style={{ color: '#fff' }}>{c.title}</h2>
          <div className="grid grid-cols-3 gap-6">
            {(c.columns || []).map((col, i) => (
              <div key={i} className="rounded-xl p-4" style={{ backgroundColor: theme.secondaryColor }}>
                {col.title && <h3 className="text-sm font-semibold mb-2" style={{ color: accent }}>{col.title}</h3>}
                <div className={`${compact ? 'text-xs' : 'text-sm'} leading-relaxed`} style={{ color: '#cbd5e1' }}>
                  {renderBody(col.content)}
                </div>
              </div>
            ))}
          </div>
        </div>
      );

    case 'image_full':
      return (
        <div className="w-full h-full relative flex items-center justify-center">
          {c.image_url && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={c.image_url} alt={c.caption || ''} className="w-full h-full object-cover rounded-xl" />
          )}
          {c.overlay_text && (
            <div className="absolute inset-0 flex items-center justify-center bg-black/40 rounded-xl">
              <p className={`${h1Size} font-bold text-white text-center px-8`}>{c.overlay_text}</p>
            </div>
          )}
          {c.caption && (
            <p className="absolute bottom-4 left-0 right-0 text-center text-sm text-white/80">{c.caption}</p>
          )}
        </div>
      );

    case 'chart':
      return (
        <div className="w-full max-w-4xl">
          <h2 className={`${h2Size} font-bold mb-6`} style={{ color: '#fff' }}>{c.title}</h2>
          <SimpleChart chartData={c.chart_data} chartType={c.chart_type} accent={accent} compact={compact} />
        </div>
      );

    case 'comparison':
      return (
        <div className="w-full max-w-5xl">
          <h2 className={`${h2Size} font-bold mb-6`} style={{ color: '#fff' }}>{c.title}</h2>
          <div className="grid grid-cols-2 gap-4 md:grid-cols-3">
            {(c.items || []).map((item, i) => (
              <div
                key={i}
                className="rounded-xl p-4 border"
                style={{
                  backgroundColor: item.highlighted ? `${accent}22` : theme.secondaryColor,
                  borderColor: item.highlighted ? accent : 'transparent',
                }}
              >
                <h3 className="font-semibold mb-2" style={{ color: item.highlighted ? accent : '#fff' }}>
                  {item.name}
                </h3>
                <ul className="space-y-1">
                  {(item.features || []).map((f, j) => (
                    <li key={j} className="text-sm" style={{ color: '#94a3b8' }}>{f}</li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>
      );

    case 'timeline':
      return (
        <div className="w-full max-w-4xl">
          <h2 className={`${h2Size} font-bold mb-6`} style={{ color: '#fff' }}>{c.title}</h2>
          <div className="space-y-4 relative">
            <div className="absolute left-3 top-2 bottom-2 w-0.5" style={{ backgroundColor: accent }} />
            {(c.events || []).map((evt, i) => (
              <div key={i} className="pl-8 relative">
                <div className="absolute left-1.5 top-1.5 w-3 h-3 rounded-full border-2" style={{ borderColor: accent, backgroundColor: theme.primaryColor }} />
                {evt.date && <span className="text-xs font-medium" style={{ color: accent }}>{evt.date}</span>}
                <h3 className="font-semibold mt-0.5" style={{ color: '#fff' }}>{evt.title}</h3>
                {evt.description && <p className="text-sm mt-1" style={{ color: '#94a3b8' }}>{evt.description}</p>}
              </div>
            ))}
          </div>
        </div>
      );

    case 'quote':
      return (
        <div className="text-center max-w-3xl px-8">
          <div className={`${compact ? 'text-2xl' : 'text-5xl'} mb-4`} style={{ color: accent }}>&ldquo;</div>
          <blockquote className={`${compact ? 'text-sm' : 'text-xl md:text-2xl'} italic leading-relaxed mb-6`} style={{ color: '#e2e8f0' }}>
            {c.quote}
          </blockquote>
          {c.author && (
            <div>
              <p className="font-semibold" style={{ color: '#fff' }}>{c.author}</p>
              {c.role && <p className="text-sm" style={{ color: '#94a3b8' }}>{c.role}</p>}
            </div>
          )}
        </div>
      );

    case 'team':
      return (
        <div className="w-full max-w-5xl">
          <h2 className={`${h2Size} font-bold mb-6`} style={{ color: '#fff' }}>{c.title}</h2>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            {(c.members || []).map((m, i) => (
              <div key={i} className="text-center rounded-xl p-4" style={{ backgroundColor: theme.secondaryColor }}>
                {m.image_url ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={m.image_url} alt={m.name || ''} className="w-16 h-16 rounded-full mx-auto mb-3 object-cover" />
                ) : (
                  <div className="w-16 h-16 rounded-full mx-auto mb-3 flex items-center justify-center text-xl font-bold" style={{ backgroundColor: `${accent}33`, color: accent }}>
                    {(m.name || '?')[0]}
                  </div>
                )}
                <h3 className="font-semibold text-sm" style={{ color: '#fff' }}>{m.name}</h3>
                {m.role && <p className="text-xs mt-0.5" style={{ color: accent }}>{m.role}</p>}
                {m.description && <p className="text-xs mt-1" style={{ color: '#94a3b8' }}>{m.description}</p>}
              </div>
            ))}
          </div>
        </div>
      );

    case 'metrics':
      return (
        <div className="w-full max-w-5xl">
          <h2 className={`${h2Size} font-bold mb-6`} style={{ color: '#fff' }}>{c.title}</h2>
          <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
            {(c.metrics || []).map((m, i) => (
              <div key={i} className="rounded-xl p-5 text-center" style={{ backgroundColor: theme.secondaryColor }}>
                <p className="text-xs uppercase tracking-wider mb-2" style={{ color: '#94a3b8' }}>{m.label}</p>
                <p className={`${compact ? 'text-xl' : 'text-3xl'} font-bold`} style={{ color: '#fff' }}>{m.value}</p>
                {m.change && (
                  <p className="text-sm mt-1" style={{ color: m.trend === 'down' ? '#f87171' : '#4ade80' }}>
                    {m.change}
                  </p>
                )}
              </div>
            ))}
          </div>
        </div>
      );

    case 'blank':
      return (
        <div className="w-full max-w-4xl">
          <div className={`${bodySize} leading-relaxed`} style={{ color: '#cbd5e1' }}>
            {renderBody(c.content)}
          </div>
        </div>
      );

    case 'closing':
      return (
        <div className="text-center max-w-3xl">
          <h1 className={`${h1Size} font-bold mb-4`} style={{ color: '#fff' }}>{c.title}</h1>
          {c.subtitle && <p className={`${subSize} mb-6`} style={{ color: accent }}>{c.subtitle}</p>}
          {c.contact_info && <p className="text-sm" style={{ color: '#94a3b8' }}>{c.contact_info}</p>}
        </div>
      );

    default:
      return (
        <div className="text-center max-w-3xl">
          <p className="text-sm" style={{ color: '#94a3b8' }}>Unknown layout: {slide.layout}</p>
          {c.title && <h2 className={`${h2Size} font-bold mt-4`} style={{ color: '#fff' }}>{c.title}</h2>}
          {c.body && <div className="mt-4" style={{ color: '#cbd5e1' }}>{renderBody(c.body)}</div>}
        </div>
      );
  }
}

// ==================== Helpers ====================

/** Render body text with \n as bullet points */
function renderBody(text?: string) {
  if (!text) return null;
  const lines = text.split('\n').filter(Boolean);
  if (lines.length <= 1) return <p>{text}</p>;
  return (
    <ul className="space-y-1.5">
      {lines.map((line, i) => {
        // Strip leading bullet characters
        const clean = line.replace(/^[\s]*[•\-\*]\s*/, '');
        return <li key={i} className="flex items-start gap-2"><span className="mt-1.5 w-1.5 h-1.5 rounded-full bg-current opacity-50 shrink-0" />{clean}</li>;
      })}
    </ul>
  );
}

/** Simple bar/column chart renderer (no external library) */
function SimpleChart({ chartData, chartType, accent, compact }: {
  chartData?: SlideContent['chart_data'];
  chartType?: string;
  accent: string;
  compact: boolean;
}) {
  if (!chartData?.datasets?.length || !chartData?.labels?.length) {
    return <p className="text-sm" style={{ color: '#94a3b8' }}>No chart data</p>;
  }

  const dataset = chartData.datasets[0];
  const values = dataset.data || [];
  const maxVal = Math.max(...values, 1);
  const barColor = dataset.color || accent;

  return (
    <div className="w-full">
      {dataset.label && (
        <p className="text-sm font-medium mb-3" style={{ color: '#94a3b8' }}>{dataset.label}</p>
      )}
      <div className={`flex items-end gap-2 ${compact ? 'h-24' : 'h-48'}`}>
        {values.map((val, i) => (
          <div key={i} className="flex-1 flex flex-col items-center gap-1 h-full justify-end">
            <span className="text-xs font-medium" style={{ color: '#94a3b8' }}>{val}</span>
            <div
              className="w-full rounded-t-md transition-all"
              style={{
                height: `${(val / maxVal) * 100}%`,
                backgroundColor: barColor,
                minHeight: '4px',
              }}
            />
            <span className="text-xs truncate w-full text-center" style={{ color: '#64748b' }}>
              {chartData.labels?.[i]}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
