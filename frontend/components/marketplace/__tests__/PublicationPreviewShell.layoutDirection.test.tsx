// @vitest-environment jsdom
/**
 * A marketplace preview shows a published workflow the way its AUTHOR laid it out, whatever
 * the viewer prefers: the shell pins the snapshot's saved direction, and a snapshot saved
 * before the direction existed reads as the historical left-to-right layout. The pin reaches
 * the canvas scope every builder mounts, so the preview canvas cannot be re-oriented either.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

let currentOrgId: string | null = null;
vi.mock('@/lib/stores/current-org-store', () => ({ useCurrentOrg: () => ({ currentOrgId }) }));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  PublicationSnapshotProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  WorkflowRunProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

import { PublicationPreviewShell } from '../PublicationPreviewShell';
import {
  WorkflowCanvasDirectionScope,
  useWorkflowLayoutDirection,
} from '@/contexts/WorkflowLayoutDirectionContext';

/** Stands in for the preview canvas: a builder inside its own scope. */
function PreviewCanvas() {
  const { direction, isPinned } = useWorkflowLayoutDirection();
  return <span data-testid="preview">{`${direction}:${isPinned}`}</span>;
}

function renderPreview(planSnapshot: Record<string, unknown>) {
  return render(
    <PublicationPreviewShell
      publication={{ id: 'pub-1', workflowId: 'wf-1', showcaseRunId: null, planSnapshot } as any}
    >
      <WorkflowCanvasDirectionScope>
        <PreviewCanvas />
      </WorkflowCanvasDirectionScope>
    </PublicationPreviewShell>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
  // The viewer prefers top to bottom: the preview must not follow them.
  window.localStorage.setItem('lc.workflow.layoutDirection:personal', 'vertical');
});

afterEach(cleanup);

describe('PublicationPreviewShell - reading direction', () => {
  it('pins the direction the author saved', () => {
    renderPreview({ layoutDirection: 'horizontal', triggers: [] });

    expect(screen.getByTestId('preview')).toHaveProperty('textContent', 'horizontal:true');
  });

  it('pins a vertical author direction for a horizontal viewer too', () => {
    window.localStorage.setItem('lc.workflow.layoutDirection:personal', 'horizontal');
    renderPreview({ layoutDirection: 'vertical', triggers: [] });

    expect(screen.getByTestId('preview')).toHaveProperty('textContent', 'vertical:true');
  });

  it('reads a snapshot published before the direction existed as left to right', () => {
    renderPreview({ triggers: [] });

    expect(screen.getByTestId('preview')).toHaveProperty('textContent', 'horizontal:true');
  });
});
