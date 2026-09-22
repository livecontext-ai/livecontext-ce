// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

/**
 * "Generated with X, from these words" - over a file that would otherwise be indistinguishable
 * from an upload.
 *
 * <p>The two facts pinned here are the ones the feature stands on: a file that was NOT generated
 * shows nothing at all (which is almost every file in a workspace, so a card that appeared anyway
 * would be noise on every screen), and the control hands back the recipe VERBATIM, because a
 * regeneration that dropped a parameter would quietly make something else.
 */

const mocks = vi.hoisted(() => ({
  useGenerationProvenance: vi.fn(),
  useGenerationModels: vi.fn(),
}));

vi.mock('@/hooks/useGenerationHistory', () => ({
  useGenerationProvenance: mocks.useGenerationProvenance,
}));

vi.mock('@/hooks/useGenerationModels', () => ({
  useGenerationModels: mocks.useGenerationModels,
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${Object.values(values).join(',')}` : key,
}));

import { GenerationRecipeCard } from '../GenerationRecipeCard';

const RECIPE = {
  model: 'flux-1.1-pro',
  kind: 'image',
  prompt: 'a lighthouse at dusk',
  params: { aspect_ratio: '16:9' },
};

beforeEach(() => {
  mocks.useGenerationProvenance.mockReturnValue({ provenance: RECIPE, isLoading: false });
  mocks.useGenerationModels.mockReturnValue({
    models: [{ model: 'flux-1.1-pro', kind: 'image', label: 'FLUX 1.1 Pro', iconSlug: 'flux' }],
    isLoading: false,
    availability: 'ready',
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('GenerationRecipeCard', () => {
  it('renders nothing for a file that was not generated here', () => {
    mocks.useGenerationProvenance.mockReturnValue({ provenance: null, isLoading: false });

    const { container } = render(
      <GenerationRecipeCard entryId="f1" onRegenerate={() => {}} />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('names the model in the catalogue words, as the history does for the same asset', () => {
    render(<GenerationRecipeCard entryId="f1" onRegenerate={() => {}} />);

    expect(screen.getByText('generatedWith:FLUX 1.1 Pro')).toBeInTheDocument();
  });

  it('still names a model that has left the catalogue, by its id', () => {
    // Losing the row would leave the card saying nothing at all about what made the file.
    mocks.useGenerationModels.mockReturnValue({ models: [], isLoading: false, availability: 'ready' });

    render(<GenerationRecipeCard entryId="f1" onRegenerate={() => {}} />);

    expect(screen.getByText('generatedWith:flux-1.1-pro')).toBeInTheDocument();
  });

  it('states what the platform charged for it', () => {
    mocks.useGenerationProvenance.mockReturnValue({
      provenance: { ...RECIPE, billedCredits: 78 }, isLoading: false,
    });

    render(<GenerationRecipeCard entryId="f1" onRegenerate={() => {}} />);

    // The VALUE the recipe carries, anchored to the start of the stub translator's output - see the
    // note in GenerationHistoryList.test.tsx. The rendered wording is pinned against the real
    // dictionaries in GenerationCard.price.realIntl.test.tsx.
    expect(screen.getByTitle('costTitle')).toHaveTextContent(/^cost:78,/);
  });

  it('says nothing about price when the platform charged nothing, rather than saying zero', () => {
    // The reader ran it on their own provider key: they were charged, by that provider. "0 credits"
    // here would be a claim about money, not a missing value.
    render(<GenerationRecipeCard entryId="f1" onRegenerate={() => {}} />);

    expect(screen.queryByTitle('costTitle')).not.toBeInTheDocument();
  });

  it('shows the words it was made from', () => {
    render(<GenerationRecipeCard entryId="f1" onRegenerate={() => {}} />);

    expect(screen.getByText('a lighthouse at dusk')).toBeInTheDocument();
  });

  it('refuses to re-run a model that has left the catalogue', () => {
    // The form has no row to select for it. Opening anyway would put the old prompt in front of a
    // DIFFERENT model, and produce a variant of something else with nothing saying so.
    mocks.useGenerationModels.mockReturnValue({ models: [], isLoading: false, availability: 'ready' });

    render(<GenerationRecipeCard entryId="f1" onRegenerate={() => {}} />);

    expect(screen.getByText('modify').closest('button')).toBeDisabled();
  });

  it('hands back the whole recipe, parameters included', () => {
    // Anything dropped here is a parameter the re-run would silently change.
    const onRegenerate = vi.fn();
    render(<GenerationRecipeCard entryId="f1" onRegenerate={onRegenerate} />);

    fireEvent.click(screen.getByText('modify'));

    expect(onRegenerate).toHaveBeenCalledWith(RECIPE);
  });
});
