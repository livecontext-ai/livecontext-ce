// @vitest-environment jsdom
/**
 * Tests the Workflow Variables tab of the Credentials page: quota meter,
 * row rendering ({{$vars.name}} references), quota-gated add button, empty
 * state, and the create dialog's client-side validation (name pattern,
 * JSON/number value checks) plus the create submission payload.
 */
import '@testing-library/jest-dom/vitest';

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type {
  WorkflowVariable,
  WorkflowVariableQuota,
} from '@/lib/api/services/variables-api.service';

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

const mocks = vi.hoisted(() => ({
  variablesApi: {
    list: vi.fn(),
    getQuota: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  },
}));

// Keys come back verbatim; interpolated values are appended so quota strings
// stay assertable ("quota.used used=2 limit=3").
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values
      ? `${key} ${Object.entries(values)
          .map(([k, v]) => `${k}=${v}`)
          .join(' ')}`
      : key,
}));

vi.mock('@/lib/api/services/variables-api.service', () => ({
  variablesApi: mocks.variablesApi,
}));

vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ open, children }: { open: boolean; children: React.ReactNode }) =>
    open ? <div role="dialog">{children}</div> : null,
  DialogContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogHeader: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogFooter: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogTitle: ({ children }: { children: React.ReactNode }) => <h2>{children}</h2>,
  DialogDescription: ({ children }: { children: React.ReactNode }) => <p>{children}</p>,
}));

vi.mock('@/components/ui/select', async () => {
  const ReactModule = await import('react');
  const SelectContext = ReactModule.createContext<{
    value?: string;
    onValueChange?: (value: string) => void;
  }>({});

  return {
    Select: ({
      value,
      onValueChange,
      children,
    }: {
      value?: string;
      onValueChange?: (value: string) => void;
      children: React.ReactNode;
    }) => (
      <SelectContext.Provider value={{ value, onValueChange }}>
        <div>{children}</div>
      </SelectContext.Provider>
    ),
    SelectTrigger: ({ children }: { children: React.ReactNode }) => (
      <button type="button">{children}</button>
    ),
    SelectValue: () => {
      const ctx = ReactModule.useContext(SelectContext);
      return <span>{ctx.value}</span>;
    },
    SelectContent: ({ children }: { children: React.ReactNode }) => (
      <div role="listbox">{children}</div>
    ),
    SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => {
      const ctx = ReactModule.useContext(SelectContext);
      return (
        <button type="button" role="option" onClick={() => ctx.onValueChange?.(value)}>
          {children}
        </button>
      );
    },
  };
});

import { VariablesSection } from '../VariablesSection';

// ---------------------------------------------------------------------------
// Fixtures & helpers
// ---------------------------------------------------------------------------

const VARS: WorkflowVariable[] = [
  {
    id: 1,
    name: 'api_base_url',
    value: 'https://api.example.com',
    type: 'STRING',
    description: 'Partner API base URL',
    scope: 'workspace',
  secret: false,
    createdAt: '2026-07-01T10:00:00Z',
    updatedAt: null,
  },
  {
    id: 2,
    name: 'retry_count',
    value: '3',
    type: 'NUMBER',
    description: null,
    scope: 'personal',
  secret: false,
    createdAt: '2026-07-01T11:00:00Z',
    updatedAt: null,
  },
];

function setupApi(variables: WorkflowVariable[], quota: WorkflowVariableQuota) {
  mocks.variablesApi.list.mockResolvedValue(variables);
  mocks.variablesApi.getQuota.mockResolvedValue(quota);
}

async function renderSection(addToast = vi.fn()) {
  // The section invalidates the builder's ['workflow-variables'] react-query
  // cache on mutations - provide a client so useQueryClient resolves, and
  // hand it back so tests can spy on invalidateQueries.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <VariablesSection refreshSignal={0} addToast={addToast} />
    </QueryClientProvider>
  );
  // Wait for the initial refresh (list + quota) to settle.
  await waitFor(() => expect(mocks.variablesApi.list).toHaveBeenCalled());
  return { addToast, queryClient };
}

const addButton = () => screen.getByRole('button', { name: /^add$/ }) as HTMLButtonElement;
const nameInput = () => screen.getByLabelText('fields.name') as HTMLInputElement;
const valueInput = () => screen.getByLabelText('fields.value') as HTMLInputElement;
const createButton = () =>
  screen.getByRole('button', { name: 'dialog.create' }) as HTMLButtonElement;

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('VariablesSection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders the quota meter, the variable rows with {{$vars.name}} references, and an enabled add button', async () => {
    setupApi(VARS, { used: 2, limit: 3, planCode: 'FREE' });

    await renderSection();

    const quota = await screen.findByTestId('variables-quota');
    expect(quota).toHaveTextContent('quota.used used=2 limit=3');

    expect(screen.getByTestId('variable-row-api_base_url')).toHaveTextContent(
      '{{$vars.api_base_url}}'
    );
    expect(screen.getByTestId('variable-row-retry_count')).toHaveTextContent(
      '{{$vars.retry_count}}'
    );
    // Type label per row (i18n key echo); workspace scope is the unlabeled
    // default, only personal rows get a badge.
    expect(screen.getByText('types.STRING')).toBeInTheDocument();
    expect(screen.queryByText('scope.workspace')).not.toBeInTheDocument();
    expect(screen.getByText('scope.personal')).toBeInTheDocument();

    expect(addButton()).toBeEnabled();
  });

  it('disables the add button when the quota is exhausted (used == limit)', async () => {
    setupApi(VARS, { used: 3, limit: 3, planCode: 'FREE' });

    await renderSection();
    await screen.findByTestId('variables-quota');

    expect(addButton()).toBeDisabled();
    expect(addButton()).toHaveAttribute('title', 'quota.limitReached');
  });

  it('treats a null limit as unlimited (meter text + add stays enabled)', async () => {
    setupApi(VARS, { used: 50, limit: null, planCode: null });

    await renderSection();

    const quota = await screen.findByTestId('variables-quota');
    expect(quota).toHaveTextContent('quota.unlimited used=50');
    expect(addButton()).toBeEnabled();
  });

  it('shows the empty state when there are no variables', async () => {
    setupApi([], { used: 0, limit: 3, planCode: 'FREE' });

    await renderSection();

    expect(await screen.findByText('empty.title')).toBeInTheDocument();
    expect(screen.getByText('empty.description')).toBeInTheDocument();
    expect(screen.queryByTestId(/variable-row-/)).not.toBeInTheDocument();
  });

  it('REGRESSION (secret-search crash): searching with a secret row (value null) filters by name without crashing', async () => {
    // The API masks a secret variable's value to null (write-only). The search
    // filter used to call v.value.toLowerCase() unguarded, so ONE secret row
    // plus ANY text in the search box crashed the whole tab.
    const secretRow: WorkflowVariable = {
      id: 3,
      name: 'api_key',
      value: null,
      type: 'STRING',
      description: null,
      scope: 'personal',
      secret: true,
      createdAt: '2026-07-01T12:00:00Z',
      updatedAt: null,
    };
    setupApi([...VARS, secretRow], { used: 3, limit: 5, planCode: 'FREE' });

    await renderSection();
    await screen.findByTestId('variable-row-api_key');

    fireEvent.change(screen.getByPlaceholderText('searchPlaceholder'), {
      target: { value: 'api_key' },
    });

    // No crash, and the secret row filters by NAME (its value is null).
    expect(screen.getByTestId('variable-row-api_key')).toBeInTheDocument();
    expect(screen.queryByTestId('variable-row-retry_count')).not.toBeInTheDocument();
    expect(screen.queryByTestId('variable-row-api_base_url')).not.toBeInTheDocument();
  });

  it('surfaces a list load failure as a toasts.loadFailed error toast instead of a silent empty state', async () => {
    mocks.variablesApi.list.mockRejectedValue(new Error('boom'));
    mocks.variablesApi.getQuota.mockResolvedValue({ used: 0, limit: 3, planCode: 'FREE' });

    const { addToast } = await renderSection();

    await waitFor(() =>
      expect(addToast).toHaveBeenCalledWith(
        expect.objectContaining({ type: 'error', title: 'toasts.loadFailed' })
      )
    );
    // The tab itself survives (search shell still rendered, no crash).
    expect(screen.getByPlaceholderText('searchPlaceholder')).toBeInTheDocument();
  });

  describe('create dialog validation', () => {
    beforeEach(async () => {
      setupApi([], { used: 0, limit: 3, planCode: 'FREE' });
    });

    it('rejects an invalid name (leading digit) and keeps the create button disabled', async () => {
      await renderSection();
      fireEvent.click(addButton());

      fireEvent.change(nameInput(), { target: { value: '1bad' } });
      fireEvent.change(valueInput(), { target: { value: 'ok' } });

      expect(await screen.findByText('validation.name')).toBeInTheDocument();
      expect(createButton()).toBeDisabled();
      expect(mocks.variablesApi.create).not.toHaveBeenCalled();
    });

    it('previews the {{$vars.name}} reference for a valid name (no error shown)', async () => {
      await renderSection();
      fireEvent.click(addButton());

      fireEvent.change(nameInput(), { target: { value: 'api_url' } });

      expect(screen.queryByText('validation.name')).not.toBeInTheDocument();
      expect(screen.getByText('{{$vars.api_url}}')).toBeInTheDocument();
    });

    it('flags invalid JSON for a JSON-typed variable and disables create until fixed', async () => {
      await renderSection();
      fireEvent.click(addButton());

      fireEvent.change(nameInput(), { target: { value: 'config' } });
      // Switch the type to JSON via the (mocked) select.
      fireEvent.click(screen.getByRole('option', { name: 'types.JSON' }));

      // JSON type renders a textarea for the value.
      const jsonValue = screen.getByLabelText('fields.value') as HTMLTextAreaElement;
      expect(jsonValue.tagName).toBe('TEXTAREA');

      fireEvent.change(jsonValue, { target: { value: '{oops' } });
      expect(await screen.findByText('validation.json')).toBeInTheDocument();
      expect(createButton()).toBeDisabled();

      fireEvent.change(jsonValue, { target: { value: '{"endpoint": "https://x"}' } });
      await waitFor(() => expect(screen.queryByText('validation.json')).not.toBeInTheDocument());
      expect(createButton()).toBeEnabled();
    });

    it('flags a non-numeric value for a NUMBER-typed variable', async () => {
      await renderSection();
      fireEvent.click(addButton());

      fireEvent.change(nameInput(), { target: { value: 'retries' } });
      fireEvent.click(screen.getByRole('option', { name: 'types.NUMBER' }));
      fireEvent.change(valueInput(), { target: { value: 'not-a-number' } });

      expect(await screen.findByText('validation.number')).toBeInTheDocument();
      expect(createButton()).toBeDisabled();
    });

    it('submits a valid variable: trimmed payload, success toast, list refreshed', async () => {
      mocks.variablesApi.create.mockResolvedValue({
        ...VARS[0],
        id: 9,
        name: 'api_url',
        value: 'https://x',
      });
      const { addToast } = await renderSection();
      fireEvent.click(addButton());

      fireEvent.change(nameInput(), { target: { value: 'api_url' } });
      fireEvent.change(valueInput(), { target: { value: 'https://x' } });
      // Whitespace-only description is trimmed away and sent as null.
      fireEvent.change(screen.getByLabelText('fields.description'), {
        target: { value: '   ' },
      });
      fireEvent.click(createButton());

      await waitFor(() =>
        expect(mocks.variablesApi.create).toHaveBeenCalledWith({
          name: 'api_url',
          value: 'https://x',
          type: 'STRING',
          description: null,
          secret: false,
        })
      );
      await waitFor(() =>
        expect(addToast).toHaveBeenCalledWith(
          expect.objectContaining({
            type: 'success',
            title: 'toasts.created',
            message: '$vars.api_url',
          })
        )
      );
      // Dialog closed and the list re-fetched after the create.
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(mocks.variablesApi.list).toHaveBeenCalledTimes(2);
    });

    it("invalidates the builder's ['workflow-variables'] react-query cache after a successful create", async () => {
      // The workflow builder's $vars chips read useWorkflowVariables (react-query,
      // key ['workflow-variables']); a create here must invalidate that cache or
      // the builder keeps showing a stale variable list.
      mocks.variablesApi.create.mockResolvedValue({
        ...VARS[0],
        id: 9,
        name: 'api_url',
        value: 'https://x',
      });
      const { queryClient } = await renderSection();
      // Initial refresh has fully settled once the quota meter is rendered
      // (invalidateQueries runs before the loading flag flips), so calls seen
      // by this spy belong to the post-create refresh only.
      await screen.findByTestId('variables-quota');
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

      fireEvent.click(addButton());
      fireEvent.change(nameInput(), { target: { value: 'api_url' } });
      fireEvent.change(valueInput(), { target: { value: 'https://x' } });
      fireEvent.click(createButton());

      await waitFor(() =>
        expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['workflow-variables'] })
      );
    });
  });
});
