import type { Metadata } from 'next';
import React from 'react';

/**
 * An account page, not a search result: without this it inherited the landing's
 * title, description and `index, follow`, so any crawler that fetched it saw a
 * duplicate of the home page. (Login and register are also disallowed in
 * robots.txt; this keeps them out of the index if a crawler reaches them anyway.)
 */
export const metadata: Metadata = {
  robots: { index: false, follow: false },
};

export default function AccountPageLayout({ children }: { children: React.ReactNode }) {
  return children;
}
