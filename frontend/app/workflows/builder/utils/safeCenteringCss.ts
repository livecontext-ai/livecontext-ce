/**
 * Body-level CSS prepended to publisher iframes (interface previews, marketplace
 * cards, showcase, application carousel, the standalone /app/interface/[id] page,
 * etc.) so a small interface looks centered AND a tall dashboard remains scrollable
 * from its top.
 *
 * <p>The keyword `safe center` is the load-bearing piece: when the flex child
 * exceeds the container's cross-size, browsers fall back to `flex-start`. Without
 * `safe`, the classic flex-center overflow bug clips the top of an oversized
 * dashboard above the viewport with no scrollbar reachable above scrollTop=0
 * (observed in prod on the "Competitive Intelligence Daily" marketplace preview).</p>
 *
 * <p>Browser support: Chrome 93+, Firefox 63+, Safari 16.4+. On older browsers
 * the keyword is silently ignored and the standard `center` value is used -
 * graceful degradation, no syntax error.</p>
 */
export const SAFE_CENTERING_CSS = `
  html { height: 100%; }
  body {
    min-height: 100%;
    margin: 0;
    display: flex;
    align-items: safe center;
    justify-content: safe center;
  }
`;
