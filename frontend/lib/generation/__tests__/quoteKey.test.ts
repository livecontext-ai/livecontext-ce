// @vitest-environment node
import fs from 'node:fs';
import path from 'node:path';
import { describe, it, expect } from 'vitest';

import { generationQuoteKey } from '../quoteKey';

/**
 * One question, one key.
 *
 * <p>Five surfaces ask this endpoint what a generation costs, and two of them are on screen at
 * once. React Query dedupes on the key: identical means one request and ONE amount in front of the
 * reader; different by a single element means two requests and two numbers that can disagree.
 *
 * <p>The promise used to be three comments saying "same key shape as the inspector's", and it broke
 * the moment a field was added to three call sites out of four - for EVERY model, including the
 * ones with no factor at all, because `1` is still an extra element. It is a function now, and
 * these tests pin what that function guarantees.
 */
describe('generationQuoteKey', () => {
  it('asks the same question the same way whatever shape the caller holds it in', () => {
    // Absent and null are one statement to the server ("this surface cannot say"), and they must
    // not be two keys.
    const spelledOut = generationQuoteKey({
      integrationName: 'seedance',
      apiToolId: null,
      modelId: null,
      quantity: null,
      generation: false,
      quantityUnit: null,
      priceMultiplier: null,
    });
    const omitted = generationQuoteKey({ integrationName: 'seedance' });

    expect(spelledOut).toEqual(omitted);
  });

  it('reads a case-different integration as the same account', () => {
    expect(generationQuoteKey({ integrationName: 'SeeDance' }))
      .toEqual(generationQuoteKey({ integrationName: 'seedance' }));
  });

  it('treats a call at the published rate and one with no factor as one question', () => {
    // The two mean the same thing, and the surfaces that carry a factor have to share a cache
    // entry with the ones that do not.
    expect(generationQuoteKey({ integrationName: 'x', priceMultiplier: 1 }))
      .toEqual(generationQuoteKey({ integrationName: 'x' }));
  });

  it('separates two calls that differ only by their factor', () => {
    // Otherwise a reader who switches to 1080p keeps the 720p amount on screen beside a button
    // that spends the larger one.
    expect(generationQuoteKey({ integrationName: 'x', priceMultiplier: 2 }))
      .not.toEqual(generationQuoteKey({ integrationName: 'x', priceMultiplier: 1 }));
  });

  it('separates every other thing that changes the answer', () => {
    const base = {
      integrationName: 'x', apiToolId: 't', modelId: 'm', quantity: 10,
      generation: true, quantityUnit: 'second',
    } as const;
    const variants = [
      { ...base, apiToolId: 'other' },
      { ...base, modelId: 'other' },
      { ...base, quantity: 11 },
      { ...base, generation: false },
      { ...base, quantityUnit: 'image' },
    ];
    for (const variant of variants) {
      expect(generationQuoteKey(variant)).not.toEqual(generationQuoteKey(base));
    }
  });

  it('keeps a zero quantity distinct from no quantity at all', () => {
    // Zero is "the caller measured it and it was empty"; absent is "nobody could measure it". The
    // server answers them differently, so the cache must too.
    expect(generationQuoteKey({ integrationName: 'x', quantity: 0 }))
      .not.toEqual(generationQuoteKey({ integrationName: 'x' }));
  });
});

/**
 * The part a unit test cannot reach: whether the callers actually USE it.
 *
 * <p>Every assertion above holds for a function nobody calls. The bug this file exists for was not
 * a wrong key, it was a SIXTH spelling of the right one, written at a new call site by an author
 * who had no way to know the other five existed. A test of the function cannot see that; only a
 * test of the tree can.
 *
 * <p>So the literal is the invariant: it may appear in exactly one file. A call site that spells
 * the array out again fails here, on the line it was written, instead of on a screen showing two
 * prices for one generation.
 */
describe('the key has ONE spelling in the tree', () => {
  // Assembled rather than written out, so this file is not itself an occurrence and the assertion
  // does not have to carve out an exception that would also hide a real one.
  const LITERAL = ['platform', 'credential', 'public', 'info'].join('-');
  const OWNER = path.join('lib', 'generation', 'quoteKey.ts');

  function sourceFiles(dir: string, out: string[] = []): string[] {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) sourceFiles(full, out);
      else if (/\.tsx?$/.test(entry.name)) out.push(full);
    }
    return out;
  }

  // A minute, against a default of twenty seconds. This one reads the whole source tree, which
  // costs a couple of seconds alone and several times that in a full run, where it competes with
  // two hundred other files for the same cores. Timed out there, it reported a failure that said
  // nothing about the tree it was checking.
  it('is written in the module that owns it, and nowhere else', () => {
    const roots = ['app', 'components', 'hooks', 'lib'];
    const spelled = roots
      .flatMap((root) => sourceFiles(path.join(process.cwd(), root)))
      // BOTH quote styles. This looked only for `'...'`, and the repo has files written entirely
      // in double quotes (CredentialWizard among them), so a sixth call site spelling the literal
      // \"platform-credential-public-info\" passed the guard in silence: the exact fail-open shape
      // this test's own preamble condemns.
      .filter((file) => {
        const source = fs.readFileSync(file, 'utf8');
        return source.includes(`'${LITERAL}'`) || source.includes(`"${LITERAL}"`)
          || source.includes(`\`${LITERAL}\``);
      })
      .map((file) => path.relative(process.cwd(), file));

    expect(spelled).toEqual([OWNER]);
  }, 60_000);
});
