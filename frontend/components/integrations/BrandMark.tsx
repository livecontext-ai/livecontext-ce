import { ServiceLogo } from '@/components/ui/service-logo';

/**
 * One integration's brand mark. The single entry point for every brand logo on the public site.
 *
 * <p>A plain `<img>` (through {@link ServiceLogo}), not `components/ui/service-icon.tsx`. That
 * one is a client component (it keeps an error flag in state) and renders through
 * `next/image`; this one has to render inside server components on pages that ship no
 * JavaScript for it, and ~1000 marks on the directory page is exactly the case where the
 * optimizer costs more than it saves on a 1 KB SVG that is already served statically.
 *
 * <p>It is one component because the icon key is NOT the slug (`google-sheets` is drawn by
 * `googlesheets`), and because the dark theme needs the brand's dark file where one ships:
 * {@link ServiceLogo} renders `<key>.dark.svg` beside the default file and the landing's own
 * `.landing-root.dark` picks it, the same way the app's `.dark` does.
 *
 * <p>`alt` is empty on purpose. Every call site puts the integration's name in adjacent
 * text, so a real alt would make a screen reader announce the name twice, once as an image.
 */
export function BrandMark({
  iconSlug,
  src,
  size = 20,
  className = '',
}: {
  /** Key into `/icons/services/{iconSlug}.svg`. Never derive it from the slug. */
  iconSlug: string;
  /**
   * Overrides where the artwork is fetched from, for integrations that declare their own
   * `iconUrl` in the catalogue.
   */
  src?: string | null;
  size?: number;
  className?: string;
}) {
  return (
    <ServiceLogo
      src={src ?? `/icons/services/${iconSlug}.svg`}
      alt=""
      width={size}
      height={size}
      loading="lazy"
      decoding="async"
      className={className}
      style={{ width: size, height: size, objectFit: 'contain' }}
    />
  );
}

export default BrandMark;
