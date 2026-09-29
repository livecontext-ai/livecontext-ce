import { describe, expect, it } from 'vitest';
import { stripEditorHighlightSpans } from '@/lib/utils/editorHighlightSpans';

describe('stripEditorHighlightSpans', () => {
  it("keeps a template's own closing </span> tags (prod regression: every </span> was deleted on save)", () => {
    // Shape of the real interface that broke: once its closing tags were gone, #pager nested inside
    // #toolinfo and the script's toolinfo.textContent assignment deleted it.
    const html =
      '<div class="tools"><span class="tl">Inbox</span>' +
      '<span class="tl2" id="toolinfo"></span><div class="grow"></div>' +
      '<span class="tl2" id="pager"></span></div>';

    expect(stripEditorHighlightSpans(html)).toBe(html);
  });

  it('removes a highlight wrapper together with its own closing tag and keeps the expression', () => {
    const saved = '<p>Hello <span class="token-expression">{{name|there}}</span>!</p>';

    expect(stripEditorHighlightSpans(saved)).toBe('<p>Hello {{name|there}}!</p>');
  });

  it("removes only the wrappers when highlight spans and the template's own spans are mixed", () => {
    const saved =
      '<span class="badge"><span class="token-expression">{{count|0}}</span> items</span>';

    expect(stripEditorHighlightSpans(saved)).toBe('<span class="badge">{{count|0}} items</span>');
  });

  it("leaves a template's own token-like classes untouched (only token-expression is the editor's)", () => {
    const html = '<span class="tokenomics">supply</span><span class="token-price">$5</span>';

    expect(stripEditorHighlightSpans(html)).toBe(html);
  });

  it('returns an empty string for empty input and the same string when there is no wrapper', () => {
    expect(stripEditorHighlightSpans('')).toBe('');
    expect(stripEditorHighlightSpans('<b>plain</b>')).toBe('<b>plain</b>');
  });
});
