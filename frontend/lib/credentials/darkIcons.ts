import { DARK_ICON_SLUGS } from './darkIconSlugs.generated';

const SERVICE_ICON = /^\/icons\/services\/([^/?#]+)\.svg$/;

/**
 * The file to draw on the dark theme for a brand icon, or null when its default file
 * already reads on both themes.
 *
 * <p>Takes the SRC, not a slug, because that is what every call site holds: the icon key
 * is not the integration slug (`google-sheets` is drawn by `googlesheets.svg`) and each
 * caller has already resolved it. A src that is not a shipped `/icons/services/<key>.svg`
 * (an uploaded custom-API icon, a data URL) has no dark variant by construction.
 */
export function darkIconSrc(src: string | null | undefined): string | null {
  const key = src?.match(SERVICE_ICON)?.[1];
  return key && DARK_ICON_SLUGS.has(key) ? `/icons/services/${key}.dark.svg` : null;
}
