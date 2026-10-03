import { configure } from '@testing-library/dom';
import { afterEach } from 'vitest';

/**
 * Global test setup.
 *
 * `waitFor` defaults to a 1s budget. That is generous for a small hook and far too tight for the
 * heavier component trees in this suite: `InterfaceMappingsColumn.format.test.tsx` needs 0.4-3.7s
 * per test on an idle machine, so on a loaded one (the full 161-file run, or a backend build in
 * parallel) it overshot and failed. The failure looked exactly like a regression and was not one -
 * every one of those tests passes in isolation.
 *
 * 15s is sized off the slowest observed test with room for a loaded machine. It does NOT weaken
 * any assertion: `waitFor` still returns the moment the condition holds, so passing tests stay
 * fast; only a genuinely failing one now takes longer to report.
 *
 * The underlying slowness is worth its own look - a component that needs seconds to settle in
 * jsdom usually says something about its render or effect churn. Raising the budget stops that
 * slowness from producing FALSE failures; it does not make it acceptable.
 */
configure({ asyncUtilTimeout: 15_000 });

/**
 * Every test starts on a clean address.
 *
 * View state is mirrored in the query string (`useUrlState`), written through the history API,
 * and jsdom keeps ONE address for a whole test file. Without this, the tab or filter a test
 * clicked is still in the address when the next test mounts, and that test starts on it.
 */
afterEach(() => {
  if (typeof window !== 'undefined') window.history.replaceState(null, '', '/');
});
