/** @vitest-environment node */

import React from 'react';
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import LogoAnimate from '../LogoAnimate';
import LoadingSpinner from '../LoadingSpinner';

// Regression pin: <style jsx> (styled-jsx) has no SSR registry in the App
// Router, so it emitted `jsx-undefined` classes in the server HTML and a real
// hash on the client - a React #418 hydration mismatch on every page that
// rendered the logo (all marketing chrome) or the auth spinner. These
// components must ship plain <style> tags: without the styled-jsx transform,
// a leftover `jsx` prop renders as a literal attribute and fails this test.
describe('marketing chrome components server rendering', () => {
  it('LogoAnimate renders without any styled-jsx artifact', () => {
    const html = renderToString(<LogoAnimate />);
    expect(html).toContain('<svg');
    expect(html).not.toMatch(/<style[^>]*\sjsx/);
    expect(html).not.toContain('jsx-undefined');
  });

  it('LoadingSpinner renders without any styled-jsx artifact', () => {
    const html = renderToString(<LoadingSpinner />);
    expect(html).toContain('<svg');
    expect(html).not.toMatch(/<style[^>]*\sjsx/);
    expect(html).not.toContain('jsx-undefined');
  });
});
