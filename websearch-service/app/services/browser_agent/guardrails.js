/*!
 * Browser-agent DOM guardrails - injected on every page load before each
 * browser-use step. Two purposes:
 *
 *  1. Block the browser-use action layer (and the user during takeover)
 *     from typing into password / credit-card / CVV fields. Any keystroke
 *     reaching those inputs is intercepted at the capture phase and the
 *     event is silently swallowed.
 *
 *  2. Mask matched fields visually (and in pre-screenshot snapshot) so the
 *     LLM never receives the raw value back as part of the page state.
 *     The mask is a rectangular overlay positioned over the input rect.
 *
 * Intentionally vanilla JS (no deps) so it can be passed via CDP
 * `Page.addScriptToEvaluateOnNewDocument`.
 */
(function () {
  'use strict';

  if (window.__lcGuardrailsInstalled) return;
  window.__lcGuardrailsInstalled = true;

  // Selectors that match sensitive inputs. Maintained as a flat list for
  // easy diffing; conservative - a false positive only blocks input on a
  // benign field, never the inverse.
  var SENSITIVE_SELECTORS = [
    'input[type="password"]',
    'input[autocomplete*="cc-"]',
    'input[autocomplete*="card"]',
    'input[autocomplete="new-password"]',
    'input[autocomplete="current-password"]',
    'input[name*="cvv" i]',
    'input[name*="cvc" i]',
    'input[name*="cardnumber" i]',
    'input[name*="card-number" i]',
    'input[name*="creditcard" i]',
    'input[name*="credit-card" i]',
    'input[name*="securitycode" i]',
    'input[name*="security-code" i]',
    'input[name*="cardholder" i]',
    'input[id*="cvv" i]',
    'input[id*="cardnumber" i]',
    'input[id*="creditcard" i]',
    'input[type="tel"][autocomplete*="cc"]'
  ];

  function matches(el) {
    if (!el || el.nodeType !== 1) return false;
    for (var i = 0; i < SENSITIVE_SELECTORS.length; i++) {
      try {
        if (el.matches(SENSITIVE_SELECTORS[i])) return true;
      } catch (e) {
        // Old browsers/edge cases - ignore selector errors.
      }
    }
    return false;
  }

  function blockEvent(e) {
    var t = e.target;
    if (matches(t)) {
      e.preventDefault();
      e.stopImmediatePropagation();
      // Clear any value the action may have set in the same tick.
      try { if (t && 'value' in t) t.value = ''; } catch (_) {}
      // Surface the block on the element so the LLM sees it (if it reads
      // attributes from the snapshot).
      try { t.setAttribute('data-lc-blocked', '1'); } catch (_) {}
      return false;
    }
  }

  // Use capture phase: we must intercept before site-specific handlers.
  ['beforeinput', 'input', 'keydown', 'keypress', 'keyup', 'paste', 'change']
    .forEach(function (ev) {
      document.addEventListener(ev, blockEvent, true);
    });

  function applyMask(el) {
    if (!el || el.dataset.lcMasked === '1') return;
    el.dataset.lcMasked = '1';
    // Visual mask: black box overlay. Avoid position-relative on the input
    // itself (some sites' layouts break) - use an absolute sibling at the
    // input's bounding rect, re-positioned on resize / scroll.
    var mask = document.createElement('div');
    mask.setAttribute('data-lc-guard-mask', '1');
    mask.style.cssText = (
      'position:fixed;background:#000;border:1px solid #444;color:#fff;' +
      'font:bold 11px/1 sans-serif;display:flex;align-items:center;' +
      'justify-content:center;pointer-events:none;z-index:2147483647;'
    );
    mask.textContent = '🔒 redacted';
    document.documentElement.appendChild(mask);

    function reposition() {
      if (!el.isConnected) {
        try { mask.remove(); } catch (_) {}
        return;
      }
      var r = el.getBoundingClientRect();
      mask.style.left = r.left + 'px';
      mask.style.top = r.top + 'px';
      mask.style.width = r.width + 'px';
      mask.style.height = r.height + 'px';
    }
    reposition();
    window.addEventListener('scroll', reposition, true);
    window.addEventListener('resize', reposition);
    var ro = (typeof ResizeObserver === 'function') ? new ResizeObserver(reposition) : null;
    if (ro) {
      try { ro.observe(el); } catch (_) {}
      try { ro.observe(document.documentElement); } catch (_) {}
    }
  }

  function scanAndMask(root) {
    var nodes;
    try {
      nodes = (root || document).querySelectorAll(SENSITIVE_SELECTORS.join(','));
    } catch (_) {
      return;
    }
    for (var i = 0; i < nodes.length; i++) applyMask(nodes[i]);
  }

  // Initial pass + observe future mutations so SPA-rendered fields are
  // covered without a full re-scan.
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { scanAndMask(); });
  } else {
    scanAndMask();
  }

  if (typeof MutationObserver === 'function') {
    var mo = new MutationObserver(function (mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var m = mutations[i];
        for (var j = 0; j < m.addedNodes.length; j++) {
          var n = m.addedNodes[j];
          if (n.nodeType === 1) scanAndMask(n);
        }
      }
    });
    mo.observe(document.documentElement, { childList: true, subtree: true });
  }

  // Surface the install for tests / debugging.
  window.__lcGuardrails = {
    selectors: SENSITIVE_SELECTORS.slice(),
    isMatch: matches,
    rescan: function () { scanAndMask(); }
  };
})();
