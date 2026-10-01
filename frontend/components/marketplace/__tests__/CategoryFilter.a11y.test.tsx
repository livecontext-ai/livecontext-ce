// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

const getCategories = vi.fn();

vi.mock('@/lib/api', () => ({
  orchestratorApi: { getCategories: (...args: unknown[]) => getCategories(...args) },
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false }),
}));

// The cloud path reads the categories through orchestratorApi; the CE path fetches the public
// cloud API directly. The accessible name does not depend on the edition.
vi.mock('@/lib/edition', () => ({ IS_CE: false }));

import { CategoryFilter } from '../CategoryFilter';

afterEach(cleanup);

beforeEach(() => {
  getCategories.mockReset();
  getCategories.mockResolvedValue({
    categories: [{ id: 'category-ops', name: 'Operations', slug: 'operations', iconSlug: 'settings' }],
  });
});

function renderFilter(selectedCategory?: string) {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <CategoryFilter selectedCategory={selectedCategory} onCategoryChange={() => {}} />
    </NextIntlClientProvider>,
  );
}

/**
 * The marketplace filter row holds six selects: category, type, sort, rating, date and price.
 * The five others carry an aria-label; the category one did not, and a combobox takes no name
 * from its content, so it was the one control of the row a screen reader announced unnamed
 * (and the one an end-to-end test could only find by position).
 */
describe('CategoryFilter accessible name', () => {
  it('names the category combobox "Filter by category" while it shows All', async () => {
    renderFilter();

    const trigger = await screen.findByRole('combobox', { name: 'Filter by category' });
    expect(trigger).toHaveTextContent('All');
  });

  it('keeps the same name once a category is selected, so it stays findable by it', async () => {
    renderFilter('operations');

    const trigger = await screen.findByRole('combobox', { name: 'Filter by category' });
    expect(trigger).toHaveTextContent('Operations');
  });
});
