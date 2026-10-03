/**
 * Interface HTML Utilities
 *
 * Centralized utilities for rendering interface HTML templates.
 * Used by all interface preview components (node, fullscreen, inspector, etc.)
 */

import { isFileRef, fileRefToUrl, normalizeFileRef, type FileRef } from '@/lib/api/orchestrator/file.service';
import { assetDisplayUrl } from '@/lib/datatable/assetValue';
import DOMPurify from 'dompurify';

// =============================================================================
// HTML Processing
// =============================================================================

/**
 * Platform base stylesheet for FRAGMENT templates only - the default theme a
 * bare HTML snippet inherits when the platform wraps it in its own document
 * scaffold (system font, border-box sizing, 8px breathing room).
 *
 * NEVER injected into a COMPLETE document (isCompleteHtml): the author owns
 * the whole page there, and injecting this at the end of <head> would WIN the
 * cascade over their own body/box-sizing rules - making the preview diverge
 * from the screenshot/video renderer, which injects nothing (WYSIWYG parity;
 * the backend fragment scaffold injects this same base, see
 * InterfaceScreenshotServiceImpl.FRAGMENT_BASE_CSS - keep both in sync).
 */
const BASE_IFRAME_CSS = `
* { box-sizing: border-box; }
body {
  margin: 0;
  padding: 8px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}
`.trim();

/**
 * Elements whose CONTENT the HTML tokenizer reads as text rather than markup (RCDATA for
 * {@code textarea} and {@code title}, RAWTEXT for the rest). A literal {@code <script>} written
 * between their tags is inert, exactly like one written inside a comment.
 *
 * <p>{@code plaintext} is deliberately absent: it has no end tag at all (the tokenizer stays in
 * the PLAINTEXT state until EOF), so it is handled as "nothing after this point is an opener".</p>
 */
const RAWTEXT_ELEMENTS = new Set([
  'textarea', 'title', 'style', 'xmp', 'iframe', 'noembed', 'noframes',
]);

/**
 * Roots of a foreign (SVG / MathML) subtree, where the rule above does NOT hold: inside foreign
 * content a start tag is inserted as a foreign element and the tokenizer is never switched to
 * RAWTEXT, so {@code <svg><style><script>alert(1)} carries a REAL script and must still truncate.
 */
const FOREIGN_CONTENT_ROOTS = new Set(['svg', 'math']);

/**
 * Index just past the end tag that closes the RAWTEXT/RCDATA element whose content starts at
 * {@code from}, or {@code -1} when it is never closed (its content then runs to EOF, so nothing
 * after it is an opener either).
 *
 * @param tagName always a member of {@link RAWTEXT_ELEMENTS}, i.e. a literal we own, never
 *                caller-controlled text that could reach the pattern
 */
function findRawtextEnd(html: string, from: number, tagName: string): number {
  const closer = new RegExp(`</${tagName}(?=[\\s/>]|$)`, 'gi');
  closer.lastIndex = from;
  const match = closer.exec(html);
  if (!match) return -1;
  // An end tag may carry (ignored) attributes, so its ">" is not necessarily the next character.
  const gt = html.indexOf('>', match.index + match[0].length);
  return gt < 0 ? -1 : gt + 1;
}

/** HTML tokenizer whitespace (tab, LF, FF, CR, space). CR is normalized to LF before parsing. */
const TAG_WHITESPACE = /[\t\n\f\r ]/;

/**
 * Where a tag that starts at {@code tagStart} ends, and whether it self-closes.
 *
 * <p>A miniature of the HTML tokenizer's tag states, because the two questions it answers cannot
 * be answered by looking for characters. Both were wrong when read naively:</p>
 * <ul>
 *   <li><b>the end of the tag.</b> A {@code "} or {@code '} only opens a quoted value when the
 *       tokenizer is waiting for one. In {@code <div data-x=a'b>} the quote is an ordinary
 *       character of the UNQUOTED value, so the tag ends at the {@code >} right after it. Treating
 *       the quote as an opener made the scan swallow the rest of the document and report "no
 *       opener", which is a truncation this function exists to prevent from being skipped;</li>
 *   <li><b>self-closing.</b> In an unquoted value the tokenizer APPENDS a solidus to the value:
 *       {@code <svg data-x=a/>} carries {@code data-x="a/"} and does NOT self-close. Reading the
 *       character before {@code >} said it did, so the SVG subtree was believed closed at once and
 *       the RAWTEXT skip stayed enabled inside foreign content, where {@code <style>} is a plain
 *       element: {@code <svg data-x=a/><style><script>alert(1)} survived untouched.</li>
 * </ul>
 *
 * @param tagStart index of the {@code <}
 * @returns {@code after} = index just past the tag (or the input length when the tag is never
 *          closed, since everything left is then part of it), and {@code selfClosing}
 */
function scanTag(html: string, tagStart: number): { after: number; selfClosing: boolean } {
  const len = html.length;
  // Tokenizer states, named after the spec ones. The tag name starts right after "<" (an end
  // tag's "/" is consumed by the same walk, which is harmless: a "/" there only leads to the
  // self-closing state, and self-closing is only ever read for START tags).
  type State =
    | 'name' | 'beforeAttrName' | 'attrName' | 'afterAttrName' | 'beforeAttrValue'
    | 'valueDouble' | 'valueSingle' | 'valueUnquoted' | 'afterValueQuoted' | 'selfClosing';
  let state: State = 'name';

  for (let i = tagStart + 1; i < len; i++) {
    const ch = html.charAt(i);
    const isSpace = TAG_WHITESPACE.test(ch);

    switch (state) {
      case 'name':
      case 'attrName':
        if (isSpace) state = state === 'name' ? 'beforeAttrName' : 'afterAttrName';
        else if (ch === '/') state = 'selfClosing';
        else if (ch === '>') return { after: i + 1, selfClosing: false };
        else if (ch === '=' && state === 'attrName') state = 'beforeAttrValue';
        break;
      case 'beforeAttrName':
      case 'afterAttrName':
      case 'afterValueQuoted':
        if (isSpace) break;
        if (ch === '/') state = 'selfClosing';
        else if (ch === '>') return { after: i + 1, selfClosing: false };
        else if (ch === '=' && state === 'afterAttrName') state = 'beforeAttrValue';
        else state = 'attrName';
        break;
      case 'beforeAttrValue':
        if (isSpace) break;
        if (ch === '"') state = 'valueDouble';
        else if (ch === "'") state = 'valueSingle';
        else if (ch === '>') return { after: i + 1, selfClosing: false };
        else state = 'valueUnquoted';
        break;
      case 'valueDouble':
        if (ch === '"') state = 'afterValueQuoted';
        break;
      case 'valueSingle':
        if (ch === "'") state = 'afterValueQuoted';
        break;
      case 'valueUnquoted':
        // "/" and the quote characters are ordinary value characters here: only whitespace and
        // ">" leave this state. That is the whole of the fix.
        if (isSpace) state = 'beforeAttrName';
        else if (ch === '>') return { after: i + 1, selfClosing: false };
        break;
      case 'selfClosing':
        if (ch === '>') return { after: i + 1, selfClosing: true };
        // Anything else is a parse error and is reconsumed before the next attribute name.
        i--;
        state = 'beforeAttrName';
        break;
    }
  }
  // No ">" before EOF: the tag never ends, so nothing after it is markup.
  return { after: len, selfClosing: false };
}

/**
 * Index of the first {@code <script} opener that is really an opener, or {@code -1}.
 *
 * <p>Scans the markup the way the HTML tokenizer does, and that is exactly what makes the
 * truncation in {@link removeScriptTags} safe to apply:</p>
 * <ul>
 *   <li>a comment ({@code <!-- … -->}) is inert, so a {@code <script>} written INSIDE one is not
 *       an opener. An UNTERMINATED comment swallows the rest of the input, so nothing after it
 *       is an opener either;</li>
 *   <li>the same holds for every RCDATA/RAWTEXT element ({@link RAWTEXT_ELEMENTS}) and for
 *       {@code <plaintext>}: their content is text, so we skip to the matching end tag (or, when
 *       there is none, report that the rest of the input holds no opener);</li>
 *   <li>a {@code <} inside an attribute value is literal text, so {@code <div title="<script>">}
 *       is not an opener. Which characters delimit that value is decided by {@link scanTag},
 *       not guessed: a quote inside an UNQUOTED value opens nothing;</li>
 *   <li>a {@code <} not followed by a tag-name character ({@code a < b}) is literal text;</li>
 *   <li>a declaration or processing instruction ({@code <!DOCTYPE html>}, {@code <?xml … ?>}) is
 *       skipped whole.</li>
 * </ul>
 *
 * <p>The RCDATA/RAWTEXT rule is suspended inside a foreign subtree
 * ({@link FOREIGN_CONTENT_ROOTS}), where those names are plain SVG/MathML elements and a nested
 * {@code <script>} really is one. The suspension is coarse on purpose: an HTML integration point
 * ({@code <svg><foreignObject>}) restores HTML parsing rules, and we keep truncating there. That
 * costs content in a rare shape, which is the safe direction to be wrong in.</p>
 *
 * <p>Before this scanner, pass 2 of {@link removeScriptTags} was a bare
 * {@code /<script\b[\s\S]*$/i}, which truncated to end-of-input on the first TEXTUAL occurrence.
 * {@code "<!-- <script> --><div>real content</div>"} therefore lost the entire document although
 * it runs nothing at all, and the loss was silent: the caller got a shorter string, not an error.
 * {@code "<textarea>paste your <script> here</textarea><div>real content</div>"} lost it in
 * exactly the same way until the inert contexts above were covered too.</p>
 */
function findUnterminatedScriptOpener(html: string): number {
  const len = html.length;
  let i = 0;
  // Depth of the enclosing SVG/MathML subtree, 0 when we are in ordinary HTML content.
  let foreignDepth = 0;
  while (i < len) {
    const lt = html.indexOf('<', i);
    if (lt < 0) return -1;

    // Comment or bogus comment: inert until "-->" (or, unterminated, until EOF).
    if (html.startsWith('<!--', lt)) {
      const end = html.indexOf('-->', lt + 4);
      if (end < 0) return -1;
      i = end + 3;
      continue;
    }
    // Declaration (<!DOCTYPE …>) or processing instruction (<?…>): skip to its ">".
    if (html.startsWith('<!', lt) || html.startsWith('<?', lt)) {
      const end = html.indexOf('>', lt + 2);
      if (end < 0) return -1;
      i = end + 1;
      continue;
    }
    // Not a tag start at all ("a < b"): the "<" is literal text.
    if (!/^<\/?[a-zA-Z]/.test(html.slice(lt, lt + 3))) {
      i = lt + 1;
      continue;
    }
    // "<script" followed by a separator, "/", ">" or end of input is the real thing. Checked
    // before the foreign-content bookkeeping: an SVG <script> executes just like an HTML one.
    if (/^<script(?=[\s/>]|$)/i.test(html.slice(lt, lt + 8))) {
      return lt;
    }
    // Tag name, from a bounded slice (the longest name we test for is 9 characters). End tags are
    // named too, so a foreign subtree can be closed again.
    const isEndTag = html.charAt(lt + 1) === '/';
    const nameMatch = /^[a-zA-Z][a-zA-Z0-9]*/.exec(html.slice(lt + (isEndTag ? 2 : 1), lt + 13));
    const tagName = nameMatch ? nameMatch[0].toLowerCase() : '';

    // Any other tag: skip past its ">", the way the tokenizer finds it.
    const { after: afterTag, selfClosing } = scanTag(html, lt);

    if (isEndTag) {
      if (foreignDepth > 0 && FOREIGN_CONTENT_ROOTS.has(tagName)) foreignDepth--;
      i = afterTag;
      continue;
    }
    if (FOREIGN_CONTENT_ROOTS.has(tagName)) {
      // "<svg/>" self-closes in foreign content, so it opens no subtree. "<svg data-x=a/>" does
      // NOT: that solidus belongs to the unquoted attribute value (see scanTag).
      if (!selfClosing) foreignDepth++;
      i = afterTag;
      continue;
    }
    if (foreignDepth === 0) {
      // PLAINTEXT never ends: everything from here to EOF is text.
      if (tagName === 'plaintext') return -1;
      if (RAWTEXT_ELEMENTS.has(tagName)) {
        const end = findRawtextEnd(html, afterTag, tagName);
        if (end < 0) return -1;
        i = end;
        continue;
      }
    }
    i = afterTag;
  }
  return -1;
}

/**
 * Remove script tags from HTML for security.
 *
 * <p>Two passes, because a single balanced-pair regex is bypassable:</p>
 * <ol>
 *   <li>balanced {@code <script>…</script>} pairs (any attributes, any case, multi-line);</li>
 *   <li>any REMAINING {@code <script} opener with no closing tag. An HTML parser treats
 *       everything after an unterminated opener as script source until EOF, so we drop the
 *       rest of the input. Without this pass {@code <script>alert(1)} survived untouched
 *       (pass 1 needs the {@code </script>} to match) and executed in every surface that
 *       relies on this function to neutralise publisher HTML.</li>
 * </ol>
 *
 * <p>Pass 2 truncates, so it must only fire on a REAL opener: it locates one with
 * {@link findUnterminatedScriptOpener}, which honours comments, quoted attribute values and
 * declarations, rather than on the first textual {@code <script} anywhere in the input.</p>
 */
export function removeScriptTags(html: string): string {
  if (!html) return '';
  const withoutPairs = html.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '');
  // Unterminated opener: everything from it to the end of the input is script source.
  const opener = findUnterminatedScriptOpener(withoutPairs);
  return opener < 0 ? withoutPairs : withoutPairs.slice(0, opener);
}

/**
 * Matches an inline event-handler attribute (`onclick=…`, `onerror=…`, …).
 *
 * <p>The separator class is {@code [\s/]} and not {@code \s}: HTML allows a solidus between
 * attributes, so {@code <img/onerror=alert(1)>} carries a live handler while carrying no
 * whitespace at all, and the previous {@code \s+} form left it intact. {@code \s} already
 * covers the newline-separated variant ({@code <img\nonerror=…>}), which stays covered here.</p>
 */
const INLINE_EVENT_HANDLER_PATTERN = /[\s/]+on\w+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]*)/gi;

/**
 * Sanitize publisher HTML so that NO publisher JavaScript can run in the surface that renders it.
 *
 * <p>Two stages, in this order:</p>
 * <ol>
 *   <li><b>Textual pre-pass</b> ({@link removeScriptTags} + {@link INLINE_EVENT_HANDLER_PATTERN}).
 *       DOM-free, so it is also the WHOLE sanitizer when no DOM exists: the callers are React
 *       client components, and Next.js renders those on the server too, where {@code DOMParser}
 *       is undefined. Reaching for it there would throw a {@code ReferenceError} mid-render
 *       instead of sanitizing.</li>
 *   <li><b>DOMPurify allow-list pass</b> ({@link sanitizeWithDom}), whenever a DOM is available,
 *       configured with this module's element allow-list and URL-scheme rules. The regex stage
 *       alone let {@code <a href="javascript:...">}, {@code <iframe src="javascript:...">} and
 *       {@code <iframe srcdoc>} through, and the hand-rolled DOM walker that followed it was
 *       mutation-XSS-able (a {@code <math><style>} whose text re-parsed into a live
 *       {@code <img onerror>}). DOMPurify is built for exactly that parse/serialize round trip.</li>
 * </ol>
 *
 * <p>The surfaces that call this also render into a sandboxed, opaque-origin iframe, so the
 * sanitizer is one layer of two rather than the only one.</p>
 */
export function sanitizeHtml(html: string): string {
  if (!html) return '';
  let sanitized = removeScriptTags(html);
  // Remove all inline event handlers (on* attributes)
  sanitized = sanitized.replace(INLINE_EVENT_HANDLER_PATTERN, '');
  const instance = getPurifier();
  if (!instance) return sanitized;
  return sanitizeWithDom(sanitized, instance);
}

/**
 * URL schemes a sanitized navigation attribute ({@code href}, {@code action}, ...) may name.
 * Everything else with an explicit scheme ({@code javascript:}, {@code vbscript:}, {@code data:},
 * ...) is removed by {@link sanitizeWithDom}.
 */
const OPENABLE_NAVIGATION_PROTOCOLS = new Set(['https:', 'http:', 'mailto:', 'tel:']);

// =============================================================================
// DOM allow-list sanitizer (see sanitizeHtml)
// =============================================================================

/**
 * Elements a sanitized publisher document may keep.
 *
 * <p>Allow-list, not deny-list: everything absent is removed. A deny-list has to stay ahead of
 * every element that can host a URL or a script, and it silently loses that race whenever the
 * HTML surface grows.</p>
 */
const ALLOWED_ELEMENTS = new Set([
  // Sections and grouping
  'div', 'span', 'p', 'section', 'article', 'aside', 'header', 'footer', 'main', 'nav',
  'figure', 'figcaption', 'hr', 'br', 'wbr', 'address', 'blockquote', 'pre', 'details',
  'summary', 'dialog', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  // Text level
  'a', 'abbr', 'b', 'bdi', 'bdo', 'cite', 'code', 'data', 'dfn', 'em', 'i', 'kbd', 'mark',
  'q', 'rp', 'rt', 'ruby', 's', 'samp', 'small', 'strong', 'sub', 'sup', 'time', 'u', 'var',
  'del', 'ins',
  // Lists
  'ul', 'ol', 'li', 'dl', 'dt', 'dd', 'menu',
  // Tables
  'table', 'caption', 'colgroup', 'col', 'thead', 'tbody', 'tfoot', 'tr', 'td', 'th',
  // Forms
  'form', 'fieldset', 'legend', 'label', 'input', 'button', 'select', 'option', 'optgroup',
  'textarea', 'datalist', 'output', 'progress', 'meter',
  // Media
  'img', 'picture', 'source', 'video', 'audio', 'track', 'canvas', 'map', 'area',
  // Document head (a complete publisher document keeps its own styling and metadata)
  'title', 'style', 'meta', 'link',
  // SVG (lowercased: tagName is compared lowercased, so camelCase SVG names appear folded)
  'svg', 'g', 'defs', 'symbol', 'use', 'path', 'circle', 'ellipse', 'line', 'polyline',
  'polygon', 'rect', 'text', 'tspan', 'textpath', 'marker', 'mask', 'clippath', 'pattern',
  'lineargradient', 'radialgradient', 'stop', 'filter', 'desc',
]);

/**
 * Elements removed WITH their subtree.
 *
 * <p>Everything else that is merely not allow-listed is unwrapped instead (the element goes, its
 * children stay), so an unknown or custom element costs its tag and nothing else. These ones
 * cannot be unwrapped: their content is script source, a nested document, or plugin data, and
 * promoting it to markup or to visible text is worse than dropping it.</p>
 */
const DROPPED_SUBTREE_ELEMENTS = new Set([
  'script', 'iframe', 'object', 'embed', 'applet', 'frame', 'frameset', 'noembed', 'noframes',
  'noscript', 'template', 'base', 'xmp', 'plaintext', 'portal',
]);

/**
 * Attributes whose value is a URL the user NAVIGATES to. They accept only
 * {@link OPENABLE_NAVIGATION_PROTOCOLS} (or no explicit scheme at all).
 */
const NAVIGATION_URL_ATTRIBUTES = new Set(['href', 'action', 'formaction', 'ping', 'xlink:href']);

/**
 * Attributes whose value is a URL the browser FETCHES as a subresource. Same schemes as
 * navigation, plus the inert inline forms an {@code <img>}/{@code <video>} needs: run mode
 * rewrites every FileRef to a {@code data:} URI, and publishers inline small images the same way.
 */
const RESOURCE_URL_ATTRIBUTES = new Set(['src', 'poster', 'background', 'data', 'srcset']);

/**
 * Inline {@code data:} payloads a resource attribute may carry: image (except SVG, which is a
 * document and can carry script), video, audio, font. {@code data:text/html} and
 * {@code data:image/svg+xml} are documents, so they stay out.
 */
const INERT_DATA_MEDIA_TYPE = /^data:(?:image\/(?!svg)[a-z0-9.+-]+|video\/[a-z0-9.+-]+|audio\/[a-z0-9.+-]+|font\/[a-z0-9.+-]+)[;,]/i;

/** CSS that a browser could once turn into script. Cheap to reject, and never legitimate. */
const UNSAFE_CSS_PATTERN = /(?:javascript|vbscript)\s*:|expression\s*\(/i;

/**
 * Whether a URL-valued attribute is safe to keep.
 *
 * <p>A value with NO explicit scheme (a relative path, {@code #anchor}, {@code //host}) can never
 * be {@code javascript:}, so it passes: dropping those would break in-document anchors and every
 * relative asset. A value WITH an explicit scheme must name an allow-listed one.</p>
 *
 * <p>Leading control characters and interior whitespace are stripped BEFORE the scheme is read,
 * because the HTML parser ignores them too: {@code "jav\tascript:alert(1)"} is a
 * {@code javascript:} URL to the browser and must be one to us.</p>
 *
 * @param rawUrl the attribute value, already entity-decoded by the parser
 * @param allowInlineMedia true for a subresource attribute, which may also carry an inert
 *                         {@code data:}/{@code blob:} payload
 */
function isSafeAttributeUrl(rawUrl: string, allowInlineMedia: boolean): boolean {
  const collapsed = rawUrl.replace(/[\u0000-\u0020]/g, '');
  if (!collapsed) return true;
  const scheme = /^([a-z][a-z0-9+.-]*):/i.exec(collapsed);
  if (!scheme) return true;
  const protocol = `${scheme[1].toLowerCase()}:`;
  if (OPENABLE_NAVIGATION_PROTOCOLS.has(protocol)) return true;
  if (!allowInlineMedia) return false;
  if (protocol === 'blob:') return true;
  return protocol === 'data:' && INERT_DATA_MEDIA_TYPE.test(collapsed);
}

/**
 * Attributes DOMPurify's default allow-list lacks but legitimate publisher markup uses. Everything
 * here is inert: none is an event handler, none takes a URL that is navigated or fetched.
 */
const EXTRA_ALLOWED_ATTRIBUTES = [
  'target', 'content', 'charset', 'form', 'formmethod', 'formnovalidate', 'formtarget',
  'contenteditable', 'autofocus', 'referrerpolicy', 'xmlns:xlink',
];

type Purifier = ReturnType<typeof DOMPurify>;
let purifier: Purifier | null = null;

/**
 * One DOMPurify instance with this module's policy as hooks. Created lazily (and never at import)
 * because the module is also evaluated during server rendering, where there is no window.
 *
 * <p>DOMPurify does the parsing and the tree walk, which is exactly where the hand-rolled walker
 * failed: it handles namespace confusion (a {@code <style>} or {@code <title>} under
 * {@code <math>}/{@code <svg>} whose text re-parses into live markup), DOM clobbering and the
 * other mutation-XSS classes. The hooks only ADD this module's own rules on top of its defaults,
 * so every value DOMPurify would reject is still rejected.</p>
 */
function getPurifier(): Purifier | null {
  if (purifier) return purifier;
  if (typeof window === 'undefined' || typeof DOMParser === 'undefined') return null;
  const instance = DOMPurify(window);
  if (!instance.isSupported) return null;

  // A <meta http-equiv="refresh"> redirects the document, and its destination hides inside
  // `content` where no scheme check reaches it: drop the element.
  instance.addHook('uponSanitizeElement', (node, data) => {
    if (data.tagName === 'meta' && (node as Element).hasAttribute?.('http-equiv')) {
      node.parentNode?.removeChild(node);
    }
  });

  instance.addHook('uponSanitizeAttribute', (_node, data) => {
    const name = data.attrName;
    if (name === 'style') {
      if (UNSAFE_CSS_PATTERN.test(data.attrValue)) data.keepAttr = false;
      return;
    }
    const isResource = RESOURCE_URL_ATTRIBUTES.has(name);
    if (!isResource && !NAVIGATION_URL_ATTRIBUTES.has(name)) return;
    // srcset is a comma-separated candidate list; each candidate's URL is its first token.
    const safe = name === 'srcset'
      ? data.attrValue.split(',').every(candidate => isSafeAttributeUrl(candidate.trim().split(/\s+/)[0] || '', true))
      : isSafeAttributeUrl(data.attrValue, isResource);
    if (!safe) {
      data.keepAttr = false;
      return;
    }
    // DOMPurify's own URI allow-list has no blob:, which run mode and uploads use for media.
    // isSafeAttributeUrl already accepted it, for a SUBRESOURCE attribute only.
    if (isResource && /^\s*blob:/i.test(data.attrValue)) data.forceKeepAttr = true;
  });

  purifier = instance;
  return purifier;
}

/**
 * DOM allow-list pass, PRESERVING the input's shape: a complete document comes back as a
 * complete document (downstream, {@link isCompleteHtml} decides whether the platform wraps it
 * and injects base CSS), a fragment as a fragment with its leading {@code <style>} kept
 * (FORCE_BODY, otherwise the parser hoists it into {@code <head>} and it is lost).
 */
function sanitizeWithDom(html: string, instance: Purifier): string {
  const complete = isCompleteHtml(html);
  const allowedTags = [...ALLOWED_ELEMENTS];
  if (complete) allowedTags.push('html', 'head', 'body', '!doctype');
  const result = instance.sanitize(html, {
    ALLOWED_TAGS: allowedTags,
    ADD_ATTR: EXTRA_ALLOWED_ATTRIBUTES,
    // Script source, nested documents and plugin data go WITH their content (not unwrapped).
    ADD_FORBID_CONTENTS: [...DROPPED_SUBTREE_ELEMENTS],
    WHOLE_DOCUMENT: complete,
    FORCE_BODY: !complete,
    RETURN_TRUSTED_TYPE: false,
  });
  return String(result);
}

/**
 * Check if HTML is already a complete document
 */
export function isCompleteHtml(html: string): boolean {
  if (!html) return false;
  const trimmed = html.trim().toLowerCase();
  return trimmed.startsWith('<!doctype') || trimmed.startsWith('<html');
}

/**
 * Insert `content` immediately before the first occurrence of `marker` in
 * `haystack`. If `marker` is absent or empty, returns `haystack` unchanged.
 *
 * MUST be used instead of `haystack.replace(marker, content + marker)` whenever
 * `content` may contain user-controlled text. JS `String.prototype.replace`
 * interprets `$&`, `$'`, `` $` ``, `$$`, `$1`-`$9` in the replacement string -
 * a user JS template containing `'$'` would get the post-match substring
 * spliced into the middle of a string literal, corrupting the injected source.
 *
 * This was the root cause of an iframe SyntaxError when a user JS contained
 * `var curMap = { USD: '$' }` - the `$'` expanded to `\n</html>` (whatever
 * followed `</body>` in the host document), opening a multi-line string and
 * killing the parser. See interfaceHtmlUtils.test.ts → `injectBefore`.
 *
 * Empty-marker contract: an empty `marker` returns `haystack` unchanged
 * (NOT a silent prepend at index 0). `indexOf('')` would return 0 natively,
 * which would prepend - almost certainly a caller bug, so we no-op instead.
 */
export function injectBefore(haystack: string, marker: string, content: string): string {
  if (!marker) return haystack;
  const idx = haystack.indexOf(marker);
  if (idx < 0) return haystack;
  return haystack.slice(0, idx) + content + haystack.slice(idx);
}

/**
 * Decide what a click on an anchor inside a sandboxed interface iframe should do.
 *
 * <p>Pure and fully self-contained (references nothing at module scope) so its source can be
 * embedded verbatim into {@link NAVIGATION_GATE_SCRIPT} via {@code Function.prototype.toString()}
 * AND unit-tested directly - the test then drives the exact logic that runs inside the iframe,
 * with zero drift.</p>
 *
 * <ul>
 *   <li>{@code 'block'} - placeholder / no-op anchor ({@code href="#"}, {@code ""}, {@code null},
 *       or {@code javascript:...}), OR a scheme-less RELATIVE path ({@code page2.html}, {@code /foo}):
 *       {@code preventDefault()} and do nothing. This is the original "anchor neutralizer" behaviour.
 *       The iframe loads via {@code srcDoc} (effective URL {@code about:srcdoc}), so an unresolved
 *       relative href base-resolves against the EMBEDDING page - clicking one would otherwise navigate
 *       the iframe to {@code <parent-app-url>/...} and load the host app inside the iframe. We must NOT
 *       gate these (the resolved URL is a meaningless host-app URL, never what the author intended).</li>
 *   <li>{@code 'allow'} - genuine in-document hash link ({@code href="#section"}) or an anchor with a
 *       non-navigable explicit scheme we deliberately leave alone ({@code blob:}, {@code data:}, custom
 *       app schemes): let the browser handle it.</li>
 *   <li>{@code 'gate'} - a real external target: the RAW href explicitly carries a navigable scheme
 *       ({@code http}, {@code https}, {@code mailto}, {@code tel}) or is protocol-relative ({@code //host}).
 *       {@code preventDefault()} and ask the PARENT to confirm before opening it in a new tab. The sandbox
 *       (no {@code allow-popups} / {@code allow-top-navigation}) silently swallows such navigations
 *       otherwise, so the link appears dead; the parent {@code InterfaceIframe} surfaces a confirmation
 *       modal instead.</li>
 * </ul>
 *
 * <p>The decision keys off the RAW href's scheme, not the RESOLVED protocol: in an
 * {@code about:srcdoc} document every relative href resolves to an {@code http(s):} URL on the
 * embedding origin, so {@code new URL(anchor.href).protocol} cannot tell a real external link from a
 * relative path. The resolved href is used only to compute the URL to OPEN once we have decided to gate.</p>
 *
 * @param rawHref the anchor's literal {@code href} attribute (unresolved, may be null)
 * @param resolvedHref the anchor's resolved absolute URL ({@code HTMLAnchorElement.href})
 */
export function classifyAnchorNavigation(
  rawHref: string | null | undefined,
  resolvedHref: string
): { action: 'allow' | 'block' | 'gate'; url: string | null } {
  if (rawHref == null || rawHref === '' || rawHref === '#' || /^\s*javascript:/i.test(rawHref)) {
    return { action: 'block', url: null };
  }
  const href = rawHref.trim();
  // Genuine in-document hash link: let the browser scroll.
  if (href.charAt(0) === '#') {
    return { action: 'allow', url: null };
  }
  // Real external target only when the RAW href carries a navigable scheme (or is protocol-relative).
  const isExternalScheme = /^(?:https?|mailto|tel):/i.test(href);
  const isProtocolRelative = href.charAt(0) === '/' && href.charAt(1) === '/';
  if (isExternalScheme || isProtocolRelative) {
    let url = resolvedHref;
    try {
      url = new URL(resolvedHref).href;
    } catch (err) {
      url = href;
    }
    return { action: 'gate', url: url };
  }
  // Any OTHER explicit scheme (blob:, data:, custom app links): leave to the browser. A scheme-less
  // relative path: block it - in srcdoc it would navigate the iframe to the embedding app origin.
  const hasExplicitScheme = /^[a-z][a-z0-9+.-]*:/i.test(href);
  return { action: hasExplicitScheme ? 'allow' : 'block', url: null };
}

/**
 * Whether the parent frame may open {@code url} in a new tab on an interface's request (LC-076).
 *
 * <p>MUST be evaluated on the PARENT side. {@link classifyAnchorNavigation} runs INSIDE the
 * sandboxed iframe, so a hostile interface skips it entirely by posting a
 * {@code navigation-request} of its own; the parent is not sandboxed, so a {@code javascript:}
 * URL handed to {@code window.open()} there would execute on the app origin. Only absolute
 * {@code https:}/{@code http:}/{@code mailto:}/{@code tel:} URLs pass (the set the in-frame classifier
 * gates; none of them executes script); everything else ({@code javascript:}, {@code data:},
 * {@code blob:}, relative paths, unparseable strings) is rejected.</p>
 */
export function isOpenableNavigationUrl(url: unknown): boolean {
  if (typeof url !== 'string') return false;
  const trimmed = url.trim();
  if (!trimmed) return false;
  try {
    return OPENABLE_NAVIGATION_PROTOCOLS.has(new URL(trimmed).protocol.toLowerCase());
  } catch (err) {
    return false;
  }
}

/**
 * Resolve a {@code window.open()} argument to the absolute URL the navigation gate should confirm.
 * Returns {@code ''} when the argument is absent or unparseable (the gate then does nothing). Pure and
 * self-contained so its source is embedded verbatim into {@link NAVIGATION_GATE_SCRIPT} and unit-tested
 * directly.
 *
 * @param rawUrl the first argument passed to {@code window.open} (may be relative, absolute, null)
 * @param baseUri the iframe document's {@code document.baseURI} (resolution base)
 */
export function resolveNavigableUrl(rawUrl: string | null | undefined, baseUri: string): string {
  if (!rawUrl) return '';
  try {
    return new URL(rawUrl, baseUri).href;
  } catch (err) {
    return '';
  }
}

/**
 * Whether a {@code window.open()} call should be confirmed (gated) given the document's
 * {@code navigator.userActivation}. Only popups opened under transient user activation are gated; a
 * no-gesture {@code window.open()} on load stays a silent no-op so a page cannot pop the confirmation
 * modal on its own (e.g. across a marketplace browse). Fails open ({@code true}) when
 * {@code userActivation} is unavailable (older engines). Pure and self-contained so its source is
 * embedded verbatim into {@link NAVIGATION_GATE_SCRIPT} and unit-tested directly.
 *
 * @param userActivation the document's {@code navigator.userActivation} (may be undefined)
 */
export function shouldGateWindowOpen(userActivation: { isActive?: boolean } | null | undefined): boolean {
  if (!userActivation) return true;
  return userActivation.isActive === true;
}

/**
 * Navigation gate injected into every interface iframe. Supersedes the old "anchor
 * neutralizer": besides neutralizing placeholder anchors, it intercepts clicks that would
 * navigate to a real external URL (and {@code window.open} calls from publisher JS) and, instead
 * of letting the sandbox silently swallow them, posts a {@code navigation-request} to the parent
 * so the embedding React app can ask the user for confirmation before opening the link in a new
 * tab. See {@link classifyAnchorNavigation} for the per-anchor decision and {@code InterfaceIframe}
 * for the parent-side confirmation modal ({@code OpenLinkConfirmModal}).
 *
 * <p>Capture phase so we still run when a publisher-supplied inline handler calls
 * {@code stopPropagation()} before the bubble phase reaches us.</p>
 */
const NAVIGATION_GATE_SCRIPT = `
<script>
(function() {
  var classifyAnchorNavigation = ${classifyAnchorNavigation.toString()};
  var resolveNavigableUrl = ${resolveNavigableUrl.toString()};
  var shouldGateWindowOpen = ${shouldGateWindowOpen.toString()};
  function requestNavigation(url) {
    if (!url) return;
    try { window.parent.postMessage({ type: 'navigation-request', url: url }, '*'); } catch (err) {}
  }
  document.addEventListener('click', function(e) {
    var anchor = e.target && e.target.closest && e.target.closest('a');
    if (!anchor) return;
    var decision = classifyAnchorNavigation(anchor.getAttribute('href'), anchor.href);
    if (decision.action === 'allow') return;
    e.preventDefault();
    if (decision.action === 'gate') { requestNavigation(decision.url); }
  }, true);
  try {
    window.open = function(u) {
      // Only confirm popups opened in response to a real user gesture. A no-gesture
      // window.open() on load stays a silent no-op (its pre-sandbox-gate behaviour), so a
      // page cannot pop the confirmation modal on its own across a marketplace browse.
      var active = true;
      try { active = shouldGateWindowOpen(navigator.userActivation); } catch (err) { active = true; }
      if (active) { requestNavigation(resolveNavigableUrl(u, document.baseURI)); }
      return null;
    };
  } catch (err) {}
})();
</script>`;

/**
 * Script that replaces broken images with a transparent pixel to preserve layout.
 */
const BROKEN_IMG_SCRIPT = `
<script>
(function() {
  var TRANSPARENT = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
  function fixOnError(img) {
    img.addEventListener('error', function() { this.src = TRANSPARENT; }, { once: true });
  }
  document.querySelectorAll('img').forEach(fixOnError);
  new MutationObserver(function(mutations) {
    mutations.forEach(function(m) {
      m.addedNodes.forEach(function(n) {
        if (n.nodeName === 'IMG') fixOnError(n);
        else if (n.querySelectorAll) n.querySelectorAll('img').forEach(fixOnError);
      });
    });
  }).observe(document.body, { childList: true, subtree: true });
})();
</script>`;

/**
 * Auto-fit script that scales content to fit within the viewport
 */
const AUTO_FIT_SCRIPT = `
<script>
(function() {
  function fitContent() {
    var wrapper = document.getElementById('auto-fit-wrapper');
    if (!wrapper || !wrapper.firstElementChild) return;

    var content = wrapper.firstElementChild;
    var viewportWidth = window.innerWidth - 16;
    var viewportHeight = window.innerHeight - 16;

    // Reset transform to measure actual size
    wrapper.style.transform = 'none';
    var contentWidth = content.scrollWidth || content.offsetWidth;
    var contentHeight = content.scrollHeight || content.offsetHeight;

    if (contentWidth === 0 || contentHeight === 0) return;

    // Calculate scale to fit both dimensions
    var scaleX = viewportWidth / contentWidth;
    var scaleY = viewportHeight / contentHeight;
    var scale = Math.min(scaleX, scaleY, 1);

    if (scale < 1) {
      wrapper.style.transform = 'scale(' + scale + ')';
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', fitContent);
  } else {
    fitContent();
  }
  setTimeout(fitContent, 100);
  setTimeout(fitContent, 300);
})();
</script>`;

/**
 * Generate a bridge script that intercepts DOM events (submit, click) on elements
 * with data-action attributes and routes them via postMessage to the parent React app.
 * Optionally pre-fills form fields with previous trigger data.
 *
 * @param actionMapping - Map of action names to trigger refs (e.g. { "submit": "trigger:my_form:submit" })
 * @param triggerData - Previous trigger submission data keyed by trigger ref (e.g. { "trigger:search": { field: "value" } })
 */
export function generateBridgeScript(
  actionMapping: Record<string, string>,
  triggerData?: Record<string, Record<string, unknown>>
): string {
  if (!actionMapping || Object.keys(actionMapping).length === 0) return '';

  // Fix: escape </ to prevent </script> breakout from JSON values
  const mappingJson = JSON.stringify(actionMapping).replace(/<\//g, '<\\/');
  const triggerDataJson = (triggerData ? JSON.stringify(triggerData) : 'null').replace(/<\//g, '<\\/');


  return `
<script>
(function() {
  var mapping = ${mappingJson};
  var triggerData = ${triggerDataJson};
  var _targetOrigin = '*';
  try { if (document.referrer) { _targetOrigin = new URL(document.referrer).origin; } } catch(e) {}

  // Find elements by action name: try as CSS selector first, then data-action attribute
  // Action names may be wrapped in quotes: '#search-form' or "#search-form" → #search-form
  function findElements(actionName) {
    var stripped = actionName.replace(/^["']|["']$/g, '');
    // 1. Try stripped value as CSS selector (handles #id, .class, tag, etc.)
    try {
      var bySelector = document.querySelectorAll(stripped);
      if (bySelector.length > 0) return bySelector;
    } catch(e) { /* invalid CSS selector, ignore */ }
    // 2. Fallback: match data-action attribute (try both raw and stripped)
    var byAttr = document.querySelectorAll('[data-action="' + stripped + '"]');
    if (byAttr.length > 0) return byAttr;
    if (stripped !== actionName) {
      byAttr = document.querySelectorAll('[data-action="' + actionName + '"]');
    }
    return byAttr;
  }

  // Collect form data from closest form of an element (non-file fields only)
  function collectFormData(el) {
    var data = {};
    var form = el.closest('form');
    if (!form) form = (el.tagName && el.tagName.toLowerCase() === 'form') ? el : null;
    if (form && form.tagName && form.tagName.toLowerCase() === 'form') {
      var formData = new FormData(form);
      formData.forEach(function(value, key) {
        // Skip File objects - they will be handled separately via file upload delegation
        if (value instanceof File && value.size > 0) return;
        data[key] = value;
      });
    }
    return data;
  }

  // Collect file inputs from the closest form
  function collectFileInputs(el) {
    var files = [];
    var form = el.closest('form');
    if (!form) form = (el.tagName && el.tagName.toLowerCase() === 'form') ? el : null;
    if (form && form.tagName && form.tagName.toLowerCase() === 'form') {
      var fileInputs = form.querySelectorAll('input[type="file"]');
      fileInputs.forEach(function(input) {
        if (input.files && input.files.length > 0) {
          for (var i = 0; i < input.files.length; i++) {
            files.push({ fieldName: input.name || 'file', file: input.files[i] });
          }
        }
      });
    }
    return files;
  }

  // Upload files via parent delegation and return a promise that resolves with FileRef map
  var _fileUploadCounter = 0;
  function uploadFilesViaParent(fileEntries) {
    if (fileEntries.length === 0) return Promise.resolve({});
    var results = {};
    var promises = fileEntries.map(function(entry) {
      return new Promise(function(resolve) {
        var uploadId = 'upload_' + (++_fileUploadCounter);
        var reader = new FileReader();
        reader.onload = function() {
          // Listen for response from parent
          function onResponse(event) {
            if (event.data && event.data.type === 'file-upload-response' && event.data.uploadId === uploadId) {
              window.removeEventListener('message', onResponse);
              if (event.data.fileRef) {
                results[entry.fieldName] = event.data.fileRef;
              }
              resolve();
            }
          }
          window.addEventListener('message', onResponse);
          // Send file data to parent for upload
          window.parent.postMessage({
            type: 'file-upload-request',
            uploadId: uploadId,
            fieldName: entry.fieldName,
            fileName: entry.file.name,
            mimeType: entry.file.type || 'application/octet-stream',
            fileData: reader.result
          }, _targetOrigin);
        };
        reader.onerror = function() { resolve(); };
        reader.readAsArrayBuffer(entry.file);
      });
    });
    return Promise.all(promises).then(function() { return results; });
  }

  // Collect form data including file uploads (async)
  function collectFormDataWithFiles(el, submitter) {
    var data = collectFormData(el);
    if (submitter && submitter.name) {
      data[submitter.name] = submitter.value || '';
    }
    var fileEntries = collectFileInputs(el);
    if (fileEntries.length === 0) return Promise.resolve(data);
    return uploadFilesViaParent(fileEntries).then(function(fileRefs) {
      Object.keys(fileRefs).forEach(function(key) { data[key] = fileRefs[key]; });
      return data;
    });
  }

  // Pre-fill form fields with previous trigger data
  function prefillForms() {
    console.log('[BridgePrefill] start triggerData=', triggerData ? Object.keys(triggerData) : 'NULL', 'mapping=', mapping);
    if (!triggerData) return;
    Object.keys(mapping).forEach(function(actionName) {
      var triggerRef = mapping[actionName];
      // Remove action type suffix: "trigger:name:submit" -> "trigger:name"
      var parts = triggerRef.split(':');
      var triggerKey = parts.length >= 3 ? parts.slice(0, -1).join(':') : triggerRef;
      var data = triggerData[triggerKey];
      console.log('[BridgePrefill] action=' + actionName + ' triggerKey=' + triggerKey + ' data=', data ? Object.keys(data) : 'MISSING');
      if (!data) return;
      // Find elements (CSS selector or data-action) and pre-fill their closest form
      var elements = findElements(actionName);
      console.log('[BridgePrefill] elements found=' + elements.length);
      elements.forEach(function(el) {
        var form = el.closest('form');
        if (!form) return;
        Object.keys(data).forEach(function(fieldName) {
          var value = data[fieldName];
          if (value === null || value === undefined || typeof value === 'object') return;
          var input = form.querySelector('[name="' + fieldName + '"]');
          if (!input) { console.log('[BridgePrefill] NO_INPUT for ' + fieldName); return; }
          var tag = input.tagName.toLowerCase();
          var stringValue = String(value);
          // Length only, never the value: prefill carries what the end user typed (LC-091).
          console.log('[BridgePrefill] SET ' + tag + '[name=' + fieldName + '] valueLength=' + stringValue.length);
          if (tag === 'textarea') {
            // textareas store their initial value in textContent (the HTML
            // between the tags). Setting .value alone displays the prefill
            // BUT leaves the HTML representation empty AND a React hydration
            // / iframe srcDoc re-render reverts to the empty textContent.
            // Set both so the HTML and the live value match.
            input.textContent = stringValue;
            input.value = stringValue;
          } else if (tag === 'select') {
            input.value = stringValue;
            // Mirror onto the matching <option selected> attribute so the HTML
            // view reflects the choice. Strip any pre-existing selected
            // markers first so we don't leave stale ones behind.
            var opts = input.querySelectorAll('option');
            for (var oi = 0; oi < opts.length; oi++) {
              if (opts[oi].value === stringValue) opts[oi].setAttribute('selected', '');
              else opts[oi].removeAttribute('selected');
            }
          } else if (tag === 'input') {
            input.value = stringValue;
            input.setAttribute('value', stringValue);
          }
        });
      });
    });
  }

  // Set up event listeners for action mapping (CSS selector or data-action)
  Object.keys(mapping).forEach(function(actionName) {
    var triggerRef = mapping[actionName];
    var parts = triggerRef.split(':');
    var actionType = parts.length >= 3 ? parts[parts.length - 1] : 'click';
    var elements = findElements(actionName);
    console.log('[BridgeScript] actionName="' + actionName + '" triggerRef="' + triggerRef + '" elements found:', elements.length);
    elements.forEach(function(el) {
      var tag = el.tagName.toLowerCase();
      // Pagination controls - handled by parent, not a workflow trigger
      if (triggerRef.indexOf('__pagination:') === 0) {
        var direction = triggerRef.replace('__pagination:', '');
        el.addEventListener('click', function(e) {
          e.preventDefault();
          window.parent.postMessage({ type: 'pagination', direction: direction }, _targetOrigin);
        });
        return;
      }
      // Variable pagination - __varpage:variableName:pageNumber
      if (triggerRef.indexOf('__varpage:') === 0) {
        var vparts = triggerRef.replace('__varpage:', '').split(':');
        var varName = vparts[0];
        var varPage = parseInt(vparts[1] || '0', 10);
        el.addEventListener('click', function(e) {
          e.preventDefault();
          window.parent.postMessage({ type: 'variable-pagination', variable: varName, page: varPage }, _targetOrigin);
        });
        return;
      }
      // __continue - resolve the interface signal and continue the workflow
      if (triggerRef === '__continue') {
        console.log('[BridgeScript] __continue handler attached to:', tag, el.id || el.className, 'selector:', actionName);
        var evtType = (tag === 'form') ? 'submit' : 'click';
        el.addEventListener(evtType, function(e) {
          e.preventDefault();
          console.log('[BridgeScript] __continue ' + evtType + ' fired for:', actionName);
          collectFormDataWithFiles(e.target || el, e.submitter).then(function(data) {
            // Field names only, never the submitted values (LC-091).
            console.log('[BridgeScript] Sending postMessage continue:', { actionKey: actionName, fields: Object.keys(data) });
            window.parent.postMessage({ type: 'continue', actionKey: actionName, data: data }, _targetOrigin);
          });
        });
        return;
      }
      if (actionType === 'submit') {
        el.addEventListener('submit', function(e) {
          e.preventDefault();
          collectFormDataWithFiles(e.target, e.submitter).then(function(data) {
            window.parent.postMessage({ type: 'action-trigger', triggerRef: triggerRef, actionName: actionName, data: data }, _targetOrigin);
          });
        });
      } else if (actionType === 'message') {
        if (tag === 'form') {
          el.addEventListener('submit', function(e) {
            e.preventDefault();
            collectFormDataWithFiles(e.target, e.submitter).then(function(data) {
              window.parent.postMessage({ type: 'action-trigger', triggerRef: triggerRef, actionName: actionName, data: data }, _targetOrigin);
              e.target.reset();
            });
          });
        } else if (tag === 'input' || tag === 'textarea') {
          el.addEventListener('keydown', function(e) {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              var val = el.value.trim();
              if (!val) return;
              window.parent.postMessage({ type: 'action-trigger', triggerRef: triggerRef, actionName: actionName, data: { message: val } }, _targetOrigin);
              el.value = '';
            }
          });
        }
      } else {
        el.addEventListener('click', function(e) {
          e.preventDefault();
          var data = collectFormData(el);
          window.parent.postMessage({ type: 'action-trigger', triggerRef: triggerRef, actionName: actionName, data: data }, _targetOrigin);
        });
      }
    });
  });

  // Pre-fill after DOM is ready and event listeners are set up
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', prefillForms);
  } else {
    prefillForms();
  }
})();
</script>`;
}

/**
 * Auto-fit CSS styles - FRAGMENT templates only (like BASE_IFRAME_CSS and
 * SAFE_CENTERING_CSS). A complete document never receives these: the
 * overflow:hidden + flex centering would silently clip/re-center the author's
 * page in canvas previews while the renderer and application panel inject
 * nothing, breaking WYSIWYG parity between surfaces.
 */
const AUTO_FIT_CSS = `
html, body {
  width: 100%;
  height: 100%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
#auto-fit-wrapper {
  transform-origin: center center;
}
`;

/**
 * Height reporter script - sends content dimensions to parent via postMessage.
 * Injected into every iframe so the parent can measure height without
 * needing allow-same-origin (no contentDocument access required).
 */
const HEIGHT_REPORTER_SCRIPT = `
<script>
(function() {
  var _t;
  function reportSize() {
    clearTimeout(_t);
    _t = setTimeout(function() {
      var h = document.documentElement.scrollHeight || document.body.scrollHeight;
      var w = document.documentElement.scrollWidth || document.body.scrollWidth;
      if (h > 0) {
        window.parent.postMessage({ type: '__iframe_size', width: w, height: h }, '*');
      }
    }, 50);
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', reportSize);
  } else {
    reportSize();
  }
  window.addEventListener('load', reportSize);
  window.addEventListener('resize', reportSize);
  new MutationObserver(reportSize)
    .observe(document.documentElement, { childList: true, subtree: true, attributes: true });
})();
</script>`;

/**
 * Media-audio controller - injected into every interface iframe.
 *
 * Two jobs, both of which have to happen INSIDE the frame: the host cannot mute
 * a sandboxed cross-origin document, and it cannot see whether the document even
 * has a media element.
 *
 * 1. **Tells the host whether this interface can make noise**, by posting
 *    `__iframe_audio` whenever the answer changes. Any `<audio>`/`<video>` counts:
 *    a video's audio track cannot be inspected reliably before playback, so
 *    "there is a media element" is the honest signal, and it is the one that
 *    decides whether the host shows a sound control at all.
 * 2. **Applies the host's mute preference.** `window.__LC_MEDIA_MUTED__` sets the
 *    initial value (injected only when the embedder manages audio - everywhere
 *    else this script only reports and changes nothing), and the host flips it at
 *    runtime with `__iframe_set_muted`, which needs no reload.
 *
 * Muting rather than pausing: a muted `<video>` keeps animating, which is the
 * point of a preview, and browsers only autoplay video that IS muted - so this
 * makes a preview play where an unmuted one would have been blocked outright.
 *
 * A MutationObserver covers media added later by the interface's own JS, and the
 * `loadstart` capture listener covers a `src` swapped on an element that already
 * existed - neither shows up as a childList mutation.
 *
 * Not covered: an interface synthesising sound through the Web Audio API. There
 * is no element to mute, so the host will not offer a control for it either.
 */
const MEDIA_AUDIO_SCRIPT = `
<script>
(function() {
  var muted = !!window.__LC_MEDIA_MUTED__;
  var lastReported = null;

  function mediaElements() {
    return document.querySelectorAll('audio, video');
  }

  function apply() {
    var list = mediaElements();
    // Only touch elements we are actually managing: with no flag injected, this
    // script reports presence and leaves the page exactly as authored.
    if (window.__LC_MEDIA_MUTED__ !== undefined) {
      for (var i = 0; i < list.length; i++) {
        list[i].muted = muted;
        list[i].defaultMuted = muted;
      }
    }
    var hasAudio = list.length > 0;
    if (hasAudio !== lastReported) {
      lastReported = hasAudio;
      try { window.parent.postMessage({ type: '__iframe_audio', hasAudio: hasAudio }, '*'); } catch (e) {}
    }
  }

  window.addEventListener('message', function(event) {
    var data = event.data;
    if (!data || data.type !== '__iframe_set_muted') return;
    muted = !!data.muted;
    window.__LC_MEDIA_MUTED__ = muted;
    apply();
    if (!muted) {
      // Unmuting is always the result of a click in the host, so the frame has a
      // user gesture to spend: resume anything autoplay left paused. A rejected
      // play() is normal (nothing to resume) and must not throw into the page.
      var list = mediaElements();
      for (var i = 0; i < list.length; i++) {
        try {
          var p = list[i].play();
          if (p && p.catch) p.catch(function() {});
        } catch (e) {}
      }
    }
  });

  // A src set on an existing element is not a childList mutation, so observe the
  // load itself. Capture phase: media events do not bubble.
  document.addEventListener('loadstart', apply, true);
  new MutationObserver(apply).observe(document.documentElement, { childList: true, subtree: true });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', apply);
  } else {
    apply();
  }
  window.addEventListener('load', apply);
})();
</script>`;

/**
 * Recursively replace FileRef objects in resolved data (for window.__RESOLVED_DATA__) with a
 * renderable file URL, so JS templates can use file URLs (e.g. dynamic <img src> injection).
 *
 * <p>{@code resolveFileUrl} maps the opaque, id-based file URL ({@link fileRefToUrl}) to a base64
 * {@code data:} URI fetched with the auth header by the embedding component - the session token is
 * NEVER injected into the iframe HTML (the leak this replaces; a {@code data:} URI renders in the
 * sandboxed iframe regardless of origin). A legacy FileRef with no id resolves to ''.
 */
function injectFileProxyUrls(data: Record<string, unknown>, resolveFileUrl?: (rawUrl: string) => string): Record<string, unknown> {
  if (!resolveFileUrl) return data;

  function processValue(val: unknown): unknown {
    // Pre-order with FileRef short-circuit: at each object node, FIRST test
    // isFileRef → if it matches, replace with URL string and stop descending.
    // Otherwise descend via Object.entries. Step envelopes carrying _status
    // fields are correctly NOT matched by the flat branch of isFileRef
    // (file.service.ts:29-58) so descent proceeds to their FileRef leaves.
    if (Array.isArray(val)) {
      return val.map(processValue);
    }
    if (val && typeof val === 'object') {
      // FileRef object (canonical {_type:'file', path, ...} OR legacy {key} OR
      // flat {file_url, file_name, ...}). Convert to a data: URI string so
      // <img src="{{var}}"> works on dynamic JS-template data. Replacement is a
      // STRING - js_template iteration loses .name/.mimeType by design; map name
      // separately if needed (see file_storage help).
      // A table media cell is an asset map: it always carries a URL but not always a path,
      // mimeType or size, so it fails isFileRef's stricter shape test and would otherwise reach
      // the template as JSON - a broken <img src>. Checked first, and strictly (_type must be
      // 'file'), so no ordinary object is turned into a bare URL.
      const assetUrl = assetDisplayUrl(val);
      if (assetUrl) return resolveFileUrl(assetUrl);
      if (isFileRef(val)) {
        // normalize first so a flat {file_url,...} ref recovers its opaque id before building the URL.
        const raw = fileRefToUrl(normalizeFileRef(val as unknown as FileRef), { inline: true });
        return raw ? resolveFileUrl(raw) : '';
      }
      const result: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(val as Record<string, unknown>)) {
        result[k] = processValue(v);
      }
      return result;
    }
    return val;
  }

  return processValue(data) as Record<string, unknown>;
}

/**
 * Wrap HTML fragment in a complete document structure
 */
/**
 * Makes CSS safe to inline in a `<style>` element. Publisher CSS is interpolated AFTER the HTML
 * sanitizer runs, so a literal `</style>` in it would close the element and inject markup (for
 * example a script into a `removeScripts` preview). `<` has no meaning in CSS outside strings, and
 * inside a string the CSS escape `\3c ` renders the same character, so the rewrite keeps the
 * stylesheet's meaning.
 */
export function neutralizeStyleBreakout(css: string): string {
  return css.replace(/</g, '\\3c ');
}

export function ensureCompleteHtml(html: string, customCss?: string, autoFit?: boolean, actionMapping?: Record<string, string>, triggerData?: Record<string, Record<string, unknown>>, jsTemplate?: string, resolvedData?: Record<string, unknown>, resolveFileUrl?: (rawUrl: string) => string, muteMedia?: boolean): string {
  if (!html) return '';

  const autoFitStyles = autoFit ? AUTO_FIT_CSS : '';
  const renderedHtml = resolvedData ? renderForRunMode(html, resolvedData, resolveFileUrl) : html;
  // Complete documents get NO platform styling from THIS funnel: no base CSS
  // and no AUTO_FIT_CSS. The author owns the page, and the screenshot/video
  // renderer and the application panel inject nothing (WYSIWYG parity; an
  // embedder may still pass surface-specific customCss, e.g. the thumbnail's
  // scrollbar suppression). Before
  // this gate, AUTO_FIT_CSS's overflow:hidden + flex centering silently
  // re-centered/clipped complete documents in canvas-node previews only,
  // masking body-margin bleed that the run panel then exposed.
  // Fragments inherit the base theme, with the author's CSS AFTER it so every
  // base rule stays overridable.
  const combinedCss = neutralizeStyleBreakout((isCompleteHtml(renderedHtml)
    ? [customCss]
    : [BASE_IFRAME_CSS, autoFitStyles, customCss]
  ).filter(Boolean).join('\n'));

  // Generate bridge script if action mapping is provided
  const bridgeScriptHtml = actionMapping ? generateBridgeScript(actionMapping, triggerData) : '';

  // Initial mute state, read by MEDIA_AUDIO_SCRIPT. Emitted ONLY when the
  // embedder manages audio: leaving the global undefined is what tells the
  // controller to report presence and otherwise not touch the page, so every
  // surface that does not ask for this keeps playing exactly as authored.
  const mediaMutedFlagHtml = muteMedia === undefined
    ? ''
    : `<script>window.__LC_MEDIA_MUTED__ = ${muteMedia ? 'true' : 'false'};</script>`;

  // Inject resolved data as a global variable so user JS can access complex objects
  // without HTML-escaping issues (escapeHtml breaks JSON inside <script> tags)
  // Process FileRefs in data so JS templates can use them (same blob: rewrite as the HTML)
  const processedData = resolvedData ? injectFileProxyUrls(resolvedData, resolveFileUrl) : undefined;
  const varPaginationApi = `<script>window.__paginateVariable = function(varName, page) { window.parent.postMessage({ type: 'variable-pagination', variable: varName, page: page }, '*'); };</script>`;
  const dataScriptHtml = processedData && Object.keys(processedData).length > 0
    ? `<script>window.__RESOLVED_DATA__ = ${JSON.stringify(processedData).replace(/<\//g, '<\\/')};</script>${varPaginationApi}`
    : '';

  // Generate user JS script tag if jsTemplate is provided
  const userJsScriptHtml = jsTemplate ? `<script>\n${jsTemplate}\n</script>` : '';

  // Already complete, just inject custom CSS if needed
  if (isCompleteHtml(renderedHtml)) {
    let result = renderedHtml;
    if (combinedCss) {
      const styleTag = `<style data-injected>${combinedCss}</style>`;
      // injectBefore() instead of .replace() - content may contain `$&`, `$'`,
      // `$1` etc. that .replace() would expand. See helper docstring.
      if (result.includes('</head>')) {
        result = injectBefore(result, '</head>', `${styleTag}\n`);
      } else if (result.includes('</body>')) {
        result = injectBefore(result, '</body>', `${styleTag}\n`);
      } else {
        result = styleTag + '\n' + result;
      }
    }
    // Inject scripts before </body>. AUTO_FIT_SCRIPT is deliberately absent:
    // only fragments get the #auto-fit-wrapper it targets, so on a complete
    // document it was dead weight shipped with every preview.
    const scriptsToInject = [HEIGHT_REPORTER_SCRIPT, NAVIGATION_GATE_SCRIPT, BROKEN_IMG_SCRIPT, mediaMutedFlagHtml, MEDIA_AUDIO_SCRIPT, bridgeScriptHtml, dataScriptHtml, userJsScriptHtml].filter(Boolean).join('\n');
    if (scriptsToInject) {
      if (result.includes('</body>')) {
        result = injectBefore(result, '</body>', `${scriptsToInject}\n`);
      } else {
        result = result + scriptsToInject;
      }
    }
    return result;
  }

  // Wrap content in auto-fit wrapper if needed
  const contentHtml = autoFit ? `<div id="auto-fit-wrapper">\n${renderedHtml}\n</div>` : renderedHtml;
  const scriptHtml = [HEIGHT_REPORTER_SCRIPT, NAVIGATION_GATE_SCRIPT, BROKEN_IMG_SCRIPT, autoFit ? AUTO_FIT_SCRIPT : '', mediaMutedFlagHtml, MEDIA_AUDIO_SCRIPT, bridgeScriptHtml, dataScriptHtml, userJsScriptHtml].filter(Boolean).join('\n');

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <style>${combinedCss}</style>
</head>
<body>
${contentHtml}
${scriptHtml}
</body>
</html>`;
}

// =============================================================================
// Form Field Extraction (output fields auto-detection)
// =============================================================================

/**
 * Extract form field names from an HTML template.
 * Scans for <input>, <select>, and <textarea> elements with name attributes.
 *
 * These represent the output fields available to downstream nodes
 * when a user submits a form in an interface.
 */
export function extractFormFields(html: string): string[] {
  if (!html) return [];
  const parser = new DOMParser();
  const doc = parser.parseFromString(html, 'text/html');
  const fields = new Set<string>();
  doc.querySelectorAll('input[name], select[name], textarea[name]')
     .forEach(el => {
       const name = el.getAttribute('name');
       if (name) fields.add(name);
     });
  return Array.from(fields);
}

/**
 * Extract action names from an HTML template.
 * Scans for elements with data-action attributes.
 *
 * These represent the action keys available for action mapping configuration.
 */
export function extractActionNames(html: string): string[] {
  if (!html) return [];
  const parser = new DOMParser();
  const doc = parser.parseFromString(html, 'text/html');
  const actions = new Set<string>();
  doc.querySelectorAll('[data-action]')
     .forEach(el => {
       const action = el.getAttribute('data-action');
       if (action) actions.add(action);
     });
  return Array.from(actions);
}

/**
 * Extract form fields scoped per action from an HTML template.
 *
 * Action names in actionMapping are CSS selectors (e.g. "#search-form", ".btn-next").
 * For each selector, find the matching element in the HTML, then:
 * - If it IS a <form>, look for fields inside it
 * - If it's inside a <form>, look for fields in that parent form
 * - Otherwise look for fields in its own subtree
 * - If no fields found, the action only produces fired_at
 *
 * Returns a Map from action name to field names.
 */
export function extractFormFieldsByAction(html: string, actionNames: string[]): Map<string, string[]> {
  const result = new Map<string, string[]>();
  if (!html || actionNames.length === 0) return result;

  const parser = new DOMParser();
  const doc = parser.parseFromString(html, 'text/html');
  const fieldSelector = 'input[name], select[name], textarea[name]';

  for (const actionName of actionNames) {
    // Strip surrounding quotes: "#search-form" or '#search-form' → #search-form
    const selector = actionName.replace(/^["']|["']$/g, '');

    let el: Element | null = null;
    // 1. Try as CSS selector (matches id, class, etc.)
    try {
      el = doc.querySelector(selector);
    } catch {
      // Invalid CSS selector - skip
    }
    // 2. Fallback: match data-action attribute (with or without quotes in the value)
    if (!el) {
      el = doc.querySelector(`[data-action="${selector}"]`)
        || doc.querySelector(`[data-action="'${selector}'"]`)
        || doc.querySelector(`[data-action='"${selector}"']`);
    }

    if (!el) {
      result.set(actionName, []);
      continue;
    }

    let scope: Element;
    if (el.tagName === 'FORM') {
      scope = el;
    } else {
      const parentForm = el.closest('form');
      scope = parentForm || el;
    }

    const fields: string[] = [];
    scope.querySelectorAll(fieldSelector).forEach(field => {
      const name = field.getAttribute('name');
      if (name && !fields.includes(name)) fields.push(name);
    });

    result.set(actionName, fields);
  }

  return result;
}

// =============================================================================
// Variable Resolution
// =============================================================================

// group(1)=expression, group(2)=default value (optional, after pipe).
// Mirrors backend TemplateEngine.EXPRESSION_PATTERN - accepts SpEL string literals
// containing `}` or `|` so {{json('{"a":1}')}} matches correctly.
const VARIABLE_PATTERN = /\{\{((?:'(?:[^'\\]|\\.)*'|[^}|])+?)(?:\|([^}]*))?\}\}/g;

/**
 * Escape HTML special characters
 */
export function escapeHtml(text: string): string {
  if (!text) return '';
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * Rewrite FileRef objects to a renderable file URL via {@code resolveFileUrl} (the opaque,
 * id-based URL mapped to a base64 data: URI - no session token in the iframe HTML).
 * Works recursively on strings, arrays, and objects so both single FileRefs and arrays of
 * FileRefs (from aggregate nodes) are handled.
 */
function rewriteFileProxyUrls(value: unknown, resolveFileUrl: (rawUrl: string) => string): unknown {
  if (Array.isArray(value)) {
    return value.map(item => rewriteFileProxyUrls(item, resolveFileUrl));
  }
  if (value && typeof value === 'object') {
    // FileRef object → blob: URL string. Same contract as injectFileProxyUrls
    // above. Required so HTML template substitution `<img src="{{photo}}">`
    // sees a URL string instead of JSON-stringifying the FileRef Map.
    // Same contract as injectFileProxyUrls above: a table asset resolves to its URL.
    const assetUrl = assetDisplayUrl(value);
    if (assetUrl) return resolveFileUrl(assetUrl);
    if (isFileRef(value)) {
      const raw = fileRefToUrl(normalizeFileRef(value as unknown as FileRef), { inline: true });
      return raw ? resolveFileUrl(raw) : '';
    }
    const result: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      result[k] = rewriteFileProxyUrls(v, resolveFileUrl);
    }
    return result;
  }
  return value;
}

/**
 * Extract display label from variable expression
 * "mcp:enricher.output.data.user.name" -> "name"
 */
export function extractDisplayLabel(varExpr: string): string {
  const parts = varExpr.split('.');
  return parts[parts.length - 1];
}

/**
 * Extract short label for placeholder
 * "mcp:enricher.output.data.user.name" -> "enricher.name"
 */
export function extractShortLabel(varExpr: string): string {
  let expr = varExpr;

  // Remove type prefix (mcp:, trigger:, etc.)
  const colonIndex = expr.indexOf(':');
  if (colonIndex > 0) {
    expr = expr.substring(colonIndex + 1);
  }

  const dotIndex = expr.indexOf('.');
  if (dotIndex < 0) return expr;

  const alias = expr.substring(0, dotIndex);
  const path = expr.substring(dotIndex + 1);
  const lastDotIndex = path.lastIndexOf('.');
  const lastSegment = lastDotIndex >= 0 ? path.substring(lastDotIndex + 1) : path;

  if (lastSegment === 'output') return alias;
  return `${alias}.${lastSegment}`;
}

/**
 * Find resolved value for a variable expression.
 *
 * Resolution order:
 * 1. Exact key match in resolvedData
 * 2. Without `type:` prefix (e.g. `mcp:foo.output.x` → `foo.output.x`)
 * 3. `current_item.…` aliases for split-context items
 * 4. **Dotted-drill into objects/arrays** - when the agent maps a FileRef under
 *    a single alias (e.g. `{'photo':'{{core:dl.output.file}}'}`) and the HTML
 *    references a sub-field (`{{photo.name}}`), walk into the resolved object
 *    so `.name`/`.mimeType`/`.size` resolve from the FileRef without forcing
 *    the agent to add a second mapping entry. Array indices supported via
 *    `images[0]` / `images.0` syntax. Falls back to {@code undefined} only
 *    when the path genuinely doesn't exist.
 */
export function findResolvedValue(
  varExpr: string,
  resolvedData: Record<string, unknown> | undefined
): unknown | undefined {
  if (!resolvedData) return undefined;

  // Try exact match
  if (varExpr in resolvedData) {
    return resolvedData[varExpr];
  }

  // Try without type prefix
  const colonIndex = varExpr.indexOf(':');
  if (colonIndex > 0) {
    const withoutPrefix = varExpr.substring(colonIndex + 1);
    if (withoutPrefix in resolvedData) {
      return resolvedData[withoutPrefix];
    }
  }

  // Try current_item variations
  if (varExpr.startsWith('current_item.')) {
    const withoutCurrentItem = varExpr.substring('current_item.'.length);
    if (withoutCurrentItem.startsWith('data.')) {
      const field = withoutCurrentItem.substring('data.'.length);
      if (field in resolvedData) {
        return resolvedData[field];
      }
    }
    if (withoutCurrentItem in resolvedData) {
      return resolvedData[withoutCurrentItem];
    }
  }

  // Dotted-drill - split on `.` and walk into nested objects/arrays. Resolves
  // `{{photo.name}}` when `photo` is mapped to a FileRef object, or
  // `{{images.0}}` / `{{images[0]}}` when an array entry is needed.
  // Normalise bracket notation first so `images[0].name` becomes `images.0.name`
  // and the head/tail split lands on the root key (`images`).
  const normalised = varExpr.replace(/\[(\d+)\]/g, '.$1');
  const dotIndex = normalised.indexOf('.');
  if (dotIndex > 0) {
    const head = normalised.substring(0, dotIndex);
    const tail = normalised.substring(dotIndex + 1);
    const rootCandidates: string[] = [];
    if (head in resolvedData) rootCandidates.push(head);
    if (colonIndex > 0 && colonIndex < dotIndex) {
      const headNoPrefix = head.substring(colonIndex + 1);
      if (headNoPrefix in resolvedData) rootCandidates.push(headNoPrefix);
    }
    for (const rootKey of rootCandidates) {
      const drilled = drillPath(resolvedData[rootKey], tail);
      if (drilled !== undefined) return drilled;
    }
  }

  return undefined;
}

/**
 * Walk a dotted path (`a.b.c` or `a[0].b` or `a.0.b`) into a nested
 * object/array structure. Returns {@code undefined} on any missing segment so
 * the caller can fall through to the {@code [placeholder]} default rather than
 * crash on null traversal.
 */
function drillPath(root: unknown, path: string): unknown | undefined {
  if (root == null || !path) return undefined;
  // Normalise `a[0].b` → `a.0.b` so we can split on `.` uniformly.
  const segments = path.replace(/\[(\d+)\]/g, '.$1').split('.').filter(Boolean);
  let current: unknown = root;
  for (const segment of segments) {
    if (current == null) return undefined;
    if (Array.isArray(current)) {
      const idx = Number(segment);
      if (!Number.isInteger(idx) || idx < 0 || idx >= current.length) return undefined;
      current = current[idx];
    } else if (typeof current === 'object') {
      const obj = current as Record<string, unknown>;
      if (!(segment in obj)) return undefined;
      current = obj[segment];
    } else {
      // Scalar - cannot drill further.
      return undefined;
    }
  }
  return current;
}

/**
 * Get a type-aware default value for a template variable.
 * Used by drag-and-drop to auto-generate pipe defaults.
 */
export function getDefaultForType(fieldType: string, fieldName: string): string {
  switch (fieldType) {
    case 'number':    return '0';
    case 'boolean':   return 'true';
    case 'datetime':  return '2024-01-01';
    case 'text':
    default:          return `Sample ${fieldName}`;
  }
}

// =============================================================================
// Trigger Data Merge
// =============================================================================

/**
 * Merge trigger data into resolved data for template variable resolution.
 * Flattens { "trigger:name": { field: value } } into:
 * - "trigger:name.output.field" → value  (standard convention matching MCP steps)
 * - "trigger:name.field" → value          (shorthand)
 */
export function mergeTriggerDataIntoResolved(
  resolvedData: Record<string, unknown> | undefined,
  triggerData: Record<string, Record<string, unknown>> | undefined
): Record<string, unknown> | undefined {
  if (!triggerData) return resolvedData;
  const merged: Record<string, unknown> = { ...(resolvedData || {}) };
  for (const [triggerKey, fields] of Object.entries(triggerData)) {
    if (!fields || typeof fields !== 'object') continue;
    for (const [fieldName, value] of Object.entries(fields)) {
      merged[`${triggerKey}.output.${fieldName}`] = value;
      merged[`${triggerKey}.${fieldName}`] = value;
    }
  }
  return merged;
}

// =============================================================================
// Mapping Translation
// =============================================================================

/**
 * Translate resolved data using variable mapping.
 * If data is keyed by workflow expressions (old backend) and mapping is available,
 * this adds entries keyed by generic variable names.
 *
 * This is a safety net for the transition period. The backend should already
 * key data by generic names when mapping is present.
 */
export function translateWithMapping(
  data: Record<string, unknown>,
  mapping: Record<string, string> | undefined
): Record<string, unknown> {
  if (!mapping || !data) return data;
  const result: Record<string, unknown> = { ...data };
  for (const [genericName, workflowExpr] of Object.entries(mapping)) {
    // If generic name already exists in data, skip (backend already resolved correctly)
    if (genericName in result) continue;
    // If workflow expression exists as a key in data, copy under generic name
    if (workflowExpr in data) {
      result[genericName] = data[workflowExpr];
    }
  }
  return result;
}

// =============================================================================
// Template Rendering
// =============================================================================

export type RenderMode = 'edit' | 'run' | 'preview';

/**
 * Render template for EDIT/PREVIEW mode
 * Variables with pipe default show the default value.
 * Variables without pipe show [lastPart] placeholders (current behavior).
 */
export function renderForEditMode(template: string): string {
  if (!template) return '';

  return template.replace(VARIABLE_PATTERN, (match, varExpr: string, defaultVal?: string) => {
    if (defaultVal !== undefined) {
      return escapeHtml(defaultVal);
    }
    // No pipe → keep current behavior: show [label] placeholder
    const label = extractDisplayLabel(varExpr.trim());
    return `[${label}]`;
  });
}

/**
 * Render template for RUN mode
 * Variables are replaced with actual data, pipe defaults, or [lastPart] placeholders
 */
export function renderForRunMode(
  template: string,
  resolvedData?: Record<string, unknown>,
  resolveFileUrl?: (rawUrl: string) => string
): string {
  if (!template) return '';

  // Handle {{expr|default}} format - resolve with data, pipe default, or [label] placeholder
  let result = template.replace(VARIABLE_PATTERN, (match, varExpr: string, defaultVal?: string) => {
    const trimmedExpr = varExpr.trim();
    const value = findResolvedValue(trimmedExpr, resolvedData);

    if (value !== undefined && value !== null) {
      // Rewrite FileRefs to blob: URLs before stringifying (handles single refs and arrays of refs)
      const processed = resolveFileUrl ? rewriteFileProxyUrls(value, resolveFileUrl) : value;
      let stringified = typeof processed === 'object' ? JSON.stringify(processed) : String(processed);
      // File proxy URLs must NOT be HTML-escaped (& in query params would become &amp;
      // and the browser's attribute parser would decode that correctly, but tooling
      // and copy-paste downstream get confused). All first-party shapes:
      // - /api/proxy/files/by-id/{id}/raw?... = opaque, id-based authenticated serve (canonical)
      // - /api/files/proxy-signed?sig=...     = anonymous marketplace (HMAC, no token)
      if (typeof processed === 'string'
          && (processed.startsWith('/api/proxy/files/by-id/')
              || processed.startsWith('/api/files/proxy-signed?'))) {
        return stringified;
      }
      return escapeHtml(stringified);
    }

    // Use pipe default if present
    if (defaultVal !== undefined) {
      return escapeHtml(defaultVal);
    }

    // No pipe → keep current behavior: show [label] placeholder
    const label = extractDisplayLabel(trimmedExpr);
    return `[${label}]`;
  });

  return result;
}

// =============================================================================
// Main Render Function
// =============================================================================

export interface RenderOptions {
  mode: RenderMode;
  resolvedData?: Record<string, unknown>;
  removeScripts?: boolean;
  wrapInDocument?: boolean;
  customCss?: string;
  /** Enable auto-fit scaling to fit content within container */
  autoFit?: boolean;
  /** Action mapping for bridge script injection (action name -> trigger ref) */
  actionMapping?: Record<string, string>;
  /** Previous trigger data for form pre-fill (trigger ref -> field values) */
  triggerData?: Record<string, Record<string, unknown>>;
  /** JavaScript template to inject as a script tag */
  jsTemplate?: string;
  /**
   * Maps the opaque, id-based file URL ({@link fileRefToUrl}) to a renderable base64 {@code data:}
   * URI fetched with the auth header - so FileRefs render in the sandboxed iframe (no
   * {@code allow-same-origin}) WITHOUT the session token ever appearing in the HTML/URL. Provided by
   * the embedding component (see {@code useInterfaceFileUrls}). Run mode only.
   */
  resolveFileUrl?: (rawUrl: string) => string;
  /**
   * Initial mute state for the interface's `<audio>`/`<video>`. Leave undefined
   * (the default) to let the page play as authored; pass a boolean to hand the
   * embedder control, which it then flips at runtime with an `__iframe_set_muted`
   * message rather than by re-rendering the document.
   */
  muteMedia?: boolean;
}

/**
 * Main function to render interface HTML
 * Handles all modes and options in one place
 */
export function renderInterfaceTemplate(
  template: string,
  options: RenderOptions
): string {
  if (!template) return '';

  const {
    mode,
    resolvedData,
    removeScripts = true,
    wrapInDocument = true,
    customCss,
    autoFit = false,
    actionMapping,
    triggerData,
    jsTemplate,
    resolveFileUrl,
    muteMedia,
  } = options;

  let html = template;

  // Step 1: Resolve variables based on mode (in both HTML and CSS)
  let resolvedCss = customCss;
  if (mode === 'run') {
    html = renderForRunMode(html, resolvedData, resolveFileUrl);
    if (resolvedCss) {
      resolvedCss = renderForRunMode(resolvedCss, resolvedData);
    }
  } else {
    html = renderForEditMode(html);
    if (resolvedCss) {
      resolvedCss = renderForEditMode(resolvedCss);
    }
  }

  // Step 2: Neutralise publisher-supplied script when asked to (system scripts are added by
  // ensureCompleteHtml afterwards). sanitizeHtml, not removeScriptTags alone: inline handlers
  // (<img/onerror=...>), javascript: URLs and srcdoc documents execute just like a <script>, so a
  // caller relying on removeScripts must get all of them removed (LC-077).
  // AFTER substitution, not before: a value is HTML-escaped when it is substituted, so it cannot
  // add markup, but it CAN complete a URL attribute (href="{{link}}" with link =
  // "javascript:..."). Only the substituted document shows that attribute's real value.
  if (removeScripts) {
    html = sanitizeHtml(html);
  }

  // Step 3: Wrap in complete document if needed
  if (wrapInDocument) {
    // removeScripts promises that no publisher JS runs: the js_template is publisher JS too.
    html = ensureCompleteHtml(html, resolvedCss, autoFit, actionMapping, triggerData, removeScripts ? undefined : jsTemplate, mode === 'run' ? resolvedData : undefined, mode === 'run' ? resolveFileUrl : undefined, muteMedia);
  }

  return html;
}
