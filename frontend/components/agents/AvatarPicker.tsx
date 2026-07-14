'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Plus, Check, Sparkles, Palette, RotateCcw, Ban } from 'lucide-react';
import { useTranslations } from 'next-intl';
import LoadingSpinner from '@/components/LoadingSpinner';
import { cn } from '@/lib/utils';
import {
  parsePresetValue,
  buildPresetValue,
  buildRecoloredPresetDataUri,
  getAvatarGradient,
  PRESET_GRADIENTS,
  type AvatarCustomColors,
} from './avatarColors';
import { AVATAR_TOOLS, getAvatarTool } from './avatarTools';

// Predefined avatar presets using static images
export const AVATAR_PRESETS = [
  { id: 'preset:purple', image: '/avatars/avatar-1.svg', defaultName: 'Nova' },
  { id: 'preset:blue', image: '/avatars/avatar-2.svg', defaultName: 'Echo' },
  { id: 'preset:green', image: '/avatars/avatar-3.svg', defaultName: 'Atlas' },
  { id: 'preset:orange', image: '/avatars/avatar-4.svg', defaultName: 'Blitz' },
  { id: 'preset:pink', image: '/avatars/avatar-5.svg', defaultName: 'Sakura' },
  { id: 'preset:yellow', image: '/avatars/avatar-6.svg', defaultName: 'Helios' },
  { id: 'preset:teal', image: '/avatars/avatar-7.svg', defaultName: 'Helix' },
  { id: 'preset:indigo', image: '/avatars/avatar-8.svg', defaultName: 'Orion' },
  { id: 'preset:slate', image: '/avatars/avatar-9.svg', defaultName: 'Sensei' },
  { id: 'preset:red', image: '/avatars/avatar-10.svg', defaultName: 'Pulse' },
  { id: 'preset:emerald', image: '/avatars/avatar-11.svg', defaultName: 'Aurora' },
  { id: 'preset:coral', image: '/avatars/avatar-12.svg', defaultName: 'Reef' },
  { id: 'preset:gold', image: '/avatars/avatar-13.svg', defaultName: 'Midas' },
  { id: 'preset:cyan', image: '/avatars/avatar-14.svg', defaultName: 'Drift' },
  { id: 'preset:lavender', image: '/avatars/avatar-15.svg', defaultName: 'Aether' },
  { id: 'preset:burgundy', image: '/avatars/avatar-16.svg', defaultName: 'Ember' },
  { id: 'preset:fuchsia', image: '/avatars/avatar-17.svg', defaultName: 'Prism' },
  { id: 'preset:lime', image: '/avatars/avatar-18.svg', defaultName: 'Sprout' },
  { id: 'preset:sand', image: '/avatars/avatar-19.svg', defaultName: 'Zen' },
  { id: 'preset:mint', image: '/avatars/avatar-20.svg', defaultName: 'Spark' },
  { id: 'preset:olive', image: '/avatars/avatar-21.svg', defaultName: 'Hive' },
  { id: 'preset:periwinkle', image: '/avatars/avatar-22.svg', defaultName: 'Cosmo' },
  { id: 'preset:peach', image: '/avatars/avatar-23.svg', defaultName: 'Harmony' },
  { id: 'preset:navy', image: '/avatars/avatar-24.svg', defaultName: 'Abyss' },
  { id: 'preset:wine', image: '/avatars/avatar-25.svg', defaultName: 'Garnet' },
  { id: 'preset:charcoal', image: '/avatars/avatar-26.svg', defaultName: 'Neon' },
  { id: 'preset:forest', image: '/avatars/avatar-27.svg', defaultName: 'Sylva' },
  { id: 'preset:bubblegum', image: '/avatars/avatar-28.svg', defaultName: 'Fizz' },
  { id: 'preset:arctic', image: '/avatars/avatar-29.svg', defaultName: 'Frost' },
  { id: 'preset:sunshine', image: '/avatars/avatar-30.svg', defaultName: 'Sol' },
];

// Set of all preset default names for detecting if user has a custom name
const PRESET_DEFAULT_NAMES = new Set(AVATAR_PRESETS.map(p => p.defaultName));

export function getPresetDefaultName(avatarUrl?: string): string | undefined {
  const parsed = parsePresetValue(avatarUrl);
  if (!parsed) return undefined;
  return AVATAR_PRESETS.find(p => p.id === parsed.presetId)?.defaultName;
}

export function isPresetDefaultName(name: string): boolean {
  return PRESET_DEFAULT_NAMES.has(name);
}

/**
 * Resolve the preset entry behind an avatar value. Customized values
 * ('preset:x?c1=..&c2=..') resolve to their base preset.
 */
export function getAvatarPreset(avatarUrl?: string) {
  const parsed = parsePresetValue(avatarUrl);
  if (!parsed) return null;
  return AVATAR_PRESETS.find(p => p.id === parsed.presetId);
}

interface AvatarPickerProps {
  value?: string;
  onChange: (value: string) => void;
  onUpload?: (file: File) => Promise<string>;
  /** One-shot AI generation: prompt in, sanitized SVG markup out. */
  onGenerate?: (prompt: string) => Promise<string>;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

export function AvatarPicker({
  value,
  onChange,
  onUpload,
  onGenerate,
  size = 'md',
  className,
}: AvatarPickerProps) {
  const t = useTranslations('avatarPicker');
  const [isUploading, setIsUploading] = useState(false);
  const [uploadedUrl, setUploadedUrl] = useState<string | null>(
    value && !value.startsWith('preset:') ? value : null
  );
  const fileInputRef = useRef<HTMLInputElement>(null);

  // AI generation state
  const [aiOpen, setAiOpen] = useState(false);
  const [aiPrompt, setAiPrompt] = useState('');
  const [aiSvg, setAiSvg] = useState<string | null>(null);
  const [aiBusy, setAiBusy] = useState(false);
  const [aiSaving, setAiSaving] = useState(false);
  const [aiError, setAiError] = useState<string | null>(null);

  const sizeClasses = {
    sm: 'w-10 h-10',
    md: 'w-14 h-14',
    lg: 'w-20 h-20',
  };

  const parsed = parsePresetValue(value);
  const selectedPresetId = parsed?.presetId ?? null;
  const defaultColors = selectedPresetId ? PRESET_GRADIENTS[selectedPresetId] : undefined;
  const activeColors: AvatarCustomColors | null = parsed?.colors
    ?? (defaultColors ? { c1: defaultColors[0], c2: defaultColors[1] } : null);
  const hasCustomColors = !!parsed?.colors;
  const activeTool = parsed?.tool ?? null;
  const hasCustomization = hasCustomColors || !!activeTool;

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !onUpload) return;

    setIsUploading(true);
    try {
      const url = await onUpload(file);
      setUploadedUrl(url);
      onChange(url);
    } catch (error) {
      console.error('Upload failed:', error);
    } finally {
      setIsUploading(false);
    }
  };

  const handleColorChange = (which: 'c1' | 'c2', hex: string) => {
    if (!selectedPresetId || !activeColors) return;
    onChange(buildPresetValue(selectedPresetId, { ...activeColors, [which]: hex }, activeTool));
  };

  const handleToolChange = (toolId: string | null) => {
    if (!selectedPresetId) return;
    onChange(buildPresetValue(selectedPresetId, activeColors, toolId));
  };

  const handleGenerate = async () => {
    if (!onGenerate || !aiPrompt.trim() || aiBusy) return;
    setAiBusy(true);
    setAiError(null);
    try {
      const svg = await onGenerate(aiPrompt.trim());
      setAiSvg(svg);
    } catch (error: any) {
      setAiError(error?.message || t('aiError'));
    } finally {
      setAiBusy(false);
    }
  };

  const handleUseGenerated = async () => {
    if (!aiSvg || !onUpload || aiSaving) return;
    setAiSaving(true);
    setAiError(null);
    try {
      // Persist through the same path as manual uploads: the generated SVG
      // becomes a stored avatar file with a stable, shareable URL.
      const file = new File([aiSvg], 'ai-avatar.svg', { type: 'image/svg+xml' });
      const url = await onUpload(file);
      setUploadedUrl(url);
      onChange(url);
      setAiOpen(false);
      setAiSvg(null);
    } catch (error: any) {
      setAiError(error?.message || t('aiError'));
    } finally {
      setAiSaving(false);
    }
  };

  const isSelected = (presetId: string) => selectedPresetId === presetId;
  const isCustomSelected = value && !value.startsWith('preset:');
  const aiPreviewUri = aiSvg ? `data:image/svg+xml;charset=utf-8,${encodeURIComponent(aiSvg)}` : null;

  return (
    <div className={cn('space-y-3 w-[324px]', className)}>
      {/* Avatars grid (presets + upload + AI) */}
      <div className="grid grid-cols-6 gap-2 max-h-[280px] overflow-y-auto p-2">
        {AVATAR_PRESETS.map((preset) => {
          const selected = isSelected(preset.id);

          return (
            <button
              key={preset.id}
              type="button"
              onClick={() => onChange(preset.id)}
              className={cn(
                sizeClasses[size],
                'rounded-full transition-all relative overflow-visible',
                selected ? 'ring-2 ring-offset-2 ring-[var(--accent-primary)] scale-110' : 'hover:scale-105',
              )}
            >
              {selected && hasCustomColors && activeColors ? (
                <RecoloredPresetImage
                  presetId={preset.id}
                  image={preset.image}
                  colors={activeColors}
                  alt={preset.id}
                  className="w-full h-full object-cover rounded-full"
                />
              ) : (
                <img src={preset.image} alt={preset.id} className="w-full h-full object-cover rounded-full" />
              )}
              {selected && (
                <div className="absolute -bottom-1 -right-1 w-5 h-5 bg-[var(--accent-primary)] rounded-full flex items-center justify-center z-10">
                  <Check className="w-3 h-3 text-[var(--accent-foreground)]" />
                </div>
              )}
            </button>
          );
        })}

        {/* Uploaded image preview (inline in grid) */}
        {onUpload && uploadedUrl && (
          <button
            type="button"
            onClick={() => onChange(uploadedUrl)}
            className={cn(
              sizeClasses[size],
              'rounded-full overflow-hidden relative transition-all',
              isCustomSelected ? 'ring-2 ring-offset-2 ring-[var(--accent-primary)] scale-110' : 'hover:scale-105',
            )}
          >
            <img src={uploadedUrl} alt={t('customAvatarAlt')} className="w-full h-full object-cover" />
            {isCustomSelected && (
              <div className="absolute -bottom-1 -right-1 w-5 h-5 bg-[var(--accent-primary)] rounded-full flex items-center justify-center">
                <Check className="w-3 h-3 text-[var(--accent-foreground)]" />
              </div>
            )}
          </button>
        )}

        {/* Upload button (dashed circle with +) */}
        {onUpload && (
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            disabled={isUploading}
            title={t('uploadTitle')}
            className={cn(
              sizeClasses[size],
              'rounded-full border-2 border-dashed border-[var(--text-secondary)] flex items-center justify-center transition-all',
              'hover:border-[var(--accent-primary)] hover:text-[var(--accent-primary)] hover:scale-105',
              'text-[var(--text-secondary)]',
            )}
          >
            {isUploading ? (
              <LoadingSpinner size="sm" />
            ) : (
              <Plus className="w-5 h-5" />
            )}
          </button>
        )}

        {/* AI generation button (dashed circle with sparkles) */}
        {onGenerate && onUpload && (
          <button
            type="button"
            onClick={() => setAiOpen((v) => !v)}
            title={t('aiTitle')}
            className={cn(
              sizeClasses[size],
              'rounded-full border-2 border-dashed flex items-center justify-center transition-all hover:scale-105',
              aiOpen
                ? 'border-[var(--accent-primary)] text-[var(--accent-primary)]'
                : 'border-[var(--text-secondary)] text-[var(--text-secondary)] hover:border-[var(--accent-primary)] hover:text-[var(--accent-primary)]',
            )}
          >
            <Sparkles className="w-5 h-5" />
          </button>
        )}
      </div>

      {/* Display customization (colors + tool badge) - shown when a preset is selected */}
      {selectedPresetId && activeColors && !aiOpen && (
        <div className="rounded-xl border border-theme px-3 py-2.5 space-y-2">
          <div className="flex items-center justify-between">
            <span className="inline-flex items-center gap-1.5 text-xs font-medium text-theme-secondary">
              <Palette className="h-3 w-3" />
              {t('customize')}
            </span>
            {hasCustomization && (
              <button
                type="button"
                onClick={() => onChange(selectedPresetId)}
                className="inline-flex items-center gap-1 text-xs text-theme-secondary hover:text-theme-primary transition-colors"
              >
                <RotateCcw className="h-3 w-3" />
                {t('reset')}
              </button>
            )}
          </div>
          <div className="flex items-center gap-4">
            <label className="flex items-center gap-2 text-xs text-theme-secondary cursor-pointer">
              <input
                type="color"
                value={activeColors.c1}
                onChange={(e) => handleColorChange('c1', e.target.value)}
                aria-label={t('primaryColor')}
                className="h-7 w-9 cursor-pointer rounded border border-theme bg-transparent p-0.5"
              />
              {t('primaryColor')}
            </label>
            <label className="flex items-center gap-2 text-xs text-theme-secondary cursor-pointer">
              <input
                type="color"
                value={activeColors.c2}
                onChange={(e) => handleColorChange('c2', e.target.value)}
                aria-label={t('secondaryColor')}
                className="h-7 w-9 cursor-pointer rounded border border-theme bg-transparent p-0.5"
              />
              {t('secondaryColor')}
            </label>
          </div>
          {/* Tool badge picker: 'none' + the full registry, rendered as a compact scrollable grid. */}
          <div className="space-y-1.5">
            <span className="block text-xs text-theme-secondary">{t('toolLabel')}</span>
            <div className="grid grid-cols-8 gap-1 max-h-24 overflow-y-auto pr-1">
              <button
                type="button"
                onClick={() => handleToolChange(null)}
                title={t('toolNone')}
                aria-label={t('toolNone')}
                aria-pressed={!activeTool}
                className={cn(
                  'flex h-8 w-8 items-center justify-center rounded-lg border transition-all',
                  !activeTool
                    ? 'border-[var(--accent-primary)] ring-1 ring-[var(--accent-primary)] text-[var(--accent-primary)]'
                    : 'border-theme text-theme-secondary hover:border-[var(--accent-primary)] hover:text-[var(--accent-primary)]',
                )}
              >
                <Ban className="h-4 w-4" />
              </button>
              {AVATAR_TOOLS.map((tool) => {
                const selected = activeTool === tool.id;
                return (
                  <button
                    key={tool.id}
                    type="button"
                    onClick={() => handleToolChange(tool.id)}
                    title={t(`tools.${tool.id}`)}
                    aria-label={t(`tools.${tool.id}`)}
                    aria-pressed={selected}
                    className={cn(
                      'flex h-8 w-8 items-center justify-center rounded-lg border transition-all',
                      selected
                        ? 'border-[var(--accent-primary)] ring-1 ring-[var(--accent-primary)] text-[var(--accent-primary)]'
                        : 'border-theme text-theme-secondary hover:border-[var(--accent-primary)] hover:text-[var(--accent-primary)]',
                    )}
                  >
                    <tool.Icon className="h-4 w-4" />
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {/* AI generation panel */}
      {aiOpen && onGenerate && onUpload && (
        <div className="rounded-xl border border-theme px-3 py-2.5 space-y-2">
          <span className="inline-flex items-center gap-1.5 text-xs font-medium text-theme-secondary">
            <Sparkles className="h-3 w-3" />
            {t('aiTitle')}
          </span>
          <textarea
            value={aiPrompt}
            onChange={(e) => setAiPrompt(e.target.value)}
            placeholder={t('aiPromptPlaceholder')}
            rows={2}
            className="w-full rounded-lg border border-theme bg-theme-primary px-3 py-2 text-sm text-theme-primary resize-none focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]"
          />
          {aiError && <p className="text-xs text-red-500">{aiError}</p>}
          <div className="flex items-center gap-3">
            {aiPreviewUri && (
              <img
                src={aiPreviewUri}
                alt={t('aiPreviewAlt')}
                className="w-14 h-14 rounded-full object-cover border border-theme shrink-0"
              />
            )}
            <div className="flex items-center gap-2 flex-1">
              <button
                type="button"
                onClick={handleGenerate}
                disabled={aiBusy || aiSaving || !aiPrompt.trim()}
                className="inline-flex items-center justify-center gap-1.5 h-8 px-3 rounded-lg text-sm font-medium bg-theme-secondary text-theme-primary hover:opacity-90 disabled:opacity-50 transition-opacity"
              >
                {aiBusy ? <LoadingSpinner size="sm" /> : <Sparkles className="h-3.5 w-3.5" />}
                {aiSvg ? t('aiRegenerate') : t('aiGenerate')}
              </button>
              {aiSvg && (
                <button
                  type="button"
                  onClick={handleUseGenerated}
                  disabled={aiBusy || aiSaving}
                  className="inline-flex items-center justify-center gap-1.5 h-8 px-3 rounded-lg text-sm font-medium bg-[var(--accent-primary)] text-[var(--accent-foreground)] hover:opacity-90 disabled:opacity-50 transition-opacity"
                >
                  {aiSaving ? <LoadingSpinner size="sm" /> : <Check className="h-3.5 w-3.5" />}
                  {t('aiUse')}
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {onUpload && (
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          onChange={handleFileChange}
          className="hidden"
        />
      )}
    </div>
  );
}

// Helper component to display an avatar
interface AvatarDisplayProps {
  avatarUrl?: string;
  name?: string;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  className?: string;
}

export function AvatarDisplay({ avatarUrl, name, size = 'md', className }: AvatarDisplayProps) {
  const sizeClasses = {
    sm: 'w-8 h-8',
    md: 'w-10 h-10',
    lg: 'w-14 h-14',
    xl: 'w-20 h-20',
  };

  // Check if it's a preset (customized 'preset:x?c1=..&tool=..' values resolve too)
  const parsed = parsePresetValue(avatarUrl);
  const preset = getAvatarPreset(avatarUrl);

  if (preset) {
    // The circle clips its image in an INNER div so the tool badge can sit on the
    // outer edge without being cut by overflow-hidden. '@container' lets the badge
    // scale to the RENDERED size (callers override the size prop via className);
    // applied only when a tool is present so badge-less avatars keep their exact
    // pre-existing layout behavior (no size containment).
    return (
      <div className={cn(sizeClasses[size], 'relative flex-shrink-0', parsed?.tool && '@container', className)}>
        <div className="w-full h-full rounded-full overflow-hidden">
          {parsed?.colors ? (
            <RecoloredPresetImage
              presetId={preset.id}
              image={preset.image}
              colors={parsed.colors}
              alt={name || 'Avatar'}
              className="w-full h-full object-cover"
            />
          ) : (
            <img src={preset.image} alt={name || 'Avatar'} className="w-full h-full object-cover" />
          )}
        </div>
        {parsed?.tool && <AvatarToolBadge toolId={parsed.tool} avatarUrl={avatarUrl} />}
      </div>
    );
  }

  // Custom uploaded image. Anti-blink: a pulsing placeholder fills the circle until the
  // network image is painted (fade-in), so a fresh mount never flashes empty → photo.
  if (avatarUrl) {
    return <CustomUrlAvatar avatarUrl={avatarUrl} name={name} sizeClass={sizeClasses[size]} className={className} />;
  }

  // Default fallback - first preset image
  return (
    <div
      className={cn(
        sizeClasses[size],
        'rounded-full overflow-hidden flex-shrink-0',
        className
      )}
    >
      <img src={AVATAR_PRESETS[0].image} alt={name || 'Avatar'} className="w-full h-full object-cover" />
    </div>
  );
}

/**
 * Small tool badge on the bottom-right edge of the avatar circle. Background is
 * the avatar's own primary gradient stop (custom c1 when recolored, else the
 * preset default), so the badge always matches the avatar palette.
 * Container-query tiers keyed on the circle's RENDERED width (callers shrink
 * avatars via className, so the size prop alone can't be trusted): tiny avatars
 * (sidebar conversation rows, panel tab icons) get a discreet color dot that
 * never hides the face; from 32px the icon appears; from 48px the border thickens.
 * Only mounts when a tool is present, so tool-less avatars pay no i18n cost.
 */
function AvatarToolBadge({ toolId, avatarUrl }: { toolId: string; avatarUrl?: string }) {
  const t = useTranslations('avatarPicker');
  const tool = getAvatarTool(toolId);
  if (!tool) return null;
  const gradient = getAvatarGradient(avatarUrl);
  const label = t(`tools.${toolId}`);
  return (
    <span
      title={label}
      aria-label={label}
      data-testid="avatar-tool-badge"
      className="absolute -bottom-0.5 -right-0.5 z-10 flex h-[34%] w-[34%] min-h-1.5 min-w-1.5 items-center justify-center rounded-full border border-[var(--bg-primary)] @[32px]:min-h-2.5 @[32px]:min-w-2.5 @[48px]:border-2"
      style={{ backgroundColor: gradient ? gradient[0] : 'var(--accent-primary)' }}
    >
      <tool.Icon className="hidden h-[58%] w-[58%] text-white @[32px]:block" strokeWidth={2.5} />
    </span>
  );
}

/**
 * Preset SVG with its gradient stops swapped for user-picked colors. Renders the
 * stock preset while the recolored data-URI is being built (no empty flash), and
 * keeps the stock preset if the rebuild fails.
 */
function RecoloredPresetImage({
  presetId,
  image,
  colors,
  alt,
  className,
}: {
  presetId: string;
  image: string;
  colors: AvatarCustomColors;
  alt: string;
  className?: string;
}) {
  const [dataUri, setDataUri] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    buildRecoloredPresetDataUri(presetId, image, colors).then((uri) => {
      if (!cancelled) setDataUri(uri);
    });
    return () => { cancelled = true; };
  }, [presetId, image, colors.c1, colors.c2]);

  return <img src={dataUri ?? image} alt={alt} className={className} />;
}

/**
 * Network-loaded avatar with a skeleton placeholder: pulses until the image paints,
 * then fades it in; on error it falls back to the default preset (never a broken img).
 */
function CustomUrlAvatar({
  avatarUrl,
  name,
  sizeClass,
  className,
}: {
  avatarUrl: string;
  name?: string;
  sizeClass: string;
  className?: string;
}) {
  const [loaded, setLoaded] = React.useState(false);
  const [errored, setErrored] = React.useState(false);

  // A new URL restarts the load cycle (e.g. switching DM threads reuses the component).
  React.useEffect(() => {
    setLoaded(false);
    setErrored(false);
  }, [avatarUrl]);

  if (errored) {
    return (
      <div className={cn(sizeClass, 'rounded-full overflow-hidden flex-shrink-0', className)}>
        <img src={AVATAR_PRESETS[0].image} alt={name || 'Avatar'} className="w-full h-full object-cover" />
      </div>
    );
  }

  return (
    <div className={cn(sizeClass, 'rounded-full overflow-hidden flex-shrink-0 relative', className)}>
      {!loaded && <div className="absolute inset-0 animate-pulse rounded-full bg-theme-tertiary" />}
      <img
        src={avatarUrl}
        alt={name || 'Avatar'}
        onLoad={() => setLoaded(true)}
        onError={() => setErrored(true)}
        className={cn('w-full h-full object-cover transition-opacity duration-150', loaded ? 'opacity-100' : 'opacity-0')}
      />
    </div>
  );
}
