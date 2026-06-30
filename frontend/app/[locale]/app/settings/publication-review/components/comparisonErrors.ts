/**
 * Decode an approve/reject failure into a user-facing message.
 *
 * The moderation endpoints return a meaningful body on 4xx - most importantly a
 * 409 "Publication is not pending review. Current status: …" when another admin
 * already reviewed it. Surfacing that real reason (instead of an opaque "failed")
 * lets the reviewer understand why the action didn't apply. Transport / 5xx errors
 * carry no actionable detail, so those fall back to a generic translated message.
 */
export function actionErrorMessage(e: unknown, fallback: string): string {
  const err = e as { status?: number; message?: string };
  if (err && typeof err.status === "number" && err.status >= 400 && err.status < 500 && err.message) {
    return err.message;
  }
  return fallback;
}
