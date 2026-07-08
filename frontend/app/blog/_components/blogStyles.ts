// Blog-specific CSS, injected via `LandingShell`'s `extraStyles` prop alongside
// `docsStyles` (which supplies the shared eyebrow / h1 / lead / prose styling).
// Every selector is scoped under `.landing-root` so it inherits the public
// theme tokens and stays decoupled from the app-wide theme.

export const blogStyles = `
  /* ---- Shared image + meta bits ------------------------------------------ */
  .landing-root .blog-cover {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    background: var(--bg-tertiary);
  }

  .landing-root .blog-meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
    font-size: 0.75rem;
    color: var(--text-muted);
  }

  .landing-root .blog-dot {
    color: var(--text-muted);
  }

  .landing-root .blog-tags {
    display: inline-flex;
    flex-wrap: wrap;
    gap: 0.375rem;
  }

  .landing-root .blog-tag {
    display: inline-flex;
    align-items: center;
    padding: 0.15rem 0.55rem;
    border-radius: 9999px;
    background: var(--bg-secondary);
    border: 1px solid var(--border-color);
    font-size: 0.6875rem;
    font-weight: 500;
    color: var(--text-secondary);
  }

  /* ---- Author byline (persona avatars + names) --------------------------- */
  .landing-root .blog-byline {
    display: inline-flex;
    align-items: center;
    gap: 0.45rem;
  }

  .landing-root .blog-avatars {
    display: inline-flex;
    flex-shrink: 0;
  }

  .landing-root .blog-avatar {
    width: 22px;
    height: 22px;
    border-radius: 9999px;
    object-fit: cover;
    border: 1.5px solid var(--bg-tertiary);
    background: var(--bg-secondary);
  }

  .landing-root .blog-avatar + .blog-avatar {
    margin-left: -8px;
  }

  /* ---- Featured (newest) post -------------------------------------------- */
  .landing-root .blog-featured {
    display: grid;
    grid-template-columns: 1fr;
    gap: 0;
    margin-top: 2.5rem;
    border-radius: 1.25rem;
    overflow: hidden;
    border: 1px solid var(--border-color);
    background: var(--bg-tertiary);
    box-shadow: var(--landing-card-shadow);
    transition: transform 0.25s ease, border-color 0.25s ease;
  }

  .landing-root .blog-featured:hover {
    transform: translateY(-3px);
    border-color: var(--text-muted);
  }

  @media (min-width: 768px) {
    .landing-root .blog-featured {
      grid-template-columns: 1.15fr 1fr;
      align-items: stretch;
    }
  }

  .landing-root .blog-featured-media {
    position: relative;
    aspect-ratio: 16 / 10;
    overflow: hidden;
  }

  @media (min-width: 768px) {
    .landing-root .blog-featured-media {
      aspect-ratio: auto;
      min-height: 100%;
    }
  }

  .landing-root .blog-featured-flag {
    position: absolute;
    top: 0.9rem;
    left: 0.9rem;
    padding: 0.2rem 0.6rem;
    border-radius: 9999px;
    font-size: 0.625rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    background: rgba(23, 22, 20, 0.72);
    color: #f1f5f9;
    backdrop-filter: blur(6px);
    -webkit-backdrop-filter: blur(6px);
  }

  .landing-root .blog-featured-body {
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 0.75rem;
    padding: 1.75rem;
  }

  @media (min-width: 768px) {
    .landing-root .blog-featured-body {
      padding: 2.5rem;
    }
  }

  .landing-root .blog-featured-title {
    font-family: var(--font-outfit), 'Outfit', sans-serif;
    font-weight: 700;
    font-size: 1.65rem;
    line-height: 1.15;
    letter-spacing: -0.02em;
    color: var(--text-primary);
  }

  @media (min-width: 768px) {
    .landing-root .blog-featured-title {
      font-size: 2rem;
    }
  }

  .landing-root .blog-featured-excerpt {
    font-size: 0.9375rem;
    line-height: 1.65;
    color: var(--text-secondary);
  }

  /* ---- Post grid --------------------------------------------------------- */
  .landing-root .blog-grid {
    margin-top: 1.5rem;
    display: grid;
    grid-template-columns: 1fr;
    gap: 1.5rem;
  }

  @media (min-width: 640px) {
    .landing-root .blog-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  .landing-root .blog-card {
    display: flex;
    flex-direction: column;
    border-radius: 1rem;
    overflow: hidden;
    border: 1px solid var(--border-color);
    background: var(--bg-tertiary);
    box-shadow: var(--landing-card-shadow);
    transition: transform 0.25s ease, border-color 0.25s ease;
  }

  .landing-root .blog-card:hover {
    transform: translateY(-3px);
    border-color: var(--text-muted);
  }

  .landing-root .blog-card-media {
    aspect-ratio: 16 / 9;
    overflow: hidden;
  }

  .landing-root .blog-cover {
    transition: transform 0.4s ease;
  }

  .landing-root .blog-card:hover .blog-cover {
    transform: scale(1.03);
  }

  .landing-root .blog-card-body {
    display: flex;
    flex-direction: column;
    gap: 0.6rem;
    padding: 1.25rem;
  }

  .landing-root .blog-card-title {
    font-family: var(--font-outfit), 'Outfit', sans-serif;
    font-weight: 700;
    font-size: 1.2rem;
    letter-spacing: -0.015em;
    line-height: 1.2;
    color: var(--text-primary);
  }

  .landing-root .blog-card-excerpt {
    font-size: 0.875rem;
    line-height: 1.6;
    color: var(--text-secondary);
  }

  .landing-root .blog-read-more {
    margin-top: 0.15rem;
    display: inline-flex;
    align-items: center;
    gap: 0.35rem;
    font-size: 0.8125rem;
    font-weight: 600;
    color: var(--text-primary);
  }

  .landing-root .blog-card:hover .blog-read-more,
  .landing-root .blog-featured:hover .blog-read-more {
    gap: 0.55rem;
  }

  /* ---- Article ----------------------------------------------------------- */
  .landing-root .blog-back {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    font-size: 0.8125rem;
    color: var(--text-muted);
    transition: color 0.2s ease;
  }

  .landing-root .blog-back:hover {
    color: var(--text-primary);
  }

  .landing-root .blog-article-cover {
    margin-top: 1.5rem;
    aspect-ratio: 16 / 8;
    overflow: hidden;
    border-radius: 1.25rem;
    border: 1px solid var(--border-color);
    box-shadow: var(--landing-frame-shadow);
  }

  .landing-root .blog-article-meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem;
    margin-top: 1rem;
    font-size: 0.8125rem;
    color: var(--text-muted);
  }

  .landing-root .blog-cta {
    margin-top: 3.5rem;
    padding: 2rem;
    border-radius: 1.25rem;
    border: 1px solid var(--border-color);
    background: var(--bg-secondary);
    text-align: center;
  }

  .landing-root .blog-cta-title {
    font-family: var(--font-outfit), 'Outfit', sans-serif;
    font-weight: 700;
    font-size: 1.35rem;
    letter-spacing: -0.015em;
    color: var(--text-primary);
  }

  .landing-root .blog-cta-text {
    margin-top: 0.5rem;
    font-size: 0.9375rem;
    color: var(--text-secondary);
  }

  .landing-root .blog-cta-button {
    margin-top: 1.25rem;
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    height: 2.75rem;
    padding: 0 1.5rem;
    border-radius: 9999px;
    font-size: 0.875rem;
    font-weight: 600;
    background: var(--accent-primary);
    color: var(--accent-foreground);
    transition: filter 0.2s ease;
  }

  .landing-root .blog-cta-button:hover {
    filter: brightness(1.1);
  }
`;
