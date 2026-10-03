/**
 * The interface-rendering shell (LC-027 CASA E3, docs/CASA_HANDOFF-style note kept in
 * lib/security/securityHeaders.mjs's module header - read that first).
 *
 * `InterfaceIframe.tsx` used to hand publisher/agent-authored interface HTML to the browser via
 * `<iframe srcDoc=...>`. An `about:srcdoc` document INHERITS the embedding page's CSP, which is
 * why the app's own CSP could never enforce a strict `script-src`: doing so would have blanked
 * every interface (they need real inline JS - the bridge script, height reporter, publisher
 * `js_template`, `window.__RESOLVED_DATA__`).
 *
 * A real navigation response does NOT inherit the parent's CSP - it gets its OWN
 * `Content-Security-Policy` header (see `interfaceFrameHeaders()` in securityHeaders.mjs, which
 * is deliberately permissive for script/style/media: interfaces could already run anything
 * there). So instead of `srcDoc`, `InterfaceIframe` now points a SANDBOXED iframe's `src` at
 * this route (still `sandbox="allow-scripts ..."` with NO `allow-same-origin` - the framed
 * document keeps an opaque, unique origin either way, so this is not a same-origin relaxation)
 * and hands over the actual rendered HTML afterwards, over `postMessage`. This file is that
 * receiving end: a deliberately tiny, framework-free document (no Next/React bundle, nothing
 * that could itself be a script-src dependency) that does nothing but:
 *
 *   1. announce itself ready to its parent window,
 *   2. wait for a `{ type: 'interface-frame-html', html }` message FROM that same parent window
 *      (checked by `event.source`, not by origin - the parent is a normal, non-sandboxed window
 *      with a real origin, but this document's OWN origin is opaque, so it cannot usefully
 *      assert anything about its own `event.origin` either way), and
 *   3. replace itself with that HTML via `document.open()/write()/close()`, which is how a real
 *      navigation constructs a document from network bytes too - so this is not a new sink, just
 *      the same construction the browser already performs for `srcDoc`.
 *
 * The listener is NOT removed after the first message: `InterfaceIframe` reuses the same shell
 * document across content updates (epoch swaps, pagination, mute changes) rather than reloading
 * the iframe element itself, and posts a fresh `interface-frame-html` each time.
 *
 * Every existing bridge mechanism (NAVIGATION_GATE_SCRIPT, HEIGHT_REPORTER_SCRIPT,
 * MEDIA_AUDIO_SCRIPT, generateBridgeScript's action/file-upload delegation, the
 * `__iframe_set_muted` channel) already targets `window.parent.postMessage(...)` from INSIDE the
 * interface content, and `window.parent` is unaffected by `document.write()` (it replaces the
 * document, not the browsing context) - so none of that machinery needed to change.
 */
export const dynamic = 'force-static';

const SHELL_HTML = `<!doctype html>
<html><head><meta charset="utf-8" /></head><body style="margin:0">
<script>
(function () {
  var parentWindow = window.parent;
  function post(message) {
    try { parentWindow.postMessage(message, '*'); } catch (e) { /* no parent, or parent gone */ }
  }
  function onMessage(event) {
    if (event.source !== parentWindow) return;
    var data = event.data;
    if (!data || data.type !== 'interface-frame-html' || typeof data.html !== 'string') return;
    try {
      document.open();
      document.write(data.html);
      document.close();
    } catch (e) {
      try { document.body.textContent = 'Failed to render interface.'; } catch (e2) { /* ignore */ }
    }
  }
  window.addEventListener('message', onMessage);
  post({ type: 'interface-frame-ready' });
})();
</script>
</body></html>`;

export function GET() {
  return new Response(SHELL_HTML, {
    headers: {
      'Content-Type': 'text/html; charset=utf-8',
      // Never cached: it is byte-identical across the app's lifetime by construction (all real
      // content arrives over postMessage, never over the network), so this is belt-and-braces
      // rather than a correctness requirement.
      'Cache-Control': 'private, no-store',
    },
  });
}
