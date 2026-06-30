import WidgetChat from './components/WidgetChat';

interface WidgetEmbedPageProps {
  params: Promise<{ token: string }>;
}

/**
 * Public widget embed page.
 * Loaded inside an iframe by widget.js.
 * No authentication required - uses widget token.
 */
export default async function WidgetEmbedPage({ params }: WidgetEmbedPageProps) {
  const { token } = await params;

  return <WidgetChat token={token} />;
}
