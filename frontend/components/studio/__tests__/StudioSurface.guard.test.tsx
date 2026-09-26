// @vitest-environment jsdom
/**
 * The studio route refuses to write into a conversation that is not one.
 *
 * <p>The kind is immutable and the server enforces it, but that only protects the conversation ROW.
 * Nothing stopped a chat conversation's id being opened at a studio URL - by an old link, a
 * bookmark, a typo - and the studio would then append generation envelopes to a chat, where the chat
 * renderer shows raw JSON and the chat pipeline feeds them to a model as context. The guard has to
 * live at the surface that writes, which is this one.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const replace = vi.fn();
const push = vi.fn();
// Typed on its argument, so `run.mock.calls[0][0]` is a request rather than a tuple of length
// zero - a zero-argument mock makes every assertion about WHAT was submitted a type error.
// The outcome shape the surface reads: a status, and a result whose `success` decides whether the
// composer keeps its draft. Typed WIDELY on purpose - narrowing it to the happy path is what would
// make a refusal or a lost turn unrepresentable here, which is exactly what those tests need.
type TurnOutcome = {
  status?: 'recorded' | 'lost';
  conversationId: string;
  result?: { success: boolean; error?: string; data?: Record<string, unknown> };
} | null;
const run = vi.fn(async (_request: Record<string, unknown>): Promise<TurnOutcome> => (
  { conversationId: 'c1', result: { success: true } }));
const invalidateQueries = vi.fn();
// The options the surface hands the turn hook, so the callbacks it OWNS can be exercised.
let turnOptions: { onTurnRecorded?: (id: string, phase: 'request' | 'result') => void } = {};
// The hook's `error`, which is the only place a turn that never reached the thread says why.
let turnError: { code: string } | null = null;
const track = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => track(...a) }));

// The conversation the route was opened on, and whether reading it FAILED. Swapped per test.
let conversation: Record<string, unknown> | undefined = { id: 'c1', title: 'A chat' };
let conversationErrored = false;



vi.mock('next-intl', () => ({
  useTranslations: () => {
    const t = (key: string) => key;
    t.has = () => false;
    return t;
  },
}));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push, replace }),
  usePathname: () => '/app/studio/c1',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQueryClient: () => ({ invalidateQueries }),
  useQuery: ({ queryKey, enabled }: { queryKey: readonly unknown[]; enabled?: boolean }) => {
    if (!enabled) return { data: undefined, isLoading: false };
    if (queryKey[0] === 'conversation') {
      return { data: conversation, isLoading: false, isError: conversationErrored };
    }
    // The thread's messages: empty, so the surface renders its welcome state.
    if (queryKey[0] === 'studio-messages') return { data: [], isLoading: false };
    return { data: undefined, isLoading: false };
  },
}));
// A REAL model, and the whole suite depends on it.
//
// This mock returned an empty catalogue, which meant `selectedModel` stayed null and every submit
// left `handleSubmit` on its FIRST line - before the guard each test below is named for. Deleting
// that guard outright kept all of them green. An empty catalogue is a different test (the surface
// says the catalogue is empty), not a cheaper way to write this one.
const MODEL = {
  model: 'seedance-2', kind: 'video', label: 'Seedance 2.0', provider: 'seedance',
  iconSlug: null, apiToolId: 't1', integrationName: 'seedance',
  accepts: ['prompt'], required: [], limits: {},
  billedOn: null, measuredUnit: null, defaultQuantity: null,
  price: { unit: 'call', baseCredits: '0', unitCredits: '0' }, async: false,
};
const OTHER_MODEL = { ...MODEL, model: 'flux-1', provider: 'flux', integrationName: 'flux', kind: 'image' };
vi.mock('@/hooks/useGenerationModels', () => ({
  useGenerationModels: () => ({ models: [MODEL, OTHER_MODEL], availability: 'ready', isLoading: false }),
}));
vi.mock('@/hooks/useStudioTurn', () => ({
  useStudioTurn: (options: Record<string, unknown>) => {
    turnOptions = options as typeof turnOptions;
    return { isRunning: false, error: turnError, run, clearError: vi.fn() };
  },
}));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/components/studio/StudioApps', () => ({ StudioApps: () => null }));
/**
 * The last promise onSubmit returned, kept so a test can AWAIT it.
 *
 * <p>The boolean it resolves to is the whole draft-retention contract: the real composer clears the
 * prompt and the uploaded files on true and keeps them on false. Nothing else in this suite
 * observes it, so without this it is a behaviour with no witness.
 *
 * <p>Awaited directly rather than mirrored into the DOM. Rendering it would make the assertion
 * depend on the surface happening to re-render after the promise settles, which is a race that has
 * nothing to do with the rule under test.
 */
let lastSubmit: Promise<boolean> | null = null;

vi.mock('@/components/studio/StudioComposer', () => ({
  // Renders the notice slot: a refusal that is not on screen is not a refusal, so the tests below
  // assert the words as well as the absence of a call.
  StudioComposer: (props: {
    onSubmit: (i: unknown) => Promise<boolean>;
    notice?: React.ReactNode;
    disabled?: boolean;
    credentialId?: number | null;
    credentialSource?: string;
    onSelectModel?: (m: unknown) => void;
    onCredentialSourceChange?: (s: string) => void;
    onCredentialIdChange?: (id: number | null) => void;
  }) => (
    <div>
      {props.notice}
      <button
        type="button"
        data-testid="submit"
        onClick={() => { lastSubmit = props.onSubmit({ prompt: 'x', params: {} }); }}
      >
        submit
      </button>
      {/* The surface's own refusal state, surfaced so a silent no-op is visible to a test. */}
      <span data-testid="composer-disabled">{String(!!props.disabled)}</span>
      <span data-testid="payer-source">{String(props.credentialSource)}</span>
      <span data-testid="payer-id">{String(props.credentialId)}</span>
      <button type="button" data-testid="pick-user-key" onClick={() => {
        props.onCredentialSourceChange?.('user');
        props.onCredentialIdChange?.(42);
      }}>key</button>
      {/* Back to the platform key WITHOUT clearing the id - the real control does exactly this,
          and it is the only state in which sending the id would be wrong rather than merely null. */}
      <button type="button" data-testid="back-to-platform"
              onClick={() => props.onCredentialSourceChange?.('platform')}>platform</button>
      <button type="button" data-testid="switch-model" onClick={() => props.onSelectModel?.(OTHER_MODEL)}>
        switch
      </button>
    </div>
  ),
}));

import { StudioSurface } from '../StudioSurface';

beforeEach(() => {
  vi.clearAllMocks();
  conversationErrored = false;
  lastSubmit = null;
  turnError = null;
  run.mockResolvedValue({ conversationId: 'c1', result: { success: true } });
});
afterEach(cleanup);

describe('StudioSurface - a conversation that is not a studio one', () => {
  it('sends a chat conversation back to the chat route', async () => {
    conversation = { id: 'c1', title: 'A chat', kind: 'chat' };

    render(<StudioSurface conversationId="c1" />);

    // `replace`, not `push`: Back should return where the reader came from rather than bounce
    // between the two routes.
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/app/c/c1'));
  });

  it('treats a conversation with no kind as a chat - every row that predates the column is one', async () => {
    conversation = { id: 'c1', title: 'An old conversation' };

    render(<StudioSurface conversationId="c1" />);

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/app/c/c1'));
  });

  it('refuses to submit while the kind is still UNKNOWN', async () => {
    // Not the same as "it is a chat": until the answer lands the kind is unknown, and a submit in
    // that window would write generation envelopes into whatever this turns out to be. `undefined`
    // is what the query returns while it is in flight.
    conversation = undefined as unknown as Record<string, unknown>;
    const { getByTestId } = render(<StudioSurface conversationId="c1" />);

    getByTestId('submit').click();

    await waitFor(() => expect(run).not.toHaveBeenCalled());
    // And nothing is redirected on a kind nobody knows yet.
    expect(replace).not.toHaveBeenCalled();
  });

  it('refuses to submit a turn into it, even while the redirect is in flight', async () => {
    // The redirect is a navigation, which is not instant. A submit in that window would append
    // generation envelopes to a chat, which is the whole thing being prevented.
    conversation = { id: 'c1', title: 'A chat', kind: 'chat' };
    const { getByTestId } = render(<StudioSurface conversationId="c1" />);

    getByTestId('submit').click();

    await waitFor(() => expect(replace).toHaveBeenCalled());
    expect(run).not.toHaveBeenCalled();
  });

  it('says so when the conversation cannot be READ, instead of swallowing every press', async () => {
    // "Not known yet" and "could not be read" are different facts. Treating them alike makes a
    // deleted conversation, a 403 or a dropped request into a silent dead end: the button stays
    // enabled, every press does nothing, and nothing on screen says why.
    conversation = undefined;
    conversationErrored = true;
    const { getByTestId, findByText } = render(<StudioSurface conversationId="c1" />);

    expect(await findByText('notices.conversation_unreadable')).toBeInTheDocument();

    getByTestId('submit').click();
    await waitFor(() => expect(run).not.toHaveBeenCalled());
    // And it is not mistaken for a chat: nothing is redirected on a kind nobody could read.
    expect(replace).not.toHaveBeenCalled();
  });

  it('leaves a studio conversation where it is', async () => {
    conversation = { id: 'c1', title: 'A studio thread', kind: 'studio' };

    render(<StudioSurface conversationId="c1" />);

    await waitFor(() => expect(replace).not.toHaveBeenCalled());
  });

  it('asks nothing about a conversation that does not exist yet', async () => {
    // A brand-new studio has no id, so there is nothing to check and nowhere to send anyone.
    render(<StudioSurface conversationId={null} />);

    await waitFor(() => expect(replace).not.toHaveBeenCalled());
  });
});

describe('StudioSurface - a refusal the reader can SEE', () => {
  it('disables the composer for every reason the submit guard would refuse', async () => {
    // The guard answers `false` silently. Any refusal it can make that the button does not show is
    // a press that does nothing and says nothing, which is exactly what a reader retries. Before
    // this, `disabled` covered only the unreadable case, so a pending or chat conversation left the
    // button live.
    conversation = { id: 'c1', title: 'A chat', kind: 'chat' };
    const { getByTestId } = render(<StudioSurface conversationId="c1" />);

    await waitFor(() => expect(getByTestId('composer-disabled').textContent).toBe('true'));
  });

  it('leaves the composer enabled on a real studio conversation', async () => {
    // The other half: a `disabled` that is always true would pass the test above and lock the studio.
    conversation = { id: 'c1', title: 'A studio', kind: 'studio' };
    const { getByTestId } = render(<StudioSurface conversationId="c1" />);

    await waitFor(() => expect(getByTestId('composer-disabled').textContent).toBe('false'));
    getByTestId('submit').click();
    await waitFor(() => expect(run).toHaveBeenCalled());
  });
});

describe('StudioSurface - whose key pays', () => {
  it('forgets the chosen key when the model moves to another integration', async () => {
    // A credential belongs to an INTEGRATION: an ElevenLabs key is not an account on Seedance.
    // Carrying it across sends model B an id minted for model A, and the execution path then
    // substitutes a key it CAN use and runs anyway - on an account the reader did not choose, with
    // the price hidden because the composer believes the reader is paying.
    conversation = { id: 'c1', title: 'A studio', kind: 'studio' };
    const { getByTestId } = render(<StudioSurface conversationId="c1" />);

    getByTestId('pick-user-key').click();
    await waitFor(() => expect(getByTestId('payer-id').textContent).toBe('42'));

    getByTestId('switch-model').click();

    await waitFor(() => expect(getByTestId('payer-id').textContent).toBe('null'));
    expect(getByTestId('payer-source').textContent).toBe('platform');
  });

  it('sends no credential id at all when the platform key is paying', async () => {
    // The id is meaningful only beside `user`. Sending it on the platform key would attribute the
    // charge to an account that is not being billed.
    conversation = { id: 'c1', title: 'A studio', kind: 'studio' };
    const { getByTestId } = render(<StudioSurface conversationId="c1" />);

    await waitFor(() => expect(getByTestId('composer-disabled').textContent).toBe('false'));
    getByTestId('submit').click();

    await waitFor(() => expect(run).toHaveBeenCalled());
    expect(run.mock.calls[0][0]).toMatchObject({ credentialSource: 'platform', credentialId: null });
  });

  it('sends the chosen id once the reader picks their own key', async () => {
    conversation = { id: 'c1', title: 'A studio', kind: 'studio' };
    const { getByTestId } = render(<StudioSurface conversationId="c1" />);

    getByTestId('pick-user-key').click();
    await waitFor(() => expect(getByTestId('payer-id').textContent).toBe('42'));
    getByTestId('submit').click();

    await waitFor(() => expect(run).toHaveBeenCalled());
    expect(run.mock.calls[0][0]).toMatchObject({ credentialSource: 'user', credentialId: 42 });
  });
});

describe('StudioSurface - the two callbacks the surface owns', () => {
  it('does NOT send a leftover credential id once the platform key is paying again', async () => {
    // The id survives the source flip on purpose (re-picking it would be its own annoyance), so the
    // ONLY thing keeping it off the wire is the ternary at the submit site. With the id still null,
    // dropping that ternary is invisible - which is why the key is chosen first here.
    conversation = { id: 'c1', title: 'A studio', kind: 'studio' };
    const { getByTestId } = render(<StudioSurface conversationId="c1" />);

    getByTestId('pick-user-key').click();
    await waitFor(() => expect(getByTestId('payer-id').textContent).toBe('42'));
    getByTestId('back-to-platform').click();
    await waitFor(() => expect(getByTestId('payer-source').textContent).toBe('platform'));

    getByTestId('submit').click();
    await waitFor(() => expect(run).toHaveBeenCalled());
    expect(run.mock.calls[0][0]).toMatchObject({ credentialSource: 'platform', credentialId: null });
  });

  it('refreshes the thread on the ANSWER only, never on the request', async () => {
    // Refreshing after the request replaces the in-flight card with a turn read from the server that
    // has a request and no answer - which on screen is the "this may have been charged" state, shown
    // over a generation that is running perfectly normally.
    conversation = { id: 'c1', title: 'A studio', kind: 'studio' };
    render(<StudioSurface conversationId="c1" />);
    await waitFor(() => expect(turnOptions.onTurnRecorded).toBeTypeOf('function'));

    invalidateQueries.mockClear();
    turnOptions.onTurnRecorded?.('c1', 'request');
    expect(invalidateQueries).not.toHaveBeenCalled();

    turnOptions.onTurnRecorded?.('c1', 'result');
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['studio-messages', 'c1'] });
  });
});

describe('StudioSurface - what survives in the composer after a turn', () => {
  /**
   * onSubmit's boolean IS the draft-retention rule: the composer clears the prompt and the
   * uploaded files on true and keeps them on false. Nothing else in this suite reads it, so every
   * case below is a behaviour with exactly one witness.
   */
  async function submitAndRead() {
    render(<StudioSurface conversationId="c1" />);
    // Not until the guard has settled: a submit while the kind is still unknown is refused before
    // the rule under test is reached, and would answer false for the wrong reason.
    await waitFor(() => expect(screen.getByTestId('composer-disabled')).toHaveTextContent('false'));
    fireEvent.click(screen.getByTestId('submit'));
    expect(lastSubmit).not.toBeNull();
    return lastSubmit;
  }

  it('clears the draft once the turn ran and produced something', async () => {
    conversation = { id: 'c1', title: 'A studio', kind: 'studio' };

    expect(await submitAndRead()).toBe(true);
  });

  it('KEEPS the draft when the turn was REFUSED before it reached the provider', async () => {
    // A refusal answers with no data at all: it never ran, so it cost nothing. Erasing the prompt
    // and the uploaded files would make a free refusal more expensive to recover from than a paid
    // success, and the files are the expensive half to redo.
    conversation = { id: 'c1', title: 'A studio', kind: 'studio' };
    run.mockResolvedValue({
      status: 'recorded', conversationId: 'c1',
      result: { success: false, error: 'No published price for this model' },
    });

    expect(await submitAndRead()).toBe(false);
  });

  it('CLEARS the draft when the turn failed but was CHARGED anyway', async () => {
    // The distinction `success: false` alone cannot make. Billing commits before the asset is
    // fetched and stored, so a generation that ran upstream and could not be filed was PAID FOR -
    // and the endpoint says so by attaching what is left of it. Re-priming the composer with the
    // identical prompt in front of someone who has just been charged is the second purchase this
    // whole outcome type exists to prevent.
    conversation = { id: 'c1', title: 'A studio', kind: 'studio' };
    run.mockResolvedValue({
      status: 'recorded', conversationId: 'c1',
      result: {
        success: false,
        error: 'The generation ran but no asset could be retrieved.',
        data: { asset_url: 'https://provider.example/clip.mp4?exp=1' },
      },
    });

    expect(await submitAndRead()).toBe(true);
  });

  it('treats an EMPTY data object as a refusal, so an ordinary refusal still keeps its draft', async () => {
    // `GenerationResult.failed(error)` builds exactly this shape. Reading "data is present" rather
    // than "data has anything in it" would erase the draft on every ordinary refusal.
    conversation = { id: 'c1', title: 'A studio', kind: 'studio' };
    run.mockResolvedValue({
      status: 'recorded', conversationId: 'c1',
      result: { success: false, error: 'Out of credits', data: {} },
    });

    expect(await submitAndRead()).toBe(false);
  });

  it('clears the draft when the submission was LOST, because it may already have been paid for', async () => {
    // The opposite rule, and the reason "keep on failure" cannot be the blanket answer: a lost turn
    // may be running and billed upstream, so a composer still holding the identical prompt is an
    // invitation to buy the same generation twice.
    conversation = { id: 'c1', title: 'A studio', kind: 'studio' };
    run.mockResolvedValue({ status: 'lost', conversationId: 'c1' });

    expect(await submitAndRead()).toBe(true);
  });

  it('KEEPS the draft when nothing was sent at all', async () => {
    // `run` answers null for "nothing left and nothing was charged" - the words and the files are
    // still the reader's.
    conversation = { id: 'c1', title: 'A studio', kind: 'studio' };
    run.mockResolvedValue(null);

    expect(await submitAndRead()).toBe(false);
  });
});

describe('StudioSurface - analytics', () => {
  const studio = () => { conversation = { id: 'c1', title: 'A studio', kind: 'studio' }; };
  const submitted = () => track.mock.calls.filter(([e]) => e === 'studio_generation_submitted');

  async function submit() {
    const view = render(<StudioSurface conversationId="c1" />);
    await waitFor(() => expect(screen.getByTestId('composer-disabled')).toHaveTextContent('false'));
    // The surface adopts the thread's model on its own; that restore is not a reader's pick.
    track.mockClear();
    fireEvent.click(screen.getByTestId('switch-model'));
    fireEvent.click(screen.getByTestId('submit'));
    await lastSubmit;
    return view;
  }

  it('reports the model the reader picked, and the outcome of the turn, never the prompt', async () => {
    studio();
    await submit();

    expect(track).toHaveBeenCalledWith('studio_model_selected', {
      model: 'flux-1', kind: 'image', provider: 'flux', entry_point: 'studio',
    });
    expect(submitted()).toEqual([['studio_generation_submitted', {
      model: 'flux-1', kind: 'image', provider: 'flux', credential_source: 'platform',
      outcome: 'success', entry_point: 'studio',
    }]]);
  });

  it.each([
    ['refused', { success: false, error: 'Out of credits', data: {} }],
    ['failed', { success: false, error: 'ran, not stored', data: { asset_url: 'https://p/x' } }],
  ])('a recorded %s answer is reported as such', async (outcome, result) => {
    studio();
    run.mockResolvedValue({ status: 'recorded', conversationId: 'c1', result });
    await submit();

    expect(submitted()).toEqual([['studio_generation_submitted', expect.objectContaining({ outcome })]]);
  });

  it('a lost turn is reported once, as lost', async () => {
    studio();
    run.mockImplementation(async () => {
      turnError = { code: 'connection_lost' };
      return { status: 'lost', conversationId: 'c1' };
    });
    const view = await submit();
    view.rerender(<StudioSurface conversationId="c1" />);

    expect(submitted()).toEqual([['studio_generation_submitted', expect.objectContaining({ outcome: 'lost' })]]);
  });

  it('a turn refused before it reached the thread is reported from the hook error', async () => {
    studio();
    run.mockImplementation(async () => {
      turnError = { code: 'refused' };
      return null;
    });
    const view = await submit();
    view.rerender(<StudioSurface conversationId="c1" />);

    await waitFor(() => expect(submitted()).toEqual([
      ['studio_generation_submitted', expect.objectContaining({ outcome: 'refused', model: 'flux-1' })],
    ]));
  });
});
