import { describe, it, expect } from 'vitest';
import { renderMarkdownPreview, safePreviewHref } from '../renderMarkdownPreview';

describe('renderMarkdownPreview - XSS safety', () => {
  it('escapes raw HTML tags so an onerror handler cannot execute', () => {
    const out = renderMarkdownPreview('<img src=x onerror=alert(document.cookie)>');
    // Regression: pre-fix the raw tag passed straight into dangerouslySetInnerHTML.
    // The tag must be inert (escaped as text), i.e. NO real `<img` element in the output.
    expect(out).not.toContain('<img');
    expect(out).toContain('&lt;img');
    expect(out).toContain('&gt;');
  });

  it('escapes a <script> tag', () => {
    const out = renderMarkdownPreview('<script>alert(1)</script>');
    expect(out).not.toContain('<script>');
    expect(out).toContain('&lt;script&gt;');
  });

  it('neutralizes a javascript: link href', () => {
    const out = renderMarkdownPreview('[click](javascript:alert(1))');
    expect(out).toContain('href="#"');
    expect(out).not.toContain('href="javascript:');
  });

  it('keeps a legitimate https link', () => {
    const out = renderMarkdownPreview('[site](https://example.com)');
    expect(out).toContain('href="https://example.com"');
  });

  it('still renders bold/italic/blockquote markdown', () => {
    expect(renderMarkdownPreview('**b**')).toContain('<strong>b</strong>');
    expect(renderMarkdownPreview('*i*')).toContain('<em>i</em>');
    expect(renderMarkdownPreview('> quote')).toContain('<blockquote class="quote">quote</blockquote>');
  });

  it('safePreviewHref allows only safe schemes', () => {
    expect(safePreviewHref('https://x.com')).toBe('https://x.com');
    expect(safePreviewHref('mailto:a@b.c')).toBe('mailto:a@b.c');
    expect(safePreviewHref('/rel')).toBe('/rel');
    expect(safePreviewHref('javascript:alert(1)')).toBe('#');
    expect(safePreviewHref('data:text/html,x')).toBe('#');
    expect(safePreviewHref('vbscript:x')).toBe('#');
  });
});
