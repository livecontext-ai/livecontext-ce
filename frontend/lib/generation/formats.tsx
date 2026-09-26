'use client';

import * as React from 'react';
import { Image as ImageIcon, Music, Film, Mic, AudioWaveform, Sparkles } from 'lucide-react';
import { ServiceLogo } from '@/components/ui/service-logo';

/**
 * The formats a generation can produce, and how each is drawn.
 *
 * <p>Shared by the dialog that starts a generation and the history that lists what was generated:
 * one list, one icon per format, so a format cannot read as a film reel on one screen and a sparkle
 * on the other. The platform decides which formats actually exist (a model's kind is what admits it
 * to one), so this is the presentation of a format - never the list of what is available.
 */

export const FORMAT_ICONS: Record<string, React.ComponentType<{ className?: string }>> = {
  image: ImageIcon,
  video: Film,
  audio: AudioWaveform,
  voice: Mic,
  music: Music,
};

/** The order a catalogue is usually browsed in. A format outside it sorts after, alphabetically. */
export const FORMAT_ORDER = ['image', 'video', 'audio', 'voice', 'music'];

/** The icon a format reads as, falling back to the app's generic "AI" sparkle for a new one. */
export function formatIcon(kind: string | undefined | null) {
  return (kind && FORMAT_ICONS[kind]) || Sparkles;
}

/**
 * The format's icon, as an element.
 *
 * <p>Built with {@code createElement} rather than by assigning {@link formatIcon} to a capitalised
 * variable and rendering that: the second form reads to the linter (rightly) as a component defined
 * during render, which is a real hazard when the value can change - React remounts the subtree
 * instead of updating it. Here the caller only ever wants a glyph, so this hands one back.
 */
export function FormatGlyph({ kind, className }: { kind?: string | null; className?: string }) {
  return React.createElement(formatIcon(kind), { className });
}

/**
 * The provider's own mark, from the same place every other surface takes it: the icon slug the API
 * catalogue ships beside the integration.
 *
 * <p>Falls back to nothing rather than to a generic glyph. A row that reads "OpenAI" with a
 * placeholder box beside it is noisier than the name alone, and a missing file is the one case
 * where the name is already doing the work.
 */
export function ProviderIcon({ slug, className }: { slug?: string | null; className?: string }) {
  const [failed, setFailed] = React.useState(false);
  if (!slug || failed) return null;
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <ServiceLogo
      src={`/icons/services/${slug}.svg`}
      alt=""
      aria-hidden="true"
      className={className ?? 'h-4 w-4 flex-shrink-0 rounded-sm'}
      onError={() => setFailed(true)}
    />
  );
}
