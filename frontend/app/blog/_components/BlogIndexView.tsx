import Link from 'next/link';
import { ArrowRight } from 'lucide-react';
import type { BlogPost } from '@/lib/blog/postUtils';
import { formatBlogDate, estimateReadingMinutes } from '@/lib/blog/postUtils';
import type { BlogUi } from '@/lib/blog/i18n';
import { AuthorByline } from './AuthorByline';

// Presentational blog index (featured post + card grid), shared by the English
// route (`/blog`) and the localized routes (`/<locale>/blog`). All copy comes in
// through `ui`; `hrefFor` builds the correct per-locale article link.
export function BlogIndexView({
  posts,
  ui,
  locale,
  hrefFor,
}: {
  posts: BlogPost[];
  ui: BlogUi;
  locale: string;
  hrefFor: (slug: string) => string;
}) {
  const [featured, ...rest] = posts;

  return (
    <div className="max-w-5xl mx-auto px-6 py-16 md:py-20">
      <header className="mb-2">
        <span className="docs-eyebrow">{ui.eyebrow}</span>
        <h1 className="docs-h1">{ui.blogTitle}</h1>
        <p className="docs-lead">{ui.lead}</p>
      </header>

      {featured && (
        <Link href={hrefFor(featured.slug)} className="blog-featured">
          <div className="blog-featured-media">
            <span className="blog-featured-flag">{ui.latest}</span>
            <img className="blog-cover" src={featured.cover} alt={featured.coverAlt} width={1280} height={800} />
          </div>
          <div className="blog-featured-body">
            <div className="blog-meta">
              <AuthorByline authors={featured.authors} by={ui.by} and={ui.and} />
              <span className="blog-dot" aria-hidden="true">·</span>
              <time dateTime={featured.date}>{formatBlogDate(featured.date, locale)}</time>
              <span className="blog-dot" aria-hidden="true">·</span>
              <span>{estimateReadingMinutes(featured.content)} {ui.minRead}</span>
            </div>
            <h2 className="blog-featured-title">{featured.title}</h2>
            <p className="blog-featured-excerpt">{featured.excerpt}</p>
            <span className="blog-read-more">
              {ui.readThePost} <ArrowRight className="h-4 w-4" />
            </span>
          </div>
        </Link>
      )}

      {rest.length > 0 && (
        <div className="blog-grid">
          {rest.map((post) => (
            <Link key={post.slug} href={hrefFor(post.slug)} className="blog-card">
              <div className="blog-card-media">
                <img className="blog-cover" src={post.cover} alt={post.coverAlt} loading="lazy" width={800} height={450} />
              </div>
              <div className="blog-card-body">
                <div className="blog-meta">
                  <AuthorByline authors={post.authors} by={ui.by} and={ui.and} />
                  <span className="blog-dot" aria-hidden="true">·</span>
                  <time dateTime={post.date}>{formatBlogDate(post.date, locale)}</time>
                  <span className="blog-dot" aria-hidden="true">·</span>
                  <span>{estimateReadingMinutes(post.content)} {ui.minRead}</span>
                </div>
                <h2 className="blog-card-title">{post.title}</h2>
                <p className="blog-card-excerpt">{post.excerpt}</p>
                {post.tags.length > 0 && (
                  <div className="blog-tags">
                    {post.tags.map((tag) => (
                      <span key={tag} className="blog-tag">{tag}</span>
                    ))}
                  </div>
                )}
                <span className="blog-read-more">
                  {ui.readMore} <ArrowRight className="h-4 w-4" />
                </span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
