// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';

/**
 * A self-hosted install has no free tier, so it has no Free tier tab.
 *
 * <p>Its own file because the edition is read once, at module load: the sibling suite
 * mocks a cloud build, and the tab list is a module-level constant.
 */

const mocks = vi.hoisted(() => ({
  getEffectiveModels: vi.fn(),
  listExecutionLinks: vi.fn().mockResolvedValue([]),
}));

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (k: string) => (ns ? `${ns}.${k}` : k),
}));
vi.mock('@/lib/api/model-config.service', () => ({
  modelConfigService: {
    getEffectiveModels: mocks.getEffectiveModels,
    listExecutionLinks: mocks.listExecutionLinks,
  },
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
vi.mock('@/lib/edition/edition', () => ({
  EDITION: 'ce', IS_CE: true, IS_CLOUD: false, IS_MANAGED_CLOUD: false,
}));
vi.mock('../AddModelDialog', () => ({ default: () => null }));

import ModelManagementPanel from '../ModelManagementPanel';

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ModelManagementPanel - the Free tier tab on a self-hosted install', () => {
  it('is not offered, and its list is never requested', async () => {
    mocks.getEffectiveModels.mockResolvedValue([]);

    const { container } = render(<ModelManagementPanel t={(k: string) => k} />);
    await screen.findByText('modelConfig.category.chat.label');

    const tabs = Array.from(container.querySelectorAll('button[data-category-id]'))
      .map((b) => b.getAttribute('data-category-id'));
    expect(tabs).toEqual(['chat', 'browser_agent']);
    expect(mocks.getEffectiveModels).not.toHaveBeenCalledWith('free_tier');
  });
});
