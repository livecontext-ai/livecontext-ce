import { describe, it, expect } from 'vitest';
import {
  removeScriptTags,
  sanitizeHtml,
  isCompleteHtml,
  injectBefore,
  ensureCompleteHtml,
  escapeHtml,
  extractDisplayLabel,
  extractShortLabel,
  findResolvedValue,
  getDefaultForType,
  mergeTriggerDataIntoResolved,
  translateWithMapping,
  renderForEditMode,
  renderForRunMode,
  generateBridgeScript,
  renderInterfaceTemplate,
  classifyAnchorNavigation,
  resolveNavigableUrl,
  shouldGateWindowOpen,
} from '../interfaceHtmlUtils';

// =============================================================================
// removeScriptTags
// =============================================================================
describe('removeScriptTags', () => {
  it('should return empty string for empty input', () => {
    expect(removeScriptTags('')).toBe('');
  });

  it('should return empty string for null/undefined input', () => {
    expect(removeScriptTags(null as unknown as string)).toBe('');
    expect(removeScriptTags(undefined as unknown as string)).toBe('');
  });

  it('should remove a simple script tag', () => {
    const input = '<div>Hello</div><script>alert("xss")</script><p>World</p>';
    expect(removeScriptTags(input)).toBe('<div>Hello</div><p>World</p>');
  });

  it('should remove script tag with attributes', () => {
    const input = '<script type="text/javascript" src="evil.js"></script>';
    expect(removeScriptTags(input)).toBe('');
  });

  it('should remove script tag with content spanning multiple lines', () => {
    const input = `<script>
      var x = 1;
      alert(x);
    </script>`;
    expect(removeScriptTags(input)).toBe('');
  });

  it('should remove multiple script tags', () => {
    const input = '<script>a()</script><div>ok</div><script>b()</script>';
    expect(removeScriptTags(input)).toBe('<div>ok</div>');
  });

  it('should handle case-insensitive script tags', () => {
    const input = '<SCRIPT>alert("xss")</SCRIPT>';
    expect(removeScriptTags(input)).toBe('');
  });

  it('should handle mixed case script tags', () => {
    const input = '<Script>alert("xss")</Script>';
    expect(removeScriptTags(input)).toBe('');
  });

  it('should preserve non-script content', () => {
    const input = '<div><p>Safe content</p></div>';
    expect(removeScriptTags(input)).toBe('<div><p>Safe content</p></div>');
  });

  it('should remove script tag with src attribute and no content', () => {
    const input = '<script src="https://evil.com/steal.js"></script>';
    expect(removeScriptTags(input)).toBe('');
  });

  it('should remove script tag with data attributes', () => {
    const input = '<script data-id="123" async defer></script>';
    expect(removeScriptTags(input)).toBe('');
  });

  it('should handle HTML with no script tags', () => {
    const input = '<div>Hello <strong>World</strong></div>';
    expect(removeScriptTags(input)).toBe(input);
  });
});

// =============================================================================
// sanitizeHtml
// =============================================================================
describe('sanitizeHtml', () => {
  it('should return empty string for empty input', () => {
    expect(sanitizeHtml('')).toBe('');
  });

  it('should return empty string for null/undefined', () => {
    expect(sanitizeHtml(null as unknown as string)).toBe('');
    expect(sanitizeHtml(undefined as unknown as string)).toBe('');
  });

  it('should remove script tags', () => {
    const input = '<script>alert("xss")</script><div>safe</div>';
    expect(sanitizeHtml(input)).toBe('<div>safe</div>');
  });

  it('should remove onclick handler', () => {
    const input = '<button onclick="stealData()">Click</button>';
    const result = sanitizeHtml(input);
    expect(result).not.toContain('onclick');
    expect(result).toContain('<button');
    expect(result).toContain('>Click</button>');
  });

  it('should remove onerror handler', () => {
    const input = '<img src="x" onerror="alert(1)">';
    const result = sanitizeHtml(input);
    expect(result).not.toContain('onerror');
    expect(result).toContain('<img');
  });

  it('should remove onload handler', () => {
    const input = '<body onload="malicious()">';
    const result = sanitizeHtml(input);
    expect(result).not.toContain('onload');
    expect(result).toContain('<body');
  });

  it('should remove onmouseover handler', () => {
    const input = '<div onmouseover="alert(document.cookie)">Hover me</div>';
    const result = sanitizeHtml(input);
    expect(result).not.toContain('onmouseover');
    expect(result).toContain('>Hover me</div>');
  });

  it('should remove onfocus handler', () => {
    const input = '<input onfocus="alert(1)">';
    const result = sanitizeHtml(input);
    expect(result).not.toContain('onfocus');
  });

  it('should remove multiple event handlers on one element', () => {
    const input = '<div onclick="a()" onmouseover="b()" onmouseout="c()">text</div>';
    const result = sanitizeHtml(input);
    expect(result).not.toContain('onclick');
    expect(result).not.toContain('onmouseover');
    expect(result).not.toContain('onmouseout');
    expect(result).toContain('>text</div>');
  });

  it('should handle double-quoted event handler values', () => {
    const input = '<div onclick="alert(\'xss\')">test</div>';
    const result = sanitizeHtml(input);
    expect(result).not.toContain('onclick');
  });

  it('should handle single-quoted event handler values', () => {
    const input = "<div onclick='alert(1)'>test</div>";
    const result = sanitizeHtml(input);
    expect(result).not.toContain('onclick');
  });

  it('should handle unquoted event handler values', () => {
    const input = '<div onclick=alert(1)>test</div>';
    const result = sanitizeHtml(input);
    expect(result).not.toContain('onclick');
  });

  it('should preserve legitimate attributes', () => {
    const input = '<a href="https://example.com" class="link" id="my-link">Link</a>';
    const result = sanitizeHtml(input);
    expect(result).toContain('href="https://example.com"');
    expect(result).toContain('class="link"');
    expect(result).toContain('id="my-link"');
  });

  it('should remove both scripts and event handlers', () => {
    const input = '<script>evil()</script><img onerror="bad()" src="ok.png">';
    const result = sanitizeHtml(input);
    expect(result).not.toContain('<script');
    expect(result).not.toContain('onerror');
    expect(result).toContain('src="ok.png"');
  });
});

// =============================================================================
// isCompleteHtml
// =============================================================================
describe('isCompleteHtml', () => {
  it('should return false for empty string', () => {
    expect(isCompleteHtml('')).toBe(false);
  });

  it('should return false for null/undefined', () => {
    expect(isCompleteHtml(null as unknown as string)).toBe(false);
    expect(isCompleteHtml(undefined as unknown as string)).toBe(false);
  });

  it('should return true for HTML starting with <!DOCTYPE', () => {
    expect(isCompleteHtml('<!DOCTYPE html><html><body></body></html>')).toBe(true);
  });

  it('should return true for HTML starting with <!doctype (lowercase)', () => {
    expect(isCompleteHtml('<!doctype html><html><body></body></html>')).toBe(true);
  });

  it('should return true for HTML starting with <html', () => {
    expect(isCompleteHtml('<html><head></head><body></body></html>')).toBe(true);
  });

  it('should return true for HTML starting with <HTML (uppercase)', () => {
    expect(isCompleteHtml('<HTML><HEAD></HEAD><BODY></BODY></HTML>')).toBe(true);
  });

  it('should return true with leading whitespace', () => {
    expect(isCompleteHtml('  <!doctype html><html></html>')).toBe(true);
  });

  it('should return false for HTML fragment', () => {
    expect(isCompleteHtml('<div>Hello World</div>')).toBe(false);
  });

  it('should return false for plain text', () => {
    expect(isCompleteHtml('Hello World')).toBe(false);
  });

  it('should return false for partial match like <htm', () => {
    expect(isCompleteHtml('<htm>test</htm>')).toBe(false);
  });
});

// =============================================================================
// injectBefore - safe alternative to String.replace(string, string)
// =============================================================================
describe('injectBefore', () => {
  it('inserts content before the first occurrence of the marker', () => {
    expect(injectBefore('hello world', 'world', 'BIG ')).toBe('hello BIG world');
  });

  it('returns haystack unchanged when marker is absent', () => {
    expect(injectBefore('hello', 'XXX', 'BIG ')).toBe('hello');
  });

  it('only replaces the first occurrence (semantic match with .replace(string,…))', () => {
    expect(injectBefore('a-X-b-X-c', 'X', '#')).toBe('a-#X-b-X-c');
  });

  // Regression: $'/$&/$`/$1/$$ in `content` MUST land verbatim. String.prototype
  // .replace(marker, content) would interpret these as special replacement
  // patterns; injectBefore must not. Real-world hit: user JS template
  // `var m = { USD: '$' }` - the `$'` expanded to the post-marker substring
  // (`</html>…`), corrupting the injected script source.
  it("preserves $' (post-match) literally - does not splice the trailing text", () => {
    const out = injectBefore('AAA</body>ZZZ', '</body>', "var x = '$';\n");
    expect(out).toBe("AAAvar x = '$';\n</body>ZZZ");
  });

  it('preserves $& (whole-match), $` (pre-match), $1-$9 literally', () => {
    const out = injectBefore(
      'pre</body>post',
      '</body>',
      "{ a: '$&', b: '$`', c: '$1', d: '$2' }\n"
    );
    expect(out).toBe("pre{ a: '$&', b: '$`', c: '$1', d: '$2' }\n</body>post");
  });

  it('preserves $$ literally (no collapse to single $)', () => {
    const out = injectBefore('a</body>b', '</body>', 'price = $$10\n');
    expect(out).toBe('aprice = $$10\n</body>b');
  });

  // Empty-marker contract: native indexOf('') === 0 would silently prepend,
  // which is almost always a caller bug (lost marker through refactor). No-op
  // is safer - pins explicit contract from the helper docstring.
  it('returns haystack unchanged when marker is empty (no silent prepend)', () => {
    expect(injectBefore('hello', '', 'PREFIX')).toBe('hello');
  });
});

// =============================================================================
// ensureCompleteHtml
// =============================================================================
describe('ensureCompleteHtml', () => {
  it('should return empty string for empty input', () => {
    expect(ensureCompleteHtml('')).toBe('');
  });

  it('should wrap a fragment in a complete HTML document', () => {
    const result = ensureCompleteHtml('<div>Hello</div>');
    expect(result).toContain('<!DOCTYPE html>');
    expect(result).toContain('<html lang="en">');
    expect(result).toContain('<div>Hello</div>');
    expect(result).toContain('</body>');
    expect(result).toContain('</html>');
  });

  it('should include base CSS in the style tag', () => {
    const result = ensureCompleteHtml('<p>Test</p>');
    expect(result).toContain('box-sizing: border-box');
    expect(result).toContain('font-family');
  });

  it('should include custom CSS when provided', () => {
    const result = ensureCompleteHtml('<p>Test</p>', '.custom { color: red; }');
    expect(result).toContain('.custom { color: red; }');
  });

  it('should inject styles into existing complete HTML before </head>', () => {
    const complete = '<!doctype html><html><head></head><body>Hi</body></html>';
    const result = ensureCompleteHtml(complete, '.injected { color: blue; }');
    expect(result).toContain('.injected { color: blue; }');
    expect(result).toContain('data-injected');
  });

  it('should inject styles before </body> when no </head> exists in complete HTML', () => {
    const complete = '<!doctype html><html><body>Hi</body></html>';
    const result = ensureCompleteHtml(complete, '.injected { color: blue; }');
    expect(result).toContain('data-injected');
  });

  it('should prepend styles when neither </head> nor </body> exist in complete HTML', () => {
    const complete = '<html>Hi</html>';
    const result = ensureCompleteHtml(complete, '.test {}');
    expect(result).toContain('data-injected');
  });

  it('should add auto-fit wrapper and script when autoFit is true', () => {
    const result = ensureCompleteHtml('<p>Content</p>', undefined, true);
    expect(result).toContain('id="auto-fit-wrapper"');
    expect(result).toContain('fitContent');
  });

  it('should not add auto-fit elements when autoFit is false or omitted', () => {
    const result = ensureCompleteHtml('<p>Content</p>');
    expect(result).not.toContain('auto-fit-wrapper');
    expect(result).not.toContain('fitContent');
  });

  it('should inject bridge script when actionMapping is provided', () => {
    const mapping = { 'form': 'trigger:my_form:submit' };
    const result = ensureCompleteHtml('<form>...</form>', undefined, false, mapping);
    expect(result).toContain('trigger:my_form:submit');
    expect(result).toContain('postMessage');
  });

  it('should inject user JS script when jsTemplate is provided', () => {
    const result = ensureCompleteHtml('<div>Hi</div>', undefined, false, undefined, undefined, 'console.log("hello")');
    expect(result).toContain('console.log("hello")');
  });

  // Regression for 2026-05-21 user report: clicking <a href="#">Cars</a>
  // in a marketplace iframe navigated the iframe to "<parent-app-url>#",
  // loading the host app (sidepanel and all) inside the iframe. Root cause:
  // srcdoc iframes have an effective URL of about:srcdoc, so unresolved
  // relative hrefs base-resolve against the embedding document. The fix
  // injects a capture-phase delegated click handler whose per-anchor decision
  // is classifyAnchorNavigation: 'block' placeholder anchors (href="#" / "" /
  // "javascript:..."), 'allow' intra-document hash links (#section), and now
  // 'gate' real external URLs behind a parent confirmation modal instead of
  // letting the sandbox silently swallow the click.
  describe('navigation gate (srcdoc relative-URL fix + external-link confirm)', () => {
    it('injects the navigation gate script on the wrapped path', () => {
      const result = ensureCompleteHtml('<a href="#">Cars</a>');
      // The capture-phase handler must be present, delegate to the classifier,
      // and post navigation-requests to the parent for the gate branch.
      expect(result).toContain("anchor.getAttribute('href')");
      expect(result).toContain('classifyAnchorNavigation(');
      expect(result).toContain("type: 'navigation-request'");
    });

    it('injects the navigation gate script on the complete-HTML path', () => {
      const complete = '<!doctype html><html><head></head><body><a href="#">Cars</a></body></html>';
      const result = ensureCompleteHtml(complete);
      expect(result).toContain("anchor.getAttribute('href')");
      expect(result).toContain('classifyAnchorNavigation(');
      expect(result).toContain("type: 'navigation-request'");
    });

    it('also gates window.open() - through resolveNavigableUrl + shouldGateWindowOpen', () => {
      const result = ensureCompleteHtml('<div>Hi</div>');
      expect(result).toContain('window.open = function');
      expect(result).toContain('resolveNavigableUrl(');
      // A no-gesture window.open() on load must stay a silent no-op, so it cannot
      // pop the confirmation modal on its own (e.g. across a marketplace browse).
      expect(result).toContain('shouldGateWindowOpen(navigator.userActivation)');
    });

    it('still injects the gate on the untrusted-publisher path (removeScripts:true)', () => {
      // Marketplace/showcase previews drop publisher JS but MUST keep the system gate,
      // so external links there are still confirmed rather than silently dead.
      const result = renderInterfaceTemplate('<a href="https://x.com">x</a>', {
        mode: 'edit',
        removeScripts: true,
        wrapInDocument: true,
      });
      expect(result).toContain('classifyAnchorNavigation(');
      expect(result).toContain("type: 'navigation-request'");
    });

    it('registers the click handler with useCapture=true so publisher inline handlers cannot escape it via stopPropagation', () => {
      const result = ensureCompleteHtml('<a href="#">Cars</a>');
      // Last argument to addEventListener - must be true (capture phase),
      // otherwise an inline onclick that calls stopPropagation() on the
      // anchor would block our handler.
      expect(result).toMatch(/addEventListener\(\s*'click'\s*,[\s\S]+?,\s*true\s*\)/);
    });

    it('renders genuine in-document hash anchors (href="#section") verbatim', () => {
      const result = ensureCompleteHtml('<a href="#cars">Cars</a>');
      expect(result).toContain('<a href="#cars">Cars</a>');
    });
  });

  // The exact per-anchor decision that runs inside the iframe (the gate script
  // embeds this function's source verbatim via toString), so these tests pin the
  // shipped logic with zero drift.
  describe('classifyAnchorNavigation', () => {
    it("blocks placeholder anchors (href='#', '', null) - the legacy SPA no-op", () => {
      expect(classifyAnchorNavigation('#', 'about:srcdoc#').action).toBe('block');
      expect(classifyAnchorNavigation('', 'about:srcdoc').action).toBe('block');
      expect(classifyAnchorNavigation(null, 'about:srcdoc').action).toBe('block');
      expect(classifyAnchorNavigation(undefined, 'about:srcdoc').action).toBe('block');
    });

    it('blocks javascript: URLs (incl. leading whitespace / mixed case)', () => {
      expect(classifyAnchorNavigation('javascript:alert(1)', 'javascript:alert(1)').action).toBe('block');
      expect(classifyAnchorNavigation('  JavaScript:void(0)', 'javascript:void(0)').action).toBe('block');
    });

    it('allows genuine in-document hash links so scroll-to-section keeps working', () => {
      expect(classifyAnchorNavigation('#section', 'about:srcdoc#section')).toEqual({ action: 'allow', url: null });
    });

    it('gates external http/https URLs, returning the resolved URL to open', () => {
      expect(classifyAnchorNavigation('https://example.com/x', 'https://example.com/x'))
        .toEqual({ action: 'gate', url: 'https://example.com/x' });
      expect(classifyAnchorNavigation('http://example.com', 'http://example.com/'))
        .toEqual({ action: 'gate', url: 'http://example.com/' });
    });

    it('gates mailto: and tel: links', () => {
      expect(classifyAnchorNavigation('mailto:a@b.com', 'mailto:a@b.com').action).toBe('gate');
      expect(classifyAnchorNavigation('tel:+15551234', 'tel:+15551234').action).toBe('gate');
    });

    it('gates protocol-relative URLs (//host), returning the resolved absolute URL', () => {
      expect(classifyAnchorNavigation('//example.com/x', 'https://example.com/x'))
        .toEqual({ action: 'gate', url: 'https://example.com/x' });
    });

    it('leaves other explicit schemes (blob:, data:) to default browser handling', () => {
      expect(classifyAnchorNavigation('blob:abc', 'blob:abc').action).toBe('allow');
      expect(classifyAnchorNavigation('data:text/plain,hi', 'data:text/plain,hi').action).toBe('allow');
    });

    it('blocks scheme-less RELATIVE paths instead of gating a meaningless host-app URL', () => {
      // In about:srcdoc, anchor.href base-resolves a relative path against the EMBEDDING
      // app origin. Gating it would confirm a nonsensical host URL; block it silently.
      expect(classifyAnchorNavigation('page2.html', 'https://app.example.com/page2.html').action).toBe('block');
      expect(classifyAnchorNavigation('/foo', 'https://app.example.com/foo').action).toBe('block');
      expect(classifyAnchorNavigation('../bar', 'https://app.example.com/bar').action).toBe('block');
      expect(classifyAnchorNavigation('example.com', 'https://app.example.com/example.com').action).toBe('block');
    });

    it('gates but falls back to the raw href when the resolved URL is unparseable', () => {
      // Explicit external scheme so we gate; the resolved value is garbage, so url = raw href.
      expect(classifyAnchorNavigation('https://ok.com/p', 'not a url'))
        .toEqual({ action: 'gate', url: 'https://ok.com/p' });
    });
  });

  // resolveNavigableUrl - the pure URL resolution embedded in the window.open override.
  describe('resolveNavigableUrl', () => {
    it('returns an absolute URL unchanged', () => {
      expect(resolveNavigableUrl('https://example.com/a', 'https://app.example.com/')).toBe('https://example.com/a');
    });

    it('resolves a relative URL against the base URI', () => {
      expect(resolveNavigableUrl('/x', 'https://app.example.com/page')).toBe('https://app.example.com/x');
    });

    it('returns empty string for absent arguments or an unparseable base', () => {
      expect(resolveNavigableUrl(null, 'https://app.example.com/')).toBe('');
      expect(resolveNavigableUrl('', 'https://app.example.com/')).toBe('');
      expect(resolveNavigableUrl(undefined, 'https://app.example.com/')).toBe('');
      // The URL constructor parses the base first and throws if it is invalid, even for an
      // absolute url. In practice document.baseURI is always a valid URL, so this is defensive.
      expect(resolveNavigableUrl('http://x', 'not a base')).toBe('');
      expect(resolveNavigableUrl('relative', 'not a base')).toBe('');
    });
  });

  // shouldGateWindowOpen - the activation gate embedded in the window.open override. This is the
  // BLOCKER fix (no-gesture popups must not pop the modal), tested behaviorally rather than by
  // source-string matching.
  describe('shouldGateWindowOpen', () => {
    it('gates a popup opened under transient user activation', () => {
      expect(shouldGateWindowOpen({ isActive: true })).toBe(true);
    });

    it('does NOT gate (silent no-op) a no-gesture window.open on load', () => {
      expect(shouldGateWindowOpen({ isActive: false })).toBe(false);
      expect(shouldGateWindowOpen({})).toBe(false);
    });

    it('fails open (gates) when navigator.userActivation is unavailable (older engines)', () => {
      expect(shouldGateWindowOpen(undefined)).toBe(true);
      expect(shouldGateWindowOpen(null)).toBe(true);
    });
  });

  // Regression: FlyFinder iframe crashed on `var m = { USD: '$' }` because
  // .replace('</body>', scripts + '</body>') interpreted `$'` in the user JS
  // as "post-match substring" → spliced `\n</html>...` into the middle of the
  // string literal, breaking the parser at line 40 col 33. After the fix
  // (injectBefore), the user JS must land verbatim.
  it("preserves $' in user jsTemplate verbatim through injection (FlyFinder regression)", () => {
    const jsTemplate = "var curMap = { EUR: '€', USD: '$', GBP: '£' };";
    const html = '<!doctype html><html><head></head><body>Hi</body></html>';
    const result = ensureCompleteHtml(html, undefined, false, undefined, undefined, jsTemplate);
    expect(result).toContain("USD: '$'");
    // The classic corruption symptom: `$'` expanded to `</html>` (post-`</body>` text).
    expect(result).not.toContain("USD: '\n</html>");
    expect(result).not.toContain("USD: '</html>");
  });

  it('preserves $&, $`, $$, $1-$9 in user jsTemplate verbatim', () => {
    const jsTemplate = "var t = { a: '$&', b: '$`', c: '$$', d: '$1' };";
    const html = '<!doctype html><html><head></head><body></body></html>';
    const result = ensureCompleteHtml(html, undefined, false, undefined, undefined, jsTemplate);
    expect(result).toContain("a: '$&'");
    expect(result).toContain("b: '$`'");
    expect(result).toContain("c: '$$'");
    expect(result).toContain("d: '$1'");
  });

  it("preserves $' in customCss when injected before </head>", () => {
    const css = ".price::after { content: '$'; }";
    const html = '<!doctype html><html><head></head><body>Hi</body></html>';
    const result = ensureCompleteHtml(html, css);
    expect(result).toContain(".price::after { content: '$'; }");
  });

  // Same regression on the </body>-fallback branch when </head> is absent.
  // Exercises the second code path in the complete-HTML CSS injection.
  it("preserves $' in customCss when injected before </body> (no </head> branch)", () => {
    const css = ".price::after { content: '$'; }";
    const html = '<!doctype html><html><body>Hi</body></html>';
    const result = ensureCompleteHtml(html, css);
    expect(result).toContain(".price::after { content: '$'; }");
    expect(result).not.toContain("content: '</html>");
  });
});

// =============================================================================
// escapeHtml
// =============================================================================
describe('escapeHtml', () => {
  it('should return empty string for empty input', () => {
    expect(escapeHtml('')).toBe('');
  });

  it('should return empty string for null/undefined', () => {
    expect(escapeHtml(null as unknown as string)).toBe('');
    expect(escapeHtml(undefined as unknown as string)).toBe('');
  });

  it('should escape ampersand', () => {
    expect(escapeHtml('A & B')).toBe('A &amp; B');
  });

  it('should escape less-than', () => {
    expect(escapeHtml('a < b')).toBe('a &lt; b');
  });

  it('should escape greater-than', () => {
    expect(escapeHtml('a > b')).toBe('a &gt; b');
  });

  it('should escape double quotes', () => {
    expect(escapeHtml('say "hello"')).toBe('say &quot;hello&quot;');
  });

  it('should escape single quotes', () => {
    expect(escapeHtml("it's")).toBe('it&#39;s');
  });

  it('should escape all special characters together', () => {
    expect(escapeHtml('<div class="a" data=\'b\'>&</div>')).toBe(
      '&lt;div class=&quot;a&quot; data=&#39;b&#39;&gt;&amp;&lt;/div&gt;'
    );
  });

  it('should not modify text without special characters', () => {
    expect(escapeHtml('Hello World 123')).toBe('Hello World 123');
  });
});

// =============================================================================
// extractDisplayLabel
// =============================================================================
describe('extractDisplayLabel', () => {
  it('should extract the last part of a dotted path', () => {
    expect(extractDisplayLabel('mcp:enricher.output.data.user.name')).toBe('name');
  });

  it('should return the expression itself when there are no dots', () => {
    expect(extractDisplayLabel('username')).toBe('username');
  });

  it('should return last segment from a simple dotted path', () => {
    expect(extractDisplayLabel('a.b')).toBe('b');
  });

  it('should handle expression with type prefix and single dot', () => {
    expect(extractDisplayLabel('trigger:start.output')).toBe('output');
  });
});

// =============================================================================
// extractShortLabel
// =============================================================================
describe('extractShortLabel', () => {
  it('should return alias.lastSegment for full expression', () => {
    expect(extractShortLabel('mcp:enricher.output.data.user.name')).toBe('enricher.name');
  });

  it('should remove the type prefix', () => {
    expect(extractShortLabel('trigger:start.output.field')).toBe('start.field');
  });

  it('should return alias when last segment is "output"', () => {
    expect(extractShortLabel('mcp:enricher.output')).toBe('enricher');
  });

  it('should return the expression as-is when no colon and no dot', () => {
    expect(extractShortLabel('simple')).toBe('simple');
  });

  it('should handle expression without type prefix but with dots', () => {
    expect(extractShortLabel('enricher.output.data.name')).toBe('enricher.name');
  });

  it('should handle single dot path after prefix removal', () => {
    expect(extractShortLabel('mcp:step.value')).toBe('step.value');
  });

  it('should return alias when expression is alias.output (output is last)', () => {
    expect(extractShortLabel('trigger:webhook.output')).toBe('webhook');
  });
});

// =============================================================================
// findResolvedValue
// =============================================================================
describe('findResolvedValue', () => {
  it('should return undefined when resolvedData is undefined', () => {
    expect(findResolvedValue('any', undefined)).toBeUndefined();
  });

  it('should find exact match', () => {
    const data = { 'mcp:step.output.name': 'John' };
    expect(findResolvedValue('mcp:step.output.name', data)).toBe('John');
  });

  it('should find match without type prefix', () => {
    const data = { 'step.output.name': 'Jane' };
    expect(findResolvedValue('mcp:step.output.name', data)).toBe('Jane');
  });

  it('should handle current_item.data.field pattern', () => {
    const data = { 'name': 'Alice' };
    expect(findResolvedValue('current_item.data.name', data)).toBe('Alice');
  });

  it('should handle current_item.field pattern', () => {
    const data = { 'field': 'value' };
    expect(findResolvedValue('current_item.field', data)).toBe('value');
  });

  it('should return undefined when key not found', () => {
    const data = { 'other_key': 'val' };
    expect(findResolvedValue('missing_key', data)).toBeUndefined();
  });

  it('should prioritize exact match over prefix-stripped match', () => {
    const data = {
      'mcp:step.output': 'exact',
      'step.output': 'stripped',
    };
    expect(findResolvedValue('mcp:step.output', data)).toBe('exact');
  });

  it('should handle expression without colon prefix', () => {
    const data = { 'simple_key': 42 };
    expect(findResolvedValue('simple_key', data)).toBe(42);
  });

  // ===========================================================================
  // PR2 round-5: dotted-drill into resolved objects (FileRef sub-fields).
  // Closes the audit gap where `{{photo.name}}` returned `[name]` placeholder
  // when only `photo` was mapped to the FileRef object.
  // ===========================================================================
  describe('dotted-drill into nested objects', () => {
    it('drills into a FileRef object mapped under a single alias', () => {
      const data = {
        photo: { _type: 'file', path: 't/p.png', name: 'cat.png', mimeType: 'image/png', size: 1024 },
      };
      expect(findResolvedValue('photo.name', data)).toBe('cat.png');
      expect(findResolvedValue('photo.mimeType', data)).toBe('image/png');
      expect(findResolvedValue('photo.size', data)).toBe(1024);
    });

    it('returns undefined when drilling into a missing sub-field', () => {
      const data = { photo: { _type: 'file', path: 't/p.png', name: 'cat.png' } };
      expect(findResolvedValue('photo.nonExistent', data)).toBeUndefined();
    });

    it('returns undefined when the root is missing', () => {
      const data = { other: { name: 'x' } };
      expect(findResolvedValue('photo.name', data)).toBeUndefined();
    });

    it('drills multiple levels into nested objects', () => {
      const data = { wrap: { inner: { leaf: 'value' } } };
      expect(findResolvedValue('wrap.inner.leaf', data)).toBe('value');
    });

    it('drills into arrays via bracket notation (images[0])', () => {
      const data = {
        images: [
          { _type: 'file', path: 't/a.png', name: 'a.png' },
          { _type: 'file', path: 't/b.png', name: 'b.png' },
        ],
      };
      expect(findResolvedValue('images[0].name', data)).toBe('a.png');
      expect(findResolvedValue('images[1].name', data)).toBe('b.png');
    });

    it('drills into arrays via dot notation (images.0)', () => {
      const data = { images: [{ name: 'first' }, { name: 'second' }] };
      expect(findResolvedValue('images.0.name', data)).toBe('first');
    });

    it('returns undefined for out-of-bounds array index', () => {
      const data = { images: [{ name: 'only-one' }] };
      expect(findResolvedValue('images[5].name', data)).toBeUndefined();
    });

    it('returns undefined when drilling into a scalar value', () => {
      const data = { scalar: 'a-string' };
      expect(findResolvedValue('scalar.length', data)).toBeUndefined();
    });

    it('returns undefined when an intermediate segment is null', () => {
      const data = { photo: { name: null } };
      expect(findResolvedValue('photo.name.length', data)).toBeUndefined();
    });

    it('also drills when the head has a type prefix (mcp:photo.name)', () => {
      const data = {
        'photo': { _type: 'file', path: 't/p.png', name: 'cat.png' },
      };
      // Prefix-stripped path: `photo.name` - the head `photo` matches a
      // resolved key, then `.name` drills into the FileRef.
      expect(findResolvedValue('mcp:photo.name', data)).toBe('cat.png');
    });
  });
});

// =============================================================================
// getDefaultForType
// =============================================================================
describe('getDefaultForType', () => {
  it('should return "0" for number type', () => {
    expect(getDefaultForType('number', 'age')).toBe('0');
  });

  it('should return "true" for boolean type', () => {
    expect(getDefaultForType('boolean', 'active')).toBe('true');
  });

  it('should return "2024-01-01" for datetime type', () => {
    expect(getDefaultForType('datetime', 'createdAt')).toBe('2024-01-01');
  });

  it('should return "Sample fieldName" for text type', () => {
    expect(getDefaultForType('text', 'username')).toBe('Sample username');
  });

  it('should return "Sample fieldName" for unknown type (default)', () => {
    expect(getDefaultForType('unknown', 'data')).toBe('Sample data');
  });
});

// =============================================================================
// mergeTriggerDataIntoResolved
// =============================================================================
describe('mergeTriggerDataIntoResolved', () => {
  it('should return resolvedData unchanged when triggerData is undefined', () => {
    const data = { key: 'value' };
    expect(mergeTriggerDataIntoResolved(data, undefined)).toBe(data);
  });

  it('should return undefined when both are undefined', () => {
    expect(mergeTriggerDataIntoResolved(undefined, undefined)).toBeUndefined();
  });

  it('should merge trigger data into resolved data', () => {
    const resolved = { existing: 'data' };
    const triggerData = { 'trigger:search': { query: 'hello' } };
    const result = mergeTriggerDataIntoResolved(resolved, triggerData);

    expect(result).toEqual({
      existing: 'data',
      'trigger:search.output.query': 'hello',
      'trigger:search.query': 'hello',
    });
  });

  it('should handle multiple trigger keys', () => {
    const triggerData = {
      'trigger:form1': { name: 'Alice' },
      'trigger:form2': { email: 'bob@example.com' },
    };
    const result = mergeTriggerDataIntoResolved({}, triggerData);

    expect(result?.['trigger:form1.output.name']).toBe('Alice');
    expect(result?.['trigger:form1.name']).toBe('Alice');
    expect(result?.['trigger:form2.output.email']).toBe('bob@example.com');
    expect(result?.['trigger:form2.email']).toBe('bob@example.com');
  });

  it('should handle empty trigger data object', () => {
    const resolved = { key: 'value' };
    const result = mergeTriggerDataIntoResolved(resolved, {});
    expect(result).toEqual({ key: 'value' });
  });

  it('should create a new resolved data object when resolvedData is undefined', () => {
    const triggerData = { 'trigger:t1': { field: 'val' } };
    const result = mergeTriggerDataIntoResolved(undefined, triggerData);
    expect(result).toEqual({
      'trigger:t1.output.field': 'val',
      'trigger:t1.field': 'val',
    });
  });

  it('should skip non-object trigger values', () => {
    const triggerData = { 'trigger:t1': null as unknown as Record<string, unknown> };
    const result = mergeTriggerDataIntoResolved({}, triggerData);
    expect(result).toEqual({});
  });
});

// =============================================================================
// translateWithMapping
// =============================================================================
describe('translateWithMapping', () => {
  it('should return data unchanged when mapping is undefined', () => {
    const data = { key: 'value' };
    expect(translateWithMapping(data, undefined)).toBe(data);
  });

  it('should return data unchanged when data is falsy', () => {
    expect(translateWithMapping(null as unknown as Record<string, unknown>, { a: 'b' })).toBe(null);
  });

  it('should add generic name entries from workflow expression keys', () => {
    const data = { 'mcp:step.output.name': 'John' };
    const mapping = { 'user_name': 'mcp:step.output.name' };
    const result = translateWithMapping(data, mapping);

    expect(result['user_name']).toBe('John');
    expect(result['mcp:step.output.name']).toBe('John');
  });

  it('should not overwrite existing generic name entries', () => {
    const data = { 'user_name': 'Existing', 'mcp:step.output.name': 'FromWorkflow' };
    const mapping = { 'user_name': 'mcp:step.output.name' };
    const result = translateWithMapping(data, mapping);

    expect(result['user_name']).toBe('Existing');
  });

  it('should handle mapping with no matching workflow expressions', () => {
    const data = { 'other_key': 'val' };
    const mapping = { 'generic': 'non_existent_expr' };
    const result = translateWithMapping(data, mapping);

    expect(result['generic']).toBeUndefined();
    expect(result['other_key']).toBe('val');
  });

  it('should handle multiple mappings', () => {
    const data = { 'mcp:a.output.x': 1, 'mcp:b.output.y': 2 };
    const mapping = { 'field_x': 'mcp:a.output.x', 'field_y': 'mcp:b.output.y' };
    const result = translateWithMapping(data, mapping);

    expect(result['field_x']).toBe(1);
    expect(result['field_y']).toBe(2);
  });
});

// =============================================================================
// renderForEditMode
// =============================================================================
describe('renderForEditMode', () => {
  it('should return empty string for empty template', () => {
    expect(renderForEditMode('')).toBe('');
  });

  it('should return empty string for null/undefined', () => {
    expect(renderForEditMode(null as unknown as string)).toBe('');
  });

  it('should replace variable without default with [label] placeholder', () => {
    const template = '<p>Hello {{mcp:step.output.data.name}}</p>';
    const result = renderForEditMode(template);
    expect(result).toBe('<p>Hello [name]</p>');
  });

  it('should replace variable with pipe default using the default value', () => {
    const template = '<p>Hello {{mcp:step.output.data.name|World}}</p>';
    const result = renderForEditMode(template);
    expect(result).toBe('<p>Hello World</p>');
  });

  it('should escape HTML in default values', () => {
    const template = '<p>{{var|<script>alert(1)</script>}}</p>';
    const result = renderForEditMode(template);
    expect(result).toContain('&lt;script&gt;');
  });

  it('should handle multiple variables', () => {
    const template = '{{a.b}} and {{c.d|default}}';
    const result = renderForEditMode(template);
    expect(result).toBe('[b] and default');
  });

  it('should handle template with no variables', () => {
    const template = '<p>Plain text</p>';
    expect(renderForEditMode(template)).toBe('<p>Plain text</p>');
  });

  it('should handle empty default value (pipe with no value)', () => {
    const template = '{{mcp:step.output.name|}}';
    const result = renderForEditMode(template);
    // Empty default is still a defined default
    expect(result).toBe('');
  });
});

// =============================================================================
// renderForRunMode
// =============================================================================
describe('renderForRunMode', () => {
  it('should return empty string for empty template', () => {
    expect(renderForRunMode('')).toBe('');
  });

  it('should return empty string for null/undefined', () => {
    expect(renderForRunMode(null as unknown as string)).toBe('');
  });

  it('should replace variable with resolved data', () => {
    const template = '<p>Hello {{mcp:step.output.name}}</p>';
    const data = { 'mcp:step.output.name': 'John' };
    expect(renderForRunMode(template, data)).toBe('<p>Hello John</p>');
  });

  it('should use pipe default when no resolved data for variable', () => {
    const template = '<p>Hello {{mcp:step.output.name|Guest}}</p>';
    expect(renderForRunMode(template)).toBe('<p>Hello Guest</p>');
  });

  it('should use [label] placeholder when no data and no default', () => {
    const template = '<p>Hello {{mcp:step.output.name}}</p>';
    expect(renderForRunMode(template)).toBe('<p>Hello [name]</p>');
  });

  it('should prefer resolved data over pipe default', () => {
    const template = '<p>{{name|Default}}</p>';
    const data = { 'name': 'Resolved' };
    expect(renderForRunMode(template, data)).toBe('<p>Resolved</p>');
  });

  it('should escape HTML in resolved values', () => {
    const template = '<p>{{key}}</p>';
    const data = { 'key': '<b>bold</b>' };
    expect(renderForRunMode(template, data)).toBe('<p>&lt;b&gt;bold&lt;/b&gt;</p>');
  });

  it('should handle multiple variables with mixed resolution', () => {
    const template = '{{a}} | {{b|default_b}} | {{c.d}}';
    const data = { 'a': 'val_a' };
    const result = renderForRunMode(template, data);
    expect(result).toBe('val_a | default_b | [d]');
  });

  it('should handle template with no variables', () => {
    expect(renderForRunMode('<p>Static</p>')).toBe('<p>Static</p>');
  });

  it('should resolve current_item variables', () => {
    const template = '{{current_item.data.name}}';
    const data = { 'name': 'Alice' };
    expect(renderForRunMode(template, data)).toBe('Alice');
  });

  // Regression: when the marketplace card renders a FileRef without an auth
  // token (anonymous path), prior code JSON-stringified the object into the
  // <img src> attribute, producing <img src='{"_type":"file",...}'>. With the
  // backend rewriter now in place, the anonymous payload reaches the frontend
  // already as a string URL - this test pins that the FileRef-as-JSON output
  // never appears, and the URL substitution is what lands in the markup.
  it('renders a presigned-URL string into <img src> without leaking FileRef JSON', () => {
    const template = '<img src="{{img}}">';
    const data = { 'img': 'https://s3.example.com/x.png?X-Amz-Signature=abc&X-Amz-Expires=900' };
    const result = renderForRunMode(template, data);
    // Crucially: no FileRef envelope leaked into the markup - that was the
    // pre-fix bug (anonymous marketplace card got <img src='{"_type":"file",...}'>).
    expect(result).not.toContain('"_type"');
    // The URL host + path must be present (escapeHtml turns & into &amp;,
    // which the browser still parses back as &; the regression we're guarding
    // against is the JSON object form, not the entity-encoding).
    expect(result).toContain('https://s3.example.com/x.png');
    expect(result).toContain('X-Amz-Signature=abc');
  });
});

// =============================================================================
// FileRef → proxy URL rewriter (regression: blank <img> for MCP file outputs)
// =============================================================================
describe('FileRef rewriting (rewriteFileProxyUrls + injectFileProxyUrls)', () => {
  // The interface receives a resolver that maps the opaque by-id URL → a base64 data: URI
  // (fetched with the auth header). The SESSION TOKEN never appears in the iframe HTML - that is the
  // leak this resolver-based design replaces. Here it deterministically prefixes "data:resolved," so
  // we can assert which raw URL each FileRef resolved to.
  const resolve = (raw: string) => 'data:resolved,' + raw;
  const fileRef = (path: string, name = 'foo.png', id = 'fid-' + name) => ({
    _type: 'file' as const,
    path,
    name,
    mimeType: 'image/png',
    size: 1234,
    id,
  });

  it('rewrites a single FileRef object to the resolved blob: URL in HTML substitution', () => {
    const template = '<img src="{{photo}}"/>';
    const html = ensureCompleteHtml(template, undefined, false, undefined, undefined, undefined, {
      photo: fileRef('tenant1/cat/abc.png'),
    }, resolve);
    // Substituted src is the resolver's blob: URL over the opaque by-id URL - no token, no s3 key, no tenant prefix.
    expect(html).toContain('data:resolved,/api/proxy/files/by-id/fid-foo.png/raw');
    expect(html).not.toMatch(/token=/);
    expect(html).not.toContain('_type');
    expect(html).not.toContain('tenant1');
  });

  it('does NOT rewrite a drilled .path bare string (documented as wrong pattern)', () => {
    const template = '<p data-x="{{p}}">{{p}}</p>';
    const html = ensureCompleteHtml(template, undefined, false, undefined, undefined, undefined, {
      p: 'tenant1/cat/abc.png',
    }, resolve);
    // Bare S3 key stays raw - no false-positive proxy wrap.
    expect(html).not.toContain('/api/proxy/files/proxy?');
    expect(html).toContain('tenant1/cat/abc.png');
  });

  it('does NOT rewrite an arbitrary path-shaped user string (regression on false positives)', () => {
    const template = '<a href="{{path}}">link</a>';
    const html = ensureCompleteHtml(template, undefined, false, undefined, undefined, undefined, {
      path: 'users/login',
    }, resolve);
    expect(html).not.toContain('/api/proxy/files/proxy?');
    expect(html).toContain('users/login');
  });

  it('rewrites a mixed object - only the FileRef field becomes a URL', () => {
    // The "outer" object is recursed into; only its FileRef leaves are converted.
    const template = '<img src="{{outer.file}}" data-name="{{outer.name}}"/>';
    const html = ensureCompleteHtml(template, undefined, false, undefined, undefined, undefined, {
      outer: { name: 'foo', file: fileRef('tenant1/m/abc.png') },
    }, resolve);
    expect(html).toContain('data:resolved,/api/proxy/files/by-id/fid-foo.png/raw');
    expect(html).toContain('foo');
  });

  it('rewrites every FileRef inside an array (multi-image js_template iteration)', () => {
    const template = '<div></div>';
    // Inject a JS template that iterates window.__RESOLVED_DATA__.images.
    const js = 'var data = window.__RESOLVED_DATA__; var u = data.images.map(function(x){return x;}); window.__TEST__ = u;';
    const html = ensureCompleteHtml(template, undefined, false, undefined, undefined, js, {
      images: [fileRef('tenant1/m/a.png', 'a'), fileRef('tenant1/m/b.png', 'b')],
    }, resolve);
    // Both resolved blob: URLs over opaque by-id URLs end up in the inlined __RESOLVED_DATA__ JSON.
    expect(html).toContain('data:resolved,/api/proxy/files/by-id/fid-a/raw');
    expect(html).toContain('data:resolved,/api/proxy/files/by-id/fid-b/raw');
    // SECURITY: no session token anywhere in the iframe HTML.
    expect(html).not.toMatch(/token=/);
    // The raw FileRef shape should not appear in the inlined JSON - it's been
    // collapsed to URL strings; neither should the s3 key.
    expect(html).not.toContain('"_type":"file"');
    expect(html).not.toContain('tenant1%2Fm');
  });
});

// =============================================================================
// generateBridgeScript
// =============================================================================
describe('generateBridgeScript', () => {
  it('should return empty string for null/undefined mapping', () => {
    expect(generateBridgeScript(null as unknown as Record<string, string>)).toBe('');
  });

  it('should return empty string for empty mapping', () => {
    expect(generateBridgeScript({})).toBe('');
  });

  it('should generate a script tag with the mapping', () => {
    const mapping = { 'form': 'trigger:my_form:submit' };
    const result = generateBridgeScript(mapping);
    expect(result).toContain('<script>');
    expect(result).toContain('</script>');
    expect(result).toContain('trigger:my_form:submit');
  });

  it('should include postMessage call', () => {
    const mapping = { 'button': 'trigger:btn:click' };
    const result = generateBridgeScript(mapping);
    expect(result).toContain('postMessage');
  });

  it('should include trigger data when provided', () => {
    const mapping = { 'form': 'trigger:search:submit' };
    const triggerData = { 'trigger:search': { query: 'test' } };
    const result = generateBridgeScript(mapping, triggerData);
    expect(result).toContain('prefillForms');
    expect(result).toContain('trigger:search');
  });

  it('should set triggerData to null when not provided', () => {
    const mapping = { 'button': 'trigger:btn:click' };
    const result = generateBridgeScript(mapping);
    expect(result).toContain('var triggerData = null');
  });

  it('should handle pagination mapping', () => {
    const mapping = { '.next-btn': '__pagination:next' };
    const result = generateBridgeScript(mapping);
    expect(result).toContain('__pagination:');
    expect(result).toContain('pagination');
  });

  it('should handle multiple selectors in mapping', () => {
    const mapping = {
      'form': 'trigger:form1:submit',
      '.btn': 'trigger:btn1:click',
    };
    const result = generateBridgeScript(mapping);
    expect(result).toContain('trigger:form1:submit');
    expect(result).toContain('trigger:btn1:click');
  });
});

// =============================================================================
// renderInterfaceTemplate (main function)
// =============================================================================
describe('renderInterfaceTemplate', () => {
  it('should return empty string for empty template', () => {
    expect(renderInterfaceTemplate('', { mode: 'edit' })).toBe('');
  });

  it('should render in edit mode with variable placeholders', () => {
    const result = renderInterfaceTemplate('<p>{{mcp:step.output.name}}</p>', {
      mode: 'edit',
    });
    expect(result).toContain('[name]');
    expect(result).toContain('<!DOCTYPE html>');
  });

  it('should render in run mode with resolved data', () => {
    const result = renderInterfaceTemplate('<p>{{key}}</p>', {
      mode: 'run',
      resolvedData: { key: 'Hello' },
    });
    expect(result).toContain('Hello');
    expect(result).toContain('<!DOCTYPE html>');
  });

  it('should render in preview mode (same as edit)', () => {
    const result = renderInterfaceTemplate('<p>{{a.b}}</p>', {
      mode: 'preview',
    });
    expect(result).toContain('[b]');
  });

  it('should remove scripts by default', () => {
    const result = renderInterfaceTemplate(
      '<script>alert("xss")</script><p>safe</p>',
      { mode: 'edit' }
    );
    expect(result).not.toContain('alert("xss")');
    expect(result).toContain('<p>safe</p>');
  });

  it('should keep scripts when removeScripts is false', () => {
    const result = renderInterfaceTemplate(
      '<script>var x = 1;</script><p>ok</p>',
      { mode: 'edit', removeScripts: false }
    );
    expect(result).toContain('var x = 1;');
  });

  it('should not wrap in document when wrapInDocument is false', () => {
    const result = renderInterfaceTemplate('<p>Hello</p>', {
      mode: 'edit',
      wrapInDocument: false,
    });
    expect(result).not.toContain('<!DOCTYPE html>');
    expect(result).toBe('<p>Hello</p>');
  });

  it('should include custom CSS', () => {
    const result = renderInterfaceTemplate('<p>Test</p>', {
      mode: 'edit',
      customCss: '.custom { font-size: 16px; }',
    });
    expect(result).toContain('.custom { font-size: 16px; }');
  });

  it('should resolve variables in custom CSS in run mode', () => {
    const result = renderInterfaceTemplate('<p>Test</p>', {
      mode: 'run',
      customCss: 'body { color: {{theme.color|black}}; }',
    });
    expect(result).toContain('color: black');
  });

  it('should include auto-fit when enabled', () => {
    const result = renderInterfaceTemplate('<p>Content</p>', {
      mode: 'edit',
      autoFit: true,
    });
    expect(result).toContain('auto-fit-wrapper');
    expect(result).toContain('fitContent');
  });

  it('should inject bridge script when actionMapping is provided', () => {
    const result = renderInterfaceTemplate('<form>...</form>', {
      mode: 'run',
      actionMapping: { 'form': 'trigger:f:submit' },
    });
    expect(result).toContain('trigger:f:submit');
    expect(result).toContain('postMessage');
  });

  it('should inject user JS when jsTemplate is provided', () => {
    const result = renderInterfaceTemplate('<div>Hi</div>', {
      mode: 'edit',
      jsTemplate: 'console.log("injected")',
    });
    expect(result).toContain('console.log("injected")');
  });
});
