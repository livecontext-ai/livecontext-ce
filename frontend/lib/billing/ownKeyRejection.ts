/**
 * Telling "your own key was refused" apart from "no key is configured at all".
 *
 * Both arrive as a plain error string, and they send the reader to two different pages: one
 * has a key to fix on the AI-providers tab, the other has nothing configured anywhere. The
 * only thing that separates them is the sentence the provider wrote, so the match lives in
 * one place instead of at each call site.
 *
 * Contract: `AbstractLLMProvider.ownKeyRejectedMessage()` in shared-agent-lib. The two sides
 * are strings in two languages with no compiler between them, which is what
 * `ownKeyRejection.test.ts` reads the Java source to pin.
 */
export function isOwnKeyRejection(message: string): boolean {
  const lower = message.toLowerCase();
  return lower.includes('your own') && lower.includes('api key was rejected');
}
