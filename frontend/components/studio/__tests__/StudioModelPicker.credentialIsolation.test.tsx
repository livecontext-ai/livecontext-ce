// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import enMessages from '@/messages/en.json';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

const api = vi.hoisted(() => ({
  getAllCredentials: vi.fn(),
  getCredentialTemplates: vi.fn(),
  getCredentialTemplateByName: vi.fn(),
  getPlatformCredentialPublicInfo: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator', async (original) => {
  const actual = await original<typeof import('@/lib/api/orchestrator')>();
  return { ...actual, orchestratorApi: { ...actual.orchestratorApi, ...api } };
});
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isPreviewOnly: false }),
}));
vi.mock('@/components/credentials/CredentialWizard', () => ({ CredentialWizard: () => null }));

import { StudioModelPicker } from '../StudioModelPicker';

const MODEL = {
  model: 'openai-image', kind: 'image', label: 'OpenAI Image', provider: 'OpenAI',
  iconSlug: null, apiToolId: 't1', integrationName: 'openai',
  accepts: ['prompt'], required: [], limits: {}, async: false,
  billedOn: null, measuredUnit: null, defaultQuantity: null, price: null,
} as unknown as GenerationModel;
const OTHER = { ...MODEL, model: 'flux-image', label: 'Flux Image', provider: 'Flux', integrationName: 'flux' };

// Keep CredentialSection real: its automatic choice is what a pane-only stub hid.
function Harness() {
  const [selected, setSelected] = React.useState(MODEL);
  const [credentialId, setCredentialId] = React.useState<number | null>(12);
  const [source, setSource] = React.useState<'platform' | 'user'>('user');
  return <>
    <output data-testid="choice">{selected.model}:{source}:{credentialId}</output>
    <StudioModelPicker models={[MODEL, OTHER]} selected={selected}
      onSelect={setSelected} credentialId={credentialId} onCredentialIdChange={setCredentialId}
      credentialSource={source} onCredentialSourceChange={setSource} />
  </>;
}

beforeEach(() => {
  vi.clearAllMocks();
  Element.prototype.scrollIntoView = vi.fn();
  api.getAllCredentials.mockResolvedValue([
    { id: 11, name: 'OpenAI default', integration: 'openai', is_default: true },
    { id: 12, name: 'OpenAI chosen', integration: 'openai', is_default: false },
    { id: 21, name: 'Flux default', integration: 'flux', is_default: true },
  ]);
  api.getCredentialTemplates.mockResolvedValue([]);
  api.getCredentialTemplateByName.mockResolvedValue(null);
  api.getPlatformCredentialPublicInfo.mockResolvedValue({ available: false, hasPricing: false });
});
afterEach(cleanup);

describe('StudioModelPicker credential isolation', () => {
  it('keeps the selected non-default key after browsing another provider and closing', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={enMessages}><Harness /></NextIntlClientProvider>
    </QueryClientProvider>);
    fireEvent.click(screen.getByTitle('Change model'));
    await screen.findByText('OpenAI chosen');

    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    fireEvent.click(screen.getByRole('button', { name: /Flux/ }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Flux Image' })).toBeInTheDocument());
    fireEvent.keyDown(document.body, { key: 'Escape' });

    expect(screen.getByTestId('choice')).toHaveTextContent('openai-image:user:12');
    fireEvent.click(screen.getByTitle('Change model'));
    await screen.findByText('OpenAI chosen');
    expect(screen.getByTestId('choice')).toHaveTextContent('openai-image:user:12');
  });
});
