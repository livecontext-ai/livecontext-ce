// @vitest-environment jsdom
/**
 * The studio's fixed ambient ground is drawn by EVERY layout the surface renders.
 *
 * <p>Three layouts, three separate return statements: empty desktop, empty narrow, and a thread
 * (open or loading). A layout that forgets the backdrop shows the scene on one screen and a plain
 * page on the next, with nothing failing. So each is rendered here and asked for the layer.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

let narrow = false;
let queriesLoading = false;

vi.mock('next-intl', () => ({
  useTranslations: () => {
    const t = (key: string) => key;
    t.has = () => false;
    return t;
  },
}));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/app/studio',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
  // A conversation whose messages are still loading takes the THREAD layout.
  useQuery: ({ enabled }: { enabled?: boolean }) => ({ data: undefined, isLoading: !!enabled && queriesLoading }),
}));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => narrow }));
vi.mock('@/hooks/useGenerationModels', () => ({
  useGenerationModels: () => ({ models: [], availability: 'ready', isLoading: false }),
}));
vi.mock('@/hooks/useStudioTurn', () => ({
  useStudioTurn: () => ({ isRunning: false, error: null, run: vi.fn(), clearError: vi.fn() }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/components/studio/StudioApps', () => ({ StudioApps: () => <div data-testid="studio-apps" /> }));
vi.mock('@/components/generation/GenerationHistoryList', () => ({ GenerationHistoryList: () => null }));
vi.mock('@/components/studio/StudioDynamicTitle', () => ({ StudioDynamicTitle: () => <h1>title</h1> }));
vi.mock('@/components/studio/StudioComposer', () => ({
  StudioComposer: () => <div data-testid="studio-composer" />,
}));

import { StudioSurface } from '../StudioSurface';

beforeEach(() => {
  narrow = false;
  queriesLoading = false;
});

afterEach(() => {
  cleanup();
});

function expectBackdropBehindComposer() {
  const ambient = screen.getByTestId('studio-ambient');
  expect(ambient).toHaveAttribute('aria-hidden', 'true');
  // Behind the content, inside the same isolated wrapper: the composer is a descendant of the
  // layer's parent, never of the layer itself.
  const wrapper = ambient.parentElement as HTMLElement;
  expect(wrapper).toHaveClass('isolate');
  expect(wrapper).toContainElement(screen.getByTestId('studio-composer'));
  expect(ambient).not.toContainElement(screen.getByTestId('studio-composer'));
}

describe('StudioSurface - the ambient ground on every layout', () => {
  it('draws it on the empty desktop studio', () => {
    render(<StudioSurface />);
    expectBackdropBehindComposer();
  });

  it('draws it on the empty narrow studio', () => {
    narrow = true;
    render(<StudioSurface />);
    expectBackdropBehindComposer();
  });

  it('draws it on a studio thread', () => {
    queriesLoading = true;
    render(<StudioSurface conversationId="c1" />);
    // Proves the THREAD layout was reached: it shows the loading line and drops the apps row,
    // which both empty layouts render. Without this the test could pass on the empty layout.
    expect(screen.getByText('thread.loading')).toBeInTheDocument();
    expect(screen.queryByTestId('studio-apps')).not.toBeInTheDocument();
    expectBackdropBehindComposer();
  });

});
