// @vitest-environment node
import fs from 'node:fs';
import path from 'node:path';
import { describe, it, expect } from 'vitest';

import { generationQuoteKey } from '../quoteKey';

/**
 * Every surface that quotes a GENERATION asks the identical question.
 *
 * <p><b>The bug, and why the function alone does not close it.</b> Four surfaces quote the same
 * endpoint (the studio composer, the payer pane inside its model picker, the workflow inspector's
 * Generate form, the chat's generation dialog) and two of them can be on screen at once. React
 * Query dedupes on the key: identical means one request and ONE amount in front of the reader;
 * different by a single element means two requests and two numbers that can disagree. The original
 * failure was exactly that - a field added to three call sites out of four - and it broke the cache
 * for EVERY model, including the ones with no factor at all, because `1` is still an extra element.
 *
 * <p>`quoteKey.test.ts` pins what the function guarantees and that its literal has one spelling.
 * Neither can see a caller that passes six fields where its neighbours pass seven: the function is
 * perfectly self-consistent on both. So this reads the call sites themselves.
 *
 * <p>A comment in CredentialSection used to claim "`PriceQuoteKeyParityTest` pins the shape". No
 * such test existed anywhere in the repo. This is that test, under the name the code now points at.
 *
 * <p><b>What it can and cannot see, stated because the difference has already cost something.</b>
 * It reads the object literal at each call site, so it sees the SHAPE of the question: a field
 * added to three surfaces out of four, a field renamed, a fifth surface appearing. It does NOT see
 * the VALUES: all four passed `quantity` while the studio rounded a character count up to the next
 * 50 and the other three sent the exact one, so one call was two cache entries and two amounts on
 * screen, and this file was green throughout. That divergence is now closed at the source (the
 * studio asks exactly, `useGenerationQuote`), and the regression guard for it lives where the
 * value is computed - `useGenerationQuote.test.tsx`, "quotes the EXACT length" - because that is
 * the only place it is visible.
 */
describe('generationQuoteKey call sites', () => {
  const ROOT = process.cwd();

  /** Every surface that quotes a generation, and must therefore ask the same question. */
  const GENERATION_CALL_SITES = [
    'hooks/useGenerationQuote.ts',
    'app/workflows/builder/components/inspector/CredentialSection.tsx',
    'app/workflows/builder/components/inspector/forms/GenerateParametersForm.tsx',
    'components/chat/CreateGenerationModal.tsx',
  ];

  /**
   * The credential wizard quotes an INTEGRATION, not a generation: no model, no size, nothing to
   * apply a factor to. It shares the key function so the two cannot drift apart on the parts they
   * do share, and the function defaults the rest. Named here so the exception is a decision on
   * record rather than a file this test forgot.
   */
  const INTEGRATION_ONLY_CALL_SITE = 'components/credentials/CredentialWizard.tsx';

  /** The property names passed in the object literal at a `generationQuoteKey({ ... })` call. */
  function fieldsPassedIn(file: string): string[] {
    const source = fs.readFileSync(path.join(ROOT, file), 'utf8');
    const start = source.indexOf('generationQuoteKey({');
    expect(start, `${file} must call generationQuoteKey with an object literal`).toBeGreaterThan(-1);
    const open = source.indexOf('{', start);
    let depth = 0;
    let end = open;
    for (let i = open; i < source.length; i += 1) {
      if (source[i] === '{') depth += 1;
      if (source[i] === '}') {
        depth -= 1;
        if (depth === 0) { end = i; break; }
      }
    }
    const literal = source.slice(open + 1, end);
    // `name:` and the shorthand `name,` / `name }`, at one nesting level.
    return [...literal.matchAll(/(?:^|[,{])\s*([A-Za-z_$][\w$]*)\s*(?=[,:}]|$)/g)]
      .map((m) => m[1])
      .filter((name, index, all) => all.indexOf(name) === index)
      .sort();
  }

  it('asks for the SAME fields at every generation surface', () => {
    const byFile = GENERATION_CALL_SITES.map((file) => [file, fieldsPassedIn(file)] as const);
    const reference = byFile[0][1];

    // Non-empty first: a parse that found nothing would make every comparison below trivially true.
    expect(reference.length).toBeGreaterThan(3);
    for (const [file, fields] of byFile) {
      expect(fields, `${file} asks a different question from ${byFile[0][0]}`).toEqual(reference);
    }
  });

  it('sends one field per element the key is built from, so none is silently defaulted', () => {
    // The mirror of the test above, which four call sites agreeing on six fields out of seven
    // would satisfy. Counted against the key the function actually returns: every element after
    // the leading literal is a question a surface has to answer, and a surface that answers fewer
    // is served a DIFFERENT cache entry while looking, in review, like it agrees with the others.
    const elements = generationQuoteKey({ integrationName: 'x' }).length;

    expect(fieldsPassedIn(GENERATION_CALL_SITES[0]))
      .toHaveLength(elements - 1);
  });

  it('leaves the integration-only caller free of the generation fields', () => {
    // It quotes a whole integration: a model id or a size there would ask about a call that does
    // not exist. Pinned so the parity rule above is never "fixed" by making it lie.
    const fields = fieldsPassedIn(INTEGRATION_ONLY_CALL_SITE);

    expect(fields).toEqual(['integrationName']);
  });

  it('has no generation surface this test does not know about', () => {
    // The failure mode the other three cannot see: a FIFTH surface, added later, quoting with its
    // own field set. The list above is only a guard while it is complete.
    const roots = ['app', 'components', 'hooks', 'lib'];
    const callers: string[] = [];
    const walk = (dir: string) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) { walk(full); continue; }
        if (!/\.tsx?$/.test(entry.name)) continue;
        if (full.includes('__tests__')) continue;
        if (fs.readFileSync(full, 'utf8').includes('generationQuoteKey(')) {
          callers.push(path.relative(ROOT, full).split(path.sep).join('/'));
        }
      }
    };
    roots.forEach((root) => walk(path.join(ROOT, root)));

    expect(callers.sort()).toEqual(
      [...GENERATION_CALL_SITES, INTEGRATION_ONLY_CALL_SITE, 'lib/generation/quoteKey.ts'].sort(),
    );
  }, 60_000);
});
