// @vitest-environment jsdom
/**
 * ServiceLogo draws a brand's dark-theme file where one ships. The CSS that picks between
 * the two copies cannot run in jsdom, so this pins what the component controls (which
 * files, which classes, the caller's props untouched) and reads the stylesheet for the
 * selectors that do the picking.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { ServiceLogo } from '../service-logo';

afterEach(cleanup);

describe('ServiceLogo', () => {
  it('renders a single untouched <img> for an icon with no dark file', () => {
    const { container } = render(<ServiceLogo src="/icons/services/slack.svg" alt="" className="h-4 w-4" />);
    const imgs = container.querySelectorAll('img');
    expect(imgs).toHaveLength(1);
    expect(imgs[0]).toHaveAttribute('src', '/icons/services/slack.svg');
    expect(imgs[0].className).toBe('h-4 w-4');
  });

  it('renders the default and the dark file, each tagged for the theme that shows it', () => {
    const { container } = render(<ServiceLogo src="/icons/services/github.svg" alt="GitHub" className="h-4 w-4" />);
    const [light, dark] = Array.from(container.querySelectorAll('img'));
    expect(light).toHaveAttribute('src', '/icons/services/github.svg');
    expect(dark).toHaveAttribute('src', '/icons/services/github.dark.svg');
    expect(light).toHaveClass('h-4', 'w-4', 'svc-logo-light');
    expect(dark).toHaveClass('h-4', 'w-4', 'svc-logo-dark');
    // Both keep the caller's alt: the hidden copy is display:none, out of the a11y tree.
    expect(light).toHaveAttribute('alt', 'GitHub');
    expect(dark).toHaveAttribute('alt', 'GitHub');
  });

  it('passes every other prop to both copies, and renders through the given component', () => {
    const Probe = (props: Record<string, unknown>) => <img data-probe="yes" {...props} />;
    const { container } = render(
      <ServiceLogo as={Probe} src="/icons/services/openai.svg" alt="" width={16} height={16} loading="lazy" />,
    );
    const imgs = Array.from(container.querySelectorAll('img'));
    expect(imgs).toHaveLength(2);
    for (const img of imgs) {
      expect(img).toHaveAttribute('data-probe', 'yes');
      expect(img).toHaveAttribute('width', '16');
      expect(img).toHaveAttribute('loading', 'lazy');
    }
  });

  it('never rewrites a src that is not a shipped service icon', () => {
    const { container } = render(<ServiceLogo src="https://cdn.example.com/custom.svg" alt="" />);
    expect(container.querySelectorAll('img')).toHaveLength(1);
  });
});

describe('every call site draws service icons through ServiceLogo', () => {
  // A bare <img>/<Image> on an /icons/services/ file shows the light file on the dark theme:
  // that is how the workflow canvas node icon stayed black on the dark canvas.
  const root = process.cwd();
  const sources: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(join(root, dir), { withFileTypes: true })) {
      const rel = `${dir}/${entry.name}`;
      if (entry.isDirectory()) {
        if (entry.name !== 'node_modules' && entry.name !== '__tests__') walk(rel);
      } else if (rel.endsWith('.tsx') && !rel.includes('.test.')) {
        sources.push(rel);
      }
    }
  };
  ['app', 'components', 'lib'].forEach(walk);

  it('finds the call sites it guards (the scan is not vacuous)', () => {
    const through = sources.filter((f) => /<ServiceLogo\b/.test(readFileSync(join(root, f), 'utf8')));
    expect(through.length).toBeGreaterThanOrEqual(20);
  });

  /**
   * Whether a source draws an /icons/services/ file with a bare <img> or <Image>. Comments go
   * first (a `// <img> ...` note inside a tag would otherwise open a fake one), then each tag is
   * read to its `/>`, so a `=>` handler or a comment before `src` cannot hide it.
   */
  const drawsServiceIconRaw = (source: string): boolean => {
    const code = source.replace(/\{?\/\*[\s\S]*?\*\/\}?/g, '').replace(/^\s*\/\/.*$/gm, '');
    for (const tag of code.matchAll(/<(img|Image)\b[\s\S]*?\/>/g)) {
      if (/\bsrc=\{?\s*[`'"][^`'"]*\/icons\/services\//.test(tag[0])) return true;
    }
    return false;
  };

  it('flags the shapes that have shipped, and leaves ServiceLogo alone', () => {
    // The NodeIcon regression: a comment naming <img> and a key before the src.
    expect(drawsServiceIconRaw(`
      <Image
        // Keyed on the slug so the retry remounts the <img> and re-fires onError.
        key={slug}
        src={\`/icons/services/\${slug}.svg\`}
        onError={handleImageError}
      />`)).toBe(true);
    expect(drawsServiceIconRaw('<img onError={() => x()} src={`/icons/services/${a}.svg`} />')).toBe(true);
    expect(drawsServiceIconRaw("<img className=\"h-4\" src='/icons/services/codex.svg' alt=\"\" />")).toBe(true);
    expect(drawsServiceIconRaw(`
      <ServiceLogo
        as={Image}
        // remounts the <img> on retry
        src={\`/icons/services/\${slug}.svg\`}
      />`)).toBe(false);
    expect(drawsServiceIconRaw('<img src={avatarUrl} alt="" />')).toBe(false);
  });

  it('has no <img> or <Image> whose src is a literal /icons/services/ path', () => {
    const offenders = sources.filter((f) => drawsServiceIconRaw(readFileSync(join(root, f), 'utf8')));
    expect(offenders).toEqual([]);
  });
});

describe('the theme rules ServiceLogo relies on', () => {
  const css = readFileSync(join(process.cwd(), 'app', 'globals.css'), 'utf8');

  it('hides the default file in the dark theme, except inside a light .landing-root', () => {
    expect(css).toContain(
      '.svc-logo-light:where(.dark *):not(:where(.landing-root:not(.dark) *)) { display: none !important; }',
    );
  });

  it('hides the dark file outside the dark theme, including inside a light .landing-root', () => {
    expect(css).toContain('.svc-logo-dark:not(:where(.dark *)),');
    expect(css).toContain('.svc-logo-dark:where(.landing-root:not(.dark) *) { display: none !important; }');
  });

  it('keeps those rules the same shape as the `dark` variant they stand in for', () => {
    // If the variant changes (say, a new theme island), these two rules must follow it.
    expect(css).toContain(
      '@custom-variant dark (&:where(.dark, .dark *):not(:where(.landing-root:not(.dark) *)));',
    );
  });
});
