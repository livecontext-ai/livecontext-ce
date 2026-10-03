/**
 * @vitest-environment jsdom
 *
 * Security regression tests for the interface HTML utilities.
 *
 * jsdom, not the repo-default `node` environment, because sanitizeHtml is a DOM allow-list pass
 * and only a real parser can exercise it. The DOM-free fallback it keeps for server rendering is
 * covered explicitly below, by removing the DOMParser global for the duration of one test.
 *
 * LC-077: the hand-rolled sanitizer was bypassable via an unterminated <script> opener, a
 *         solidus-separated inline handler, and (wave 2) every URL-scheme vector:
 *         <a href="javascript:">, <iframe src="javascript:">, <iframe srcdoc>.
 * LC-091: the generated bridge script wrote submitted field VALUES to the browser console.
 */
import { describe, it, expect } from 'vitest';
import {
  removeScriptTags,
  sanitizeHtml,
  isCompleteHtml,
  renderInterfaceTemplate,
  generateBridgeScript,
  neutralizeStyleBreakout,
} from '../interfaceHtmlUtils';

// =============================================================================
// LC-077 - sanitizer bypasses
// =============================================================================
describe('LC-077 sanitizer bypasses', () => {
  it('drops an UNTERMINATED <script> opener (no closing tag)', () => {
    // Pre-fix: removeScriptTags only matched balanced <script>...</script> pairs, so this
    // input came back byte-identical and the browser executed alert(1).
    const input = '<div>safe</div><script>alert(1)';
    const result = removeScriptTags(input);

    expect(result).not.toContain('<script');
    expect(result).not.toContain('alert(1)');
    expect(result).toBe('<div>safe</div>');
  });

  it('drops an unterminated opener through sanitizeHtml too', () => {
    const result = sanitizeHtml('<p>hi</p><SCRIPT src="//evil.example/x.js"');

    expect(result.toLowerCase()).not.toContain('<script');
    expect(result).toBe('<p>hi</p>');
  });

  it('strips a solidus-separated inline handler: <img/onerror=...>', () => {
    // Pre-fix: the handler pattern required \s+ before "on", and this markup has no
    // whitespace at all, so onerror=alert(1) survived verbatim and fired on load.
    const result = sanitizeHtml('<img src=x/onerror=alert(1)>');

    expect(result).not.toContain('onerror');
    expect(result).not.toContain('alert(1)');
    expect(result).toContain('<img');
  });

  it('strips a solidus-separated handler directly after the tag name', () => {
    const result = sanitizeHtml('<img/onerror=alert(1)>');

    expect(result).not.toContain('onerror');
    expect(result).toBe('<img>');
  });

  it('strips a newline-separated inline handler: <img\\nonerror=...>', () => {
    const result = sanitizeHtml('<img src="x"\nonerror="alert(1)">');

    expect(result).not.toContain('onerror');
    expect(result).not.toContain('alert(1)');
    expect(result).toContain('src="x"');
  });

  it('keeps a legitimate document that contains no script opener untouched', () => {
    const input = '<div class="card"><a href="https://example.com/one">Link</a></div>';
    expect(sanitizeHtml(input)).toBe(input);
  });

  it('still removes a well-formed script pair and keeps the surrounding markup', () => {
    const input = '<div>a</div><script>evil()</script><p>b</p>';
    expect(removeScriptTags(input)).toBe('<div>a</div><p>b</p>');
  });
});

// =============================================================================
// LC-077 - the truncating pass must only fire on a REAL opener
// =============================================================================
describe('LC-077 removeScriptTags truncation is not trigger-happy', () => {
  it('keeps a document whose only "<script>" sits inside an HTML comment', () => {
    // Pre-fix pass 2 was /<script\b[\s\S]*$/i, which truncated from the first TEXTUAL match.
    // A comment runs nothing, so this input lost its entire body for no security gain, and it
    // lost it silently: the caller got a shorter string, never an error.
    const input = '<!-- <script> --><div>real content</div>';
    expect(removeScriptTags(input)).toBe(input);
  });

  it('keeps a document whose only "<script>" sits in a quoted attribute value', () => {
    // "<" inside a quoted attribute value is literal text to the HTML tokenizer, so nothing
    // executes and nothing may be dropped.
    const input = '<div title="<script>">real content</div>';
    expect(removeScriptTags(input)).toBe(input);
  });

  it('keeps everything after an UNTERMINATED comment (the rest is comment text)', () => {
    const input = '<div>kept</div><!-- <script>alert(1)';
    expect(removeScriptTags(input)).toBe(input);
  });

  it('still truncates a genuine opener that FOLLOWS a comment', () => {
    // Not a pre-fix discriminator (the old regex truncated here too): this one guards the NEW
    // scanner against the opposite mistake, giving up on the whole input at the first comment.
    const result = removeScriptTags('<!-- note --><div>a</div><script>evil()');

    expect(result).toBe('<!-- note --><div>a</div>');
    expect(result).not.toContain('evil()');
  });

  it('does not mistake a scriptish tag name for an opener', () => {
    // "<scriptural>" starts with the same seven characters; only a separator, "/", ">" or end
    // of input after "<script" makes it the real element.
    const input = '<div>a</div><scriptural>b</scriptural>';
    expect(removeScriptTags(input)).toBe(input);
  });

  it('does not mistake a bare "<" in prose for a tag', () => {
    const input = '<p>1 < 2 and 3 > 2</p>';
    expect(removeScriptTags(input)).toBe(input);
  });
});

// =============================================================================
// LC-077 residual - the inert contexts BESIDE the comment: RCDATA and RAWTEXT
// =============================================================================
describe('LC-077 removeScriptTags honours RCDATA and RAWTEXT contexts', () => {
  // The tokenizer reads the content of these elements as text, never as markup, so a literal
  // "<script>" between their tags is as inert as one inside a comment. Pre-fix the scanner knew
  // only the comment case, so each of these inputs was truncated at the "<script>" and lost
  // everything after it - the same silent content destruction, one sibling context over.
  it.each([
    ['textarea', '<textarea>paste your <script> here</textarea><div>real content</div>'],
    ['title', '<title>My <script> page</title><div>real content</div>'],
    ['style', '<style>.a{}/* <script> */</style><div>real content</div>'],
    ['xmp', '<xmp><script>x</xmp><p>real content</p>'],
    ['iframe', '<iframe><script>x</iframe><p>real content</p>'],
    ['noembed', '<noembed><script>x</noembed><p>real content</p>'],
    ['noframes', '<noframes><script>x</noframes><p>real content</p>'],
  ])('returns the input byte-identical when the only "<script>" sits inside <%s>', (_tag, input) => {
    expect(removeScriptTags(input)).toBe(input);
  });

  it('keeps everything after <plaintext>, whose content runs to end of input', () => {
    // PLAINTEXT has no end tag at all: the tokenizer never leaves that state, so nothing after
    // the opener can ever be an opener.
    const input = '<div>a</div><plaintext><script>alert(1)</plaintext>';
    expect(removeScriptTags(input)).toBe(input);
  });

  it('matches the context tag whatever its casing', () => {
    const input = '<TEXTAREA>a <SCRIPT> b</TEXTAREA><p>real content</p>';
    expect(removeScriptTags(input)).toBe(input);
  });

  it('finds the end tag past attributes and a ">" inside a quoted attribute value', () => {
    const input = '<textarea rows="2" title="a>b">x <script> y</textarea ><p>real content</p>';
    expect(removeScriptTags(input)).toBe(input);
  });

  it('keeps an UNCLOSED rawtext element and everything after it (all of it is text)', () => {
    const input = '<textarea>never closed <script>alert(1)';
    expect(removeScriptTags(input)).toBe(input);
  });

  it('keeps the surviving content through sanitizeHtml, not only through the textual pass', () => {
    // sanitizeHtml re-serializes, so the textarea's text comes back entity-escaped. What matters
    // is that the markup AFTER the textarea still exists: pre-fix it was gone.
    const result = sanitizeHtml('<textarea>paste your <script> here</textarea><div>real content</div>');

    expect(result).toContain('real content');
    expect(result).toContain('<textarea>');
  });
});

describe('LC-077 residual - the rawtext skip must not become a bypass', () => {
  it('still truncates a genuine opener that FOLLOWS a closed rawtext element', () => {
    // The opposite mistake: giving up on the document at the first <textarea>.
    const result = removeScriptTags('<textarea>a</textarea><div>b</div><script>evil()');

    expect(result).toBe('<textarea>a</textarea><div>b</div>');
    expect(result).not.toContain('evil()');
  });

  it('still truncates after a run of several closed rawtext elements', () => {
    const result = removeScriptTags('<title>t</title><style>a{}</style><div>x</div><script>evil()');

    expect(result).toBe('<title>t</title><style>a{}</style><div>x</div>');
  });

  it('still truncates inside <svg>, where <style> is a plain element and <script> is real', () => {
    // Foreign content never switches the tokenizer to RAWTEXT, so this <script> executes. The
    // rawtext skip is suspended for the whole SVG/MathML subtree for exactly this reason.
    const result = removeScriptTags('<svg><style><script>alert(1)');

    expect(result).toBe('<svg><style>');
    expect(result).not.toContain('alert(1)');
  });

  it('still truncates inside <math> too', () => {
    expect(removeScriptTags('<math><title><script>alert(1)')).toBe('<math><title>');
  });

  it('still truncates on an SVG <script>, which executes like an HTML one', () => {
    const result = removeScriptTags('<div><textarea>a</textarea></div><svg><script>alert(1)');

    expect(result).toBe('<div><textarea>a</textarea></div><svg>');
  });

  it('resumes normal rawtext handling once the SVG subtree is closed', () => {
    const input = '<svg><circle r="1"/></svg><textarea><script>x</textarea><p>real content</p>';
    expect(removeScriptTags(input)).toBe(input);
  });

  it('treats a self-closing <svg/> as opening no subtree', () => {
    const input = '<svg/><textarea><script>x</textarea><p>real content</p>';
    expect(removeScriptTags(input)).toBe(input);
  });
});

describe('LC-077 residual - a solidus or quote inside an UNQUOTED attribute value is data', () => {
  it('does not treat <svg data-x=a/> as self-closing, so the foreign subtree stays open', () => {
    // The tokenizer APPENDS the solidus to an unquoted value: this tag carries data-x="a/" and
    // opens an SVG subtree like any other <svg>. Reading the character before ">" said it
    // self-closed, so the rawtext skip stayed enabled inside foreign content, where <style> is a
    // plain element - and this REAL script survived the sanitizer byte for byte.
    const result = removeScriptTags('<svg data-x=a/><style><script>alert(1)');

    expect(result).toBe('<svg data-x=a/><style>');
    expect(result).not.toContain('alert(1)');
  });

  it('still treats <svg data-x="a"/> as self-closing, where the solidus really does close the tag', () => {
    // The counterpart, so the fix is "match the tokenizer", not "never self-close": after a
    // QUOTED value the solidus is a self-closing flag, the subtree never opens, and the rawtext
    // skip legitimately applies to the <textarea> that follows.
    const input = '<svg data-x="a"/><textarea><script>x</textarea><p>real content</p>';
    expect(removeScriptTags(input)).toBe(input);
  });

  it('still treats <svg data-x=a /> as self-closing, because whitespace ends the unquoted value', () => {
    const input = '<svg data-x=a /><textarea><script>x</textarea><p>real content</p>';
    expect(removeScriptTags(input)).toBe(input);
  });

  it('ends an ordinary tag at its ">" when an unquoted value contains a quote character', () => {
    // Same misreading, ordinary content, opposite damage: a "'" inside an unquoted value was
    // taken for the start of a quoted one, so the scan ran past the ">" to end of input and
    // reported "no opener at all". The genuine <script> that followed was left executable.
    const result = removeScriptTags("<div data-x=a'b><script>alert(1)");

    expect(result).toBe("<div data-x=a'b>");
    expect(result).not.toContain('alert(1)');
  });

  it('keeps ordinary content whose unquoted attribute value ends in a solidus', () => {
    // The no-regression half: the solidus must not derail the scan either, so the rawtext skip
    // after the tag still runs and the document survives whole.
    const input = '<div data-x=a/><textarea><script>x</textarea><p>real content</p>';
    expect(removeScriptTags(input)).toBe(input);
  });
});

// =============================================================================
// LC-077 wave 2 - URL-scheme vectors the regex sanitizer could not see
// =============================================================================
describe('LC-077 sanitizeHtml drops javascript: URL sinks', () => {
  it('strips a javascript: href but keeps the anchor and its text', () => {
    // Pre-fix: the regex sanitizer only knew <script> and on*=, so this survived byte for byte
    // and the browser executed alert(1) on click, on a surface whose contract is "no publisher JS".
    const result = sanitizeHtml('<a href="javascript:alert(1)">Click</a>');

    expect(result).not.toContain('javascript:');
    expect(result).not.toContain('href');
    expect(result).toContain('Click');
  });

  it('strips a javascript: href whatever its casing', () => {
    const result = sanitizeHtml('<a href="JaVaScRiPt:alert(1)">Click</a>');

    expect(result.toLowerCase()).not.toContain('javascript:');
  });

  it('strips a javascript: href hidden behind an entity-encoded control character', () => {
    // The parser decodes &#x09; to a tab before we ever see the value, which is precisely why
    // this pass is DOM-based: no textual scan of the source string matches "javascript:" here.
    const result = sanitizeHtml('<a href="jav&#x09;ascript:alert(1)">Click</a>');

    expect(result).not.toContain('ascript:');
    expect(result).not.toContain('href');
    expect(result).toContain('Click');
  });

  it('strips a javascript: href on an SVG anchor (xlink:href)', () => {
    const result = sanitizeHtml('<svg><a xlink:href="javascript:alert(1)"><text>x</text></a></svg>');

    expect(result).not.toContain('javascript:');
    expect(result).not.toContain('xlink:href');
  });

  it('strips a javascript: form action and formaction', () => {
    const result = sanitizeHtml(
      '<form action="javascript:alert(1)"><button formaction="javascript:alert(2)">Go</button></form>'
    );

    expect(result).not.toContain('javascript:');
    expect(result).not.toContain('action=');
    expect(result).toContain('Go');
  });

  it('drops an <iframe> carrying a javascript: src', () => {
    const result = sanitizeHtml('<div>ok</div><iframe src="javascript:alert(1)"></iframe>');

    expect(result).toBe('<div>ok</div>');
  });

  it('drops an <iframe> carrying a srcdoc document', () => {
    // The entities decode to a real <script> only once the browser parses srcdoc, so the
    // textual passes see nothing script-shaped at all.
    const result = sanitizeHtml('<iframe srcdoc="&lt;script&gt;alert(1)&lt;/script&gt;"></iframe>');

    expect(result).toBe('');
  });

  it('drops srcdoc even when it rides on an allow-listed element', () => {
    const result = sanitizeHtml('<div srcdoc="&lt;script&gt;alert(1)&lt;/script&gt;">text</div>');

    expect(result).not.toContain('srcdoc');
    expect(result).toContain('text');
  });

  it('drops <object> and <embed> with their content', () => {
    const result = sanitizeHtml(
      '<p>a</p><object data="javascript:alert(1)">fallback</object><embed src="javascript:alert(2)">'
    );

    expect(result).toBe('<p>a</p>');
  });

  it('drops <base>, which would re-point every relative URL in the document', () => {
    const result = sanitizeHtml('<base href="https://evil.example/"><a href="/page">x</a>');

    expect(result).not.toContain('<base');
    expect(result).toContain('<a href="/page">');
  });

  it('drops a <meta http-equiv="refresh"> redirect', () => {
    const result = sanitizeHtml('<meta http-equiv="refresh" content="0;url=https://evil.example">');

    expect(result).not.toContain('http-equiv');
    expect(result).not.toContain('evil.example');
  });

  it('drops a style attribute that carries a javascript: URL', () => {
    const result = sanitizeHtml('<div style="background:url(javascript:alert(1))">x</div>');

    expect(result).not.toContain('javascript:');
    expect(result).toContain('x');
  });
});

describe('LC-077 sanitizeHtml keeps legitimate publisher markup', () => {
  it('keeps http(s), mailto, relative and in-document hrefs', () => {
    const input =
      '<a href="https://example.com/a">a</a><a href="mailto:x@example.com">b</a>' +
      '<a href="/page">c</a><a href="#section">d</a>';

    expect(sanitizeHtml(input)).toBe(input);
  });

  it('keeps an inline data: image, which run mode produces for every FileRef', () => {
    const input = '<img src="data:image/png;base64,iVBORw0KGgo=" alt="pic">';

    expect(sanitizeHtml(input)).toContain('data:image/png;base64,iVBORw0KGgo=');
  });

  it('drops a data:text/html src but keeps the element', () => {
    // data:image is inert in an <img>; data:text/html is a document, so it stays out.
    const result = sanitizeHtml('<img src="data:text/html,<b>x</b>" alt="pic">');

    expect(result).not.toContain('data:text/html');
    expect(result).toContain('alt="pic"');
  });

  it('drops a data:image/svg+xml src, which is a document and can carry script', () => {
    const result = sanitizeHtml('<img src="data:image/svg+xml,%3Csvg%3E" alt="pic">');

    expect(result).not.toContain('data:image/svg+xml');
    expect(result).toContain('alt="pic"');
  });

  it('keeps a leading <style> block, which the parser hoists into <head>', () => {
    // Regression guard on the serialization: rebuilding a fragment from body.innerHTML alone
    // would silently delete the publisher's CSS, because the parser moves <style> to <head>.
    const input = '<style>.card{color:red}</style><div class="card">x</div>';

    expect(sanitizeHtml(input)).toBe(input);
  });

  it('keeps a table, a form and their content intact', () => {
    const input =
      '<form id="f"><table><thead><tr><th>h</th></tr></thead><tbody><tr><td>' +
      '<input name="q" type="text"></td></tr></tbody></table><button type="submit">Go</button></form>';

    expect(sanitizeHtml(input)).toBe(input);
  });

  it('unwraps an unknown element instead of swallowing its content', () => {
    // A sanitizer that deletes the subtree of every tag it does not recognise destroys real
    // content, which is how sanitizers end up switched off.
    const result = sanitizeHtml('<my-widget><p>keep me</p></my-widget>');

    expect(result).toBe('<p>keep me</p>');
  });

  it('keeps a complete document complete, doctype and head included', () => {
    // isCompleteHtml gates the platform's document scaffold and base CSS downstream, so a
    // sanitizer that flattened a complete document to a fragment would re-style publisher pages.
    const result = sanitizeHtml(
      '<!DOCTYPE html><html lang="en"><head><title>T</title><style>p{color:red}</style></head>' +
      '<body><p>hi</p></body></html>'
    );

    expect(isCompleteHtml(result)).toBe(true);
    expect(result).toContain('<title>T</title>');
    expect(result).toContain('<style>p{color:red}</style>');
    expect(result).toContain('<p>hi</p>');
    expect(result).toContain('lang="en"');
  });

  it('strips a handler off the <body> of a complete document without dropping the body', () => {
    const result = sanitizeHtml('<!DOCTYPE html><html><body onload="evil()"><p>hi</p></body></html>');

    expect(result).not.toContain('onload');
    expect(result).not.toContain('evil()');
    expect(result).toContain('<p>hi</p>');
    expect(isCompleteHtml(result)).toBe(true);
  });
});

// =============================================================================
// LC-077 wave 2 - the same guarantee via the exported render entry point
// =============================================================================
describe('LC-077 renderInterfaceTemplate honours removeScripts fully', () => {
  it('drops a javascript: href when removeScripts is true', () => {
    // Pre-fix step 1 called removeScriptTags, so `removeScripts: true` promised "no publisher JS"
    // and delivered "no <script> tags". A caller that trusted the option instead of pre-sanitizing
    // on its own still shipped a live javascript: sink.
    const result = renderInterfaceTemplate('<a href="javascript:alert(1)">Click</a>', {
      mode: 'edit',
      removeScripts: true,
      wrapInDocument: false,
    });

    expect(result).not.toContain('javascript:alert(1)');
    expect(result).toContain('Click');
  });

  it('drops an <iframe> src/srcdoc pair when removeScripts is true', () => {
    const result = renderInterfaceTemplate(
      '<p>keep</p><iframe srcdoc="&lt;script&gt;alert(1)&lt;/script&gt;"></iframe>',
      { mode: 'edit', removeScripts: true, wrapInDocument: false }
    );

    expect(result).toBe('<p>keep</p>');
  });

  it('leaves the markup alone when removeScripts is false', () => {
    // The option still means what it says in the other direction: a trusted surface that opts out
    // must get its author's markup back untouched.
    const input = '<a href="javascript:alert(1)">Click</a>';
    const result = renderInterfaceTemplate(input, {
      mode: 'edit',
      removeScripts: false,
      wrapInDocument: false,
    });

    expect(result).toBe(input);
  });

  it('does NOT inject jsTemplate when removeScripts is true', () => {
    // Pre-fix `jsTemplate` was passed to ensureCompleteHtml unconditionally, so the option that
    // promises "no publisher JS runs" hand-wrapped the publisher's JS in a live <script> tag.
    // InterfaceIframe already worked around it caller-side; every other caller shipped the JS.
    const result = renderInterfaceTemplate('<p>keep</p>', {
      mode: 'edit',
      removeScripts: true,
      jsTemplate: 'alert(document.cookie)',
    });

    expect(result).not.toContain('alert(document.cookie)');
    expect(result).not.toContain('document.cookie');
    // Guards against a vacuous pass: the render still happened, it just carries no publisher JS.
    expect(result).toContain('<p>keep</p>');
  });

  it('DOES inject jsTemplate when removeScripts is false', () => {
    const result = renderInterfaceTemplate('<p>keep</p>', {
      mode: 'edit',
      removeScripts: false,
      jsTemplate: 'window.appReady = true;',
    });

    expect(result).toContain('window.appReady = true;');
    expect(result).toContain('<p>keep</p>');
  });
});

describe('LC-077 sanitizeHtml server-rendering fallback', () => {
  it('still strips scripts and handlers when no DOMParser exists', () => {
    // The callers are React client components and Next.js renders those on the server too,
    // where DOMParser is undefined. Reaching for it there would throw a ReferenceError
    // mid-render instead of sanitizing, so the textual pass has to stand alone.
    const scope = globalThis as unknown as { DOMParser?: unknown };
    const original = scope.DOMParser;
    delete scope.DOMParser;
    try {
      expect(typeof DOMParser).toBe('undefined');
      const result = sanitizeHtml('<div onclick="evil()">a</div><script>alert(1)</script>');

      expect(result).not.toContain('onclick');
      expect(result).not.toContain('<script');
      expect(result).toContain('a');
    } finally {
      scope.DOMParser = original;
    }
  });
});

// =============================================================================
// LC-091 - no submitted field value reaches the console
// =============================================================================

/** Every `console.*` call in the generated script, one entry per call site. */
function consoleCallLines(script: string): string[] {
  return script
    .split('\n')
    .map(line => line.trim())
    .filter(line => line.includes('console.'));
}

describe('LC-091 bridge script console hygiene', () => {
  const script = generateBridgeScript(
    { '#search-form': 'trigger:search:submit', '#go': '__continue' },
    { 'trigger:search': { email_body: 'confidential mail content' } }
  );

  it('emits console calls at all (guards against a vacuous assertion below)', () => {
    expect(consoleCallLines(script).length).toBeGreaterThan(0);
  });

  it('never interpolates a prefilled field VALUE into a console call', () => {
    // Pre-fix the prefill logger emitted:
    //   '... = ' + stringValue.slice(0, 40)
    // so `stringValue` appeared in a console call without the `.length` accessor.
    for (const line of consoleCallLines(script)) {
      const valueRefs = line.match(/stringValue(\.\w+)?/g) || [];
      for (const ref of valueRefs) {
        expect(ref).toBe('stringValue.length');
      }
    }
  });

  it('logs the field name and the value LENGTH instead of the value', () => {
    expect(script).toContain('valueLength=');
    expect(script).toContain('stringValue.length');
    expect(script).not.toContain('stringValue.slice(0, 40)');
  });

  it('never interpolates the collected form payload object into a console call', () => {
    // Pre-fix the __continue handler logged `{ actionKey: actionName, data: data }`,
    // i.e. the whole submitted payload.
    for (const line of consoleCallLines(script)) {
      expect(line).not.toMatch(/\bdata\s*:\s*data\b/);
      expect(line).not.toMatch(/,\s*data\s*\)/);
    }
  });

  it('logs the payload KEYS for the __continue handler', () => {
    const continueLog = consoleCallLines(script).find(line => line.includes('Sending postMessage continue'));
    expect(continueLog).toBeDefined();
    expect(continueLog).toContain('Object.keys(data)');
  });
});

// =============================================================================
// LC-077 residual (audit round 2) - mutation XSS and substitution-time sinks
// =============================================================================

/**
 * Known mutation-XSS payloads: markup that is inert as PARSED, but whose SERIALIZED form
 * re-parses into live script. The hand-rolled DOM walker let the first one through: the
 * attribute text under <math><style> came back as a real <img onerror>.
 */
const MXSS_CORPUS = [
  '<math><style><a title="&lt;/style&gt;&lt;img src=x &#111;nerror=alert(1)&gt;"></a></style></math>',
  '<math><title><a title="&lt;/title&gt;&lt;img src=x onerror=alert(1)&gt;"></a></title></math>',
  '<svg><style><a title="&lt;/style&gt;&lt;img src=x onerror=alert(1)&gt;"></a></style></svg>',
  '<svg></p><style><a id="</style><img src=1 onerror=alert(1)>">',
  '<math><mtext><table><mglyph><style><!--</style><img title="--&gt;&lt;img src=1 onerror=alert(1)&gt;">',
  '<form><math><mtext></form><form><mglyph><style></math><img src onerror=alert(1)>',
  '<noscript><p title="</noscript><img src=x onerror=alert(1)>">',
  '<svg><p><style><img src=x onerror=alert(1)></style></p></svg>',
  '<a href="&#x6A;avascript:alert(1)">x</a><iframe srcdoc="<script>alert(1)</script>"></iframe>',
  '<img src="x" onerror="alert(1)"><div/onmouseover=alert(1)>y</div>',
];

/** Parse like a browser and report every live script sink left in the tree. */
function liveSinks(html: string): string[] {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const found: string[] = [];
  for (const el of Array.from(doc.querySelectorAll('*'))) {
    const tag = el.tagName.toLowerCase();
    if (tag === 'script' || tag === 'iframe' || tag === 'object' || tag === 'embed') found.push(`<${tag}>`);
    for (const attr of Array.from(el.attributes)) {
      const name = attr.name.toLowerCase();
      if (/^on/.test(name)) found.push(`${tag}[${name}]`);
      if (name === 'srcdoc') found.push(`${tag}[srcdoc]`);
      if (/^\s*(javascript|vbscript|data:text\/html)/i.test(attr.value.replace(/[\u0000-\u0020]/g, ''))) {
        found.push(`${tag}[${name}=${attr.value}]`);
      }
    }
  }
  return found;
}

describe('LC-077 residual - mutation XSS corpus', () => {
  it.each(MXSS_CORPUS)('%s: no live sink after sanitize, nor after a re-parse round trip', (payload) => {
    const once = sanitizeHtml(payload);
    expect(liveSinks(once)).toEqual([]);
    // The browser parses the sanitized string again (srcDoc), and may serialize/parse it more.
    const reparsed = new DOMParser().parseFromString(once, 'text/html').body.innerHTML;
    expect(liveSinks(reparsed)).toEqual([]);
  });

  it.each(MXSS_CORPUS)('%s: sanitize is idempotent across a re-parse', (payload) => {
    const once = sanitizeHtml(payload);
    const reparsed = new DOMParser().parseFromString(once, 'text/html').body.innerHTML;
    expect(sanitizeHtml(reparsed)).toBe(sanitizeHtml(once));
  });

  it('drops foreign-content style/title confusion instead of re-serializing it', () => {
    const out = sanitizeHtml('<p>keep</p><math><style><a title="&lt;/style&gt;&lt;img src=x &#111;nerror=alert(1)&gt;"></a></style></math>');
    expect(out).toContain('<p>keep</p>');
    expect(out).not.toMatch(/onerror/i);
  });
});

describe('LC-077 residual - sinks completed by variable substitution', () => {
  it('drops a javascript: href whose value only exists after substitution (removeScripts)', () => {
    const result = renderInterfaceTemplate('<a href="{{link}}">Open</a>', {
      mode: 'run',
      removeScripts: true,
      wrapInDocument: false,
      resolvedData: { link: 'javascript:alert(document.domain)' },
    });
    expect(result).toContain('Open');
    expect(result).not.toMatch(/javascript:/i);
  });

  it('keeps a legitimate substituted https link', () => {
    const result = renderInterfaceTemplate('<a href="{{link}}">Open</a>', {
      mode: 'run',
      removeScripts: true,
      wrapInDocument: false,
      resolvedData: { link: 'https://example.com/page' },
    });
    expect(result).toContain('href="https://example.com/page"');
  });
});

// =============================================================================
// Publisher CSS is inlined after the HTML sanitizer: it must not break out of <style>
// =============================================================================
describe('publisher CSS style breakout', () => {
  const hostile = '</style><script>window.__pwned = 1</script><style>';

  it('cannot inject a script through customCss in a removeScripts render', () => {
    // Pre-fix: ensureCompleteHtml interpolated customCss raw into <style>, after sanitizeHtml
    // had run on the body only, so `</style>` closed the element and the script tag survived.
    const result = renderInterfaceTemplate('<p>Hello</p>', {
      mode: 'edit',
      removeScripts: true,
      wrapInDocument: true,
      customCss: `body{color:red}${hostile}`,
    });

    const doc = new DOMParser().parseFromString(result, 'text/html');
    const scripts = Array.from(doc.querySelectorAll('script')).map(s => s.textContent ?? '');
    expect(scripts.some(text => text.includes('__pwned'))).toBe(false);
    expect(doc.querySelector('p')?.textContent).toBe('Hello');
  });

  it('keeps ordinary CSS intact and rewrites only the < character', () => {
    expect(neutralizeStyleBreakout('a > b { content: "<"; color: red }'))
      .toBe(String.raw`a > b { content: "\3c "; color: red }`);
    expect(neutralizeStyleBreakout('body{margin:0}')).toBe('body{margin:0}');
  });
});
