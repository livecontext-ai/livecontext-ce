// Bounds for the interface node's generateVideo options, mirrored from the backend clamps
// (InterfaceNodeConfig / InterfaceScreenshotServiceImpl). The inspector commits through
// these so a typed value can never leave the documented range.

export const VIDEO_DURATION_MIN = 5;
export const VIDEO_DURATION_MAX = 120;
export const VIDEO_DURATION_DEFAULT = 30;
export const VIDEO_FPS_OPTIONS = [24, 30, 60];

/**
 * Clamp the max-duration input on commit (blur). Non-numeric / empty input falls back to
 * the default. Regression helper for the unbounded max-duration inspector field: typing
 * 999 or 1 must land on 120 / 5, never reach the plan raw.
 */
export function clampVideoMaxDuration(raw: unknown): number {
  const parsed = typeof raw === 'number' ? raw : parseInt(String(raw), 10);
  if (!Number.isFinite(parsed)) return VIDEO_DURATION_DEFAULT;
  return Math.min(VIDEO_DURATION_MAX, Math.max(VIDEO_DURATION_MIN, parsed));
}
