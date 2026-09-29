import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The public /about page had no <h1>. The shared block renders its first heading
 * as an <h2> unless told otherwise, so the page itself must ask for the <h1>:
 * the component test covers the prop, this pins the page passing it.
 *
 * Source-level because the page mounts LandingShell, which needs the whole
 * public chrome to render.
 */
const pageSrc = readFileSync(path.resolve(__dirname, '../page.tsx'), 'utf8');

describe('/about page heading', () => {
  it('asks the shared content block for its <h1>', () => {
    expect(pageSrc).toMatch(/<AboutInformationContent\s+titleAs="h1"\s*\/>/);
  });
});
