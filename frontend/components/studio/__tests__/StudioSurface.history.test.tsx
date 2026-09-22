// @vitest-environment jsdom
/**
 * The studio shows what this workspace has already generated, and one click brings a past
 * generation back into the composer.
 *
 * <p><b>Why the wiring is worth pinning.</b> The history speaks in stored recipes and the composer
 * speaks in studio requests, so something has to map between them. Getting that mapping wrong does
 * not fail loudly: the words arrive and the MODEL does not, and the reader presses Create on a
 * prompt that made an image, against whatever model happened to be selected - a paid generation of
 * something else. So the two facts asserted here are that the prompt AND the model travel, and that
 * a recipe which cannot name a model is dropped rather than half-applied.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const push = vi.fn();

/** The entry the mocked history hands back when its button is pressed. Swapped per test. */
let historyEntry: Record<string, unknown> = {
  id: 'file-1',
  fileName: '20260824_flux.png',
  createdAt: '2026-08-24T10:00:00Z',
  provenance: {
    model: 'flux-1',
    kind: 'image',
    prompt: 'a lighthouse at dusk',
    params: { aspect_ratio: '16:9' },
    credentialSource: 'user',
  },
};

vi.mock('next-intl', () => ({
  useTranslations: () => {
    const t = (key: string) => key;
    t.has = () => false;
    return t;
  },
}));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  usePathname: () => '/app/studio',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
  useQuery: ({ enabled }: { enabled?: boolean }) => {
    if (!enabled) return { data: undefined, isLoading: false };
    return { data: undefined, isLoading: false };
  },
}));

const MODEL = {
  model: 'flux-1', kind: 'image', label: 'FLUX 1', provider: 'flux',
  iconSlug: 'flux', apiToolId: 't1', integrationName: 'flux',
  accepts: ['prompt'], required: [], limits: {},
  billedOn: null, measuredUnit: null, defaultQuantity: null,
  price: { unit: 'call', baseCredits: '0', unitCredits: '0' }, async: false,
};
const OTHER_MODEL = { ...MODEL, model: 'seedance-2', integrationName: 'seedance', provider: 'seedance' };
vi.mock('@/hooks/useGenerationModels', () => ({
  useGenerationModels: () => ({ models: [MODEL, OTHER_MODEL], availability: 'ready', isLoading: false }),
}));
vi.mock('@/hooks/useStudioTurn', () => ({
  useStudioTurn: () => ({ isRunning: false, error: null, run: vi.fn(), clearError: vi.fn() }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));

/**
 * The right-hand panel, reduced to the one method a file opens through. Swapped to null in the
 * fallback test: `useSidePanelSafe` is the null-returning variant precisely because a host
 * without a panel has to keep working, and that branch is a real screen (and every other test
 * in this file, which renders no provider).
 */
let sidePanel: { openTab: ReturnType<typeof vi.fn> } | null = null;
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => sidePanel }));
vi.mock('@/components/studio/StudioApps', () => ({ StudioApps: () => <div data-testid="studio-apps" /> }));

/**
 * The history, reduced to its two outputs. The list itself is tested where it lives; what this
 * suite needs is the surface's answer to a reader pressing those two controls.
 */
vi.mock('@/components/generation/GenerationHistoryList', () => ({
  GenerationHistoryList: ({ heading, hideWhenEmpty, onReuse, onOpen }: {
    heading?: string;
    hideWhenEmpty?: boolean;
    onReuse?: (entry: unknown) => void;
    onOpen?: (entry: unknown) => void;
  }) => (
    <div data-testid="generation-history">
      <span data-testid="history-heading">{heading}</span>
      <span data-testid="history-hide-when-empty">{String(!!hideWhenEmpty)}</span>
      <button type="button" data-testid="history-reuse" onClick={() => onReuse?.(historyEntry)}>reuse</button>
      <button type="button" data-testid="history-open" onClick={() => onOpen?.(historyEntry)}>open</button>
    </div>
  ),
}));

/** The composer, reduced to what it was handed to replay. */
vi.mock('@/components/studio/StudioComposer', () => ({
  StudioComposer: (props: {
    reuse?: { prompt?: string; model?: string; credentialSource?: string } | null;
    credentialSource?: string;
    credentialId?: number | null;
    onCredentialIdChange?: (id: number | null) => void;
  }) => (
    <div>
      <span data-testid="reuse-prompt">{props.reuse?.prompt ?? 'none'}</span>
      <span data-testid="reuse-model">{props.reuse?.model ?? 'none'}</span>
      <span data-testid="reuse-payer">{props.reuse?.credentialSource ?? 'none'}</span>
      {/* The surface's OWN payer state, which is what the next generation is actually charged
          against - as opposed to what the replayed envelope happens to carry. */}
      <span data-testid="payer-state">{String(props.credentialSource)}</span>
      <span data-testid="payer-id">{String(props.credentialId)}</span>
      <button onClick={() => props.onCredentialIdChange?.(42)}>Choose non-default key</button>
    </div>
  ),
}));

import { StudioSurface } from '../StudioSurface';

beforeEach(() => {
  vi.clearAllMocks();
  sidePanel = { openTab: vi.fn() };
  historyEntry = {
    id: 'file-1',
    fileName: '20260824_flux.png',
    mimeType: 'image/png',
    sizeBytes: 4096,
    s3Key: 'tenant-1/generated/20260824_flux.png',
    createdAt: '2026-08-24T10:00:00Z',
    provenance: {
      model: 'flux-1',
      kind: 'image',
      prompt: 'a lighthouse at dusk',
      params: { aspect_ratio: '16:9' },
      credentialSource: 'user',
    },
  };
});

afterEach(() => {
  cleanup();
});

describe('StudioSurface - what has already been generated', () => {
  it('shows the history under its own heading, beside the applications row', () => {
    render(<StudioSurface />);

    expect(screen.getByTestId('studio-apps')).toBeInTheDocument();
    expect(screen.getByTestId('generation-history')).toBeInTheDocument();
    expect(screen.getByTestId('history-heading')).toHaveTextContent('history.title');
  });

  it('asks the list to hide itself entirely on an install that has generated nothing', () => {
    // The applications row above it already works this way. Without this flag a fresh install
    // opens the studio under a heading over an empty shelf - and the heading is rendered by the
    // list precisely so that the two disappear together.
    render(<StudioSurface />);

    expect(screen.getByTestId('history-hide-when-empty')).toHaveTextContent('true');
  });

  it('loads a past generation back into the composer, model included', () => {
    render(<StudioSurface />);

    fireEvent.click(screen.getByTestId('history-reuse'));

    expect(screen.getByTestId('reuse-prompt')).toHaveTextContent('a lighthouse at dusk');
    // The half that fails silently: with the words but no model, Create would buy something else.
    expect(screen.getByTestId('reuse-model')).toHaveTextContent('flux-1');
  });

  it('drops the previous providers key when a history recipe selects another provider', () => {
    render(<StudioSurface />);
    fireEvent.click(screen.getByText('Choose non-default key'));
    expect(screen.getByTestId('payer-id')).toHaveTextContent('42');
    historyEntry.provenance = { model: 'seedance-2', kind: 'video', prompt: 'a lighthouse', credentialSource: 'user' };

    fireEvent.click(screen.getByTestId('history-reuse'));

    expect(screen.getByTestId('reuse-model')).toHaveTextContent('seedance-2');
    expect(screen.getByTestId('payer-id')).toHaveTextContent('null');
    expect(screen.getByTestId('payer-state')).toHaveTextContent('user');
  });

  it('keeps a chosen key when reusing a recipe from the same provider', () => {
    render(<StudioSurface />);
    fireEvent.click(screen.getByText('Choose non-default key'));

    fireEvent.click(screen.getByTestId('history-reuse'));

    expect(screen.getByTestId('payer-id')).toHaveTextContent('42');
    expect(screen.getByTestId('payer-state')).toHaveTextContent('user');
  });

  it('brings back WHO PAID for it, so replaying does not move the charge to another key', () => {
    render(<StudioSurface />);
    // The state the next submit is charged against, before the replay: the platform's key.
    expect(screen.getByTestId('payer-state')).toHaveTextContent('platform');

    fireEvent.click(screen.getByTestId('history-reuse'));

    // Both halves, because only the second one moves money. The envelope carrying the payer is
    // `recipeToRequest`'s doing; ADOPTING it is the surface's, and a test that only read the
    // envelope stayed green with that line deleted - which is a generation quietly re-charged to
    // the platform after the reader ran the first one on their own key.
    expect(screen.getByTestId('reuse-payer')).toHaveTextContent('user');
    expect(screen.getByTestId('payer-state')).toHaveTextContent('user');
  });

  it('drops a recipe that cannot name a model rather than half-applying it', () => {
    // Filling the prompt in over whatever is selected submits those words to a different model.
    historyEntry = { id: 'file-2', provenance: { kind: 'image', prompt: 'a boat' } };

    render(<StudioSurface />);
    fireEvent.click(screen.getByTestId('history-reuse'));

    expect(screen.getByTestId('reuse-prompt')).toHaveTextContent('none');
  });

  it('brings the composer back into view, so a press at the bottom of the page is not silent', () => {
    // The desktop empty layout anchors the composer high and puts the history below the
    // applications row: without this the reader presses Modify, sees nothing move, and presses it
    // again. jsdom implements no scrollIntoView, so the surface has to call it defensively - which
    // is also why this asserts on a stub rather than on a real scroll.
    const scrollIntoView = vi.fn();
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = scrollIntoView;
    try {
      render(<StudioSurface />);
      fireEvent.click(screen.getByTestId('history-reuse'));

      expect(scrollIntoView).toHaveBeenCalled();
    } finally {
      // Restored by DELETING it when jsdom had none: assigning `undefined` back leaves an own
      // property in place, and the absence of this method is exactly what the guard in the surface
      // exists for, so every other test in this process has to keep seeing a real absence.
      if (original) {
        Element.prototype.scrollIntoView = original;
      } else {
        delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
      }
    }
  });

  it('opens the asset beside the studio instead of navigating away from it', () => {
    // Pressing one of your own results used to replace the whole screen with the Files page,
    // taking the thread, the composer and whatever was half-typed in it with it. The asset is
    // the thing being looked at, not a reason to change screens.
    render(<StudioSurface />);

    fireEvent.click(screen.getByTestId('history-open'));

    expect(push).not.toHaveBeenCalled();
    expect(sidePanel!.openTab).toHaveBeenCalledTimes(1);
    const tab = sidePanel!.openTab.mock.calls[0][0];
    // The canonical Files tab id, not a bespoke per-file one: pressing a second asset must swap
    // this panel rather than stack another tab beside it.
    expect(tab.id).toBe('files-panel');
    // And on the asset itself, not on the list: the label IS the file, which is what says the
    // detail view was opened rather than the browser.
    expect(tab.label).toBe('20260824_flux.png');
  });

  it('hands over what the entry knows, so the detail view does not open on a placeholder', () => {
    // The panel serves media by id, but a view given a BARE id has to fetch the type back before
    // it can draw anything, and shows the generic file placeholder until it does. The history row
    // already holds all of it. Dropping any of these four from the call is silent: the panel still
    // opens, on the right file, looking briefly wrong.
    render(<StudioSurface />);

    fireEvent.click(screen.getByTestId('history-open'));

    const content = sidePanel!.openTab.mock.calls[0][0].content;
    expect(content.props.entryId).toBe('file-1');
    expect(content.props.mimeType).toBe('image/png');
    expect(content.props.sizeBytes).toBe(4096);
    expect(content.props.s3Key).toBe('tenant-1/generated/20260824_flux.png');
    expect(content.props.createdAt).toBe('2026-08-24T10:00:00Z');
    expect(content.props.fileName).toBe('20260824_flux.png');
  });

  it('sends the chevron back to the files list, never closing the panel', () => {
    // The other half of going through the canonical helper, and the half a bespoke per-file tab
    // got wrong: its back control REMOVED the tab, so the reader lost the panel instead of
    // climbing out of the file. Asserting the prop is a function proves only that something was
    // passed; pressing it is what shows where it goes.
    render(<StudioSurface />);
    fireEvent.click(screen.getByTestId('history-open'));

    sidePanel!.openTab.mock.calls[0][0].content.props.onBack();

    expect(sidePanel!.openTab).toHaveBeenCalledTimes(2);
    const back = sidePanel!.openTab.mock.calls[1][0];
    // Same tab swapped, not a second one opened, and now showing the list focused on the file
    // the reader came from rather than the file itself.
    expect(back.id).toBe('files-panel');
    expect(back.label).toBe('Files');
    expect(back.content.props.focusEntryId).toBe('file-1');
  });

  it('falls back to the files page where there is no panel to open', () => {
    // An asset that cannot be shown beside the studio must still be reachable.
    sidePanel = null;
    render(<StudioSurface />);

    fireEvent.click(screen.getByTestId('history-open'));

    expect(push).toHaveBeenCalledWith('/app/files?fileId=file-1');
  });
});
