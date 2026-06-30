/**
 * App Layout Components
 *
 * - AppSidebar: Left sidebar (navigation + conversations)
 * - AppHeader: Top header (breadcrumbs + actions)
 * - SidePanel: Right side panel (tab-based, lazy-rendered)
 *
 * All three are mounted in app/[locale]/app/layout.tsx.
 * Pages use useSidePanel() from SidePanelContext to manage tabs.
 */

export { AppSidebar } from './AppSidebar';
export { AppHeader } from './AppHeader';
export { SidePanel } from './SidePanel';
