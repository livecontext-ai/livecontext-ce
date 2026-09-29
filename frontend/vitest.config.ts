import { defineConfig } from 'vitest/config';
import path from 'path';

export default defineConfig({
  test: {
    environment: 'node',
    // The process zone, pinned.
    //
    // Several suites read as if UTC were a given: they assert a rendered day, or compare a value
    // parsed two ways, and both only hold when the host has no offset. That was true by accident -
    // CI runners are UTC - and false on a developer machine, so the same test could pass in one
    // place and fail in the other for a reason having nothing to do with the code. Pinning it makes
    // the dependence explicit and local runs match CI.
    //
    // Zone-sensitive behaviour is exercised by APPLYING a display zone in the test
    // (`applyDisplayTimeZone`) or by stubbing the device zone, never by the host - which is what
    // makes pinning safe. One consequence to know: a defect that only appears at a non-zero offset
    // (V8 parsing a whitespace-padded ISO date as LOCAL time) cannot fail an assertion here, and
    // the test for it says so rather than implying coverage.
    //
    // Three suites in this repo say "TZ= is no help, on Windows it does not change what Node
    // resolves", and both statements are true of different things. A shell prefix on a direct
    // invocation does nothing on Windows (measured: `TZ=Asia/Tokyo node -e ...` still resolved
    // Europe/Paris), because the zone is read from the OS rather than the variable. This works
    // because vitest passes `env` in the spawn environment of a fresh worker process, where Node
    // reads TZ on first use. Verified in-suite: the resolved zone is UTC and the offset is 0. If
    // the pool is ever changed to `threads`, worker threads inherit the process zone and this
    // becomes a silent no-op - the stubbing in those three suites is what keeps them honest either
    // way, which is why it stays.
    env: { TZ: 'UTC' },
    include: ['**/*.test.ts', '**/*.test.tsx'],
    exclude: ['**/node_modules/**', '**/dist/**'],
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    // Must stay ABOVE the `asyncUtilTimeout` configured in vitest.setup.ts (15s).
    // Vitest's default testTimeout is 5s, so a `waitFor`/`findBy*` that uses its full
    // budget was killed by the runner first: the test reported an opaque "Test timed
    // out in 5000ms" instead of the assertion that actually failed, and any test that
    // legitimately waits out a findBy* rejection could never pass. Keeping this margin
    // means the waitFor budget is the one that decides, and failures name their cause.
    testTimeout: 20_000,
    hookTimeout: 20_000,
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, '.'),
      // `server-only` throws on import by design, to fail a BUILD that pulls a
      // server module into a client bundle. That check still runs where it
      // matters (next build); here there is no client bundle to protect, and
      // leaving it live breaks any suite whose component tree reaches a server
      // module - which the shared public chrome now does, through the footer's
      // integration column. See vitest.stubs/server-only.ts.
      'server-only': path.resolve(__dirname, 'vitest.stubs/server-only.ts'),
    },
  },
});
