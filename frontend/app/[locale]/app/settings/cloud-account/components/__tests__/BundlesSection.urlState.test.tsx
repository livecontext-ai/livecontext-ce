// @vitest-environment jsdom
/**
 * The Bundles sub-tab keeps the kind of bundle on screen in the address (`?kind=`), so a
 * reload stays on the same catalogue.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ hasRole: () => true }) }));
vi.mock('../CatalogBundlesPanel', () => ({ default: () => <div data-testid="models-bundles" /> }));
vi.mock('../ApiCatalogBundlesPanel', () => ({ default: () => <div data-testid="api-bundles" /> }));
vi.mock('../SkillBundlesPanel', () => ({ default: () => <div data-testid="skill-bundles" /> }));

import BundlesSection from '../BundlesSection';

const PAGE = '/en/app/settings/cloud-account';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

afterEach(cleanup);

describe('BundlesSection - kind kept in the address', () => {
  it('opens on the kind the address names', () => {
    openAt('tab=bundles&kind=skills');
    render(<BundlesSection />);

    expect(screen.getByTestId('skill-bundles')).toBeInTheDocument();
    expect(screen.queryByTestId('models-bundles')).toBeNull();
  });

  it('falls back to the model bundles on a kind it does not have', () => {
    openAt('tab=bundles&kind=nope');
    render(<BundlesSection />);

    expect(screen.getByTestId('models-bundles')).toBeInTheDocument();
  });

  it('writes the kind when it changes, keeping the tab', () => {
    openAt('tab=bundles');
    render(<BundlesSection />);

    fireEvent.click(screen.getByRole('button', { name: 'tabApis' }));

    expect(screen.getByTestId('api-bundles')).toBeInTheDocument();
    expect(fakeFolderRouter.search()).toBe('tab=bundles&kind=apis');
  });
});
