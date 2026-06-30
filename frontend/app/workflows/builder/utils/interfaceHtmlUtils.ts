/**
 * Interface HTML Utilities
 *
 * Centralized utilities for rendering interface HTML templates.
 * Used by all interface preview components (node, fullscreen, inspector, etc.)
 */

import { isFileRef, fileRefToUrl, normalizeFileRef, type FileRef } from '@/lib/api/orchestrator/file.service';

// =============================================================================
// HTML Processing
// =============================================================================

/**
 * Base CSS for iframe content
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
 * Remove script tags from HTML for security
 */
export function removeScriptTags(html: string): string {
  if (!html) return '';
  return html.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '');
}

/**
 * Sanitize HTML for safe rendering in Shadow DOM.
 * Removes script tags AND inline event handlers (onclick, onerror, onload, etc.)
 */
export function sanitizeHtml(html: string): string {
  if (!html) return '';
  let sanitized = removeScriptTags(html);
  // Remove all inline event handlers (on* attributes)
  sanitized = sanitized.replace(/\s+on\w+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]*)/gi, '');
  return sanitized;
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
          console.log('[BridgePrefill] SET ' + tag + '[name=' + fieldName + '] = ' + stringValue.slice(0, 40));
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
            console.log('[BridgeScript] Sending postMessage continue:', { actionKey: actionName, data: data });
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
 * Auto-fit CSS styles
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
export function ensureCompleteHtml(html: string, customCss?: string, autoFit?: boolean, actionMapping?: Record<string, string>, triggerData?: Record<string, Record<string, unknown>>, jsTemplate?: string, resolvedData?: Record<string, unknown>, resolveFileUrl?: (rawUrl: string) => string): string {
  if (!html) return '';

  const autoFitStyles = autoFit ? AUTO_FIT_CSS : '';
  const combinedCss = [BASE_IFRAME_CSS, autoFitStyles, customCss].filter(Boolean).join('\n');
  const renderedHtml = resolvedData ? renderForRunMode(html, resolvedData, resolveFileUrl) : html;

  // Generate bridge script if action mapping is provided
  const bridgeScriptHtml = actionMapping ? generateBridgeScript(actionMapping, triggerData) : '';

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
    // Inject scripts before </body>
    const scriptsToInject = [HEIGHT_REPORTER_SCRIPT, NAVIGATION_GATE_SCRIPT, BROKEN_IMG_SCRIPT, autoFit ? AUTO_FIT_SCRIPT : '', bridgeScriptHtml, dataScriptHtml, userJsScriptHtml].filter(Boolean).join('\n');
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
  const scriptHtml = [HEIGHT_REPORTER_SCRIPT, NAVIGATION_GATE_SCRIPT, BROKEN_IMG_SCRIPT, autoFit ? AUTO_FIT_SCRIPT : '', bridgeScriptHtml, dataScriptHtml, userJsScriptHtml].filter(Boolean).join('\n');

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
  } = options;

  // Step 1: Remove user-provided scripts if needed (system scripts are added by ensureCompleteHtml)
  let html = removeScripts ? removeScriptTags(template) : template;

  // Step 2: Resolve variables based on mode (in both HTML and CSS)
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

  // Step 3: Wrap in complete document if needed
  if (wrapInDocument) {
    html = ensureCompleteHtml(html, resolvedCss, autoFit, actionMapping, triggerData, jsTemplate, mode === 'run' ? resolvedData : undefined, mode === 'run' ? resolveFileUrl : undefined);
  }

  return html;
}
