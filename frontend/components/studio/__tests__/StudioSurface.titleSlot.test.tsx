// @vitest-environment jsdom
/**
 * The empty desktop studio puts its rotating title in the SAME fixed-height slot as the chat home.
 *
 * The two composers are one box in two places (a mode switch flips between them), and each sits
 * under a rotating title whose line count changes from one title to the next. Only a shared,
 * fixed-height slot keeps the two boxes where the other one is, whichever title each shows.
 * The chat side is pinned by ChatPageLayout.titleSlot.test.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

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
  useQuery: () => ({ data: undefined, isLoading: false }),
}));
vi.mock('@/hooks/useMobileDetection', () => ({ useMobileDetection: () => false }));
vi.mock('@/hooks/useGenerationModels', () => ({
  useGenerationModels: () => ({ models: [], availability: 'ready', isLoading: false }),
}));
vi.mock('@/hooks/useStudioTurn', () => ({
  useStudioTurn: () => ({ isRunning: false, error: null, run: vi.fn(), clearError: vi.fn() }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/components/studio/StudioApps', () => ({ StudioApps: () => <div /> }));
vi.mock('@/components/generation/GenerationHistoryList', () => ({ GenerationHistoryList: () => null }));
vi.mock('@/components/studio/StudioDynamicTitle', () => ({
  StudioDynamicTitle: () => <h2 data-testid="studio-title">title</h2>,
}));
vi.mock('@/components/studio/StudioComposer', () => ({
  StudioComposer: () => <div data-testid="studio-composer" />,
}));

import { StudioSurface } from '../StudioSurface';
import { ROTATING_TITLE_SLOT_CLASS } from '@/app/shared/components/WelcomeTitle';

afterEach(() => {
  cleanup();
});

describe('StudioSurface - the rotating title cannot move the studio composer', () => {
  it('puts the title in the chat home slot, with the composer outside it', () => {
    render(<StudioSurface />);

    const slot = screen.getByTestId('studio-title-slot');
    expect(slot).toContainElement(screen.getByTestId('studio-title'));
    for (const cls of ROTATING_TITLE_SLOT_CLASS.split(' ')) {
      expect(slot).toHaveClass(cls);
    }
    expect(slot).not.toContainElement(screen.getByTestId('studio-composer'));
  });
});
