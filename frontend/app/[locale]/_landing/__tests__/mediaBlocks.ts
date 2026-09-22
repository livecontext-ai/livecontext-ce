/**
 * Read one `@media` block out of `landingStyles`, by brace matching.
 *
 * Test helper, not a test (vitest collects `*.test.ts` only, so this file runs nothing on
 * its own). The landing stylesheet is a template literal, so a suite that wants to assert
 * what a breakpoint does has to read the text. A lazy regex over the whole sheet cannot do
 * it safely: several breakpoints repeat (`max-width: 640px` appears five times, `767px`
 * twice), so `@media \(...\)[\s\S]*?<selector>` happily matches a selector that lives in a
 * LATER block and reports a rule as present when the breakpoint under test does not carry
 * it. Matching braces and then selecting the block that contains the selector is the only
 * form that cannot pass for the wrong reason, and refusing an ambiguous pair is what makes
 * a mutation test land where it was aimed.
 */
export function mediaBlock(source: string, query: string, containing: string): string {
  const opener = `@media ${query} {`;
  const blocks: string[] = [];
  for (let at = source.indexOf(opener); at !== -1; at = source.indexOf(opener, at + 1)) {
    const bodyStart = at + opener.length;
    let depth = 1;
    let i = bodyStart;
    while (i < source.length && depth > 0) {
      if (source[i] === '{') depth += 1;
      else if (source[i] === '}') depth -= 1;
      i += 1;
    }
    if (depth !== 0) throw new Error(`unbalanced braces after "${opener}"`);
    blocks.push(source.slice(bodyStart, i - 1));
  }
  if (blocks.length === 0) throw new Error(`no "${opener}" block in the stylesheet`);
  const matching = blocks.filter((block) => block.includes(containing));
  if (matching.length !== 1) {
    throw new Error(`expected exactly one "${opener}" block containing "${containing}", found ${matching.length}`);
  }
  return matching[0];
}

/** The body of a single top-level rule, e.g. `.landing-root .build-card`. */
export function rule(source: string, selector: string): string {
  const at = source.indexOf(`${selector} {`);
  if (at === -1) throw new Error(`no rule for "${selector}"`);
  const bodyStart = source.indexOf('{', at) + 1;
  const end = source.indexOf('}', bodyStart);
  return source.slice(bodyStart, end);
}
