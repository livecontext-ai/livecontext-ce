'use client';

import { Suspense } from 'react';
import { BoardView } from '@/components/views/BoardView';

/**
 * Board page - aggregated board for Tasks / Applications / Workflows.
 * The active resource is driven by ?resource=task|application|workflow (see BoardView).
 * Suspense boundary: BoardView reads useSearchParams(), which Next requires to be wrapped.
 */
export default function AppBoardPage() {
  return (
    <Suspense fallback={null}>
      <BoardView />
    </Suspense>
  );
}
