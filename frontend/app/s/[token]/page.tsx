import ShareResolver from './components/ShareResolver';

interface SharePageProps {
  params: Promise<{ token: string }>;
}

/**
 * Universal share page.
 * Accessible without authentication - uses share token for resolution.
 * Gateway routes: /s/{token} → frontend (this page)
 * Resolution API: /share/{token} → publication-service → SharedLinkResolution
 */
export default async function SharePage({ params }: SharePageProps) {
  const { token } = await params;

  return <ShareResolver token={token} />;
}
