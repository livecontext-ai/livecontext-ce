import Link from 'next/link';
import { ArrowLeft, ArrowRight } from 'lucide-react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import SignInButton from '@/app/[locale]/_landing/SignInButton';
import type { BlogPost } from '@/lib/blog/postUtils';
import { formatBlogDate, estimateReadingMinutes } from '@/lib/blog/postUtils';
import type { BlogUi } from '@/lib/blog/i18n';
import { AuthorByline } from './AuthorByline';

// Presentational article page (back link, header, cover, prose, CTA), shared by
// the English route and the localized routes. Copy comes in through `ui`;
// `backHref` points at the right blog index for the locale.
export function BlogArticleView({
  post,
  ui,
  locale,
  backHref,
}: {
  post: BlogPost;
  ui: BlogUi;
  locale: string;
  backHref: string;
}) {
  return (
    <article className="max-w-3xl mx-auto px-6 py-16 md:py-20">
      <Link href={backHref} className="blog-back">
        <ArrowLeft className="h-3.5 w-3.5" />
        {ui.allPosts}
      </Link>

      <header className="mt-6">
        {post.tags.length > 0 && (
          <div className="blog-tags">
            {post.tags.map((tag) => (
              <span key={tag} className="blog-tag">{tag}</span>
            ))}
          </div>
        )}
        <h1 className="docs-h1" style={{ marginTop: '0.75rem' }}>{post.title}</h1>
        <div className="blog-article-meta">
          <AuthorByline authors={post.authors} by={ui.by} and={ui.and} />
          <span className="blog-dot" aria-hidden="true">·</span>
          <time dateTime={post.date}>{formatBlogDate(post.date, locale)}</time>
          <span className="blog-dot" aria-hidden="true">·</span>
          <span>{estimateReadingMinutes(post.content)} {ui.minRead}</span>
        </div>
      </header>

      <div className="blog-article-cover">
        <img className="blog-cover" src={post.cover} alt={post.coverAlt} width={1280} height={640} />
      </div>

      <div className="docs-prose">
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          components={{
            table: ({ node: _n, ...p }) => (
              <div className="docs-table-wrap">
                <table {...p} />
              </div>
            ),
          }}
        >
          {post.content}
        </ReactMarkdown>
      </div>

      <div className="blog-cta">
        <p className="blog-cta-title">{ui.ctaTitle}</p>
        <p className="blog-cta-text">{ui.ctaText}</p>
        <SignInButton variant="primary" className="blog-cta-button">
          {ui.startFree} <ArrowRight className="h-4 w-4" />
        </SignInButton>
      </div>
    </article>
  );
}
