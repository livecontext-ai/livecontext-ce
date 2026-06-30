import type { ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';
import Link from 'next/link';

export function CardGrid({ cols = 2, children }: { cols?: 2 | 3; children: ReactNode }) {
  return <div className={`docs-card-grid cols-${cols}`}>{children}</div>;
}

interface CardProps {
  icon?: LucideIcon;
  title: string;
  href?: string;
  children?: ReactNode;
}

/** A linkable feature/navigation card. Internal `href` uses next/link; external opens a new tab. */
export function Card({ icon: Icon, title, href, children }: CardProps) {
  const inner = (
    <>
      {Icon ? (
        <span className="docs-card-icon">
          <Icon className="w-[18px] h-[18px]" aria-hidden="true" />
        </span>
      ) : null}
      <span className="docs-card-title">{title}</span>
      {children ? <span className="docs-card-desc">{children}</span> : null}
    </>
  );

  if (!href) return <div className="docs-card">{inner}</div>;
  if (href.startsWith('/')) {
    return (
      <Link href={href} className="docs-card">
        {inner}
      </Link>
    );
  }
  return (
    <a href={href} className="docs-card" target="_blank" rel="noopener noreferrer">
      {inner}
    </a>
  );
}
