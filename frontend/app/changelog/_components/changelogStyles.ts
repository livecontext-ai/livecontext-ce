// Changelog-specific CSS, injected once via `LandingShell`'s `extraStyles`
// prop (appended after `docsStyles`, whose `.docs-prose` rules format each
// release's markdown body). Every selector is scoped under `.landing-root` so
// it inherits the public theme tokens (`--bg-*`, `--text-*`, `--border-color`,
// `--expression-color`) and stays decoupled from the app-wide theme.
//
// The layout is a vertical timeline: a rail with one dot per release, a
// date + version-tag header row, the release title, then the markdown body.
// This replaces the old flat, undelimited text dump with a scannable
// chronological frieze.

export const changelogStyles = `
  /* ---- Timeline shell ---------------------------------------------------- */
  .landing-root .cl-timeline {
    position: relative;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .landing-root .cl-entry {
    position: relative;
    padding-left: 2rem;
    padding-bottom: 3rem;
  }

  @media (min-width: 640px) {
    .landing-root .cl-entry { padding-left: 2.75rem; }
  }

  .landing-root .cl-entry:last-child { padding-bottom: 0; }

  /* Rail: starts at this entry's dot and runs the full entry height, so it
     reaches under the next dot (opaque, z-index 1) for one continuous line.
     Hidden on the last entry so the line stops at the final dot. */
  .landing-root .cl-entry::before {
    content: '';
    position: absolute;
    left: 0.4375rem;
    top: 0.95rem;
    height: 100%;
    width: 2px;
    background: var(--border-color);
  }

  @media (min-width: 640px) {
    .landing-root .cl-entry::before { left: 0.5rem; top: 1.05rem; }
  }

  .landing-root .cl-entry:last-child::before { display: none; }

  /* Dot */
  .landing-root .cl-dot {
    position: absolute;
    left: 0;
    top: 0.45rem;
    width: 0.9375rem;
    height: 0.9375rem;
    border-radius: 9999px;
    box-sizing: border-box;
    background: var(--bg-primary);
    border: 2px solid var(--border-color);
    z-index: 1;
  }

  @media (min-width: 640px) {
    .landing-root .cl-dot { width: 1.0625rem; height: 1.0625rem; top: 0.5rem; }
  }

  .landing-root .cl-dot.is-latest {
    border-color: var(--expression-color);
    background: var(--expression-color);
    box-shadow: 0 0 0 4px color-mix(in srgb, var(--expression-color) 18%, transparent);
  }

  /* ---- Entry header (date + version + latest) ---------------------------- */
  .landing-root .cl-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.625rem;
  }

  .landing-root .cl-date {
    font-size: 0.8125rem;
    font-weight: 500;
    letter-spacing: 0.01em;
    color: var(--text-muted);
  }

  .landing-root .cl-version {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 0.75rem;
    font-weight: 600;
    color: var(--text-secondary);
    background: var(--bg-tertiary);
    border: 1px solid var(--border-color);
    border-radius: 9999px;
    padding: 0.125rem 0.5rem;
  }

  .landing-root .cl-latest {
    font-size: 0.625rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--expression-color);
    border: 1px solid color-mix(in srgb, var(--expression-color) 40%, transparent);
    border-radius: 9999px;
    padding: 0.0625rem 0.4rem;
  }

  /* ---- Title ------------------------------------------------------------- */
  .landing-root .cl-title {
    margin-top: 0.5rem;
    font-family: var(--font-outfit), 'Outfit', sans-serif;
    font-weight: 700;
    font-size: 1.375rem;
    letter-spacing: -0.015em;
    line-height: 1.25;
    color: var(--text-primary);
    scroll-margin-top: 6rem;
  }

  @media (min-width: 640px) {
    .landing-root .cl-title { font-size: 1.5rem; }
  }

  .landing-root .cl-title a { color: inherit; text-decoration: none; }
  .landing-root .cl-title a:hover { color: var(--expression-color); }

  /* ---- Body: reuse .docs-prose, trimmed for the tighter timeline column --- */
  .landing-root .cl-body.docs-prose {
    margin-top: 0.875rem;
    max-width: none;
    font-size: 0.9375rem;
  }

  .landing-root .cl-body.docs-prose > * + * { margin-top: 0.9rem; }

  /* Body headings are demoted (## -> h3, ### -> h4) so the entry title stays
     the only h2, but they keep the visual weight of a section heading. */
  .landing-root .cl-body.docs-prose h3 {
    margin-top: 1.75rem;
    padding-top: 0;
    font-size: 1.1rem;
  }

  .landing-root .cl-body.docs-prose h4 {
    margin-top: 1.35rem;
    font-size: 0.975rem;
  }

  /* ---- View-on-GitHub link ----------------------------------------------- */
  .landing-root .cl-gh {
    display: inline-flex;
    align-items: center;
    gap: 0.35rem;
    margin-top: 1.1rem;
    font-size: 0.8125rem;
    font-weight: 500;
    color: var(--text-muted);
    transition: color 0.15s ease;
  }

  .landing-root .cl-gh:hover { color: var(--text-primary); }

  /* ---- Empty / error fallback -------------------------------------------- */
  .landing-root .cl-fallback {
    border: 1px solid var(--border-color);
    border-radius: 0.875rem;
    background: var(--bg-tertiary);
    padding: 2.5rem 1.5rem;
    text-align: center;
    font-size: 0.9rem;
    color: var(--text-muted);
  }

  .landing-root .cl-fallback a { color: var(--expression-color); font-weight: 500; }
`;
