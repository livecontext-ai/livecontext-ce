'use client';

import { InterfaceTable } from '@/components/InterfaceTable';
import { AuthenticatedView } from './AuthenticatedView';

/**
 * InterfaceView - Interface list view (Pages only).
 *
 * The legacy "Web Search" tab was removed when the websearch sidepanel + history
 * feature was retired; search/fetch results now render inline in the chat as a
 * favicon stack on the tool-call row.
 */
export function InterfaceView() {
  return (
    <AuthenticatedView>
      {/* Header (title + description) is rendered by InterfaceTable itself, Applications-style,
          so it stays visible even when the list is empty - same layout as the Applications page. */}
      <InterfaceTable interfaceTypeFilter="html" />
    </AuthenticatedView>
  );
}
