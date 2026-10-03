/**
 * LC-027 CASA E3: the interface-rendering shell (see ../route.ts's own header for the full
 * rationale). This is the document InterfaceIframe.tsx navigates a sandboxed iframe to instead
 * of `srcDoc`, so a real navigation response (not CSP-inheriting `about:srcdoc`) can carry its
 * own permissive-for-script CSP while the app's own CSP goes strict.
 */
import { describe, it, expect } from 'vitest';
import { GET } from '../route';

describe('/interface-frame shell route', () => {
  it('serves HTML that announces readiness and never removes its message listener', async () => {
    const response = GET();
    const html = await response.text();
    expect(html).toContain("post({ type: 'interface-frame-ready' })");
    expect(html).not.toContain('removeEventListener');
  });

  it('only acts on interface-frame-html messages sourced from window.parent', async () => {
    const html = await GET().text();
    expect(html).toContain('event.source !== parentWindow');
    expect(html).toContain("data.type !== 'interface-frame-html'");
  });

  it('replaces its own document with document.open()/write()/close() (the srcDoc construction, not a new sink)', async () => {
    const html = await GET().text();
    expect(html).toContain('document.open()');
    expect(html).toContain('document.write(data.html)');
    expect(html).toContain('document.close()');
  });

  it('is served as HTML, never cached', () => {
    const response = GET();
    expect(response.headers.get('Content-Type')).toContain('text/html');
    expect(response.headers.get('Cache-Control')).toBe('private, no-store');
  });

  it('contains no reference to a script host outside itself (the shell document has nothing to fetch)', async () => {
    const html = await GET().text();
    expect(html).not.toMatch(/<script[^>]+src=/);
  });
});
