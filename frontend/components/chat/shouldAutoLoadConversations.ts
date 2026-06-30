/**
 * Whether the conversation list should be auto-loaded for the current app view.
 *
 * The sidebar shows conversation titles on every primary app surface (chat + all the
 * resource views), so the list must be fetched there. This is an allowlist: a view or
 * path NOT listed here renders the sidebar without conversations. The aggregated Board
 * (`/app/board`, view `'board'`) is a primary surface too - omitting it is what made the
 * conversation titles disappear there.
 *
 * Kept as a pure function so the allowlist is unit-tested (and a missing view like
 * `'board'` fails a test instead of silently blanking the sidebar).
 */
const AUTO_LOAD_VIEWS = [
  'chat',
  'data',
  'files',
  'workflow',
  'settings',
  'interface',
  'agent',
  'marketplace',
  'applications',
  'project',
  'tasks',
  'board',
] as const;

const AUTO_LOAD_PATH_PREFIXES = [
  '/app/chat',
  '/app/c/',
  '/app/tables',
  '/app/files',
  '/app/workflow',
  '/app/settings',
  '/app/interface',
  '/app/agent',
  '/app/marketplace',
  '/app/applications',
  '/app/project',
  '/app/tasks',
  '/app/board',
] as const;

export interface AutoLoadConversationsInput {
  isAuthenticated: boolean;
  currentView: string;
  urlConversationId: string | null;
  pathname: string | null | undefined;
}

export function shouldAutoLoadConversations({
  isAuthenticated,
  currentView,
  urlConversationId,
  pathname,
}: AutoLoadConversationsInput): boolean {
  if (!isAuthenticated) return false;
  return (
    (AUTO_LOAD_VIEWS as readonly string[]).includes(currentView) ||
    urlConversationId !== null ||
    (!!pathname && AUTO_LOAD_PATH_PREFIXES.some((p) => pathname.startsWith(p)))
  );
}
