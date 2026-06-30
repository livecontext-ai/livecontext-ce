// Docs-specific CSS, injected once via `LandingShell`'s `extraStyles` prop (the
// same mechanism the landing page uses for its hero/feature styles). Every
// selector is scoped under `.landing-root` so it inherits the public theme
// tokens (`--bg-*`, `--text-*`, `--border-color`, `--accent-*`) and stays
// decoupled from the app-wide theme. Header height is h-20 (80px) - sticky
// offsets and scroll-margins are tuned to it.

export const docsStyles = `
  /* ---- Layout ------------------------------------------------------------ */
  .landing-root .docs-layout {
    display: grid;
    grid-template-columns: 1fr;
    gap: 0;
    max-width: 90rem;
    margin: 0 auto;
    padding: 0 1.5rem;
  }

  @media (min-width: 1024px) {
    .landing-root .docs-layout {
      grid-template-columns: 15rem minmax(0, 1fr);
      gap: 2.5rem;
    }
  }

  @media (min-width: 1280px) {
    .landing-root .docs-layout {
      grid-template-columns: 15rem minmax(0, 1fr) 13rem;
    }
  }

  .landing-root .docs-sidebar {
    display: none;
  }

  @media (min-width: 1024px) {
    .landing-root .docs-sidebar {
      display: block;
      position: sticky;
      top: 5rem;
      align-self: start;
      height: calc(100vh - 5rem);
      overflow-y: auto;
      padding: 2rem 0.5rem 2rem 0;
    }
  }

  .landing-root .docs-content {
    min-width: 0;
    padding: 2.25rem 0 4rem;
  }

  @media (min-width: 1024px) {
    .landing-root .docs-content {
      padding: 2.75rem 0 5rem;
    }
  }

  .landing-root .docs-toc {
    display: none;
  }

  @media (min-width: 1280px) {
    .landing-root .docs-toc {
      display: block;
      position: sticky;
      top: 5rem;
      align-self: start;
      max-height: calc(100vh - 5rem);
      overflow-y: auto;
      padding: 2.75rem 0;
    }
  }

  /* ---- Sidebar nav ------------------------------------------------------- */
  .landing-root .docs-nav-search {
    width: 100%;
    height: 2.25rem;
    padding: 0 0.75rem 0 2rem;
    font-size: 0.8125rem;
    color: var(--text-primary);
    background: var(--bg-tertiary);
    border: 1px solid var(--border-color);
    border-radius: 0.625rem;
    outline: none;
    transition: border-color 0.15s ease;
  }

  .landing-root .docs-nav-search:focus {
    border-color: var(--text-muted);
  }

  .landing-root .docs-nav-search::placeholder {
    color: var(--text-muted);
  }

  .landing-root .docs-nav-section-label {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    font-size: 0.6875rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--text-muted);
    padding: 0 0.625rem;
    margin-bottom: 0.5rem;
  }

  .landing-root .docs-nav-link {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    padding: 0.375rem 0.625rem;
    font-size: 0.875rem;
    line-height: 1.3;
    color: var(--text-secondary);
    border-radius: 0.5rem;
    border-left: 2px solid transparent;
    transition: color 0.15s ease, background 0.15s ease;
  }

  .landing-root .docs-nav-link:hover {
    color: var(--text-primary);
    background: var(--bg-tertiary);
  }

  .landing-root .docs-nav-link.is-active {
    color: var(--text-primary);
    background: var(--bg-tertiary);
    font-weight: 600;
  }

  .landing-root .docs-nav-badge {
    font-size: 0.625rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--text-muted);
    border: 1px solid var(--border-color);
    border-radius: 9999px;
    padding: 0.0625rem 0.4rem;
  }

  .landing-root .docs-nav-disabled {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    padding: 0.375rem 0.625rem;
    font-size: 0.875rem;
    color: var(--text-muted);
    border-radius: 0.5rem;
    cursor: default;
  }

  /* ---- Mobile drawer ----------------------------------------------------- */
  .landing-root .docs-mobilebar {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    margin-top: 1rem;
  }

  @media (min-width: 1024px) {
    .landing-root .docs-mobilebar {
      display: none;
    }
  }

  .landing-root .docs-mobile-toggle {
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    height: 2.25rem;
    padding: 0 0.875rem;
    font-size: 0.8125rem;
    font-weight: 500;
    color: var(--text-primary);
    background: var(--bg-tertiary);
    border: 1px solid var(--border-color);
    border-radius: 9999px;
    cursor: pointer;
  }

  .landing-root .docs-drawer-backdrop {
    position: fixed;
    inset: 0;
    z-index: 60;
    background: rgba(0, 0, 0, 0.5);
    backdrop-filter: blur(2px);
  }

  .landing-root .docs-drawer {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    z-index: 61;
    width: 17rem;
    max-width: 84vw;
    overflow-y: auto;
    padding: 1.25rem 1rem;
    background: var(--bg-secondary);
    border-right: 1px solid var(--border-color);
  }

  /* ---- In-page TOC ------------------------------------------------------- */
  .landing-root .docs-toc-label {
    font-size: 0.6875rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--text-muted);
    margin-bottom: 0.75rem;
  }

  .landing-root .docs-toc-link {
    display: block;
    padding: 0.25rem 0;
    padding-left: 0.75rem;
    font-size: 0.8125rem;
    line-height: 1.35;
    color: var(--text-muted);
    border-left: 2px solid var(--border-color);
    transition: color 0.15s ease, border-color 0.15s ease;
  }

  .landing-root .docs-toc-link.is-sub {
    padding-left: 1.5rem;
  }

  .landing-root .docs-toc-link:hover {
    color: var(--text-secondary);
  }

  .landing-root .docs-toc-link.is-active {
    color: var(--text-primary);
    border-left-color: var(--text-primary);
  }

  /* ---- Page hero --------------------------------------------------------- */
  .landing-root .docs-eyebrow {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    font-size: 0.6875rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--text-muted);
  }

  .landing-root .docs-h1 {
    margin-top: 0.75rem;
    font-family: var(--font-outfit), 'Outfit', sans-serif;
    font-weight: 700;
    font-size: clamp(1.875rem, 4vw, 2.5rem);
    line-height: 1.1;
    letter-spacing: -0.02em;
    color: var(--text-primary);
  }

  .landing-root .docs-lead {
    margin-top: 1rem;
    font-size: 1.0625rem;
    line-height: 1.6;
    color: var(--text-secondary);
    max-width: 44rem;
  }

  /* ---- Prose ------------------------------------------------------------- */
  .landing-root .docs-prose {
    margin-top: 2.5rem;
    color: var(--text-secondary);
    font-size: 0.9375rem;
    line-height: 1.7;
    max-width: 46rem;
  }

  .landing-root .docs-prose > * + * {
    margin-top: 1.1rem;
  }

  .landing-root .docs-prose h2 {
    margin-top: 2.75rem;
    margin-bottom: 0.25rem;
    padding-top: 0.5rem;
    font-family: var(--font-outfit), 'Outfit', sans-serif;
    font-weight: 700;
    font-size: 1.5rem;
    letter-spacing: -0.015em;
    line-height: 1.25;
    color: var(--text-primary);
    scroll-margin-top: 6rem;
  }

  .landing-root .docs-prose h3 {
    margin-top: 2rem;
    margin-bottom: 0.25rem;
    font-family: var(--font-outfit), 'Outfit', sans-serif;
    font-weight: 600;
    font-size: 1.15rem;
    letter-spacing: -0.01em;
    line-height: 1.3;
    color: var(--text-primary);
    scroll-margin-top: 6rem;
  }

  .landing-root .docs-prose h4 {
    margin-top: 1.5rem;
    font-weight: 600;
    font-size: 1rem;
    color: var(--text-primary);
  }

  .landing-root .docs-prose p {
    color: var(--text-secondary);
  }

  .landing-root .docs-prose strong {
    color: var(--text-primary);
    font-weight: 600;
  }

  .landing-root .docs-prose a {
    color: var(--expression-color);
    text-decoration: none;
    font-weight: 500;
  }

  .landing-root .docs-prose a:hover {
    text-decoration: underline;
  }

  .landing-root .docs-prose ul,
  .landing-root .docs-prose ol {
    padding-left: 1.4rem;
    color: var(--text-secondary);
  }

  .landing-root .docs-prose ul { list-style: disc; }
  .landing-root .docs-prose ol { list-style: decimal; }

  .landing-root .docs-prose li + li {
    margin-top: 0.4rem;
  }

  .landing-root .docs-prose li::marker {
    color: var(--text-muted);
  }

  .landing-root .docs-prose blockquote {
    border-left: 3px solid var(--border-color);
    padding-left: 1rem;
    color: var(--text-muted);
    font-style: italic;
  }

  .landing-root .docs-prose hr {
    border: 0;
    border-top: 1px solid var(--border-color);
    margin: 2rem 0;
  }

  /* Inline code */
  .landing-root .docs-prose :not(pre) > code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 0.85em;
    color: var(--text-primary);
    background: var(--bg-tertiary);
    border: 1px solid var(--border-color);
    border-radius: 0.35rem;
    padding: 0.1em 0.38em;
    white-space: nowrap;
  }

  /* ---- Tables in prose --------------------------------------------------- */
  .landing-root .docs-table-wrap {
    overflow-x: auto;
    border: 1px solid var(--border-color);
    border-radius: 0.75rem;
  }

  .landing-root .docs-table-wrap table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.875rem;
  }

  .landing-root .docs-table-wrap th {
    text-align: left;
    font-weight: 600;
    color: var(--text-primary);
    background: var(--bg-tertiary);
    padding: 0.625rem 0.875rem;
    border-bottom: 1px solid var(--border-color);
    white-space: nowrap;
  }

  .landing-root .docs-table-wrap td {
    color: var(--text-secondary);
    padding: 0.625rem 0.875rem;
    border-bottom: 1px solid var(--border-color);
    vertical-align: top;
  }

  .landing-root .docs-table-wrap tr:last-child td {
    border-bottom: 0;
  }

  /* ---- Callouts ---------------------------------------------------------- */
  .landing-root .docs-callout {
    display: flex;
    gap: 0.75rem;
    padding: 0.875rem 1rem;
    border: 1px solid var(--border-color);
    border-left-width: 3px;
    border-radius: 0.625rem;
    background: var(--bg-tertiary);
    font-size: 0.9rem;
    color: var(--text-secondary);
  }

  .landing-root .docs-callout > svg {
    flex-shrink: 0;
    margin-top: 0.1rem;
  }

  .landing-root .docs-callout p { margin: 0; color: var(--text-secondary); }
  .landing-root .docs-callout p + p { margin-top: 0.5rem; }
  .landing-root .docs-callout-info { border-left-color: var(--expression-color); }
  .landing-root .docs-callout-info > svg { color: var(--expression-color); }
  .landing-root .docs-callout-tip { border-left-color: #10b981; }
  .landing-root .docs-callout-tip > svg { color: #10b981; }
  .landing-root .docs-callout-warn { border-left-color: #f59e0b; }
  .landing-root .docs-callout-warn > svg { color: #f59e0b; }

  /* ---- Code block -------------------------------------------------------- */
  .landing-root .docs-code {
    position: relative;
    border: 1px solid var(--border-color);
    border-radius: 0.75rem;
    overflow: hidden;
    background: var(--bg-secondary);
  }

  .landing-root .docs-code-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 2.25rem;
    padding: 0 0.75rem;
    border-bottom: 1px solid var(--border-color);
    background: var(--bg-tertiary);
  }

  .landing-root .docs-code-lang {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 0.7rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--text-muted);
  }

  .landing-root .docs-code-copy {
    display: inline-flex;
    align-items: center;
    gap: 0.3rem;
    font-size: 0.7rem;
    color: var(--text-muted);
    background: transparent;
    border: 0;
    cursor: pointer;
    transition: color 0.15s ease;
  }

  .landing-root .docs-code-copy:hover {
    color: var(--text-primary);
  }

  /* ---- Cards ------------------------------------------------------------- */
  .landing-root .docs-card-grid {
    display: grid;
    grid-template-columns: 1fr;
    gap: 0.875rem;
  }

  @media (min-width: 640px) {
    .landing-root .docs-card-grid.cols-2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .landing-root .docs-card-grid.cols-3 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  }

  @media (min-width: 1024px) {
    .landing-root .docs-card-grid.cols-3 { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  }

  .landing-root .docs-card {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    padding: 1.1rem;
    border: 1px solid var(--border-color);
    border-radius: 0.875rem;
    background: var(--bg-tertiary);
    box-shadow: var(--landing-card-shadow);
    transition: transform 0.2s ease, border-color 0.2s ease;
  }

  .landing-root a.docs-card:hover {
    transform: translateY(-2px);
    border-color: var(--text-muted);
  }

  .landing-root .docs-card-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 2.25rem;
    height: 2.25rem;
    border-radius: 0.625rem;
    background: var(--bg-secondary);
    border: 1px solid var(--border-color);
    color: var(--landing-icon-color);
  }

  .landing-root .docs-card-title {
    font-weight: 600;
    font-size: 0.9375rem;
    color: var(--text-primary);
  }

  .landing-root .docs-card-desc {
    font-size: 0.85rem;
    line-height: 1.5;
    color: var(--text-secondary);
  }

  /* ---- Steps ------------------------------------------------------------- */
  .landing-root .docs-steps {
    display: flex;
    flex-direction: column;
    gap: 0;
    margin-top: 0.5rem;
  }

  .landing-root .docs-step {
    position: relative;
    padding-left: 2.5rem;
    padding-bottom: 1.5rem;
  }

  .landing-root .docs-step:last-child { padding-bottom: 0; }

  .landing-root .docs-step::before {
    content: '';
    position: absolute;
    left: 0.875rem;
    top: 1.75rem;
    bottom: 0;
    width: 1px;
    background: var(--border-color);
  }

  .landing-root .docs-step:last-child::before { display: none; }

  .landing-root .docs-step-num {
    position: absolute;
    left: 0;
    top: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 1.75rem;
    height: 1.75rem;
    border-radius: 9999px;
    font-size: 0.8rem;
    font-weight: 700;
    color: var(--accent-foreground);
    background: var(--accent-primary);
  }

  .landing-root .docs-step-title {
    font-weight: 600;
    font-size: 0.9375rem;
    color: var(--text-primary);
    padding-top: 0.15rem;
  }

  .landing-root .docs-step-body {
    margin-top: 0.35rem;
    font-size: 0.9rem;
    line-height: 1.6;
    color: var(--text-secondary);
  }

  /* ---- Prev / next ------------------------------------------------------- */
  .landing-root .docs-prevnext {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.875rem;
    margin-top: 3.5rem;
    padding-top: 1.5rem;
    border-top: 1px solid var(--border-color);
  }

  .landing-root .docs-prevnext-link {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    padding: 0.875rem 1rem;
    border: 1px solid var(--border-color);
    border-radius: 0.75rem;
    transition: border-color 0.2s ease, transform 0.2s ease;
  }

  .landing-root .docs-prevnext-link:hover {
    border-color: var(--text-muted);
    transform: translateY(-1px);
  }

  .landing-root .docs-prevnext-link.is-next { text-align: right; }

  .landing-root .docs-prevnext-dir {
    font-size: 0.7rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--text-muted);
  }

  .landing-root .docs-prevnext-title {
    font-weight: 600;
    font-size: 0.9375rem;
    color: var(--text-primary);
  }
`;
