// @vitest-environment jsdom
import * as React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const apiMocks = vi.hoisted(() => ({
  // The catalogue is read through the SHARED hook, which fetches here, so the
  // stub sits at the service the whole app shares rather than at one caller's
  // wrapper: stubbing the wrapper left the hook fetching for real.
  getModels: vi.fn(),
  getModelOptions: vi.fn(),
  // The form now quotes each model row itself, so the price answer is part of
  // this suite's world. A quote that says the platform sells the model is the
  // ordinary case; the tests that need the other answer say so.
  getPlatformCredentialPublicInfo: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator/generation.service', () => ({
  generationService: {
    getModels: apiMocks.getModels,
    getModelOptions: apiMocks.getModelOptions,
  },
}));

vi.mock('@/lib/api/orchestrator', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api/orchestrator')>(
    '@/lib/api/orchestrator',
  );
  return {
    ...actual,
    orchestratorApi: {
      ...actual.orchestratorApi,
      getPlatformCredentialPublicInfo: apiMocks.getPlatformCredentialPublicInfo,
    },
  };
});

// The i18n key IS the rendered text, so an assertion names the key it depends on.
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k, useLocale: () => 'en' }));

// The upgrade notice builds a locale-prefixed link to the plans.
vi.mock('next/link', () => ({
  default: ({ href, children }: any) => <a href={href}>{children}</a>,
}));

// Whether this account's credits can pay for a generation. Stubbed at the RULE
// rather than at the balance: what the rule decides has its own suite, and this
// one is about what the inspector does with the verdict.
const credits = vi.hoisted(() => ({ blocked: false }));

// The rows are <option>s under this suite's native-select stand-in, and an
// <option> does not expose markup nested inside it. So the marker reports the
// verdict it was GIVEN, per row, which is the thing under test.
const badges = vi.hoisted(() => ({ blocked: [] as boolean[] }));
vi.mock('@/components/billing/UpgradeRequiredBadge', () => ({
  UpgradeRequiredBadge: ({ blocked }: { blocked: boolean }) => {
    badges.blocked.push(blocked);
    return null;
  },
  UpgradeRequiredNotice: ({ blocked }: { blocked: boolean }) => (
    blocked ? <a href="/en/app/settings/pricing">cta</a> : null
  ),
}));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: credits.blocked, isLoading: false }),
}));

vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: ({ value, onChange, placeholder, handleId }: any) => (
    <textarea
      data-testid={handleId}
      placeholder={placeholder}
      value={value}
      onChange={(e) => onChange(e.target.value)}
    />
  ),
}));

vi.mock('@/components/ui/input', () => ({
  Input: (props: any) => <input {...props} />,
}));

vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, children, disabled }: any) => (
    <select
      data-testid="select"
      value={value ?? ''}
      disabled={disabled}
      onChange={(e) => onValueChange(e.target.value)}
    >
      <option value="" />
      {children}
    </select>
  ),
  SelectTrigger: ({ children }: any) => <>{children}</>,
  SelectValue: () => null,
  SelectContent: ({ children }: any) => <>{children}</>,
  SelectItem: ({ value, children }: any) => <option value={value}>{children}</option>,
}));

// The provider mark is an <img> off the public folder: nothing to assert, and
// jsdom would only warn about the missing file.
vi.mock('@/lib/generation/formats', async () => {
  const actual = await vi.importActual<typeof import('@/lib/generation/formats')>(
    '@/lib/generation/formats',
  );
  return { ...actual, ProviderIcon: () => null };
});

vi.mock('@/components/ui/popover', () => ({
  Popover: ({ children }: any) => <div>{children}</div>,
  PopoverTrigger: ({ children }: any) => <div>{children}</div>,
  PopoverContent: ({ children }: any) => <div>{children}</div>,
}));

// The credential section owns the price rendering (tested on its own); here we
// only need to see WHAT the form quotes it with.
const credentialSectionProps = vi.hoisted(() => ({ last: null as any }));
vi.mock('../../CredentialSection', () => ({
  CredentialSection: (props: any) => {
    credentialSectionProps.last = props;
    return <div data-testid="credential-section" />;
  },
}));

import { GenerateParametersForm } from '../GenerateParametersForm';

const VIDEO_MODEL = {
  model: 'seedance-2.0-fast',
  kind: 'video',
  label: 'Seedance 2.0 Fast',
  provider: 'Seedance',
  iconSlug: 'seedance',
  apiToolId: 'tool-uuid',
  integrationName: 'seedance',
  accepts: ['prompt', 'duration_seconds', 'aspect_ratio'],
  required: ['prompt'],
  limits: {
    duration_seconds: { min: 1, max: 12 },
    aspect_ratio: { allowed: ['16:9', '9:16'] },
  },
  price: { unit: 'second', baseCredits: '0', unitCredits: '60' },
  async: true,
};

const VOICE_MODEL = {
  model: 'eleven-v3',
  kind: 'voice',
  label: 'Eleven v3',
  provider: 'ElevenLabs',
  iconSlug: 'elevenlabs',
  apiToolId: 'tool-uuid-2',
  integrationName: 'elevenlabs',
  accepts: ['prompt', 'voice'],
  required: ['prompt', 'voice'],
  limits: {},
  price: { unit: 'character', baseCredits: '0', unitCredits: '2' },
  async: false,
};

/** Listed per minute, still measured in seconds like every other duration. */
const MUSIC_PER_MINUTE_MODEL = {
  model: 'music-per-minute',
  kind: 'music',
  label: 'Music per minute',
  provider: 'ElevenLabs',
  iconSlug: 'elevenlabs',
  apiToolId: 'tool-uuid-3',
  integrationName: 'elevenlabs',
  accepts: ['prompt', 'duration_seconds'],
  required: ['prompt'],
  limits: {},
  price: { unit: 'minute', baseCredits: '0', unitCredits: '480' },
  async: false,
};

/** A second model of the SAME format and provider, so the model list holds two rows. */
const VIDEO_MODEL_B = {
  ...VIDEO_MODEL,
  model: 'seedance-2.0',
  label: 'Seedance 2.0',
  apiToolId: 'tool-uuid-b',
  accepts: ['prompt'],
  required: ['prompt'],
  limits: {},
};

/** Voices belong to the ACCOUNT behind the key, so the model only says asking is worth it. */
const VOICE_MODEL_DYNAMIC = {
  ...VOICE_MODEL,
  limits: { voice: { optionsAvailable: true } },
};

/** A model that animates FROM stills: two file slots, named by what they are to it. */
const IMAGE_TO_VIDEO_MODEL = {
  ...VIDEO_MODEL,
  model: 'frames-to-video',
  label: 'Frames to video',
  accepts: ['prompt', 'input_image', 'guidance_scale'],
  required: ['prompt'],
  inputs: { input_image: { role: 'first_frame', maxItems: 2 } },
  limits: { guidance_scale: { min: 1, max: 20 } },
};

/**
 * What the platform answers for a model it sells: available, keyed and priced.
 *
 * <p>The form asks this per model row now, and the answer decides two things
 * that used to be invisible here: whether a price is shown beside a model at
 * all, and whether the payer toggle can honestly offer the platform.
 */
const SOLD_QUOTE = {
  available: true,
  platformCredentialId: 7,
  hasPricing: true,
  priceUnit: 'second',
  unitCredits: '60',
  baseCredits: '0',
  // A string, as the wire type says. A fixture that disagrees with the shape
  // production receives cannot see a coercion bug.
  quantity: '10',
  markupCredits: '600',
};

/**
 * The last patch handed to onUpdate, once one carrying `key` has arrived.
 *
 * <p>Reading `mock.calls[last]` straight after a fireEvent is a race: the payer
 * flip and the stale-option drop are EFFECTS, so they append renders and
 * further update() calls after the click has been handled. Under load the
 * assertion lands on a different call than the one the case is about, or on no
 * call at all.
 */
async function patchCarrying(onUpdate: any, key: string): Promise<any> {
  await waitFor(() => {
    const calls = onUpdate.mock.calls;
    expect(
      calls.some((c: any[]) => c[0] && Object.prototype.hasOwnProperty.call(c[0], key)),
      `no update carrying "${key}" arrived`,
    ).toBe(true);
  });
  const calls = onUpdate.mock.calls.filter(
    (c: any[]) => c[0] && Object.prototype.hasOwnProperty.call(c[0], key));
  return calls[calls.length - 1][0];
}

const connectionProps = {
  connections: [],
  draggingFromHandle: null,
  handleHandleClick: vi.fn(),
  handleHandleMouseDown: vi.fn(),
  handleHandleMouseUp: vi.fn(),
} as any;

function renderForm(data: any, onUpdate = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <GenerateParametersForm
        node={{ id: 'generate-1', data } as any}
        data={data}
        onUpdate={onUpdate}
        connectionProps={connectionProps}
        findUnknownVariables={() => []}
      />
    </QueryClientProvider>,
  );
  return { ...utils, onUpdate };
}

/**
 * The generate inspector.
 *
 * <p>Two properties are load bearing. Only the parameters the SELECTED model
 * accepts may be offered, because anything else is refused at run time. And the
 * price quoted must be for the request currently typed: a generation is charged
 * per run and a per-second model costs ten times more for a ten second clip, so
 * a rate alone is not what the user needs to see.
 */
describe('GenerateParametersForm', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    credits.blocked = false;
    badges.blocked = [];
    credentialSectionProps.last = null;
    apiMocks.getModels.mockResolvedValue({
      models: [VIDEO_MODEL, VOICE_MODEL],
      count: 2,
      kinds: ['video', 'voice'],
    });
    apiMocks.getModelOptions.mockResolvedValue({ success: true, options: [] });
    apiMocks.getPlatformCredentialPublicInfo.mockResolvedValue(SOLD_QUOTE);
  });

  afterEach(cleanup);

  it('asks for the FORMAT first, and offers no model list until it has one', async () => {
    // The catalogue holds hundreds of models across five formats. Opening on a
    // single flat list made the first decision by scrolling, and the format is
    // the decision that actually narrows it.
    renderForm({});

    expect(await screen.findByText('formats.video')).toBeTruthy();
    expect(screen.getByText('formats.voice')).toBeTruthy();
    expect(screen.queryByText('Seedance 2.0 Fast')).toBeNull();
  });

  it('lands on a model of the format that was clicked, so the form below is answerable', async () => {
    const { onUpdate } = renderForm({});
    await screen.findByText('formats.voice');

    fireEvent.click(screen.getByText('formats.voice').closest('button')!);

    const patch = onUpdate.mock.calls[0][0];
    expect(patch.generateModel).toBe('eleven-v3');
    // The parameters and the pinned key belong to the model being left.
    expect(patch.generateParams).toEqual({});
    expect(patch.selectedCredentialId).toBeNull();
  });

  /**
   * Choosing what is already chosen must change nothing.
   *
   * <p>The active tile is rendered as pressed, which makes clicking it an
   * ordinary thing to do, and the handler behind it resets the node: the
   * prompt, every parameter and the pinned key are dropped and the model jumps
   * to the catalogue's first of that format, routinely another provider's.
   *
   * <p>This harness is exactly the shape that defeats a guard reading a stale
   * `kind`: `onUpdate` is a spy that never hands back a new `data`, so a
   * callback memoised without `kind` in its deps is never rebuilt.
   */
  it('re-clicking the format tile that is already active changes nothing', async () => {
    const { onUpdate } = renderForm({
      generateModel: 'seedance-2.0-fast',
      generateCredentialSource: 'user',
      selectedCredentialId: 42,
      generateParams: { prompt: 'a boat', duration_seconds: 10 },
    });

    // The pressed tile is the format the chosen model produces.
    await waitFor(() => expect(screen.getByText('formats.video')).toBeTruthy());
    const tile = screen.getByText('formats.video').closest('button')!;
    expect(tile.getAttribute('aria-pressed'), 'the video tile is the active one').toBe('true');

    fireEvent.click(tile);

    expect(
      onUpdate,
      'a re-click wiped the prompt, the parameters and the pinned key',
    ).not.toHaveBeenCalled();
  });

  it('lists only the models of the chosen PROVIDER, and switching provider moves to its first', async () => {
    apiMocks.getModels.mockResolvedValue({
      models: [VIDEO_MODEL, { ...VIDEO_MODEL_B, provider: 'Runway' }],
      count: 2,
      kinds: ['video'],
    });
    const { onUpdate } = renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });

    await waitFor(() => expect(screen.getByText(/Seedance 2\.0 Fast/)).toBeTruthy());
    // The other provider's model is not on the list being chosen from.
    //
    // Matched on the row's own label rather than with a $-anchored regex: a row
    // renders as `${label} - ${price}` now, so an anchored pattern stopped
    // matching the very row it was written to forbid, and would have gone on
    // passing with the provider filter removed.
    expect(
      screen.queryAllByText((_, el) => (el?.textContent ?? '').startsWith('Seedance 2.0 -')
        || (el?.textContent ?? '').trim() === 'Seedance 2.0'),
      'a model of another provider must not be offered on this list',
    ).toHaveLength(0);

    fireEvent.change(screen.getAllByTestId('select')[0], { target: { value: 'Runway' } });
    expect(onUpdate.mock.calls[0][0].generateModel).toBe('seedance-2.0');
  });

  it('states each model price ON its row, so models can be compared before one is chosen', async () => {
    // The price is what this choice spends. Showing it only after the choice is
    // made is the wrong order for something that costs money.
    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: { duration_seconds: 10 } });

    // A specific sentence, not any `price.` key: `price.unpriced` starts the
    // same way, and it is exactly what a failed quote produces, so the loose
    // pattern passed on the state this case exists to rule out.
    await waitFor(() =>
      expect(screen.getByText(/Seedance 2\.0 Fast - price\.total/)).toBeTruthy());
  });

  it('shows no price on a row once the node runs on the author OWN key', async () => {
    // The platform charges nothing then, so a credit figure would quote an
    // amount this run cannot cost.
    //
    // The wait is on the PLATFORM case first, and the assertion is the absence
    // of exactly the sentence that case produces. Waiting only for the model
    // label resolved before any quote had settled, so deleting the payer gate
    // left the suite green: the case named a behaviour it never reached.
    const { unmount } = renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });
    await waitFor(() =>
      expect(screen.getByText(/Seedance 2\.0 Fast - price\.total/)).toBeTruthy());
    unmount();

    renderForm({
      generateModel: 'seedance-2.0-fast',
      generateCredentialSource: 'user',
      generateParams: {},
    });

    await waitFor(() => expect(screen.getByText('Seedance 2.0 Fast')).toBeTruthy());
    expect(
      screen.queryByText(/price\.total/),
      'the same row quoted a platform price for a run the platform does not bill',
    ).toBeNull();
  });

  it('shows ONLY the parameters the selected model accepts', async () => {
    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });

    await waitFor(() => expect(screen.getByText('generate.params.prompt')).toBeTruthy());
    expect(screen.getByText('generate.params.duration_seconds')).toBeTruthy();
    expect(screen.getByText('generate.params.aspect_ratio')).toBeTruthy();
    // `voice` belongs to the other model; offering it here would produce a call
    // the provider refuses.
    expect(screen.queryByText('generate.params.voice')).toBeNull();
  });

  it('renders an enumerated limit as a closed list, so an unaccepted value cannot be typed', async () => {
    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });

    await waitFor(() => expect(screen.getByText('16:9')).toBeTruthy());
    expect(screen.getByText('9:16')).toBeTruthy();
  });

  it('drops the parameters the new model does not accept when the model changes', async () => {
    apiMocks.getModels.mockResolvedValue({
      models: [VIDEO_MODEL, VIDEO_MODEL_B], count: 2, kinds: ['video'],
    });
    const { onUpdate } = renderForm({
      generateModel: 'seedance-2.0-fast',
      generateParams: { prompt: 'a boat', duration_seconds: 10, aspect_ratio: '16:9' },
    });

    // Wait for the model list to arrive: an option that is not there yet cannot
    // be picked. Index 1 is the model list; index 0 is the provider above it.
    await waitFor(() => expect(screen.getByText(/Seedance 2\.0$/)).toBeTruthy());
    fireEvent.change(screen.getAllByTestId('select')[1], { target: { value: 'seedance-2.0' } });

    expect(onUpdate).toHaveBeenCalled();
    const patch = onUpdate.mock.calls[0][0];
    expect(patch.generateModel).toBe('seedance-2.0');
    // Carrying these over would leave the form showing values the new model refuses.
    expect(patch.generateParams).toEqual({ prompt: 'a boat' });
  });

  it('drops the pinned key when the model changes, since a key belongs to ONE provider', async () => {
    // Two models of the same format routinely come from two providers. A pin
    // carried across names a key that can never apply: the run refuses it and
    // falls back, so the node fails for a missing key rather than for the stale
    // pin. The picker cannot recover it either, because it only re-picks when
    // the new provider already has a key configured.
    apiMocks.getModels.mockResolvedValue({
      models: [VIDEO_MODEL, VIDEO_MODEL_B], count: 2, kinds: ['video'],
    });
    const { onUpdate } = renderForm({
      generateModel: 'seedance-2.0-fast',
      generateCredentialSource: 'user',
      selectedCredentialId: 42,
      generateParams: { prompt: 'a boat' },
    });

    await waitFor(() => expect(screen.getByText(/Seedance 2\.0$/)).toBeTruthy());
    fireEvent.change(screen.getAllByTestId('select')[1], { target: { value: 'seedance-2.0' } });

    expect(onUpdate.mock.calls[0][0].selectedCredentialId).toBeNull();
  });

  it('quotes the price for the SELECTED MODEL and the size currently entered', async () => {
    renderForm({
      generateModel: 'seedance-2.0-fast',
      generateParams: { prompt: 'a boat', duration_seconds: 10 },
    });

    await waitFor(() => expect(credentialSectionProps.last).toBeTruthy());
    expect(credentialSectionProps.last.integration).toBe('seedance');
    expect(credentialSectionProps.last.apiToolId).toBe('tool-uuid');
    expect(credentialSectionProps.last.modelId).toBe('seedance-2.0-fast');
    // 60 credits per second x 10 seconds is the number the user must see.
    expect(credentialSectionProps.last.quantity).toBe(10);
  });

  it('quotes a per-MINUTE model with the duration in seconds, and lets the server convert it', async () => {
    // Dividing by 60 here quoted a size the billing path never sends, so the
    // estimate and the invoice were two different sums over the same call. The
    // note prints the unit and quantity the quote answers with, not this value.
    apiMocks.getModels.mockResolvedValue({
      models: [MUSIC_PER_MINUTE_MODEL],
      count: 1,
      kinds: ['music'],
    });
    renderForm({
      generateModel: 'music-per-minute',
      generateParams: { prompt: 'lofi', duration_seconds: 60 },
    });

    await waitFor(() => expect(credentialSectionProps.last).toBeTruthy());
    expect(credentialSectionProps.last.quantity).toBe(60);
  });

  it('quotes a per-character model on the length of the prompt', async () => {
    renderForm({
      generateModel: 'eleven-v3',
      generateParams: { prompt: 'hello', voice: 'rachel' },
    });

    await waitFor(() => expect(credentialSectionProps.last).toBeTruthy());
    expect(credentialSectionProps.last.modelId).toBe('eleven-v3');
    expect(credentialSectionProps.last.quantity).toBe(5);
  });

  it('passes no size when the driving parameter is empty, so no wrong total is quoted', async () => {
    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: { prompt: 'a boat' } });

    await waitFor(() => expect(credentialSectionProps.last).toBeTruthy());
    expect(credentialSectionProps.last.quantity).toBeNull();
  });

  it('defaults to the platform key, which is the arrangement the quoted price describes', async () => {
    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });

    await waitFor(() => expect(credentialSectionProps.last).toBeTruthy());
    expect(credentialSectionProps.last.credentialSource).toBe('platform');
  });

  it('shows no price section before a model is chosen: there is nothing to price yet', async () => {
    renderForm({});

    await waitFor(() => expect(screen.getByText('formats.video')).toBeTruthy());
    expect(screen.queryByTestId('credential-section')).toBeNull();
  });

  it('writes a numeric parameter back as a number, so the size billed is not a string', async () => {
    const { onUpdate } = renderForm({
      generateModel: 'seedance-2.0-fast',
      generateParams: {},
    });

    await waitFor(() => expect(screen.getByText('generate.params.duration_seconds')).toBeTruthy());
    const numberInput = document.querySelector('input[type="number"]') as HTMLInputElement;
    fireEvent.change(numberInput, { target: { value: '8' } });

    expect(onUpdate).toHaveBeenCalled();
    expect(onUpdate.mock.calls[0][0].generateParams.duration_seconds).toBe(8);
  });

  it('reports when the platform offers no generation models at all', async () => {
    apiMocks.getModels.mockResolvedValue({ models: [], count: 0, kinds: [] });

    renderForm({});

    expect(await screen.findByText('generate.noModels')).toBeTruthy();
  });

  /**
   * A generate node splits its bill in two: the flat fee for RUNNING the node,
   * which a Free plan's monthly credits do cover, and the generation itself on
   * the platform's key, which they do not. The second half is what gets
   * refused, and it used to be refused with no warning anywhere on this form.
   */
  it('warns that the generation itself cannot be paid for, and offers the way out', async () => {
    credits.blocked = true;

    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });

    expect(await screen.findByRole('link', { name: 'cta' })).toBeTruthy();
  });

  it('says nothing to an account whose credits can pay', async () => {
    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });
    await screen.findAllByTestId('select');

    expect(screen.queryByRole('link', { name: 'cta' })).toBeNull();
  });

  it('says nothing once the node runs on the reader’s OWN key', async () => {
    // Their own key is billed by the provider directly, so no credits are
    // involved and a warning about credits would be a lie.
    credits.blocked = true;

    renderForm({
      generateModel: 'seedance-2.0-fast',
      generateParams: {},
      generateCredentialSource: 'user',
    });
    await screen.findAllByTestId('select');

    expect(screen.queryByRole('link', { name: 'cta' })).toBeNull();
  });

  it('says nothing about price on any row when the chosen model\'s quote FAILS', async () => {
    // The row quotes are disabled until the chosen model's quote answers, and
    // a disabled query reports isLoading false. So a 5xx or a dropped
    // connection left every row saying, permanently, that the model is not
    // sold here: a definite claim built from no answer at all, and there is no
    // error state on screen to distinguish it from the truth.
    apiMocks.getPlatformCredentialPublicInfo.mockRejectedValue(new Error('gateway said no'));

    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });
    await waitFor(() => expect(screen.getByText(/Seedance 2\.0 Fast/)).toBeTruthy());

    expect(
      screen.queryByText(/price\.unpriced/),
      'an unanswered quote is not an answer of "the platform does not sell this"',
    ).toBeNull();
  });

  it('keeps a {{template}} on a numeric parameter readable instead of blanking it', async () => {
    // <input type="number"> given a template renders EMPTY: the saved value is
    // invisible and the first keystroke replaces it, so a node wired to an
    // upstream duration lost that wiring by being looked at.
    renderForm({
      generateModel: 'seedance-2.0-fast',
      generateParams: { duration_seconds: '{{trigger:start.output.seconds}}' },
    });
    await screen.findAllByTestId('select');

    expect(
      screen.getByDisplayValue('{{trigger:start.output.seconds}}'),
      'the template must be on screen, in a field that can edit it',
    ).toBeTruthy();
  });

  it('drops a carried-over value the NEW model does not allow, rather than hiding it', async () => {
    // Accepting the KEY is not accepting the VALUE. Kept, the Select matches no
    // item and falls back to its placeholder: the form reads as unset while the
    // plan still holds the value, and the run is refused for a field nobody can
    // see.
    apiMocks.getModels.mockResolvedValue({
      models: [
        { ...VIDEO_MODEL, accepts: ['prompt', 'aspect_ratio'],
          limits: { aspect_ratio: { allowed: ['16:9', '9:16'] } } },
        { ...VIDEO_MODEL_B, provider: VIDEO_MODEL.provider, accepts: ['prompt', 'aspect_ratio'],
          limits: { aspect_ratio: { allowed: ['1:1'] } } },
      ],
      count: 2,
      kinds: ['video'],
    });
    const { onUpdate } = renderForm({
      generateModel: 'seedance-2.0-fast',
      generateParams: { prompt: 'a boat', aspect_ratio: '9:16' },
    });
    await screen.findAllByTestId('select');

    fireEvent.change(screen.getAllByTestId('select')[1], { target: { value: 'seedance-2.0' } });

    const patch = await patchCarrying(onUpdate, 'generateParams');
    expect(patch.generateParams).not.toHaveProperty('aspect_ratio');
    expect(patch.generateParams, 'a value the new model DOES allow is kept')
      .toHaveProperty('prompt', 'a boat');
  });

  it('keeps a {{template}} across a model change, since no list can judge a runtime value', async () => {
    apiMocks.getModels.mockResolvedValue({
      models: [
        { ...VIDEO_MODEL, accepts: ['prompt', 'aspect_ratio'],
          limits: { aspect_ratio: { allowed: ['16:9'] } } },
        { ...VIDEO_MODEL_B, provider: VIDEO_MODEL.provider, accepts: ['prompt', 'aspect_ratio'],
          limits: { aspect_ratio: { allowed: ['1:1'] } } },
      ],
      count: 2,
      kinds: ['video'],
    });
    const { onUpdate } = renderForm({
      generateModel: 'seedance-2.0-fast',
      generateParams: { aspect_ratio: '{{trigger:start.output.ratio}}' },
    });
    await screen.findAllByTestId('select');

    fireEvent.change(screen.getAllByTestId('select')[1], { target: { value: 'seedance-2.0' } });

    const patch = await patchCarrying(onUpdate, 'generateParams');
    expect(patch.generateParams)
      .toHaveProperty('aspect_ratio', '{{trigger:start.output.ratio}}');
  });

  it('says the catalogue could not be READ, not that the install serves nothing', async () => {
    // Four states share one empty list and only one of them is about the
    // installation. Reporting a hiccup as absence sends the reader to an
    // administrator over a dropped connection.
    apiMocks.getModels.mockRejectedValue(new Error('gateway said no'));

    renderForm({ generateModel: '', generateParams: {} });

    await waitFor(() =>
      expect(screen.getByText('generate.modelsUnavailable')).toBeTruthy());
    expect(screen.queryByText('generate.noModels')).toBeNull();
  });

  it('says so when the node names a model this installation does not serve', async () => {
    // Indistinguishable from an unconfigured node before: `selected` is null
    // either way, so the form collapsed to the format tiles and said nothing,
    // while the plan still held the model and the run would still be refused.
    renderForm({ generateModel: 'a-model-that-was-retired', generateParams: {} });
    // Waited on the CATALOGUE, not merely on the heading: while it is still in
    // flight the node's model is not unknown, it is unchecked, and asserting
    // then would certify the loading state.
    await waitFor(() => expect(screen.getByText('formats.video')).toBeTruthy());

    expect(screen.getByText('generate.unknownModel')).toBeTruthy();
  });

  it('regression: marks only the models the platform can actually sell', async () => {
    // A model with no platform credential behind it can only run on the
    // reader's own key, so it costs no credits and a lock on its row would be
    // the same lie the own-key branch already refuses. The list is unfiltered,
    // so both kinds appear together.
    credits.blocked = true;
    apiMocks.getModels.mockResolvedValue({
      // Same format and same provider, so BOTH are rows of the list being
      // chosen from: the list is scoped to one provider now, and two rows from
      // two providers would never have appeared together.
      models: [VIDEO_MODEL, { ...VIDEO_MODEL_B, integrationName: null }],
      count: 2,
      kinds: ['video'],
    });

    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });
    // Waited on the QUOTE, not merely on the rows. A quote still in flight is
    // not an answer of "the platform does not sell this", so every row reads as
    // blocked until it lands: asserting on the first render that had two rows
    // would certify the loading state instead of the verdict.
    await waitFor(() => expect(screen.getByText(/Seedance 2\.0 Fast - price\./)).toBeTruthy());

    // The LAST render's two rows: one marked, one left clean, being the sold
    // model and the one the platform has no credential for.
    //
    // Reading the tail is only sound because every render of this fixture emits
    // exactly the two rows of the one provider, so the count is always even.
    // Checked rather than assumed: if a change made the list render a different
    // number of rows, slicing two off the end would silently compare rows from
    // two different renders.
    // Waited on the verdicts rather than sliced off a render count. Every
    // render of this fixture emits its two rows, but the number of renders is
    // decided by effects and by two react-query resolutions, so taking the last
    // two entries could straddle a render boundary and compare one row of one
    // render with one row of the next.
    await waitFor(() => {
      const verdicts = badges.blocked.slice(-2);
      expect(verdicts.filter(Boolean), 'one row is marked').toHaveLength(1);
      expect(verdicts.filter((b) => !b), 'and one is left clean').toHaveLength(1);
    });
  });

  /**
   * A list the platform ENFORCES is a closed choice; one it merely knows about
   * is a suggestion. The difference matters most here, because this field is an
   * expression editor: a workflow binds a parameter to runtime data far more
   * often than it types one, and a closed select takes both the templating and
   * the connection handle away. A node saved with a template would then read as
   * unset, against a list that cannot contain it.
   */
  it('regression: an ADVISORY list leaves the expression field alone', async () => {
    // A list the platform does not enforce must not close the field: this one
    // is an expression editor, and a workflow binds a parameter to runtime data
    // far more often than it types one. A node saved with a template would
    // otherwise read as unset against a list that cannot contain it.
    apiMocks.getModels.mockResolvedValue({
      models: [{
        ...VIDEO_MODEL,
        limits: { aspect_ratio: { allowed: ['16:9', '9:16'], allowedEnforced: false } },
      }],
      count: 1,
      kinds: ['video'],
    });

    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });
    await waitFor(() => expect(screen.getByText('generate.params.aspect_ratio')).toBeTruthy());

    // The handle is what a connection attaches to, and only the expression
    // field carries one.
    expect(screen.queryByTestId('generate-aspect_ratio-generate-1')).not.toBeNull();
  });

  it('an ENFORCED list still becomes a closed choice', async () => {
    // The same parameter and the same values, one difference: this list is
    // refused before the call is billed, so closing the field is honest.
    renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });
    await waitFor(() => expect(screen.getByText('generate.params.aspect_ratio')).toBeTruthy());

    expect(screen.queryByTestId('generate-aspect_ratio-generate-1')).toBeNull();
  });
  /**
   * The steps are in dependency order, and the payer sits ABOVE the model list.
   *
   * <p>Not a matter of taste: the price stated on each model row only exists
   * when the PLATFORM is the one being paid. Reading the prices and then
   * discovering the node runs on the author's own key is the wrong order for a
   * decision that spends money.
   */
  it('asks who pays before it lists the models, because the prices depend on the answer', async () => {
    const { container } = renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });
    await screen.findByTestId('credential-section');

    // Read from the steps the form renders, in the order it renders them.
    // indexOf on the panel text returned -1 for a heading that was not there,
    // and -1 is less than everything: deleting a whole step made these
    // assertions pass rather than fail, which is the one thing they exist to
    // catch. The stable name is on the section itself.
    const order = Array.from(container.querySelectorAll('[data-generate-step]'))
      .map((el) => el.getAttribute('data-generate-step'));

    expect(order, 'the payer is settled between the provider and the model list')
      .toEqual(['assetType', 'provider', 'credential', 'model', 'parameters']);
  });

  /**
   * Values only the PROVIDER can name (a voice belongs to the account holding
   * the key) are offered without closing the field.
   *
   * <p>Nothing on the platform enforces them, and a workflow binds such a field
   * to runtime data at least as often as it types one. A closed select would
   * take the templating and the connection handle away, and a node saved with a
   * template would then read as unset against a list that cannot contain it.
   */
  it('offers the provider values AND keeps the expression field', async () => {
    apiMocks.getModels.mockResolvedValue({
      models: [VOICE_MODEL_DYNAMIC], count: 1, kinds: ['voice'],
    });
    apiMocks.getModelOptions.mockResolvedValue({
      success: true,
      options: [{ value: 'voice-id-1', label: 'Rachel' }],
    });

    renderForm({ generateModel: 'eleven-v3', generateParams: {} });

    expect(await screen.findByText('Rachel')).toBeTruthy();
    expect(screen.queryByTestId('generate-voice-generate-1')).not.toBeNull();
  });

  /**
   * Dropping a value the chosen KEY cannot use, and the four cases where
   * dropping it would be wrong.
   *
   * <p>This is the only place the form deletes something the author saved. A
   * voice id belongs to the provider ACCOUNT behind the key, so a node
   * carrying one the current key has never heard of would fail at the
   * provider AFTER the generation is paid for, and the field would look
   * correctly filled the whole time. That is worth a deletion.
   *
   * <p>What is not worth a deletion is anything the list cannot actually
   * judge, and every guard below exists because the answer arrives
   * incrementally: an empty list, a list still loading and a truncated one
   * are all states where 'not in the list' means 'not known yet'.
   */
  it('drops a value the chosen key does not offer, since it fails only after the call is paid for', async () => {
    apiMocks.getModels.mockResolvedValue({
      models: [VOICE_MODEL_DYNAMIC], count: 1, kinds: ['voice'],
    });
    apiMocks.getModelOptions.mockResolvedValue({
      success: true,
      options: [{ value: 'voice-id-1', label: 'Rachel' }],
    });

    const { onUpdate } = renderForm({
      generateModel: 'eleven-v3',
      generateParams: { voice: 'a-voice-from-another-account' },
    });

    await waitFor(() => expect(onUpdate).toHaveBeenCalled());
    const patch = await patchCarrying(onUpdate, 'generateParams');
    expect(patch.generateParams).not.toHaveProperty('voice');
  });

  it('keeps a value the list DOES offer, so the drop is not a blanket one', async () => {
    apiMocks.getModels.mockResolvedValue({
      models: [VOICE_MODEL_DYNAMIC], count: 1, kinds: ['voice'],
    });
    apiMocks.getModelOptions.mockResolvedValue({
      success: true,
      options: [{ value: 'voice-id-1', label: 'Rachel' }],
    });

    const { onUpdate } = renderForm({
      generateModel: 'eleven-v3',
      generateParams: { voice: 'voice-id-1' },
    });

    await waitFor(() => expect(screen.getByText('Rachel')).toBeTruthy());
    expect(onUpdate, 'a valid value must not dirty the workflow').not.toHaveBeenCalled();
  });

  it('never drops a {{template}}, whose runtime value no list can judge', async () => {
    apiMocks.getModels.mockResolvedValue({
      models: [VOICE_MODEL_DYNAMIC], count: 1, kinds: ['voice'],
    });
    apiMocks.getModelOptions.mockResolvedValue({
      success: true,
      options: [{ value: 'voice-id-1', label: 'Rachel' }],
    });

    const { onUpdate } = renderForm({
      generateModel: 'eleven-v3',
      generateParams: { voice: '{{trigger:start.output.voice}}' },
    });

    await waitFor(() => expect(screen.getByText('Rachel')).toBeTruthy());
    expect(onUpdate).not.toHaveBeenCalled();
  });

  it('never drops against an EMPTY list, which says nothing about the value', async () => {
    // A key with no voices of its own, or an endpoint that answered with
    // none: either way the value is unjudged, not wrong.
    apiMocks.getModels.mockResolvedValue({
      models: [VOICE_MODEL_DYNAMIC], count: 1, kinds: ['voice'],
    });
    apiMocks.getModelOptions.mockResolvedValue({ success: true, options: [] });

    const { onUpdate } = renderForm({
      generateModel: 'eleven-v3',
      generateParams: { voice: 'a-voice-from-another-account' },
    });

    await waitFor(() => expect(apiMocks.getModelOptions).toHaveBeenCalled());
    expect(onUpdate).not.toHaveBeenCalled();
  });

  it('never drops against a TRUNCATED list, which is a sample rather than an answer', async () => {
    // A value outside a sample is not evidence of anything, and the field is
    // left typeable precisely so a reader can name a voice the sample missed.
    apiMocks.getModels.mockResolvedValue({
      models: [VOICE_MODEL_DYNAMIC], count: 1, kinds: ['voice'],
    });
    apiMocks.getModelOptions.mockResolvedValue({
      success: true,
      truncated: true,
      options: [{ value: 'voice-id-1', label: 'Rachel' }],
    });

    const { onUpdate } = renderForm({
      generateModel: 'eleven-v3',
      generateParams: { voice: 'a-voice-outside-the-sample' },
    });

    await waitFor(() => expect(screen.getByText('Rachel')).toBeTruthy());
    expect(onUpdate).not.toHaveBeenCalled();
  });

  it('asks the provider with the key the node will actually run on', async () => {
    // A voice list read on the platform key is not the author's own. Sending
    // the wrong payer returns ids the chosen key has never heard of, and a
    // wrong voice id fails at the provider AFTER the call is paid for.
    apiMocks.getModels.mockResolvedValue({
      models: [VOICE_MODEL_DYNAMIC], count: 1, kinds: ['voice'],
    });

    renderForm({
      generateModel: 'eleven-v3',
      generateCredentialSource: 'user',
      selectedCredentialId: 42,
      generateParams: {},
    });

    await waitFor(() => expect(apiMocks.getModelOptions).toHaveBeenCalled());
    expect(apiMocks.getModelOptions).toHaveBeenCalledWith('eleven-v3', 'voice', 'user', 42);
  });

  /**
   * One field per file the model takes, each named for what that file IS to it.
   *
   * <p>A single "Reference image" on a model that animates FROM a still
   * describes the wrong thing, and one field on a model that composes two hid
   * half of what it can do.
   */
  it('offers one field per file the model takes, named by the role it plays', async () => {
    apiMocks.getModels.mockResolvedValue({
      models: [IMAGE_TO_VIDEO_MODEL], count: 1, kinds: ['video'],
    });

    renderForm({ generateModel: 'frames-to-video', generateParams: {} });

    await waitFor(() => expect(screen.getByText('assetRoles.first_frame 1')).toBeTruthy());
    expect(screen.getByText('assetRoles.first_frame 2')).toBeTruthy();
    expect(screen.queryByTestId('generate-input_image-0-generate-1')).not.toBeNull();
    expect(screen.queryByTestId('generate-input_image-1-generate-1')).not.toBeNull();
  });

  it('falls back to the parameter name when the model declares no role for the file', async () => {
    // A model that says nothing about what its file IS still has to be fillable, and the
    // parameter's own name is what any documentation about it uses.
    apiMocks.getModels.mockResolvedValue({
      models: [{ ...IMAGE_TO_VIDEO_MODEL, inputs: {} }], count: 1, kinds: ['video'],
    });

    renderForm({ generateModel: 'frames-to-video', generateParams: {} });

    await waitFor(() => expect(screen.getByText('generate.params.input_image')).toBeTruthy());
  });

  it('sends several files as a LIST, which is the shape the provider is given', async () => {
    apiMocks.getModels.mockResolvedValue({
      models: [IMAGE_TO_VIDEO_MODEL], count: 1, kinds: ['video'],
    });

    const { onUpdate } = renderForm({
      generateModel: 'frames-to-video',
      generateParams: { input_image: ['{{core:first.output.file}}'] },
    });
    await waitFor(() => expect(screen.getByText('assetRoles.first_frame 2')).toBeTruthy());

    fireEvent.change(screen.getByTestId('generate-input_image-1-generate-1'), {
      target: { value: '{{core:last.output.file}}' },
    });

    expect(onUpdate.mock.calls[0][0].generateParams.input_image)
      .toEqual(['{{core:first.output.file}}', '{{core:last.output.file}}']);
  });

  it('sends a single-file model the handle itself, not a list of one', async () => {
    apiMocks.getModels.mockResolvedValue({
      models: [{ ...IMAGE_TO_VIDEO_MODEL, inputs: { input_image: { role: 'source', maxItems: 1 } } }],
      count: 1,
      kinds: ['video'],
    });

    const { onUpdate } = renderForm({ generateModel: 'frames-to-video', generateParams: {} });
    // Headed by the ROLE, not by the parameter's own name. The heading used to say
    // "Reference image" over fields the model itself called a first frame.
    await waitFor(() => expect(screen.getByText('assetRoles.source')).toBeTruthy());
    expect(screen.queryByText('generate.params.input_image')).toBeNull();

    fireEvent.change(screen.getByTestId('generate-input_image-generate-1'), {
      target: { value: '{{core:download.output.file}}' },
    });

    expect(onUpdate.mock.calls[0][0].generateParams.input_image)
      .toBe('{{core:download.output.file}}');
  });

  /**
   * A parameter this build has never heard of is still the model's own.
   *
   * <p>Filtering the list down to a fixed vocabulary is how a model ends up half
   * configurable in the builder and fully configurable through the agent, for no
   * reason the author can see.
   */
  it('offers a parameter the model declares even with no control named for it', async () => {
    apiMocks.getModels.mockResolvedValue({
      models: [IMAGE_TO_VIDEO_MODEL], count: 1, kinds: ['video'],
    });

    renderForm({ generateModel: 'frames-to-video', generateParams: {} });

    // Shown under its contract name, and as a NUMBER because the catalogue
    // describes it with bounds: sending it as text is what the provider refuses.
    await waitFor(() => expect(screen.getByText('guidance_scale')).toBeTruthy());
    const numeric = document.querySelector('input[type="number"]') as HTMLInputElement;
    expect(numeric).not.toBeNull();
    expect(numeric.min).toBe('1');
    expect(numeric.max).toBe('20');
  });

  /**
   * The payer is rewritten only on an ANSWER, never on a silence.
   *
   * <p>A failed quote and a never-asked one read the same way as a refusal, so
   * flipping on that verdict alone would change what a run costs, and mark the
   * workflow dirty, because of a dropped request.
   */
  it('falls back to the author key when the platform answers that it cannot sell the model', async () => {
    apiMocks.getPlatformCredentialPublicInfo.mockResolvedValue({
      available: true, platformCredentialId: null, hasPricing: false,
    });

    const { onUpdate } = renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });

    await waitFor(() => expect(onUpdate).toHaveBeenCalled());
    expect(onUpdate.mock.calls[0][0].generateCredentialSource).toBe('user');
  });

  it('does not touch the payer when the quote never answered', async () => {
    apiMocks.getPlatformCredentialPublicInfo.mockRejectedValue(new Error('network'));

    const { onUpdate } = renderForm({ generateModel: 'seedance-2.0-fast', generateParams: {} });
    await screen.findByTestId('credential-section');

    expect(onUpdate).not.toHaveBeenCalled();
  });
});
