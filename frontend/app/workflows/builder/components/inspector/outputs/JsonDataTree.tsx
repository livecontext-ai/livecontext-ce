/**
 * JsonDataTree - the single key/value tree used by every run-mode column.
 *
 * Both run columns (resolved Params and Output) render the same payload shapes,
 * so they render them with the same component: one place decides how a string,
 * a file reference, an unresolved template or an embedded JSON document looks.
 * The Params column only differs in that its TOP-LEVEL keys are shown through a
 * human label (`labelForKey`); everything below is raw keys in both columns.
 *
 * What the tree knows how to surface, beyond plain values:
 *  - a FileRef, with view/download buttons and its properties;
 *  - a string that is really a JSON document, expandable as a nested tree;
 *  - a value the engine failed to resolve, called out instead of looking like
 *    ordinary text;
 *  - a long string, collapsed to a bounded box with an explicit expand rather than
 *    flooding the column - bounded, never cut: every character stays in the document;
 *  - a copy button on every row, revealed on hover.
 */

'use client';

import * as React from 'react';
import clsx from 'clsx';
import { ChevronRight, GripVertical, Download, Eye } from 'lucide-react';
import { useTranslations } from 'next-intl';
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  isFileRef,
  normalizeFileRef,
  fileService,
  fileRefToUrl,
  getFilePath,
  type FileRef,
} from '@/lib/api/orchestrator/file.service';
import { openAuthedFileInNewTab } from '@/lib/utils/url-auth';
import { useIsomorphicLayoutEffect } from '@/lib/hooks/useIsomorphicLayoutEffect';
import { CopyButton } from '../shared/CopyButton';
import {
  detectUnresolvedValue,
  parseEmbeddedJson,
  expandedRowCount,
  FILE_REF_DISPLAY_PROPS,
  LONG_STRING_CHARS,
  type UnresolvedKind,
} from './runValueUtils';

// ============================================
// Tree
// ============================================

export interface JsonValueTreeProps {
  data: any;
  /** Enable drag and drop to create expressions. */
  isDraggable?: boolean;
  /** Prefix for drag expressions, e.g. "mcp:step1.output". */
  dragPrefix?: string;
  path: string[];
  showBorder?: boolean;
  /**
   * Expanded node paths (joined by '.'), owned by the caller so the tree shape
   * survives item navigation and node switches.
   */
  expandedPaths: Set<string>;
  onToggleExpand: (pathKey: string) => void;
  /**
   * Display label for a TOP-LEVEL key (path length 1). Used by the Params
   * column to show "Duration (ms)" where the payload says "duration"; nested
   * keys stay raw so a drag path always matches what is on screen.
   */
  labelForKey?: (key: string) => string;
}

export function JsonValueTree({
  data,
  isDraggable = false,
  dragPrefix,
  path,
  showBorder = false,
  expandedPaths,
  onToggleExpand,
  labelForKey,
}: JsonValueTreeProps) {
  if (data === null) {
    return <span className="text-sm font-mono text-slate-400">null</span>;
  }

  if (data === undefined) {
    return <span className="text-sm font-mono text-slate-400">undefined</span>;
  }

  if (typeof data !== 'object') {
    return <PrimitiveValue value={data} />;
  }

  // Top-level FileRef object (e.g., download_file output in DB format)
  if (!Array.isArray(data) && isFileRef(data)) {
    return (
      <TopLevelFileRefView
        data={data}
        isDraggable={isDraggable}
        dragPrefix={dragPrefix}
        path={path}
        expandedPaths={expandedPaths}
        onToggleExpand={onToggleExpand}
      />
    );
  }

  // Step output containing a file (flat format with envelope fields):
  // a file preview card first, then every field below.
  if (
    !Array.isArray(data) &&
    typeof data === 'object' &&
    '_status' in data &&
    typeof data.file_url === 'string' &&
    typeof data.file_name === 'string'
  ) {
    // Raw data without _type so normalizeFileRef detects the flat format.
    const normalized = normalizeFileRef(data as any);
    const entries = Object.entries(data);
    return (
      <div className="space-y-2">
        <FilePreviewCard fileRef={normalized} />
        <div className="space-y-1">
          {entries.map(([key, value]) => (
            <JsonNode
              key={key}
              nodeKey={key}
              value={value}
              isDraggable={isDraggable}
              dragPrefix={dragPrefix}
              path={[...path, key]}
              expandedPaths={expandedPaths}
              onToggleExpand={onToggleExpand}
              labelForKey={labelForKey}
            />
          ))}
        </div>
      </div>
    );
  }

  if (Array.isArray(data)) {
    if (data.length === 0) {
      return <span className="text-sm font-mono text-slate-400">[]</span>;
    }
    return (
      <div className={clsx(showBorder && 'pl-3 border-l border-slate-200 dark:border-slate-700')}>
        <div className="space-y-1">
          {data.map((item, index) => (
            <JsonNode
              key={index}
              nodeKey={String(index)}
              value={item}
              isDraggable={isDraggable}
              dragPrefix={dragPrefix}
              path={[...path, String(index)]}
              expandedPaths={expandedPaths}
              onToggleExpand={onToggleExpand}
              labelForKey={labelForKey}
            />
          ))}
        </div>
      </div>
    );
  }

  // Every key, and expandedRowCount counts every key: the marker above these rows
  // and the rows themselves come from the same rule.
  const entries = Object.entries(data);
  if (entries.length === 0) {
    return <span className="text-sm font-mono text-slate-400">{'{}'}</span>;
  }

  return (
    <div className={clsx(showBorder && 'pl-3 border-l border-slate-200 dark:border-slate-700')}>
      <div className="space-y-1">
        {entries.map(([key, value]) => (
          <JsonNode
            key={key}
            nodeKey={key}
            value={value}
            isDraggable={isDraggable}
            dragPrefix={dragPrefix}
            path={[...path, key]}
            expandedPaths={expandedPaths}
            onToggleExpand={onToggleExpand}
            labelForKey={labelForKey}
          />
        ))}
      </div>
    </div>
  );
}

interface JsonNodeProps {
  nodeKey: string;
  value: any;
  isDraggable?: boolean;
  dragPrefix?: string;
  path: string[];
  expandedPaths: Set<string>;
  onToggleExpand: (pathKey: string) => void;
  labelForKey?: (key: string) => string;
}

export function JsonNode({
  nodeKey,
  value,
  isDraggable = false,
  dragPrefix,
  path,
  expandedPaths,
  onToggleExpand,
  labelForKey,
}: JsonNodeProps) {
  // Path key is shared with the lifted state - joined with '.' so nested arrays
  // and objects produce stable, unique keys.
  const pathKey = path.join('.');
  const isExpanded = expandedPaths.has(pathKey);
  const handleToggle = React.useCallback(() => onToggleExpand(pathKey), [onToggleExpand, pathKey]);

  // Only the top level is relabelled: a nested key is also a drag path segment.
  const displayKey = labelForKey && path.length === 1 ? labelForKey(nodeKey) : nodeKey;

  const isFileRefValue = isFileRef(value);
  const isExpandable = value !== null && typeof value === 'object';
  const isArray = Array.isArray(value);
  // A recognised reference returns before this marker is drawn (it gets the file
  // view's own {n}); the flag is passed anyway so this call and StringValue's apply
  // one rule rather than two that happen to agree.
  const itemCount = expandedRowCount(value, isFileRefValue);

  const fullPath = dragPrefix ? `${dragPrefix}.${path.join('.')}` : path.join('.');

  const handleDragStart = (e: React.DragEvent) => {
    if (!isDraggable) return;
    e.stopPropagation();
    e.dataTransfer.setData('text/plain', `{{${fullPath}}}`);
    e.dataTransfer.effectAllowed = 'copy';
  };

  // Primitive value - inline
  if (!isExpandable) {
    return (
      <div
        className={clsx(
          'group/row relative flex items-start justify-between gap-1 text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1',
          isDraggable
            ? 'cursor-grab active:cursor-grabbing hover:bg-slate-50 dark:hover:bg-slate-800'
            : 'cursor-default hover:bg-slate-50 dark:hover:bg-slate-800',
        )}
        draggable={isDraggable}
        onDragStart={handleDragStart}
        title={isDraggable ? fullPath : undefined}
      >
        <div className="flex items-start gap-2 flex-1 min-w-0">
          {isDraggable && (
            <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0 mt-0.5" />
          )}
          <span
            className="truncate max-w-[120px] flex-shrink-0 text-sm"
            title={displayKey}
            data-testid="json-row-key"
          >
            {displayKey}
          </span>
          <span className="text-slate-400 flex-shrink-0">:</span>
          <PrimitiveValue value={value} />
        </div>
        <RowControlOverlay>
          <CopyButton value={value} />
        </RowControlOverlay>
      </div>
    );
  }

  if (isFileRefValue) {
    return (
      <FileObjectNode
        nodeKey={displayKey}
        fileRef={value}
        isDraggable={isDraggable}
        dragPrefix={dragPrefix}
        path={path}
        isExpanded={isExpanded}
        onToggle={handleToggle}
        onDragStart={handleDragStart}
        fullPath={fullPath}
      />
    );
  }

  // Object or Array - expandable
  return (
    <div className="flex flex-col gap-1">
      <div
        className={clsx(
          'group/row flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1',
          'cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800',
        )}
        draggable={isDraggable}
        onDragStart={handleDragStart}
        onClick={handleToggle}
        title={isDraggable ? fullPath : displayKey}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          {isDraggable && (
            <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0" />
          )}
          <span
            className="truncate flex-1 min-w-0 text-sm"
            title={displayKey}
            data-testid="json-row-key"
          >
            {displayKey}
          </span>
          <ChevronRight
            className={clsx(
              'h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform flex-shrink-0 mr-2',
              isExpanded && 'rotate-90',
            )}
          />
        </div>
        <span className="text-sm font-mono text-orange-600 dark:text-orange-400 flex-shrink-0">
          {isArray ? `[${itemCount}]` : `{${itemCount}}`}
        </span>
        <RowControlOverlay>
          <CopyButton value={value} />
        </RowControlOverlay>
      </div>

      {isExpanded && (
        <JsonValueTree
          data={value}
          isDraggable={isDraggable}
          dragPrefix={dragPrefix}
          path={path}
          showBorder={true}
          expandedPaths={expandedPaths}
          onToggleExpand={onToggleExpand}
          labelForKey={labelForKey}
        />
      )}
    </div>
  );
}

// ============================================
// Primitive values
// ============================================

/**
 * One scalar, rendered for what it actually is.
 *
 * A string gets three extra readings the old renderer had none of: it can be an
 * unresolved template (shown as such instead of as data), a JSON document
 * (expandable as a tree instead of one escaped line), or simply taller than a
 * 280px column can give it (collapsed to six lines with an explicit expand; the
 * text itself is never cut - see CollapsibleText).
 */
export function PrimitiveValue({ value }: { value: any }) {
  if (value === null) {
    return <span className="font-mono text-sm text-slate-400">null</span>;
  }
  if (value === undefined) {
    return <span className="font-mono text-sm text-slate-400">undefined</span>;
  }
  if (typeof value === 'boolean') {
    return <span className="font-mono text-sm text-yellow-700 dark:text-yellow-300">{String(value)}</span>;
  }
  if (typeof value === 'number') {
    return <span className="font-mono text-sm text-green-700 dark:text-green-300">{value}</span>;
  }
  if (typeof value === 'string') {
    return <StringValue value={value} />;
  }
  return <span className="font-mono text-sm text-slate-500">{String(value)}</span>;
}

function StringValue({ value }: { value: string }) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  const unresolved = React.useMemo(() => detectUnresolvedValue(value), [value]);
  const embedded = React.useMemo(
    () => (unresolved ? undefined : parseEmbeddedJson(value)),
    [value, unresolved],
  );
  const [showJson, setShowJson] = React.useState(false);
  const [expandedPaths, setExpandedPaths] = React.useState<Set<string>>(() => new Set());
  const [showFull, setShowFull] = React.useState(false);
  // Whether the collapsed box actually hides anything. Seeded from the character
  // count - the cheap guess, right often enough for a first paint - and then
  // replaced by what the box MEASURED. The two disagree in both directions, and
  // both disagreements are user-visible: 430 characters across a fullscreen column
  // is three lines and hides nothing (the old rule still offered a control that
  // did nothing when clicked), while 300 characters holding 25 newlines is 25
  // lines and hides most of itself (the old rule offered nothing at all).
  const [overflows, setOverflows] = React.useState(() => value.length > LONG_STRING_CHARS);
  // Re-seed when the VALUE changes in place, under the same component instance.
  //
  // Not the item navigator: both run views swap the tree for a loading skeleton on
  // an item change, so this remounts there with fresh state. The path that reaches
  // it is a payload that updates under a live tree with no loading gate - the logs
  // explorer renders one straight onto its payload. Where no layout can be
  // measured (an unattached tree, a display:none ancestor) the measurement bails,
  // so without this the previous value's answer would stand for the new one.
  //
  // Derived state adjusted during render, the pattern useInspectorViewMode uses,
  // rather than an effect that would paint the stale answer once first.
  const [seededFor, setSeededFor] = React.useState(value);
  if (seededFor !== value) {
    setSeededFor(value);
    setOverflows(value.length > LONG_STRING_CHARS);
    // `showFull` too, and this half matters more: collapsed=false ALSO skips the
    // measurement, so an expanded row handed a new value renders it unbounded and a
    // 20 000-character prompt is back to pushing every sibling row out of the
    // column. The trade is deliberate and it does cost something: a reader who
    // expanded a payload that then updates under them loses the expansion. Keyed on
    // the string, so an identical refetch keeps it - only a genuine change collapses.
    setShowFull(false);
  }
  // Expanded, the control is the only way back; collapsed, it is offered exactly
  // when the box is hiding something.
  const controlVisible = showFull || overflows;

  const toggleExpand = React.useCallback((pathKey: string) => {
    setExpandedPaths((prev) => {
      const next = new Set(prev);
      if (next.has(pathKey)) next.delete(pathKey);
      else next.add(pathKey);
      return next;
    });
  }, []);

  if (unresolved) {
    // items-START below, and deliberately not the items-baseline its sibling uses.
    //
    // The badge shares its line with a COLLAPSED box, and `overflow-hidden` makes
    // that box a scroll container, whose synthesized baseline is its bottom border
    // edge. Baseline alignment would therefore drop the badge by the box's whole
    // height - up to the 8rem cap - and park it beside the LAST visible line of the
    // value it labels. Expanded, the box is not a scroll container and baseline
    // would behave; start is the only rule that is right in both states.
    //
    // The file answers this the same way wherever a box shares a line: JsonNode's
    // key/value row and FileObjectNode's property row are both items-start for this
    // reason. The sibling string branch below is the exception that can afford
    // items-baseline, because its box is alone on its line and the rule never has
    // to decide anything.
    return (
      <span className="flex min-w-0 flex-wrap items-start gap-1">
        <UnresolvedBadge kind={unresolved} />
        <CollapsibleText
          collapsed={!showFull}
          hidesContent={overflows}
          onOverflowChange={setOverflows}
          className="font-mono text-sm text-amber-700 dark:text-amber-300 break-all"
          testId="run-value-unresolved"
        >
          {value}
        </CollapsibleText>
        {controlVisible && (
          <ControlLine sticky={showFull}>
            <ShowMoreToggle expanded={showFull} stuck={showFull} onToggle={() => setShowFull((v) => !v)} />
          </ControlLine>
        )}
      </span>
    );
  }

  if (embedded !== undefined) {
    // Rendered EXACTLY as a real nested object is, because that is what it is.
    //
    // Same geometry as JsonNode: the chevron pushed to the right of the row, then
    // the `{n}` / `[n]` marker at the far edge, children indented behind a rule,
    // collapsed until asked. In particular there is NO raw-text preview beside the
    // marker - a real object never shows one, and showing it here was the whole
    // difference: an embedded object read as a different KIND of thing from the
    // identical object sitting one row above it, and the preview it added was an
    // unreadable one-line dump of the very structure the marker offers to open.
    // Nothing is filtered: an embedded {_type:'file', path} is NOT a recognised
    // reference (no mimeType, no size), so it shows {2} and opens to two rows, the
    // same as the identical real one. `_type` stays visible because on a malformed
    // reference it is the only thing saying it was meant to be a file.
    // Through isFileRef, because expanding routes a recognised file reference to the
    // file view, which draws a fixed four fields and ignores every other key.
    const embeddedCount = expandedRowCount(embedded, isFileRef(embedded));
    const embeddedMarker = Array.isArray(embedded) ? `[${embeddedCount}]` : `{${embeddedCount}}`;
    return (
      <span className="flex min-w-0 flex-1 flex-col gap-1">
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            setShowJson((v) => !v);
          }}
          title={showJson ? t('collapseJson') : t('expandJson')}
          aria-label={showJson ? t('collapseJson') : t('expandJson')}
          aria-expanded={showJson}
          data-testid="run-value-json-toggle"
          className="flex min-w-0 flex-1 items-center rounded text-left transition-colors hover:bg-slate-100 dark:hover:bg-slate-700"
        >
          <span className="flex-1" />
          <ChevronRight
            className={clsx(
              'h-3 w-3 flex-shrink-0 text-slate-400 dark:text-slate-500 transition-transform mr-2',
              showJson && 'rotate-90',
            )}
          />
          <span className="flex-shrink-0 text-sm font-mono text-orange-600 dark:text-orange-400">
            {embeddedMarker}
          </span>
        </button>
        {showJson && (
          <span className="block">
            <JsonValueTree
              data={embedded}
              path={[]}
              showBorder
              expandedPaths={expandedPaths}
              onToggleExpand={toggleExpand}
            />
          </span>
        )}
      </span>
    );
  }

  return (
    <span className="flex min-w-0 flex-1 flex-wrap items-baseline gap-1">
      <CollapsibleText
        collapsed={!showFull}
        hidesContent={overflows}
        onOverflowChange={setOverflows}
        className="font-mono text-sm text-blue-700 dark:text-blue-300 break-all"
        testId="run-value-string"
      >
        {`"${value}"`}
      </CollapsibleText>
      {controlVisible && (
        <ControlLine sticky={showFull}>
          <ShowMoreToggle expanded={showFull} stuck={showFull} onToggle={() => setShowFull((v) => !v)} />
        </ControlLine>
      )}
    </span>
  );
}

/**
 * The collapse control's own line, under the value.
 *
 * `basis-full` in the value's `flex-wrap` container, so it is a line and not a
 * neighbour. Three things go wrong when it shares the value's line, and all three
 * are about the top-right corner of a row:
 *
 *  - the row's copy button is pinned there (`RowControlOverlay`, revealed on
 *    hover), and an in-flow control at the end of the line lands underneath it
 *    once the value is expanded - the copy button paints on top and takes the
 *    click, so the reader cannot collapse the value with the mouse at all;
 *  - the control's height would be decided by the value's baseline, which is the
 *    bottom edge of a collapsed scroll container and the first line of an
 *    expanded block: it jumps the height of the box when clicked;
 *  - sharing the line costs the value the control's width on a box that is about
 *    100px wide on a compact Output column.
 *
 * Its own line costs the chevron's 16px plus the container's 4px row gap on the
 * rows that have one, and nothing else.
 */
function ControlLine({ sticky, children }: { sticky: boolean; children: React.ReactNode }) {
  return (
    <span
      className={clsx(
        'basis-full flex justify-end',
        // Expanded, the line FOLLOWS the reader: stuck to the bottom of the column
        // that scrolls it, for as long as the value it belongs to is on screen.
        //
        // The alternative was to cap the expanded value and let it scroll inside
        // itself, and the cap is the problem: the value does not live in the
        // viewport, it lives in a column whose height is set by its host. The same
        // `60vh` that is two thirds of a fullscreen inspector is one and a half
        // times a bottom-docked side panel, which is 40vh by default and can be
        // dragged to 200px - so the control ended up further out of reach in the
        // configuration the cap was supposed to rescue. A sticky line has no unit
        // to get wrong, and it leaves the value laid out in one piece rather than
        // in a scroller nested inside a scroller.
        // `z-10` is load-bearing, not decoration: the row's copy overlay is a later
        // sibling at `z-index: auto`, so with both on auto it paints above this line
        // and its `pointer-events-auto` button takes the click - the collision that
        // made an expanded value impossible to collapse with the mouse.
        sticky && 'sticky bottom-0 z-10',
      )}
      data-testid="run-value-control-line"
    >
      {children}
    </span>
  );
}

/**
 * A long value, collapsed by HEIGHT rather than cut by character count.
 *
 * It used to be cut: the first {@link LONG_STRING_CHARS} characters plus an
 * ellipsis, with the rest not in the document at all. On a resolved parameter
 * that is the wrong trade - the whole point of the Params column is to show what
 * the step ran with, and half of an agent's prompt answers no question anyone
 * has (on production runs, 54% of agent prompts are longer than the cut). It
 * also defeated find-in-page, select-all and copy, which is how a reader gets a
 * long value OUT of the panel.
 *
 * So the text is rendered whole and the BOX is bounded instead: about six lines
 * under a fade that says there is more, until the reader expands it. Nothing is
 * ever removed from the DOM.
 *
 * The box is applied to EVERY collapsed value, short ones included, and then
 * measured: `scrollHeight > clientHeight` is the only thing that knows whether
 * anything is actually hidden, because it depends on the column's width and on
 * the newlines in the value, neither of which a character count can see. The
 * caller offers its control on that answer, so the control appears exactly when
 * there is something behind it. A value that fits is unaffected by the box - it
 * is shorter than the cap, so the cap does nothing and the fade is not drawn.
 *
 * Where a measurement is impossible (no layout: jsdom, an unattached tree) both
 * heights read 0; the measurement is skipped and the caller keeps its seed,
 * rather than concluding "nothing is hidden" from an absence of information.
 */
function CollapsibleText({
  collapsed,
  hidesContent,
  onOverflowChange,
  className,
  testId,
  children,
}: {
  collapsed: boolean;
  /**
   * The caller's current answer to "is anything hidden". Drives the fade, which
   * must not be drawn over a value that fits: the box shrinks to its content, so
   * a gradient keyed on its own height would fade a one-line value to nothing.
   */
  hidesContent: boolean;
  onOverflowChange: (overflows: boolean) => void;
  className: string;
  testId: string;
  children: React.ReactNode;
}) {
  const boxRef = React.useRef<HTMLSpanElement>(null);

  // Before paint, not after: the seed is a guess and the correction is visible.
  // A 430-character value that fits would otherwise paint one frame WITH a fade
  // and a control and lose both on the next, on every row, every time the column
  // mounts. The read forces a synchronous layout either way, so doing it here
  // costs nothing extra.
  useIsomorphicLayoutEffect(() => {
    const el = boxRef.current;
    if (!el || !collapsed) return;

    const measure = () => {
      // No layout to read: leave the caller's seed alone (see the docblock).
      if (el.clientHeight === 0) return;
      // A pixel of slack: sub-pixel line heights make scrollHeight exceed
      // clientHeight by a fraction on values that are not clipped at all.
      onOverflowChange(el.scrollHeight > el.clientHeight + 1);
    };
    measure();

    // Answered unconditionally, with no "did the width change" filter in front.
    //
    // Such a filter is what a callback needs when its own result resizes what it
    // observes - the shape that produces "ResizeObserver loop completed with
    // undelivered notifications" in the dev overlay and in every pageerror
    // listener. It does not apply DIRECTLY here: the control this callback's result
    // adds or removes lives on its own line (see ControlLine), so the box's width
    // does not move, and React drops a setState that changes nothing.
    //
    // It can still apply INDIRECTLY, and the honest version is worth writing down:
    // each control line makes its row taller, so enough of them appearing at once
    // can push the column past its own height, raise its scrollbar and narrow every
    // box in it by the scrollbar's width. That converges - a scrollbar does not
    // un-appear because there is more content - and it needs a payload sitting
    // exactly on the column's height boundary, but it is the same shape one level
    // up. Should it ever be seen, the answer is to defer this setState a frame, not
    // to filter deliveries: filtering on width made the measurement permanently
    // blind to anything that changes the content's HEIGHT at constant width, a late
    // webfont swap being the obvious one, which is a real bug traded for a rare one.

    // The answer depends on the COLUMN'S WIDTH as much as on the value, and the
    // column has a drag handle. Observing the box catches that; re-measuring on
    // every render would not (the width can change without this subtree
    // re-rendering) and would force a synchronous layout per row on a tree that
    // can be hundreds of rows long.
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, [collapsed, children, onOverflowChange]);

  return (
    <span
      ref={boxRef}
      className={clsx(
        'block min-w-0 flex-1',
        collapsed && 'max-h-32 overflow-hidden',
        // The last line fades out through a MASK rather than a gradient painted in
        // a background colour: the same value is rendered on the panel, on a row,
        // and on that row while it is hovered, which are three different
        // backgrounds. A mask fades the text itself and is right on all three.
        collapsed && hidesContent &&
          '[mask-image:linear-gradient(to_bottom,black_calc(100%-1.5rem),transparent)]',
      )}
    >
      <span className={clsx(className, 'whitespace-pre-wrap')} data-testid={testId} data-collapsed={collapsed}>
        {children}
      </span>
    </span>
  );
}

/**
 * Row controls that cost the value NO width.
 *
 * In flow, a hidden control still reserves its box, so every row was permanently
 * narrower by the width of a button the reader only sees on hover. Floated to the
 * right edge instead, over an opaque strip that matches the row hover fill, so the
 * value gets the whole line and the controls simply cover its tail while the
 * pointer is there.
 *
 * `pointer-events-none` on the wrapper with `pointer-events-auto` on the buttons
 * keeps the strip from swallowing drag gestures on the row underneath.
 */
function RowControlOverlay({ children }: { children: React.ReactNode }) {
  return (
    <span
      className={clsx(
        'pointer-events-none absolute right-1 top-1 flex items-center gap-0.5 rounded-sm pl-2',
        'bg-gradient-to-l from-slate-50 via-slate-50 to-transparent',
        'dark:from-slate-800 dark:via-slate-800 dark:to-transparent',
        'opacity-0 transition-opacity group-hover/row:opacity-100 focus-within:opacity-100 focus-within:bg-slate-50 dark:focus-within:bg-slate-800',
        '[&>*]:pointer-events-auto',
      )}
    >
      {children}
    </span>
  );
}

/**
 * Collapse control for a long string, shaped like the one an object row uses.
 *
 * A chevron rather than a "show more" link: a collapsed string IS a collapsed
 * row, so it says so with the tree's own marker. The label lives in the tooltip
 * and in the accessible name, so a dense column is not carrying two words of
 * chrome on every line. It does not line up with an object row's chevron - that
 * one sits inside the row, this one on a line of its own at the right edge - and
 * the shared shape is the marker, not the column position.
 *
 * Always visible, because it is rendered only when something is actually hidden -
 * see CollapsibleText, which measures that rather than guessing it. It used to be
 * revealed on hover while collapsed, which made a bounded value read as a
 * truncated one: the reader saw text stop with no way to continue it.
 *
 * And IN FLOW, on its own line - see ControlLine, which owns that decision and
 * the reasons for it. It used to float over the value's right edge, which was the
 * right trade while it was hidden until hover: an in-flow control that is usually
 * invisible still reserves its box, so every row paid for it. It is no longer
 * that shape - it is rendered only when it has something to say - so in flow it
 * costs only the rows that have one. It covers no text either, with one exception
 * it is opaque for: while its line is STUCK it is pinned over the value it
 * belongs to.
 */
function ShowMoreToggle({
  expanded,
  onToggle,
  stuck = false,
}: {
  expanded: boolean;
  onToggle: () => void;
  /**
   * Whether its line is STICKY, which is every expanded value (see ControlLine).
   * Not the same as "currently pinned over text": a short expanded value has a
   * line that never leaves its place. Painting the background there is harmless
   * precisely because the colours match the surfaces underneath - which is the
   * same reason it must not be painted while collapsed, where the surface is the
   * panel and the chip would be visible for nothing.
   */
  stuck?: boolean;
}) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  const label = expanded ? t('showLess') : t('showMore');
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation();
        onToggle();
      }}
      data-testid="run-value-show-more"
      title={label}
      aria-label={label}
      aria-expanded={expanded}
      className={clsx(
        'flex-shrink-0 rounded p-0.5 text-slate-500 dark:text-slate-400',
        // Opaque ONLY while the line is sticky, which is the only state in which
        // this button can end up over the value's own text. Collapsed it sits on
        // the panel with nothing behind it, and an opaque chip there is a chip of
        // the wrong colour waiting to be noticed.
        //
        // Painted in the inspector's own two surfaces rather than the app's
        // `--bg-primary` token, because the panel does not use that token: it is
        // `bg-white dark:bg-gray-800`, and the row under the pointer is
        // `slate-50 / slate-800`. Both are matched. The one place this is
        // approximate is the logs explorer, whose dark surface is the token
        // (#171614) rather than gray-800 - a near-black on a near-black.
        stuck && 'bg-white dark:bg-gray-800 group-hover/row:bg-slate-50 dark:group-hover/row:bg-slate-800',
        'hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700',
        // Right-aligned on its line by ControlLine's `justify-end`; nothing here
        // decides position.
      )}
    >
      <ChevronRight className={clsx('h-3 w-3 transition-transform', expanded && 'rotate-90')} />
    </button>
  );
}

const UNRESOLVED_LABEL_KEY: Record<UnresolvedKind, string> = {
  invalid_template: 'unresolvedInvalidTemplate',
  unresolved_variable: 'unresolvedVariable',
  stringified_object: 'unresolvedStringifiedObject',
};

function UnresolvedBadge({ kind }: { kind: UnresolvedKind }) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  return (
    <span
      data-testid="run-value-unresolved-badge"
      title={t(UNRESOLVED_LABEL_KEY[kind])}
      className="inline-flex items-center rounded px-1 py-0.5 text-xs font-medium bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-300"
    >
      {t('unresolvedBadge')}
    </span>
  );
}

// ============================================
// Files
// ============================================

/**
 * Compact file card shown at the top of step outputs that carry a file.
 */
export function FilePreviewCard({ fileRef }: { fileRef: FileRef }) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  const [isDownloading, setIsDownloading] = React.useState(false);

  const handleDownload = async () => {
    setIsDownloading(true);
    try {
      await fileService.downloadAndSave(fileRef, fileRef.name);
    } catch (err) {
      console.error('Download failed:', err);
    } finally {
      setIsDownloading(false);
    }
  };

  const handlePreview = async () => {
    try {
      // View via an authenticated fetch (no token in the URL).
      const url = fileRefToUrl(fileRef, { inline: true });
      if (url) await openAuthedFileInNewTab(url);
    } catch (err) {
      console.error('Preview failed:', err);
    }
  };

  return (
    <div className="flex items-center gap-2 px-2 py-1.5 rounded-md bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700">
      <div className="flex-1 min-w-0">
        <span className="text-sm font-medium truncate block">{fileRef.name}</span>
        <span className="text-xs text-slate-500 dark:text-slate-400">
          {fileRef.mimeType} · {fileService.formatFileSize(fileRef.size)}
        </span>
      </div>
      <div className="flex items-center gap-1 flex-shrink-0">
        <button
          onClick={handlePreview}
          className="p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          title={t('viewFile')}
        >
          <Eye className="h-3.5 w-3.5" />
        </button>
        <button
          onClick={handleDownload}
          disabled={isDownloading}
          className="p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          title={t('downloadFile')}
        >
          <Download className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}

/** Wraps FileObjectNode when the WHOLE payload is a file reference. */
function TopLevelFileRefView({
  data,
  isDraggable,
  dragPrefix,
  path,
  expandedPaths,
  onToggleExpand,
}: {
  data: any;
  isDraggable: boolean;
  dragPrefix?: string;
  path: string[];
  expandedPaths: Set<string>;
  onToggleExpand: (pathKey: string) => void;
}) {
  // The empty path (`""`) is unique here: this only renders when the whole
  // payload is a FileRef, so no sibling node can share it.
  const pathKey = path.join('.');
  const isExpanded = expandedPaths.has(pathKey);
  const fullPath = dragPrefix ? `${dragPrefix}.${path.join('.')}` : path.join('.');
  const handleDragStart = (e: React.DragEvent) => {
    if (!isDraggable) return;
    e.stopPropagation();
    e.dataTransfer.setData('text/plain', `{{${fullPath}}}`);
    e.dataTransfer.effectAllowed = 'copy';
  };

  return (
    <FileObjectNode
      nodeKey="file"
      fileRef={data}
      isDraggable={isDraggable}
      dragPrefix={dragPrefix}
      path={path}
      isExpanded={isExpanded}
      onToggle={() => onToggleExpand(pathKey)}
      onDragStart={handleDragStart}
      fullPath={fullPath}
    />
  );
}

interface FileObjectNodeProps {
  nodeKey: string;
  fileRef: FileRef;
  isDraggable: boolean;
  dragPrefix?: string;
  path: string[];
  isExpanded: boolean;
  onToggle: () => void;
  onDragStart: (e: React.DragEvent) => void;
  fullPath: string;
}

function FileObjectNode({
  nodeKey,
  fileRef,
  isDraggable,
  dragPrefix,
  path,
  isExpanded,
  onToggle,
  onDragStart,
  fullPath,
}: FileObjectNodeProps) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  // Normalize DB/flattened format (file_url, file_name, …) to canonical FileRef
  const normalized = normalizeFileRef(fileRef);
  const [isDownloading, setIsDownloading] = React.useState(false);

  const handleDownload = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsDownloading(true);
    try {
      await fileService.downloadAndSave(normalized, normalized.name);
    } catch (err) {
      console.error('Download failed:', err);
    } finally {
      setIsDownloading(false);
    }
  };

  const filePath = getFilePath(normalized);

  // Keyed off FILE_REF_DISPLAY_PROPS so this list and the {n} that expandedRowCount
  // gives a file reference cannot drift apart.
  const propValues: Record<(typeof FILE_REF_DISPLAY_PROPS)[number], unknown> = {
    path: filePath,
    name: normalized.name,
    mimeType: normalized.mimeType,
    size: normalized.size,
  };
  const displayProps = FILE_REF_DISPLAY_PROPS.map((key) => ({ key, value: propValues[key] }));

  const handlePreview = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      // View via an authenticated fetch (no token in the URL).
      const url = fileRefToUrl(normalized, { inline: true });
      if (url) await openAuthedFileInNewTab(url);
    } catch (err) {
      console.error('Preview failed:', err);
    }
  };

  return (
    <div className="flex flex-col gap-1">
      <div
        className={clsx(
          'flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1',
          'cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800',
        )}
        draggable={isDraggable}
        onDragStart={onDragStart}
        onClick={onToggle}
        data-testid="json-file-toggle"
        title={isDraggable ? fullPath : nodeKey}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          {isDraggable && (
            <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0" />
          )}
          <span className="truncate flex-1 min-w-0 text-sm" title={nodeKey}>
            {nodeKey}
          </span>
          <ChevronRight
            className={clsx(
              'h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform flex-shrink-0 mr-2',
              isExpanded && 'rotate-90',
            )}
          />
        </div>
        <span className="text-sm font-mono text-orange-600 dark:text-orange-400 flex-shrink-0">
          {`{${displayProps.length}}`}
        </span>
        <div className="flex items-center gap-1 ml-2">
          <button
            onClick={handlePreview}
            className="p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
            title={t('viewFile')}
          >
            <Eye className="h-3.5 w-3.5" />
          </button>
          <button
            onClick={handleDownload}
            disabled={isDownloading}
            className="p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            title={t('downloadFile')}
          >
            {isDownloading ? <LoadingSpinner size="xs" /> : <Download className="h-3.5 w-3.5" />}
          </button>
        </div>
      </div>

      {isExpanded && (
        <div className="pl-3 border-l border-slate-200 dark:border-slate-700">
          <div className="space-y-1">
            {displayProps.map(({ key, value }) => (
              <div
                key={key}
                className={clsx(
                  'group/row relative flex items-start gap-2 text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1',
                  isDraggable
                    ? 'cursor-grab active:cursor-grabbing hover:bg-slate-50 dark:hover:bg-slate-800'
                    : 'cursor-default hover:bg-slate-50 dark:hover:bg-slate-800',
                )}
                draggable={isDraggable}
                onDragStart={(e) => {
                  if (!isDraggable) return;
                  e.stopPropagation();
                  const propPath = dragPrefix
                    ? `${dragPrefix}.${[...path, key].join('.')}`
                    : [...path, key].join('.');
                  e.dataTransfer.setData('text/plain', `{{${propPath}}}`);
                  e.dataTransfer.effectAllowed = 'copy';
                }}
                title={isDraggable ? `${fullPath}.${key}` : undefined}
              >
                {isDraggable && (
                  <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0 mt-0.5" />
                )}
                <span
                  className="truncate max-w-[120px] flex-shrink-0 text-sm"
                  title={key}
                  data-testid="json-row-key"
                >
                  {key}
                </span>
                <span className="text-slate-400 flex-shrink-0">:</span>
                <PrimitiveValue value={value} />
                <RowControlOverlay>
                  <CopyButton value={value} />
                </RowControlOverlay>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
