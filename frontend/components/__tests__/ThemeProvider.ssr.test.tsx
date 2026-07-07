/** @vitest-environment node */

import React from 'react';
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ThemeProvider } from '../ThemeProvider';

// Regression pin for the landing-SEO fix: ThemeProvider wraps the WHOLE app
// from the root layout. It used to `return null` before the client snapshot
// resolved, which stripped every page (landing, /compare, docs...) out of the
// server-rendered HTML - crawlers that do not execute JavaScript saw an empty
// <body>. Server rendering must always emit the children.
describe('ThemeProvider server rendering', () => {
  it('renders its children into the server HTML (never null pre-hydration)', () => {
    const html = renderToString(
      <ThemeProvider>
        <h1>seo critical content</h1>
      </ThemeProvider>,
    );
    expect(html).toContain('seo critical content');
  });
});
