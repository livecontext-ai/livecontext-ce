/**
 * Minimal markdown-to-HTML for the developer RichTextarea preview.
 *
 * SECURITY: the raw input is HTML-escaped BEFORE any markdown regex runs, so user-typed tags
 * (e.g. `<img src=x onerror=...>`) render as text and cannot execute in the
 * dangerouslySetInnerHTML preview. Link hrefs are scheme-validated so a `javascript:`/`data:`
 * URL cannot slip through. Markdown syntax chars (*, `, [, ], (, )) are not escaped; the `>`
 * blockquote marker becomes `&gt;`, matched below.
 */
export function escapeHtmlForPreview(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

export function safePreviewHref(url: string): string {
  const trimmed = url.trim();
  return /^(https?:|mailto:|\/|#)/i.test(trimmed) ? trimmed : '#';
}

export function renderMarkdownPreview(text: string): string {
  if (!text) return '';

  return escapeHtmlForPreview(text)
    // Line breaks
    .replace(/\n/g, '<br>')

    // Bold
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')

    // Italic
    .replace(/\*(.*?)\*/g, '<em>$1</em>')

    // Inline code
    .replace(/`(.*?)`/g, '<code class="inline-code">$1</code>')

    // Quotes (the `>` marker is now escaped to `&gt;`)
    .replace(/^&gt; (.*?)$/gm, '<blockquote class="quote">$1</blockquote>')

    // Bullet lists
    .replace(/^- (.*?)$/gm, '<li class="list-item">$1</li>')

    // Numbered lists
    .replace(/^\d+\. (.*?)$/gm, '<li class="list-item">$1</li>')

    // Links (href scheme-validated; label already HTML-escaped above)
    .replace(/\[([^\]]+)\]\(([^)]+)\)/g,
      (_m, label, url) =>
        `<a href="${safePreviewHref(url)}" target="_blank" rel="noopener noreferrer" class="link">${label}</a>`)

    // Clean up lists
    .replace(/(<li class="list-item">[\s\S]*?<\/li>)/g, '<ul class="list">$1</ul>')
    .replace(/<\/ul>\s*<ul class="list">/g, '')

    // Clean up quotes
    .replace(/(<blockquote class="quote">[\s\S]*?<\/blockquote>)/g, '<div class="quote-container">$1</div>');
}
