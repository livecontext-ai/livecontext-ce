/**
 * Reads a commission percentage typed by an admin.
 *
 * <p>Empty means "use the program default" and answers `{ ok: true, value: undefined }`.
 * Anything else must be a finite number between 0 and 100, and a decimal comma is accepted
 * ("12,5" is 12.5), because a French admin types it that way.
 *
 * <p>Why this exists: `Number("12,5")` is NaN, JSON turns NaN into `null`, and the backend
 * reads `null` as "no override": the partner was silently approved at the default rate. A
 * value this function refuses is never sent.
 */
export function parsePercent(raw: string): { ok: true; value: number | undefined } | { ok: false } {
  const text = raw.trim();
  if (text === '') return { ok: true, value: undefined };
  if (!/^\d+([.,]\d+)?$/.test(text)) return { ok: false };
  const value = Number(text.replace(',', '.'));
  if (!Number.isFinite(value) || value < 0 || value > 100) return { ok: false };
  return { ok: true, value };
}
