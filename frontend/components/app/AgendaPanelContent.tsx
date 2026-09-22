'use client';

import { Suspense } from 'react';
import { AgendaView } from '@/components/views/AgendaView';

/** The agenda adapted to the side panel shell rather than the full-page shell. */
export function AgendaPanelContent() {
  return (
    <Suspense fallback={null}>
      <AgendaView embedded />
    </Suspense>
  );
}
