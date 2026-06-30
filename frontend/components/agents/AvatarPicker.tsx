'use client';

import React, { useState, useRef } from 'react';
import { Plus, Check } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { cn } from '@/lib/utils';

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
  if (!avatarUrl || !avatarUrl.startsWith('preset:')) return undefined;
  return AVATAR_PRESETS.find(p => p.id === avatarUrl)?.defaultName;
}

export function isPresetDefaultName(name: string): boolean {
  return PRESET_DEFAULT_NAMES.has(name);
}

export function getAvatarPreset(avatarUrl?: string) {
  if (!avatarUrl || !avatarUrl.startsWith('preset:')) return null;
  return AVATAR_PRESETS.find(p => p.id === avatarUrl);
}

interface AvatarPickerProps {
  value?: string;
  onChange: (value: string) => void;
  onUpload?: (file: File) => Promise<string>;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

export function AvatarPicker({
  value,
  onChange,
  onUpload,
  size = 'md',
  className,
}: AvatarPickerProps) {
  const [isUploading, setIsUploading] = useState(false);
  const [uploadedUrl, setUploadedUrl] = useState<string | null>(
    value && !value.startsWith('preset:') ? value : null
  );
  const fileInputRef = useRef<HTMLInputElement>(null);

  const sizeClasses = {
    sm: 'w-10 h-10',
    md: 'w-14 h-14',
    lg: 'w-20 h-20',
  };

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

  const isSelected = (presetId: string) => value === presetId;
  const isCustomSelected = value && !value.startsWith('preset:');

  return (
    <div className={cn('space-y-3', className)}>
      {/* Avatars grid (presets + upload) */}
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
              <img src={preset.image} alt={preset.id} className="w-full h-full object-cover rounded-full" />
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
            <img src={uploadedUrl} alt="Custom avatar" className="w-full h-full object-cover" />
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
      </div>

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

  // Check if it's a preset
  const preset = getAvatarPreset(avatarUrl);

  if (preset) {
    return (
      <div
        className={cn(
          sizeClasses[size],
          'rounded-full overflow-hidden flex-shrink-0',
          className
        )}
      >
        <img src={preset.image} alt={name || 'Avatar'} className="w-full h-full object-cover" />
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
