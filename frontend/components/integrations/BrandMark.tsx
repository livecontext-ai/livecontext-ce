import { isMonoDarkIconSlug } from '@/lib/credentials/monoIconSlugs';

/**
 * One integration's brand mark. The single `<img>` for every brand logo on the public site.
 *
 * <p>A plain `<img>`, not `components/ui/service-icon.tsx`. That one is a client component
 * (it keeps an error flag in state) and renders through `next/image`; this one has to render
 * inside server components on pages that ship no JavaScript for it, and ~1000 marks on the
 * directory page is exactly the case where the optimizer costs more than it saves on a 1 KB
 * SVG that is already served statically.
 *
 * <p>It is one component because the nine lines it holds carry two traps that must be
 * answered identically everywhere a mark is drawn. The icon key is NOT the slug
 * (`google-sheets` is drawn by `googlesheets`), and near-black artwork needs `logo-mono` or
 * it disappears into the dark theme. "Near-black" includes artwork that declares no `fill`
 * at all, since SVG then defaults to black: Zendesk shipped invisible for exactly that
 * reason, at a moment when this markup was duplicated across two files and there was no
 * single place to fix it.
 *
 * <p>`logo-mono` is the landing chrome's class (defined in `landingChromeStyles`, so it
 * exists on every page that uses `LandingShell`): it flips a near-black brand mark to white
 * when the public theme is dark, where it would otherwise disappear into the background.
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
  /**
   * Key into `/icons/services/{iconSlug}.svg`, and the input to the mono-dark decision.
   * Never derive it from the slug.
   */
  iconSlug: string;
  /**
   * Overrides where the artwork is fetched from, for integrations that declare their own
   * `iconUrl` in the catalogue. `iconSlug` is still required, because it stays the key the
   * mono-dark decision is made on.
   */
  src?: string | null;
  size?: number;
  className?: string;
}) {
  return (
    <img
      src={src ?? `/icons/services/${iconSlug}.svg`}
      alt=""
      width={size}
      height={size}
      loading="lazy"
      decoding="async"
      className={`${isMonoDarkIconSlug(iconSlug) ? 'logo-mono' : ''} ${className}`.trim()}
      style={{ width: size, height: size, objectFit: 'contain' }}
    />
  );
}

export default BrandMark;
