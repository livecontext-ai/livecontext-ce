// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';

const models = [
  { id: 'claude-sonnet-5', name: 'Claude Sonnet 5', provider: 'anthropic' },
  { id: 'deepseek-chat', name: 'deepseek-chat', provider: 'deepseek' },
];

vi.mock('@/hooks/useModels', () => ({
  useModels: () => ({
    models,
    providers: [],
    defaultModel: null,
    defaultProvider: null,
    isLoading: false,
    error: null,
    refresh: async () => {},
  }),
}));

import { ProviderModelCell, providerOptionLabels, useModelNameIndex } from '../modelLabels';

/**
 * The usage history is the page where a charge is read, and it was the one
 * surface still calling a model by the id the ledger stored while the picker
 * that spent the credits called it by the name an admin gave it.
 */

function Cell({ provider, model }: { provider: string | null; model: string | null }) {
  const index = useModelNameIndex();
  return (
    <table><tbody><tr><td>
      <ProviderModelCell provider={provider} model={model} index={index} />
    </td></tr></tbody></table>
  );
}

afterEach(() => cleanup());

describe('the usage history names what it charged for', () => {
  it('shows the name the admin gave the model, not the id the ledger stored', () => {
    render(<Cell provider="anthropic" model="claude-sonnet-5" />);

    expect(screen.getByText('Anthropic / Claude Sonnet 5')).toBeInTheDocument();
    expect(screen.queryByText(/anthropic \/ claude-sonnet-5/)).not.toBeInTheDocument();
  });

  it('keeps the stored ids reachable, because that is what matches a provider bill', () => {
    render(<Cell provider="anthropic" model="claude-sonnet-5" />);

    expect(screen.getByText('Anthropic / Claude Sonnet 5'))
      .toHaveAttribute('title', 'anthropic / claude-sonnet-5');
  });

  it('falls back to the raw id for a model the catalogue cannot name', () => {
    // A model removed from the catalogue, or one charged on a relayed row. The
    // line must still say what was billed rather than go blank.
    render(<Cell provider="openai" model="gpt-4o-2024-05-13" />);

    expect(screen.getByText('OpenAI / gpt-4o-2024-05-13')).toBeInTheDocument();
  });

  it('shows a model nobody renamed under its own id, beside the provider label', () => {
    // The catalogue name IS the id here, so there is no alias to show and the
    // model half must read exactly as the ledger stored it.
    render(<Cell provider="deepseek" model="deepseek-chat" />);

    expect(screen.getByText('DeepSeek / deepseek-chat')).toBeInTheDocument();
  });

  it('adds no title when the line already says exactly what was stored', () => {
    // An unknown provider key is shown as-is, and this model was never renamed,
    // so the cell and the stored pair are the same string. A title repeating it
    // would be noise, and the tooltip means something only when it differs.
    render(<Cell provider="custom-llm" model="deepseek-chat" />);

    expect(screen.getByText('custom-llm / deepseek-chat')).not.toHaveAttribute('title');
  });

  it('renders a charge that has no model at all without a dangling separator', () => {
    // Top-ups, storage and every other non-LLM row reach this cell too.
    const { container } = render(<Cell provider={null} model={null} />);

    expect(container.textContent).toBe('-');
  });

  it('shows the provider alone when that is all the row carries', () => {
    render(<Cell provider="anthropic" model={null} />);

    expect(screen.getByText('Anthropic')).toBeInTheDocument();
  });

  it('shows the model alone when the row carries no provider', () => {
    // The other half of the same claim. A relayed row can arrive with the model
    // and nothing else, and it must not render as " / claude-sonnet-5".
    render(<Cell provider={null} model="claude-sonnet-5" />);

    expect(screen.getByText('Claude Sonnet 5')).toBeInTheDocument();
  });
});

describe('the provider filter list', () => {
  it('names each stored key the way the rest of the app does', () => {
    const labels = providerOptionLabels(['anthropic', 'openai']);

    expect(labels.get('anthropic')).toBe('Anthropic');
    expect(labels.get('openai')).toBe('OpenAI');
  });

  it('keeps two casings of one key apart, rather than drawing the same word twice', () => {
    // The edge the case-insensitive lookup creates: the ledger stores whatever
    // the calling service sent, so these are two distinct filter VALUES. Naming
    // both "Anthropic" would offer a reader two identical rows that filter to
    // different sets of spend, which is worse than showing the raw key.
    const labels = providerOptionLabels(['anthropic', 'Anthropic', 'openai']);

    expect(labels.get('anthropic')).toBe('anthropic');
    expect(labels.get('Anthropic')).toBe('Anthropic');
    // The key that collides with nothing is unaffected.
    expect(labels.get('openai')).toBe('OpenAI');
  });

  it('passes a provider the ledger already stored as a name straight through', () => {
    // A catalogue tool call stores the API's own title in this column.
    expect(providerOptionLabels(['Google Gemini']).get('Google Gemini')).toBe('Google Gemini');
  });

  it('answers with nothing for an empty ledger', () => {
    expect(providerOptionLabels([]).size).toBe(0);
  });
});

describe('before the catalogue has answered', () => {
  it('still prints what was billed, under the stored ids', () => {
    // The index is null until `useModels` resolves, and on a signed-out or
    // failed catalogue it stays null. The table must not go blank or wait: the
    // row is about money and the id is always true.
    render(
      <table><tbody><tr><td>
        <ProviderModelCell provider="anthropic" model="claude-sonnet-5" index={null} />
      </td></tr></tbody></table>
    );

    expect(screen.getByText('Anthropic / claude-sonnet-5')).toBeInTheDocument();
  });
});
