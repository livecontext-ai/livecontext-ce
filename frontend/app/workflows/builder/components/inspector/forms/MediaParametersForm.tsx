'use client';

import * as React from 'react';
import { ArrowDown, ArrowUp, ChevronRight, Info, Plus, Trash2 } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Input } from '@/components/ui/input';
import { Slider } from '@/components/ui/slider';
import { Switch } from '@/components/ui/switch';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { OptionalSection } from '../OptionalSection';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';
import {
  clampMediaDimension,
  clampMediaNonNegative,
  clampMediaNormalizeLufs,
  clampMediaOpacity,
  clampMediaSpeed,
  clampMediaTargetFps,
  clampMediaTransitionSeconds,
  clampMediaVolume,
  clampMediaWidthPercent,
  fileParamValue,
  MEDIA_AUDIO_FITS,
  MEDIA_CONCAT_INPUTS_MAX,
  MEDIA_DIMENSION_MAX,
  MEDIA_DIMENSION_MIN,
  MEDIA_IMAGE_FORMATS,
  MEDIA_NORMALIZE_LUFS_DEFAULT,
  MEDIA_NORMALIZE_LUFS_MAX,
  MEDIA_NORMALIZE_LUFS_MIN,
  MEDIA_OPACITY_MAX,
  MEDIA_OPACITY_MIN,
  MEDIA_OPERATIONS,
  MEDIA_OUTPUT_FORMATS,
  MEDIA_OVERLAY_POSITIONS,
  MEDIA_SPEED_DEFAULT,
  MEDIA_SPEED_MAX,
  MEDIA_SPEED_MIN,
  MEDIA_TARGET_FPS_MAX,
  MEDIA_TARGET_FPS_MIN,
  MEDIA_TRACKS_MAX,
  MEDIA_TRANSITION_SECONDS_DEFAULT,
  MEDIA_TRANSITION_SECONDS_MAX,
  MEDIA_TRANSITION_SECONDS_MIN,
  MEDIA_TRANSITIONS,
  MEDIA_VOLUME_DEFAULT,
  MEDIA_VOLUME_MAX,
  MEDIA_VOLUME_MIN,
  MEDIA_WIDTH_PERCENT_MAX,
  MEDIA_WIDTH_PERCENT_MIN,
  type MediaConcatInput,
  type MediaOperation,
  type MediaTrack,
} from '../../../utils/mediaParams';

interface MediaParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Commit a free-typed numeric draft: empty -> undefined (param omitted, backend
 * default applies), a {{...}} template passes through verbatim (resolved at run
 * time), anything else goes through the given clamp.
 */
function commitNumericDraft(
  raw: string,
  clamp: (v: unknown) => number | undefined,
): number | string | undefined {
  const trimmed = raw.trim();
  if (trimmed === '') return undefined;
  if (trimmed.includes('{{')) return trimmed;
  return clamp(trimmed);
}

function labelRow(label: string, requiredOrOptional: string) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{label}</span>
      <span className="text-sm text-slate-400 dark:text-slate-500">{requiredOrOptional}</span>
    </div>
  );
}

/** Free-form numeric input committed (clamped) on blur; accepts {{...}} templates. */
function NumberRow({
  label,
  hint,
  badge,
  value,
  onCommit,
  clamp,
  disabled,
}: {
  label: string;
  hint?: string;
  badge: string;
  value: number | string | undefined;
  onCommit: (value: number | string | undefined) => void;
  clamp: (v: unknown) => number | undefined;
  disabled: boolean;
}) {
  const [draft, setDraft] = React.useState<string>(value === undefined ? '' : String(value));
  React.useEffect(() => {
    setDraft(value === undefined ? '' : String(value));
  }, [value]);

  return (
    <div className="flex flex-col gap-1.5">
      {labelRow(label, badge)}
      <Input
        type="text"
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={() => {
          if (disabled) return;
          const committed = commitNumericDraft(draft, clamp);
          setDraft(committed === undefined ? '' : String(committed));
          onCommit(committed);
        }}
        disabled={disabled}
        className="w-full text-sm"
      />
      {hint ? <span className="text-xs text-slate-400 dark:text-slate-500">{hint}</span> : null}
    </div>
  );
}

/** Volume percent (0-400): slider + input combo. */
function VolumeRow({
  label,
  badge,
  value,
  onCommit,
  disabled,
}: {
  label: string;
  badge: string;
  value: number | string | undefined;
  onCommit: (value: number | string | undefined) => void;
  disabled: boolean;
}) {
  const numericValue = typeof value === 'number' ? value : MEDIA_VOLUME_DEFAULT;
  const [draft, setDraft] = React.useState<string>(value === undefined ? '' : String(value));
  React.useEffect(() => {
    setDraft(value === undefined ? '' : String(value));
  }, [value]);

  return (
    <div className="flex flex-col gap-1.5">
      {labelRow(label, badge)}
      <div className="flex items-center gap-3">
        <Slider
          value={[numericValue]}
          min={MEDIA_VOLUME_MIN}
          max={MEDIA_VOLUME_MAX}
          step={5}
          disabled={disabled}
          onValueChange={([v]) => {
            if (disabled) return;
            setDraft(String(v));
            onCommit(v === MEDIA_VOLUME_DEFAULT ? undefined : v);
          }}
          className="flex-1"
        />
        <Input
          type="text"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={() => {
            if (disabled) return;
            const committed = commitNumericDraft(draft, (v) => clampMediaVolume(v));
            setDraft(committed === undefined ? '' : String(committed));
            onCommit(committed === MEDIA_VOLUME_DEFAULT ? undefined : committed);
          }}
          disabled={disabled}
          className="w-20 text-sm"
        />
      </div>
    </div>
  );
}

function SwitchRow({
  label,
  checked,
  onChange,
  disabled,
}: {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled: boolean;
}) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{label}</span>
      <Switch checked={checked} onCheckedChange={onChange} disabled={disabled} />
    </div>
  );
}

const DUCK_NONE = '__none__';

/**
 * Form component for Media node parameters. Probe, mux, mix, or extract audio
 * and video files. Config lives in the generic params map with the contract
 * field names ({ operation, input/video/audio/tracks, ...options }).
 */
export function MediaParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: MediaParametersFormProps) {
  const t = useTranslations('workflowBuilder.forms');

  const operation: string = (data as any).mediaOperation ?? '';
  const params: Record<string, any> = React.useMemo(
    () => (data as any).mediaParams ?? {},
    [data],
  );
  const tracks: MediaTrack[] = Array.isArray(params.tracks) ? params.tracks : [];

  const [showOptions, setShowOptions] = React.useState(false);

  const update = React.useCallback((patch: Record<string, any>) => {
    if (isRunMode) return;
    onUpdate({ ...data, ...patch } as BuilderNodeData);
  }, [data, isRunMode, onUpdate]);

  const setParam = React.useCallback((key: string, value: unknown) => {
    if (isRunMode) return;
    const next = { ...params };
    if (value === undefined) {
      delete next[key];
    } else {
      next[key] = value;
    }
    update({ mediaParams: next });
  }, [isRunMode, params, update]);

  const setTrack = React.useCallback((index: number, patch: Record<string, unknown>) => {
    const next = tracks.map((track, i) => {
      if (i !== index) return track;
      const merged: Record<string, unknown> = { ...track };
      for (const [key, value] of Object.entries(patch)) {
        if (value === undefined) {
          delete merged[key];
        } else {
          merged[key] = value;
        }
      }
      return merged as unknown as MediaTrack;
    });
    setParam('tracks', next);
  }, [setParam, tracks]);

  const effectiveTrackId = React.useCallback((track: MediaTrack, index: number): string => {
    return track.id && track.id.trim() !== '' ? track.id : `track_${index + 1}`;
  }, []);

  /**
   * Remove a mix track AND keep the other tracks' duck_under references sane.
   * Default ids are positional (track_N by index), so removing a track renumbers
   * them and a stale reference would silently re-point to a DIFFERENT track:
   * - a duck_under that referenced the removed track is cleared (with its
   *   duck_amount_db/duck_attack_ms/duck_release_ms companions);
   * - a duck_under that referenced a surviving track via a positional default id
   *   is rewritten to that track's new positional id;
   * - references to explicit ids of surviving tracks stay untouched.
   */
  const removeTrack = React.useCallback((removeIndex: number) => {
    const effectiveIds = tracks.map((track, i) => effectiveTrackId(track, i));
    const removedId = effectiveIds[removeIndex];
    const survivors = tracks.filter((_, i) => i !== removeIndex);
    // Old effective id -> new effective id for surviving tracks. Explicit ids map
    // to themselves and never enter the remap; only shifted positional defaults do.
    const positionalRemap = new Map<string, string>();
    survivors.forEach((track, newIndex) => {
      const oldIndex = newIndex < removeIndex ? newIndex : newIndex + 1;
      const oldId = effectiveIds[oldIndex];
      const newId = effectiveTrackId(track, newIndex);
      if (oldId !== newId) positionalRemap.set(oldId, newId);
    });
    const next = survivors.map((track) => {
      const duckUnder = typeof track.duck_under === 'string' && track.duck_under.trim() !== '' ? track.duck_under : undefined;
      if (duckUnder === undefined) return track;
      if (duckUnder === removedId) {
        const cleaned: Record<string, unknown> = { ...track };
        delete cleaned.duck_under;
        delete cleaned.duck_amount_db;
        delete cleaned.duck_attack_ms;
        delete cleaned.duck_release_ms;
        return cleaned as unknown as MediaTrack;
      }
      const renumbered = positionalRemap.get(duckUnder);
      if (renumbered !== undefined) return { ...track, duck_under: renumbered };
      return track;
    });
    setParam('tracks', next);
  }, [effectiveTrackId, setParam, tracks]);

  // concat: the ordered list of input clips. Items have no cross-references
  // (unlike mix tracks' duck_under), so remove/reorder are plain array ops.
  const concatInputs: MediaConcatInput[] = Array.isArray(params.inputs) ? params.inputs : [];

  const setConcatInput = React.useCallback((index: number, patch: Record<string, unknown>) => {
    const next = concatInputs.map((item, i) => {
      if (i !== index) return item;
      const merged: Record<string, unknown> = { ...item };
      for (const [key, value] of Object.entries(patch)) {
        if (value === undefined) {
          delete merged[key];
        } else {
          merged[key] = value;
        }
      }
      return merged as unknown as MediaConcatInput;
    });
    setParam('inputs', next);
  }, [concatInputs, setParam]);

  const removeConcatInput = React.useCallback((index: number) => {
    setParam('inputs', concatInputs.filter((_, i) => i !== index));
  }, [concatInputs, setParam]);

  const moveConcatInput = React.useCallback((index: number, direction: -1 | 1) => {
    const target = index + direction;
    if (target < 0 || target >= concatInputs.length) return;
    const next = [...concatInputs];
    [next[index], next[target]] = [next[target], next[index]];
    setParam('inputs', next);
  }, [concatInputs, setParam]);

  /**
   * The current value of a file param for exprField: the expression STRING, or
   * the LITERAL FileRef object verbatim (so the chip branch below can render
   * it), '' otherwise. Same passthrough semantics as the plan export.
   */
  const fileFieldValue = (v: unknown): string | Record<string, any> =>
    fileParamValue(v) as string | Record<string, any>;

  const exprField = (
    paramKey: string,
    value: string | Record<string, any>,
    onChange: (value: string) => void,
    options: { label: string; required: boolean; placeholder: string; hint?: string },
  ) => {
    // A literal FileRef object (agent-built plan / Files picker) is not editable as an
    // expression: show it as a named chip with a remove action instead of coercing it
    // to an empty editor (which used to wipe it on the next save).
    const literalRef = value && typeof value === 'object' && typeof (value as any).path === 'string'
      ? (value as unknown as { name?: string; path: string })
      : null;
    if (literalRef) {
      return (
        <div className="flex flex-col gap-1.5">
          {labelRow(options.label, options.required ? t('required') : t('optional'))}
          <div className="flex items-center gap-2 rounded-lg border border-theme bg-[var(--bg-secondary)] px-2.5 py-1.5">
            <span className="flex-1 truncate text-sm" title={literalRef.path}>
              {literalRef.name || literalRef.path}
            </span>
            {!isRunMode && (
              <button
                type="button"
                className="text-slate-400 hover:text-red-500"
                title={t('media.removeLiteralFile')}
                onClick={() => onChange('')}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            )}
          </div>
          {options.hint ? <span className="text-xs text-slate-400 dark:text-slate-500">{options.hint}</span> : null}
        </div>
      );
    }
    const exprValue = typeof value === 'string' ? value : '';
    return (
    <div className="flex flex-col gap-1.5">
      {labelRow(options.label, options.required ? t('required') : t('optional'))}
      <ExpressionEditor
        value={exprValue}
        onChange={(v) => { if (!isRunMode) onChange(v); }}
        placeholder={options.placeholder}
        className="w-full"
        unknownVariables={findUnknownVariables({ [paramKey]: exprValue })}
        handleId={`media-${paramKey}-${node.id}`}
        connections={connectionProps.connections}
        onHandleClick={connectionProps.handleHandleClick}
        draggingFromHandle={connectionProps.draggingFromHandle}
        onHandleMouseDown={connectionProps.handleHandleMouseDown}
        onHandleMouseUp={connectionProps.handleHandleMouseUp}
        hoveredTargetHandle={connectionProps.hoveredTargetHandle}
        onSetHandleRef={connectionProps.handleSetHandleRef}
        isRequired={options.required}
        readOnly={isRunMode}
      />
      {options.hint ? <span className="text-xs text-slate-400 dark:text-slate-500">{options.hint}</span> : null}
    </div>
    );
  };

  // Normalize: auto (default -16 LUFS) | custom LUFS | off
  const normalizeValue = params.normalize;
  const normalizeMode: 'auto' | 'custom' | 'off' =
    normalizeValue === false ? 'off'
      : (typeof normalizeValue === 'number' || (typeof normalizeValue === 'string' && normalizeValue.trim() !== '' && normalizeValue !== 'true')) ? 'custom'
        : 'auto';

  const normalizeSection = (
    <>
      <div className="flex flex-col gap-1.5">
        {labelRow(t('media.normalize'), t('optional'))}
        <Select
          value={normalizeMode}
          onValueChange={(mode) => {
            if (mode === 'auto') setParam('normalize', undefined);
            else if (mode === 'off') setParam('normalize', false);
            else setParam('normalize', MEDIA_NORMALIZE_LUFS_DEFAULT);
          }}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="auto">{t('media.normalizeAuto')}</SelectItem>
            <SelectItem value="custom">{t('media.normalizeCustom')}</SelectItem>
            <SelectItem value="off">{t('media.normalizeOff')}</SelectItem>
          </SelectContent>
        </Select>
      </div>
      {normalizeMode === 'custom' && (
        <NumberRow
          label={t('media.normalizeLufs')}
          hint={t('media.normalizeLufsHint', { min: MEDIA_NORMALIZE_LUFS_MIN, max: MEDIA_NORMALIZE_LUFS_MAX })}
          badge={t('optional')}
          value={typeof normalizeValue === 'number' || typeof normalizeValue === 'string' ? normalizeValue : MEDIA_NORMALIZE_LUFS_DEFAULT}
          onCommit={(v) => setParam('normalize', v === undefined ? MEDIA_NORMALIZE_LUFS_DEFAULT : v)}
          clamp={(v) => clampMediaNormalizeLufs(v)}
          disabled={isRunMode}
        />
      )}
    </>
  );

  // Concat normalize: default OFF (differs from mux/mix where undefined means auto).
  // Loudness normalization forces the re-encode path, so it is opt-in here.
  const concatNormalizeMode: 'auto' | 'custom' | 'off' =
    normalizeValue === true ? 'auto'
      : (typeof normalizeValue === 'number' || (typeof normalizeValue === 'string' && normalizeValue.trim() !== '' && normalizeValue !== 'true')) ? 'custom'
        : 'off';

  const concatNormalizeSection = (
    <>
      <div className="flex flex-col gap-1.5">
        {labelRow(t('media.normalize'), t('optional'))}
        <Select
          value={concatNormalizeMode}
          onValueChange={(mode) => {
            if (mode === 'off') setParam('normalize', undefined);
            else if (mode === 'auto') setParam('normalize', true);
            else setParam('normalize', MEDIA_NORMALIZE_LUFS_DEFAULT);
          }}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="off">{t('media.normalizeOff')}</SelectItem>
            <SelectItem value="auto">{t('media.normalizeAuto')}</SelectItem>
            <SelectItem value="custom">{t('media.normalizeCustom')}</SelectItem>
          </SelectContent>
        </Select>
        <span className="text-xs text-slate-400 dark:text-slate-500">{t('media.concatNormalizeHint')}</span>
      </div>
      {concatNormalizeMode === 'custom' && (
        <NumberRow
          label={t('media.normalizeLufs')}
          hint={t('media.normalizeLufsHint', { min: MEDIA_NORMALIZE_LUFS_MIN, max: MEDIA_NORMALIZE_LUFS_MAX })}
          badge={t('optional')}
          value={typeof normalizeValue === 'number' || typeof normalizeValue === 'string' ? normalizeValue : MEDIA_NORMALIZE_LUFS_DEFAULT}
          onCommit={(v) => setParam('normalize', v === undefined ? MEDIA_NORMALIZE_LUFS_DEFAULT : v)}
          clamp={(v) => clampMediaNormalizeLufs(v)}
          disabled={isRunMode}
        />
      )}
    </>
  );

  const audioFitRow = (
    <div className="flex flex-col gap-1.5">
      {labelRow(t('media.audioFit'), t('optional'))}
      <Select
        value={typeof params.audio_fit === 'string' && (MEDIA_AUDIO_FITS as readonly string[]).includes(params.audio_fit) ? params.audio_fit : 'pad'}
        onValueChange={(v) => setParam('audio_fit', v === 'pad' ? undefined : v)}
        disabled={isRunMode}
      >
        <SelectTrigger className="w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="pad">{t('media.audioFitPad')}</SelectItem>
          <SelectItem value="shortest">{t('media.audioFitShortest')}</SelectItem>
          <SelectItem value="loop">{t('media.audioFitLoop')}</SelectItem>
        </SelectContent>
      </Select>
    </div>
  );

  const audioBitrateRow = (
    <div className="flex flex-col gap-1.5">
      {labelRow(t('media.audioBitrate'), t('optional'))}
      <Input
        type="text"
        value={typeof params.audio_bitrate === 'string' ? params.audio_bitrate : ''}
        placeholder="192k"
        onChange={(e) => setParam('audio_bitrate', e.target.value.trim() === '' ? undefined : e.target.value)}
        disabled={isRunMode}
        className="w-full text-sm"
      />
    </div>
  );

  const outputFormatRow = (
    <div className="flex flex-col gap-1.5">
      {labelRow(t('media.outputFormat'), t('optional'))}
      <Select
        value={typeof params.output_format === 'string' && (MEDIA_OUTPUT_FORMATS as readonly string[]).includes(params.output_format) ? params.output_format : 'mp3'}
        onValueChange={(v) => setParam('output_format', v === 'mp3' ? undefined : v)}
        disabled={isRunMode}
      >
        <SelectTrigger className="w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {MEDIA_OUTPUT_FORMATS.map((format) => (
            <SelectItem key={format} value={format}>{format}</SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );

  const keepOriginalAudioSection = (
    <>
      <SwitchRow
        label={t('media.keepOriginalAudio')}
        checked={params.keep_original_audio === true}
        onChange={(checked) => {
          if (isRunMode) return;
          const next = { ...params };
          if (checked) {
            next.keep_original_audio = true;
          } else {
            delete next.keep_original_audio;
            delete next.original_volume;
          }
          update({ mediaParams: next });
        }}
        disabled={isRunMode}
      />
      {params.keep_original_audio === true && (
        <VolumeRow
          label={t('media.originalVolume')}
          badge={t('optional')}
          value={params.original_volume}
          onCommit={(v) => setParam('original_volume', v)}
          disabled={isRunMode}
        />
      )}
    </>
  );

  // Contract: loop + trim on the same audio is invalid. While loop is on the
  // trim fields are disabled (and cleared on the loop toggle).
  const trimRows = (trimsDisabled: boolean) => (
    <>
      <NumberRow
        label={t('media.trimStartSeconds')}
        hint={trimsDisabled ? t('media.loopTrimHint') : undefined}
        badge={t('optional')}
        value={params.trim_start_seconds}
        onCommit={(v) => setParam('trim_start_seconds', v)}
        clamp={clampMediaNonNegative}
        disabled={isRunMode || trimsDisabled}
      />
      <NumberRow
        label={t('media.trimEndSeconds')}
        badge={t('optional')}
        value={params.trim_end_seconds}
        onCommit={(v) => setParam('trim_end_seconds', v)}
        clamp={clampMediaNonNegative}
        disabled={isRunMode || trimsDisabled}
      />
    </>
  );

  // Per-operation SET-option counts shown on the OptionalSection header (same
  // "how many options are set" semantics the old hand-rolled header had).
  const countSet = (keys: string[]) => keys.filter((key) => params[key] !== undefined).length;
  const muxOptionsCount = countSet(['volume', 'offset_seconds', 'trim_start_seconds', 'trim_end_seconds', 'loop', 'fade_in_seconds', 'fade_out_seconds', 'keep_original_audio', 'original_volume', 'audio_fit', 'normalize', 'audio_bitrate']);
  const mixOptionsCount = countSet(['video', 'keep_original_audio', 'original_volume', 'audio_fit', 'normalize', 'audio_bitrate', 'output_format']);
  const extractOptionsCount = countSet(['output_format', 'audio_bitrate', 'trim_start_seconds', 'trim_end_seconds']);
  const concatOptionsCount = countSet(['transition', 'transition_seconds', 'target_width', 'target_height', 'target_fps', 'fade_in_seconds', 'fade_out_seconds', 'normalize', 'audio_bitrate']);
  const frameOptionsCount = countSet(['at_seconds', 'image_format', 'width']);
  const overlayOptionsCount = countSet(['position', 'margin_px', 'width_percent', 'opacity', 'start_seconds', 'end_seconds']);

  const toggleOptions = () => setShowOptions((open) => !open);

  return (
    <div className="space-y-4 pt-2">
      {/* Operation (required) */}
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('media.operation')}</span>
            <Popover>
              <PopoverTrigger asChild>
                <button
                  type="button"
                  className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
                >
                  <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                  <p className="font-semibold text-slate-900 dark:text-slate-100">{t('media.title')}</p>
                  <p>{t('media.description')}</p>
                </div>
              </PopoverContent>
            </Popover>
          </div>
          <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
        </div>
        <Select
          value={(MEDIA_OPERATIONS as readonly string[]).includes(operation) ? operation : undefined}
          onValueChange={(op) => update({ mediaOperation: op as MediaOperation })}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full">
            <SelectValue placeholder={t('media.operationPlaceholder')} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="probe">
              <div className="flex flex-col items-start">
                <span>{t('media.opProbe')}</span>
                <span className="text-xs text-slate-400 dark:text-slate-500">{t('media.opProbeDesc')}</span>
              </div>
            </SelectItem>
            <SelectItem value="mux_audio">
              <div className="flex flex-col items-start">
                <span>{t('media.opMuxAudio')}</span>
                <span className="text-xs text-slate-400 dark:text-slate-500">{t('media.opMuxAudioDesc')}</span>
              </div>
            </SelectItem>
            <SelectItem value="mix">
              <div className="flex flex-col items-start">
                <span>{t('media.opMix')}</span>
                <span className="text-xs text-slate-400 dark:text-slate-500">{t('media.opMixDesc')}</span>
              </div>
            </SelectItem>
            <SelectItem value="extract_audio">
              <div className="flex flex-col items-start">
                <span>{t('media.opExtractAudio')}</span>
                <span className="text-xs text-slate-400 dark:text-slate-500">{t('media.opExtractAudioDesc')}</span>
              </div>
            </SelectItem>
            <SelectItem value="concat">
              <div className="flex flex-col items-start">
                <span>{t('media.opConcat')}</span>
                <span className="text-xs text-slate-400 dark:text-slate-500">{t('media.opConcatDesc')}</span>
              </div>
            </SelectItem>
            <SelectItem value="frame">
              <div className="flex flex-col items-start">
                <span>{t('media.opFrame')}</span>
                <span className="text-xs text-slate-400 dark:text-slate-500">{t('media.opFrameDesc')}</span>
              </div>
            </SelectItem>
            <SelectItem value="overlay">
              <div className="flex flex-col items-start">
                <span>{t('media.opOverlay')}</span>
                <span className="text-xs text-slate-400 dark:text-slate-500">{t('media.opOverlayDesc')}</span>
              </div>
            </SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* probe / extract_audio: input file */}
      {(operation === 'probe' || operation === 'extract_audio') && exprField(
        'input',
        fileFieldValue(params.input),
        (v) => setParam('input', v),
        {
          label: t('media.input'),
          required: true,
          placeholder: t('media.inputPlaceholder'),
          hint: t('media.inputHint'),
        },
      )}

      {/* extract_audio: format + bitrate + trims (all optional) */}
      {operation === 'extract_audio' && (
        <OptionalSection isOpen={showOptions} onToggle={toggleOptions} count={extractOptionsCount}>
          {outputFormatRow}
          {audioBitrateRow}
          {trimRows(false)}
        </OptionalSection>
      )}

      {/* mux_audio: video + audio + options */}
      {operation === 'mux_audio' && (
        <>
          {exprField('video', fileFieldValue(params.video), (v) => setParam('video', v), {
            label: t('media.video'),
            required: true,
            placeholder: t('media.videoPlaceholder'),
          })}
          {exprField('audio', fileFieldValue(params.audio), (v) => setParam('audio', v), {
            label: t('media.audio'),
            required: true,
            placeholder: t('media.audioPlaceholder'),
          })}
          <OptionalSection isOpen={showOptions} onToggle={toggleOptions} count={muxOptionsCount}>
            <VolumeRow
              label={t('media.volume')}
              badge={t('optional')}
              value={params.volume}
              onCommit={(v) => setParam('volume', v)}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.offsetSeconds')}
              badge={t('optional')}
              value={params.offset_seconds}
              onCommit={(v) => setParam('offset_seconds', v)}
              clamp={clampMediaNonNegative}
              disabled={isRunMode}
            />
            {trimRows(params.loop === true)}
            <SwitchRow
              label={t('media.loop')}
              checked={params.loop === true}
              onChange={(checked) => {
                if (isRunMode) return;
                const next = { ...params };
                if (checked) {
                  next.loop = true;
                  // loop + trim is invalid per the contract: clear the trims
                  delete next.trim_start_seconds;
                  delete next.trim_end_seconds;
                } else {
                  delete next.loop;
                }
                update({ mediaParams: next });
              }}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.fadeInSeconds')}
              badge={t('optional')}
              value={params.fade_in_seconds}
              onCommit={(v) => setParam('fade_in_seconds', v)}
              clamp={clampMediaNonNegative}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.fadeOutSeconds')}
              hint={t('media.fadeOutHint')}
              badge={t('optional')}
              value={params.fade_out_seconds}
              onCommit={(v) => setParam('fade_out_seconds', v)}
              clamp={clampMediaNonNegative}
              disabled={isRunMode}
            />
            {keepOriginalAudioSection}
            {audioFitRow}
            {normalizeSection}
            {audioBitrateRow}
          </OptionalSection>
        </>
      )}

      {/* mix: tracks (required) + optional video + global options */}
      {operation === 'mix' && (
        <>
          {/* Tracks list */}
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('media.tracks')}</span>
              <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
            </div>
            {tracks.map((track, index) => (
              <div key={index} className="rounded-lg border border-slate-200 dark:border-slate-700 p-2.5 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-semibold text-slate-600 dark:text-slate-300">
                    {t('media.track', { index: index + 1 })}
                    <span className="ml-1.5 text-xs font-normal text-slate-400 dark:text-slate-500">{effectiveTrackId(track, index)}</span>
                  </span>
                  {!isRunMode && (
                    <button
                      type="button"
                      onClick={() => removeTrack(index)}
                      className="inline-flex items-center justify-center rounded-md p-1 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20"
                      aria-label={t('media.removeTrack')}
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  )}
                </div>
                {exprField(`track-${index}-source`, fileFieldValue(track.source), (v) => setTrack(index, { source: v }), {
                  label: t('media.trackSource'),
                  required: true,
                  placeholder: t('media.trackSourcePlaceholder'),
                })}
                <TrackAdvancedSection
                  t={t}
                  track={track}
                  index={index}
                  isRunMode={isRunMode}
                  setTrack={setTrack}
                  otherTrackIds={tracks
                    .map((other, i) => ({ id: effectiveTrackId(other, i), i }))
                    .filter(({ i }) => i !== index)
                    .map(({ id }) => id)}
                />
              </div>
            ))}
            {!isRunMode && (
              <button
                type="button"
                onClick={() => setParam('tracks', [...tracks, { source: '' }])}
                disabled={tracks.length >= MEDIA_TRACKS_MAX}
                className="inline-flex items-center gap-1.5 self-start rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1.5 text-sm text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Plus className="h-3.5 w-3.5" />
                {t('media.addTrack')}
              </button>
            )}
            <span className="text-xs text-slate-400 dark:text-slate-500">{t('media.tracksHint', { max: MEDIA_TRACKS_MAX })}</span>
            {!(typeof params.video === 'string' && params.video.trim() !== '') && tracks.length > 0 && tracks.every((track) => track.loop === true) && (
              <span className="text-xs text-amber-600 dark:text-amber-500">{t('media.mixNoVideoLoopHint')}</span>
            )}
          </div>

          <OptionalSection isOpen={showOptions} onToggle={toggleOptions} count={mixOptionsCount}>
            {exprField('video', fileFieldValue(params.video), (v) => setParam('video', v.trim() === '' ? undefined : v), {
              label: t('media.video'),
              required: false,
              placeholder: t('media.videoPlaceholder'),
              hint: t('media.mixVideoHint'),
            })}
            {typeof params.video === 'string' && params.video.trim() !== '' && keepOriginalAudioSection}
            {audioFitRow}
            {normalizeSection}
            {audioBitrateRow}
            {!(typeof params.video === 'string' && params.video.trim() !== '') && outputFormatRow}
          </OptionalSection>
        </>
      )}

      {/* concat: ordered input clips + transition + global options */}
      {operation === 'concat' && (
        <>
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('media.clips')}</span>
              <span className="text-sm text-slate-500 dark:text-slate-400">{t('required')}</span>
            </div>
            {concatInputs.map((item, index) => (
              <div key={index} className="rounded-lg border border-slate-200 dark:border-slate-700 p-2.5 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-semibold text-slate-600 dark:text-slate-300">
                    {t('media.clip', { index: index + 1 })}
                  </span>
                  {!isRunMode && (
                    <div className="flex items-center gap-0.5">
                      <button
                        type="button"
                        onClick={() => moveConcatInput(index, -1)}
                        disabled={index === 0}
                        className="inline-flex items-center justify-center rounded-md p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 disabled:opacity-30 disabled:cursor-not-allowed"
                        aria-label={t('media.moveClipUp')}
                      >
                        <ArrowUp className="h-3.5 w-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={() => moveConcatInput(index, 1)}
                        disabled={index === concatInputs.length - 1}
                        className="inline-flex items-center justify-center rounded-md p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 disabled:opacity-30 disabled:cursor-not-allowed"
                        aria-label={t('media.moveClipDown')}
                      >
                        <ArrowDown className="h-3.5 w-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={() => removeConcatInput(index)}
                        className="inline-flex items-center justify-center rounded-md p-1 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20"
                        aria-label={t('media.removeClip')}
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  )}
                </div>
                {exprField(`input-${index}-source`, fileFieldValue(item.source), (v) => setConcatInput(index, { source: v }), {
                  label: t('media.clipSource'),
                  required: true,
                  placeholder: t('media.clipSourcePlaceholder'),
                })}
                <NumberRow
                  label={t('media.trimStartSeconds')}
                  badge={t('optional')}
                  value={item.trim_start_seconds}
                  onCommit={(v) => setConcatInput(index, { trim_start_seconds: v })}
                  clamp={clampMediaNonNegative}
                  disabled={isRunMode}
                />
                <NumberRow
                  label={t('media.trimEndSeconds')}
                  badge={t('optional')}
                  value={item.trim_end_seconds}
                  onCommit={(v) => setConcatInput(index, { trim_end_seconds: v })}
                  clamp={clampMediaNonNegative}
                  disabled={isRunMode}
                />
                <NumberRow
                  label={t('media.speed')}
                  hint={t('media.speedHint', { min: MEDIA_SPEED_MIN, max: MEDIA_SPEED_MAX, default: MEDIA_SPEED_DEFAULT })}
                  badge={t('optional')}
                  value={item.speed}
                  onCommit={(v) => setConcatInput(index, { speed: v })}
                  clamp={(v) => clampMediaSpeed(v)}
                  disabled={isRunMode}
                />
              </div>
            ))}
            {!isRunMode && (
              <button
                type="button"
                onClick={() => setParam('inputs', [...concatInputs, { source: '' }])}
                disabled={concatInputs.length >= MEDIA_CONCAT_INPUTS_MAX}
                className="inline-flex items-center gap-1.5 self-start rounded-md border border-slate-200 dark:border-slate-700 px-2.5 py-1.5 text-sm text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Plus className="h-3.5 w-3.5" />
                {t('media.addClip')}
              </button>
            )}
            <span className="text-xs text-slate-400 dark:text-slate-500">{t('media.clipsHint', { max: MEDIA_CONCAT_INPUTS_MAX })}</span>
          </div>

          <OptionalSection isOpen={showOptions} onToggle={toggleOptions} count={concatOptionsCount}>
            <div className="flex flex-col gap-1.5">
              {labelRow(t('media.transition'), t('optional'))}
              <Select
                value={typeof params.transition === 'string' && (MEDIA_TRANSITIONS as readonly string[]).includes(params.transition) ? params.transition : 'cut'}
                onValueChange={(v) => setParam('transition', v === 'cut' ? undefined : v)}
                disabled={isRunMode}
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="cut">{t('media.transitionCut')}</SelectItem>
                  <SelectItem value="crossfade">{t('media.transitionCrossfade')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {params.transition === 'crossfade' && (
              <NumberRow
                label={t('media.transitionSeconds')}
                hint={t('media.transitionSecondsHint', { min: MEDIA_TRANSITION_SECONDS_MIN, max: MEDIA_TRANSITION_SECONDS_MAX, default: MEDIA_TRANSITION_SECONDS_DEFAULT })}
                badge={t('optional')}
                value={params.transition_seconds}
                onCommit={(v) => setParam('transition_seconds', v)}
                clamp={(v) => clampMediaTransitionSeconds(v)}
                disabled={isRunMode}
              />
            )}
            <NumberRow
              label={t('media.targetWidth')}
              badge={t('optional')}
              value={params.target_width}
              onCommit={(v) => setParam('target_width', v)}
              clamp={clampMediaDimension}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.targetHeight')}
              hint={t('media.targetSizeHint', { min: MEDIA_DIMENSION_MIN, max: MEDIA_DIMENSION_MAX })}
              badge={t('optional')}
              value={params.target_height}
              onCommit={(v) => setParam('target_height', v)}
              clamp={clampMediaDimension}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.targetFps')}
              hint={t('media.targetFpsHint', { min: MEDIA_TARGET_FPS_MIN, max: MEDIA_TARGET_FPS_MAX })}
              badge={t('optional')}
              value={params.target_fps}
              onCommit={(v) => setParam('target_fps', v)}
              clamp={clampMediaTargetFps}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.fadeInSeconds')}
              badge={t('optional')}
              value={params.fade_in_seconds}
              onCommit={(v) => setParam('fade_in_seconds', v)}
              clamp={clampMediaNonNegative}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.fadeOutSeconds')}
              badge={t('optional')}
              value={params.fade_out_seconds}
              onCommit={(v) => setParam('fade_out_seconds', v)}
              clamp={clampMediaNonNegative}
              disabled={isRunMode}
            />
            {concatNormalizeSection}
            {audioBitrateRow}
          </OptionalSection>
        </>
      )}

      {/* frame: input + timestamp + format + width */}
      {operation === 'frame' && (
        <>
          {exprField('input', fileFieldValue(params.input), (v) => setParam('input', v), {
            label: t('media.input'),
            required: true,
            placeholder: t('media.inputPlaceholder'),
            hint: t('media.inputHint'),
          })}
          <OptionalSection isOpen={showOptions} onToggle={toggleOptions} count={frameOptionsCount}>
            <NumberRow
              label={t('media.atSeconds')}
              hint={t('media.atSecondsHint')}
              badge={t('optional')}
              value={params.at_seconds}
              onCommit={(v) => setParam('at_seconds', v)}
              clamp={clampMediaNonNegative}
              disabled={isRunMode}
            />
            <div className="flex flex-col gap-1.5">
              {labelRow(t('media.imageFormat'), t('optional'))}
              <Select
                value={typeof params.image_format === 'string' && (MEDIA_IMAGE_FORMATS as readonly string[]).includes(params.image_format) ? params.image_format : 'jpeg'}
                onValueChange={(v) => setParam('image_format', v === 'jpeg' ? undefined : v)}
                disabled={isRunMode}
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {MEDIA_IMAGE_FORMATS.map((format) => (
                    <SelectItem key={format} value={format}>{format}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <NumberRow
              label={t('media.frameWidth')}
              hint={t('media.frameWidthHint', { min: MEDIA_DIMENSION_MIN, max: MEDIA_DIMENSION_MAX })}
              badge={t('optional')}
              value={params.width}
              onCommit={(v) => setParam('width', v)}
              clamp={clampMediaDimension}
              disabled={isRunMode}
            />
          </OptionalSection>
        </>
      )}

      {/* overlay: video + image + position + options */}
      {operation === 'overlay' && (
        <>
          {exprField('video', fileFieldValue(params.video), (v) => setParam('video', v), {
            label: t('media.video'),
            required: true,
            placeholder: t('media.videoPlaceholder'),
          })}
          {exprField('image', fileFieldValue(params.image), (v) => setParam('image', v), {
            label: t('media.image'),
            required: true,
            placeholder: t('media.imagePlaceholder'),
            hint: t('media.imageHint'),
          })}
          <OptionalSection isOpen={showOptions} onToggle={toggleOptions} count={overlayOptionsCount}>
            <div className="flex flex-col gap-1.5">
              {labelRow(t('media.position'), t('optional'))}
              <Select
                value={typeof params.position === 'string' && (MEDIA_OVERLAY_POSITIONS as readonly string[]).includes(params.position) ? params.position : 'bottom_right'}
                onValueChange={(v) => setParam('position', v === 'bottom_right' ? undefined : v)}
                disabled={isRunMode}
              >
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="top_left">{t('media.positionTopLeft')}</SelectItem>
                  <SelectItem value="top_right">{t('media.positionTopRight')}</SelectItem>
                  <SelectItem value="bottom_left">{t('media.positionBottomLeft')}</SelectItem>
                  <SelectItem value="bottom_right">{t('media.positionBottomRight')}</SelectItem>
                  <SelectItem value="center">{t('media.positionCenter')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <NumberRow
              label={t('media.marginPx')}
              hint={t('media.marginPxHint')}
              badge={t('optional')}
              value={params.margin_px}
              onCommit={(v) => setParam('margin_px', v)}
              clamp={clampMediaNonNegative}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.widthPercent')}
              hint={t('media.widthPercentHint', { min: MEDIA_WIDTH_PERCENT_MIN, max: MEDIA_WIDTH_PERCENT_MAX })}
              badge={t('optional')}
              value={params.width_percent}
              onCommit={(v) => setParam('width_percent', v)}
              clamp={(v) => clampMediaWidthPercent(v)}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.opacity')}
              hint={t('media.opacityHint', { min: MEDIA_OPACITY_MIN, max: MEDIA_OPACITY_MAX })}
              badge={t('optional')}
              value={params.opacity}
              onCommit={(v) => setParam('opacity', v)}
              clamp={(v) => clampMediaOpacity(v)}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.startSeconds')}
              badge={t('optional')}
              value={params.start_seconds}
              onCommit={(v) => setParam('start_seconds', v)}
              clamp={clampMediaNonNegative}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.endSeconds')}
              hint={t('media.overlayTimingHint')}
              badge={t('optional')}
              value={params.end_seconds}
              onCommit={(v) => setParam('end_seconds', v)}
              clamp={clampMediaNonNegative}
              disabled={isRunMode}
            />
          </OptionalSection>
        </>
      )}
    </div>
  );
}

/** Per-track advanced options (collapsible): id, volume, offset, trims, loop, fades, speed, ducking. */
function TrackAdvancedSection({
  t,
  track,
  index,
  isRunMode,
  setTrack,
  otherTrackIds,
}: {
  t: (key: string, values?: Record<string, string | number>) => string;
  track: MediaTrack;
  index: number;
  isRunMode: boolean;
  setTrack: (index: number, patch: Record<string, unknown>) => void;
  otherTrackIds: string[];
}) {
  const [open, setOpen] = React.useState(false);
  const duckUnder = typeof track.duck_under === 'string' && track.duck_under.trim() !== '' ? track.duck_under : undefined;

  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <CollapsibleTrigger className="flex items-center gap-1 text-xs font-semibold text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300">
        <ChevronRight className={`h-3 w-3 transition-transform ${open ? 'rotate-90' : ''}`} />
        <span>{t('media.trackAdvanced')}</span>
      </CollapsibleTrigger>
      <CollapsibleContent className="mt-2 space-y-3">
        <div className="flex flex-col gap-1.5">
          {labelRow(t('media.trackId'), t('optional'))}
          <Input
            type="text"
            value={typeof track.id === 'string' ? track.id : ''}
            placeholder={`track_${index + 1}`}
            onChange={(e) => setTrack(index, { id: e.target.value.trim() === '' ? undefined : e.target.value })}
            disabled={isRunMode}
            className="w-full text-sm"
          />
        </div>
        <VolumeRow
          label={t('media.volume')}
          badge={t('optional')}
          value={track.volume}
          onCommit={(v) => setTrack(index, { volume: v })}
          disabled={isRunMode}
        />
        <NumberRow
          label={t('media.offsetSeconds')}
          badge={t('optional')}
          value={track.offset_seconds}
          onCommit={(v) => setTrack(index, { offset_seconds: v })}
          clamp={clampMediaNonNegative}
          disabled={isRunMode}
        />
        <NumberRow
          label={t('media.trimStartSeconds')}
          hint={track.loop === true ? t('media.loopTrimHint') : undefined}
          badge={t('optional')}
          value={track.trim_start_seconds}
          onCommit={(v) => setTrack(index, { trim_start_seconds: v })}
          clamp={clampMediaNonNegative}
          disabled={isRunMode || track.loop === true}
        />
        <NumberRow
          label={t('media.trimEndSeconds')}
          badge={t('optional')}
          value={track.trim_end_seconds}
          onCommit={(v) => setTrack(index, { trim_end_seconds: v })}
          clamp={clampMediaNonNegative}
          disabled={isRunMode || track.loop === true}
        />
        <SwitchRow
          label={t('media.loop')}
          checked={track.loop === true}
          onChange={(checked) => setTrack(index, checked
            // loop + trim is invalid per the contract: clear the trims when looping
            ? { loop: true, trim_start_seconds: undefined, trim_end_seconds: undefined }
            : { loop: undefined })}
          disabled={isRunMode}
        />
        <NumberRow
          label={t('media.fadeInSeconds')}
          badge={t('optional')}
          value={track.fade_in_seconds}
          onCommit={(v) => setTrack(index, { fade_in_seconds: v })}
          clamp={clampMediaNonNegative}
          disabled={isRunMode}
        />
        <NumberRow
          label={t('media.fadeOutSeconds')}
          badge={t('optional')}
          value={track.fade_out_seconds}
          onCommit={(v) => setTrack(index, { fade_out_seconds: v })}
          clamp={clampMediaNonNegative}
          disabled={isRunMode}
        />
        <NumberRow
          label={t('media.speed')}
          hint={t('media.speedHint', { min: MEDIA_SPEED_MIN, max: MEDIA_SPEED_MAX, default: MEDIA_SPEED_DEFAULT })}
          badge={t('optional')}
          value={track.speed}
          onCommit={(v) => setTrack(index, { speed: v })}
          clamp={(v) => clampMediaSpeed(v)}
          disabled={isRunMode}
        />
        <div className="flex flex-col gap-1.5">
          {labelRow(t('media.duckUnder'), t('optional'))}
          <Select
            value={duckUnder ?? DUCK_NONE}
            onValueChange={(v) => {
              if (v === DUCK_NONE) {
                setTrack(index, { duck_under: undefined, duck_amount_db: undefined, duck_attack_ms: undefined, duck_release_ms: undefined });
              } else {
                setTrack(index, { duck_under: v });
              }
            }}
            disabled={isRunMode || otherTrackIds.length === 0}
          >
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={DUCK_NONE}>{t('media.duckNone')}</SelectItem>
              {otherTrackIds.map((id) => (
                <SelectItem key={id} value={id}>{id}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <span className="text-xs text-slate-400 dark:text-slate-500">{t('media.duckUnderHint')}</span>
        </div>
        {duckUnder && (
          <>
            <NumberRow
              label={t('media.duckAmountDb')}
              badge={t('optional')}
              value={track.duck_amount_db}
              onCommit={(v) => setTrack(index, { duck_amount_db: v })}
              clamp={clampMediaNonNegative}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.duckAttackMs')}
              badge={t('optional')}
              value={track.duck_attack_ms}
              onCommit={(v) => setTrack(index, { duck_attack_ms: v })}
              clamp={clampMediaNonNegative}
              disabled={isRunMode}
            />
            <NumberRow
              label={t('media.duckReleaseMs')}
              badge={t('optional')}
              value={track.duck_release_ms}
              onCommit={(v) => setTrack(index, { duck_release_ms: v })}
              clamp={clampMediaNonNegative}
              disabled={isRunMode}
            />
          </>
        )}
      </CollapsibleContent>
    </Collapsible>
  );
}
