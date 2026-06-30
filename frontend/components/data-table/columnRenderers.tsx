'use client';

import React from 'react';
import { StatusBadge, mapBackendStatusToStatusType } from '@/components/ui/StatusBadge';
import { cn } from '@/lib/utils';
import { RenderType, BranchEvaluation, LoopProgress, SplitProgress } from '@/lib/api/orchestrator/types';
import { ChevronRight, Check } from 'lucide-react';
import { formatUtcDateTime, parseUtcAware } from '@/lib/utils/dateFormatters';

/**
 * Styled "No data" placeholder matching the design system.
 */
export const NoData: React.FC = () => (
  <div className="w-full min-w-0 overflow-hidden rounded-xl px-4 h-[46px] border-2 border-transparent transition-all duration-200 flex items-center justify-center">
    <span className="text-xs text-theme-secondary">No data</span>
  </div>
);

/**
 * Format a date as precise UTC datetime (e.g., "20 Mar 2026, 14:30:05 UTC").
 * All table cells display in UTC to match server-side storage.
 */
function formatPreciseDateTime(date: Date): string {
  return formatUtcDateTime(date, { withSeconds: true });
}

/**
 * Props for all column renderers.
 */
export interface RendererProps {
  value: any;
  field: string;
  width?: number;
  onNavigate?: (path: string) => void;
}

/**
 * Text renderer - default for most fields.
 */
export const TextRenderer: React.FC<RendererProps> = ({ value }) => {
  if (value === null || value === undefined) {
    return <span className="text-muted-foreground">-</span>;
  }
  return <span className="truncate">{String(value)}</span>;
};

/**
 * Code renderer - monospace font for expressions.
 */
export const CodeRenderer: React.FC<RendererProps> = ({ value }) => {
  if (value === null || value === undefined) {
    return <span className="text-muted-foreground">-</span>;
  }
  return (
    <code className="font-mono text-sm bg-muted px-1.5 py-0.5 rounded truncate block">
      {String(value)}
    </code>
  );
};

/**
 * Status badge renderer with icon and color coding.
 * Uses the shared StatusBadge component for consistency.
 */
export const StatusBadgeRenderer: React.FC<RendererProps> = ({ value }) => {
  const statusType = mapBackendStatusToStatusType(String(value));
  return (
    <StatusBadge
      status={statusType}
      variant="noBackground"
      showIcon={true}
    />
  );
};

/**
 * Branch badge renderer for Decision nodes.
 */
export const BranchBadgeRenderer: React.FC<RendererProps> = ({ value }) => {
  if (!value) return <span className="text-muted-foreground">-</span>;

  const branch = String(value);
  return <span className="truncate">{branch}</span>;
};

/**
 * HTTP status renderer with color coding.
 */
export const HttpStatusBadgeRenderer: React.FC<RendererProps> = ({ value }) => {
  if (value === null || value === undefined) {
    return <span className="text-muted-foreground">-</span>;
  }

  const status = Number(value);
  return <span className="truncate">{status}</span>;
};

/**
 * HTTP method renderer.
 */
export const HttpMethodBadgeRenderer: React.FC<RendererProps> = ({ value }) => {
  if (!value) return <span className="text-muted-foreground">-</span>;

  const method = String(value).toUpperCase();
  return <span className="truncate">{method}</span>;
};

/**
 * Boolean renderer.
 */
export const BooleanBadgeRenderer: React.FC<RendererProps> = ({ value }) => {
  if (value === null || value === undefined) {
    return <span className="text-muted-foreground">-</span>;
  }

  const bool = Boolean(value);
  return <span className="truncate">{bool ? 'true' : 'false'}</span>;
};

/**
 * Generic badge renderer.
 */
export const BadgeRenderer: React.FC<RendererProps> = ({ value }) => {
  if (!value) return <span className="text-muted-foreground">-</span>;
  return <span className="truncate">{String(value)}</span>;
};

/**
 * Duration renderer - formats milliseconds.
 */
export const DurationRenderer: React.FC<RendererProps> = ({ value }) => {
  if (value === null || value === undefined) {
    return <span className="text-muted-foreground">-</span>;
  }

  const ms = Number(value);
  if (ms < 1000) {
    return <span className="truncate">{ms}ms</span>;
  } else if (ms < 60000) {
    return <span className="truncate">{(ms / 1000).toFixed(1)}s</span>;
  } else {
    const minutes = Math.floor(ms / 60000);
    const seconds = Math.floor((ms % 60000) / 1000);
    return <span className="truncate">{minutes}m {seconds}s</span>;
  }
};

/**
 * Datetime renderer - shows precise date and time (DD/MM/YYYY HH:mm:ss).
 */
export const RelativeTimeRenderer: React.FC<RendererProps> = ({ value }) => {
  if (!value) return <span className="text-muted-foreground">-</span>;

  try {
    const date = parseUtcAware(value as string);
    return <span className="truncate">{formatPreciseDateTime(date)}</span>;
  } catch {
    return <span className="text-muted-foreground">-</span>;
  }
};

/**
 * Percentage renderer.
 */
export const PercentageRenderer: React.FC<RendererProps> = ({ value }) => {
  if (value === null || value === undefined) {
    return <span className="text-muted-foreground">-</span>;
  }

  const percent = Number(value);
  return <span className="truncate">{percent.toFixed(1)}%</span>;
};

/**
 * Progress bar renderer for loops/Split.
 */
export const ProgressBarRenderer: React.FC<RendererProps> = ({ value }) => {
  if (!value) return <span className="text-muted-foreground">-</span>;

  const { current, max } = value as LoopProgress;
  const percent = max > 0 ? (current / max) * 100 : 0;

  return (
    <div className="flex items-center gap-2">
      <div className="w-16 h-2 bg-muted rounded-full overflow-hidden">
        <div
          className="h-full bg-primary transition-all"
          style={{ width: `${percent}%` }}
        />
      </div>
      <span className="truncate">{current}/{max}</span>
    </div>
  );
};

/**
 * Loop progress renderer.
 */
export const LoopProgressRenderer: React.FC<RendererProps> = ({ value }) => {
  if (!value) return <span className="text-muted-foreground">-</span>;

  const progress = value as LoopProgress;
  return <ProgressBarRenderer value={progress} field="loopProgress" />;
};

/**
 * Split progress renderer.
 */
export const SplitProgressRenderer: React.FC<RendererProps> = ({ value }) => {
  if (!value) return <span className="text-muted-foreground">-</span>;

  const progress = value as SplitProgress;
  return (
    <div className="flex items-center gap-2">
      <div className="w-16 h-2 bg-muted rounded-full overflow-hidden">
        <div
          className="h-full bg-primary transition-all"
          style={{ width: `${progress.total > 0 ? (progress.processed / progress.total) * 100 : 0}%` }}
        />
      </div>
      <span className="truncate">{progress.processed}/{progress.total}</span>
    </div>
  );
};

/**
 * JSON preview renderer - shows truncated JSON.
 */
export const JsonPreviewRenderer: React.FC<RendererProps> = ({ value }) => {
  if (value === null || value === undefined || value === '') {
    return <NoData />;
  }

  const jsonStr = typeof value === 'string' ? value : JSON.stringify(value);

  if (jsonStr === '{}' || jsonStr === '[]') {
    return <NoData />;
  }

  const truncated = jsonStr.length > 50 ? jsonStr.substring(0, 47) + '...' : jsonStr;

  return <span className="truncate">{truncated}</span>;
};

/**
 * JSON navigable renderer - clickable to navigate into JSON structure.
 */
export const JsonNavigableRenderer: React.FC<RendererProps> = ({ value, onNavigate }) => {
  if (value === null || value === undefined || value === '') {
    return <NoData />;
  }

  const jsonStr = typeof value === 'string' ? value : JSON.stringify(value);

  // Empty object/array check
  if (jsonStr === '{}' || jsonStr === '[]') {
    return <NoData />;
  }

  const truncated = jsonStr.length > 40 ? jsonStr.substring(0, 37) + '...' : jsonStr;

  return (
    <button
      className="flex items-center gap-1 hover:bg-muted rounded px-1 py-0.5 transition-colors"
      onClick={() => onNavigate?.('output')}
    >
      <span className="truncate">{truncated}</span>
      <ChevronRight className="h-3 w-3 text-muted-foreground flex-shrink-0" />
    </button>
  );
};

/**
 * Evaluations table renderer for Decision nodes.
 */
export const EvaluationsTableRenderer: React.FC<RendererProps> = ({ value }) => {
  if (!value || !Array.isArray(value) || value.length === 0) {
    return <span className="text-muted-foreground">-</span>;
  }

  const evaluations = value as BranchEvaluation[];

  return (
    <div className="text-sm border rounded overflow-hidden">
      <table className="w-full">
        <thead className="bg-muted/50">
          <tr>
            <th className="text-left px-2 py-1 font-medium">Branch</th>
            <th className="text-left px-2 py-1 font-medium">Condition</th>
            <th className="text-left px-2 py-1 font-medium">Resolved</th>
            <th className="text-left px-2 py-1 font-medium">Result</th>
          </tr>
        </thead>
        <tbody>
          {evaluations.map((evaluation, i) => (
            <tr
              key={i}
              className={cn(
                "border-t",
                evaluation.selected && "bg-green-50 dark:bg-green-950/20"
              )}
            >
              <td className="px-2 py-1">
                {evaluation.selected ? (
                  <span className="text-green-600 flex items-center gap-1">
                    <Check className="h-3 w-3" />
                    {evaluation.branch}
                  </span>
                ) : (
                  <span className="text-muted-foreground">{evaluation.branch}</span>
                )}
              </td>
              <td className="px-2 py-1 font-mono">{evaluation.condition || '(default)'}</td>
              <td className="px-2 py-1 font-mono">{evaluation.resolved || '-'}</td>
              <td className="px-2 py-1">
                {evaluation.result !== null ? (
                  <span>{String(evaluation.result)}</span>
                ) : '-'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

/**
 * Cases table renderer for Switch nodes.
 */
export const CasesTableRenderer: React.FC<RendererProps> = ({ value }) => {
  if (!value || !Array.isArray(value) || value.length === 0) {
    return <span className="text-muted-foreground">-</span>;
  }

  return (
    <div className="text-sm border rounded overflow-hidden">
      <table className="w-full">
        <thead className="bg-muted/50">
          <tr>
            <th className="text-left px-2 py-1 font-medium">Case</th>
            <th className="text-left px-2 py-1 font-medium">Value</th>
            <th className="text-left px-2 py-1 font-medium">Selected</th>
          </tr>
        </thead>
        <tbody>
          {value.map((caseItem: any, i: number) => (
            <tr
              key={i}
              className={cn(
                "border-t",
                caseItem.selected && "bg-green-50 dark:bg-green-950/20"
              )}
            >
              <td className="px-2 py-1">{caseItem.label || caseItem.type}</td>
              <td className="px-2 py-1 font-mono">{caseItem.value || '(default)'}</td>
              <td className="px-2 py-1">
                {caseItem.selected ? (
                  <Check className="h-3 w-3 text-green-600" />
                ) : (
                  <span className="text-muted-foreground">-</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

/**
 * String list renderer. Uses ` · ` (middle dot) as separator - comma-join
 * collapsed visually when items contained internal commas (e.g. RFC 2822
 * email Date headers like "Mon, 11 May 2026 19:00:11 +0000"), making
 * adjacent dates indistinguishable. The middle dot is unambiguous and
 * stays narrow enough to keep the truncated single-line layout.
 */
export const StringListRenderer: React.FC<RendererProps> = ({ value }) => {
  if (!value || !Array.isArray(value) || value.length === 0) {
    return <span className="text-muted-foreground">-</span>;
  }

  const displayItems = value.slice(0, 5).join(' · ');
  const suffix = value.length > 5 ? ` · +${value.length - 5} more` : '';
  return <span className="truncate">{displayItems}{suffix}</span>;
};

/**
 * Text preview renderer - truncated with expandable.
 */
export const TextPreviewRenderer: React.FC<RendererProps> = ({ value }) => {
  if (value === null || value === undefined || value === '') {
    return <span className="text-muted-foreground">-</span>;
  }

  const text = String(value);
  const truncated = text.length > 100 ? text.substring(0, 97) + '...' : text;

  return <span className="truncate">{truncated}</span>;
};

/**
 * HTML preview renderer.
 */
export const HtmlPreviewRenderer: React.FC<RendererProps> = ({ value }) => {
  if (!value) return <span className="text-muted-foreground">-</span>;

  const text = String(value).replace(/<[^>]*>/g, ' ').trim();
  const truncated = text.length > 50 ? text.substring(0, 47) + '...' : text;

  return <span className="truncate">{truncated || '(HTML content)'}</span>;
};

/**
 * Map of render types to renderer components.
 */
export const columnRenderers: Record<RenderType, React.FC<RendererProps>> = {
  TEXT: TextRenderer,
  CODE: CodeRenderer,
  STATUS_BADGE: StatusBadgeRenderer,
  BRANCH_BADGE: BranchBadgeRenderer,
  HTTP_STATUS_BADGE: HttpStatusBadgeRenderer,
  HTTP_METHOD_BADGE: HttpMethodBadgeRenderer,
  BOOLEAN_BADGE: BooleanBadgeRenderer,
  BADGE: BadgeRenderer,
  DURATION: DurationRenderer,
  RELATIVE_TIME: RelativeTimeRenderer,
  PERCENTAGE: PercentageRenderer,
  PROGRESS_BAR: ProgressBarRenderer,
  JSON_PREVIEW: JsonPreviewRenderer,
  JSON_NAVIGABLE: JsonNavigableRenderer,
  EVALUATIONS_TABLE: EvaluationsTableRenderer,
  CASES_TABLE: CasesTableRenderer,
  STRING_LIST: StringListRenderer,
  TEXT_PREVIEW: TextPreviewRenderer,
  HTML_PREVIEW: HtmlPreviewRenderer,
  LOOP_PROGRESS: LoopProgressRenderer,
  SPLIT_PROGRESS: SplitProgressRenderer,
};

/**
 * Get the appropriate renderer for a render type.
 */
export function getRenderer(renderType: RenderType | string): React.FC<RendererProps> {
  return columnRenderers[renderType as RenderType] || TextRenderer;
}
