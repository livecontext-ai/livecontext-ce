import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { isOwnKeyRejection } from '../ownKeyRejection';

/**
 * The backend writes the sentence, this file decides what it means, and nothing between them
 * is type-checked. A reword on the Java side would silently downgrade every own-key rejection
 * to the generic "no key configured" modal: same screen, wrong page, wrong instruction, and no
 * error anywhere. So the test reads the actual Java source rather than a copy of it.
 */

const JAVA_SOURCE = join(
  __dirname,
  '../../../../backend/shared-agent-lib/src/main/java/com/apimarketplace/agent/provider/AbstractLLMProvider.java',
);

/**
 * Rebuild `ownKeyRejectedMessage()` for a given provider by reading its `return`, which is a
 * concatenation of plain string literals and `getProviderName()` calls and nothing else.
 */
function backendSentence(providerName: string): string {
  const source = readFileSync(JAVA_SOURCE, 'utf8');
  const method = source.split('String ownKeyRejectedMessage() {')[1];
  expect(method, 'ownKeyRejectedMessage() not found in AbstractLLMProvider.java').toBeDefined();

  const body = method.slice(method.indexOf('return '), method.indexOf(';'));
  let out = '';
  for (const m of body.matchAll(/"([^"]*)"|getProviderName/g)) {
    out += m[1] !== undefined ? m[1] : providerName;
  }
  // A parse that quietly produced nothing would make every assertion below vacuous.
  expect(out.length, `failed to rebuild the sentence from: ${body}`).toBeGreaterThan(40);
  return out;
}

describe('isOwnKeyRejection', () => {
  it.each(['deepseek', 'openai', 'anthropic', 'mistral', 'gemini'])(
    'matches the sentence AbstractLLMProvider actually emits for %s',
    (provider) => {
      expect(isOwnKeyRejection(backendSentence(provider))).toBe(true);
    },
  );

  it('keeps the two moves the modal repeats, so the pages cannot disagree', () => {
    const sentence = backendSentence('deepseek');
    expect(sentence).toContain('revoked, expired or run out of quota');
    expect(sentence).toContain('switch the provider back to the platform key');
  });

  it.each([
    // The platform route: the same 401, an operator problem, and nobody to blame.
    'HTTP 401: {"error":{"message":"Incorrect API key provided"}}',
    // Nothing configured at all, which is the OTHER modal and the other page.
    'No API key configured for openai',
    'Invalid API key',
    // A provider whose own wording happens to come close but is not this sentence.
    'Your API key was rejected',
  ])('does not claim this is the caller own key: %s', (message) => {
    expect(isOwnKeyRejection(message)).toBe(false);
  });
});
