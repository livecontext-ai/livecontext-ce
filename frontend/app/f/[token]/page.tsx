import PublicForm from './components/PublicForm';

interface PublicFormPageProps {
  params: Promise<{ token: string }>;
}

/**
 * Public form page.
 * Accessible without authentication - uses form endpoint token.
 * Gateway routes: /f/{token} → frontend (this page)
 * API calls go to: /form/{token}/config and /form/{token} (gateway → orchestrator)
 */
export default async function PublicFormPage({ params }: PublicFormPageProps) {
  const { token } = await params;

  return <PublicForm token={token} />;
}
