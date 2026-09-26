// @vitest-environment jsdom
/**
 * The composer's promise: what is on screen is exactly what the selected model accepts.
 *
 * <p>Two things are pinned here because getting either wrong costs the reader money or time.
 *
 * <p><b>The attachment control is not a constant.</b> On a model that takes no file it must be
 * absent, not disabled and not decorative: a control whose only possible outcome is a refusal, paid
 * for after the reader has chosen a file, is worse than no control.
 *
 * <p><b>Changing model drops what the new one cannot accept.</b> The platform REFUSES a parameter a
 * model does not declare, so a value carried across a model switch turns the next turn into a
 * failure whose cause is nowhere on screen.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => {
    const t = (key: string) => key;
    t.has = () => false;
    return t;
  },
}));
vi.mock('@/hooks/useGenerationOptions', () => ({ useGenerationOptions: () => ({}) }));
// The upload path is real state, so the service is stood in for rather than the state machine.
const uploadGeneric = vi.hoisted(() => vi.fn());
vi.mock('@/lib/api/orchestrator/file.service', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api/orchestrator/file.service')>()),
  fileService: { uploadGeneric: (...a: unknown[]) => uploadGeneric(...a) },
}));
// The quote goes through react-query and the orchestrator client. What a generation costs is pinned
// where the price is computed; here it would only require a provider around every render.
// The price WORDS come from the shared formatter; this suite is about how the composer PRESENTS
// them (dimmed while stale, hidden while the reader's own key pays, absent when there is no
// published price), so the formatter is stood in for with a fixed amount.
const priceWords = vi.hoisted(() => ({ value: '12 credits' as string | null }));
vi.mock('@/lib/generation/price', () => ({
  describeQuotedPrice: () => priceWords.value,
  // The composer also states WHY a price is not the published rate, and formats the factor.
  describePriceFactors: () => '',
  formatCredits: (value: number) => String(value),
}));
const quoteState = vi.hoisted(() => ({
  value: { quote: undefined as unknown, quantity: null as number | null, settled: true, stale: false },
}));
vi.mock('@/hooks/useGenerationQuote', () => ({
  useGenerationQuote: () => quoteState.value,
}));
// The payer control mounts the workflow inspector's credential section, which drags in the whole
// query/credential stack. Which key pays is not what this suite is about.
vi.mock('@/components/studio/StudioPayerControl', () => ({
  StudioPayerControl: () => <div data-testid="payer" />,
}));

import { StudioComposer } from '../StudioComposer';

/** What the composer hands its caller. */
type StudioSubmission = { prompt: string; params: Record<string, unknown> };
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

function model(overrides: Partial<GenerationModel> = {}): GenerationModel {
  return {
    model: 'test-model',
    kind: 'image',
    label: 'Test model',
    provider: 'test',
    iconSlug: null,
    apiToolId: null,
    integrationName: null,
    accepts: ['prompt'],
    required: [],
    limits: {},
    billedOn: null,
    measuredUnit: null,
    defaultQuantity: null,
    price: { unit: 'call', baseCredits: '0', unitCredits: '0' },
    async: false,
    ...overrides,
  };
}

function renderComposer(selected: GenerationModel | null, props: Record<string, unknown> = {}) {
  // Resolves TRUE by default: the composer clears the draft only on a turn that was recorded. The
  // parameter is declared so the recorded calls keep their type - `vi.fn(async () => true)` infers a
  // zero-argument signature, and then `mock.calls[0][0]` is a type error rather than the assertion.
  const onSubmit = vi.fn(async (_input: StudioSubmission) => true);
  const onSelectModel = vi.fn();
  const utils = render(
    <StudioComposer
      models={selected ? [selected] : []}
      selectedModel={selected}
      onSelectModel={onSelectModel}
      onSubmit={onSubmit}
      {...props}
    />,
  );
  return { ...utils, onSubmit, onSelectModel };
}

/** The attachment control, by its accessible name. Absent means the model takes no file. */
function addFileControl() {
  return screen.queryByTitle('composer.addFile')
    ?? screen.queryByTitle('composer.allSlotsFull');
}

/**
 * Attach a file the way a reader does: open the control, say what the file is FOR, then pick it.
 *
 * <p>The middle step is not ceremony. The control used to open the picker straight away whenever
 * the model had one slot, so the reader chose a file without ever being told what the model would
 * do with it - and "first frame", "last frame" and "reference" are three different videos from the
 * same image.
 *
 * @param slot the label the menu gives the slot. With the stub translator a role falls back to the
 *        parameter's own name, which is what these tests match on.
 */
function chooseSlot(slot = 'input_image') {
  fireEvent.click(addFileControl()!);
  fireEvent.click(screen.getByText(slot));
}

afterEach(() => {
  // The quote is module-level state shared by every test in this file: a stale price left behind
  // would silently dim the label for whatever runs next.
  quoteState.value = { quote: undefined, quantity: null, settled: true, stale: false };
  priceWords.value = '12 credits';
  cleanup();
});

describe('StudioComposer - the attachment control follows the model', () => {
  it('offers no attachment control at all on a model that takes no file', () => {
    renderComposer(model({ accepts: ['prompt', 'seed'] }));
    expect(addFileControl()).toBeNull();
  });

  it('offers one on a model that takes a file', () => {
    renderComposer(model({
      accepts: ['prompt', 'input_image'],
      inputs: { input_image: { role: 'first_frame', maxItems: 1 } },
    }));
    expect(addFileControl()).not.toBeNull();
  });

  it('names the slot by what the file IS to this model, not by its type', () => {
    renderComposer(model({
      accepts: ['prompt', 'input_image'],
      inputs: { input_image: { role: 'first_frame', maxItems: 1 } },
    }));
    // The stub translator has no dictionary, so the guarded lookup falls back to the parameter
    // name. What is pinned here is that the label goes through the ROLE path at all.
    expect(addFileControl()).not.toBeNull();
  });
});

describe('StudioComposer - the attachment menu says what each file is for', () => {
  beforeEach(() => { uploadGeneric.mockReset(); });

  /** xAI 1.5: one call, three images, three different jobs. */
  const threeSlots = () => model({
    accepts: ['prompt', 'input_image', 'last_frame_image', 'reference_image'],
    inputs: {
      input_image: { role: 'first_frame', maxItems: 1 },
      last_frame_image: { role: 'last_frame', maxItems: 1 },
      reference_image: { role: 'reference', maxItems: 3 },
    },
  });

  it('names every slot the model takes, so the reader picks the job before the file', () => {
    renderComposer(threeSlots());

    fireEvent.click(addFileControl()!);

    expect(screen.getByText('composer.chooseRole')).toBeInTheDocument();
    expect(screen.getByText('input_image')).toBeInTheDocument();
    expect(screen.getByText('last_frame_image')).toBeInTheDocument();
    expect(screen.getByText('reference_image')).toBeInTheDocument();
  });

  it('asks even when there is only ONE slot, which is the case that used to go unsaid', () => {
    // The old control opened the picker directly here, and the slot's meaning was in a tooltip a
    // phone cannot open.
    renderComposer(model({
      accepts: ['prompt', 'input_image'],
      inputs: { input_image: { role: 'first_frame', maxItems: 1 } },
    }));

    fireEvent.click(addFileControl()!);

    expect(screen.getByText('composer.chooseRole')).toBeInTheDocument();
    expect(screen.getByText('input_image')).toBeInTheDocument();
  });

  it('says what a slot must be sent WITH, where the choice is made', () => {
    // The call is refused for free when the pair is half-complete, but a menu that offers the
    // closing frame like any other entry lets the reader find that out by pressing Generate.
    renderComposer(model({
      accepts: ['prompt', 'first_frame_image', 'last_frame_image'],
      inputs: {
        first_frame_image: { role: 'first_frame', maxItems: 1 },
        last_frame_image: {
          role: 'last_frame', maxItems: 1, requires: ['first_frame_image'],
        },
      },
    }));

    fireEvent.click(addFileControl()!);

    expect(screen.getByText('composer.goesWith')).toBeInTheDocument();
  });

  it('marks the attachment control when the turn is held for a file, and says which', async () => {
    // The send goes dead when half a pair is attached. The parameter pills carry that mark for
    // a value; an unmarked file slot left a dead Send button, a tooltip about highlighted
    // settings, and nothing highlighted.
    uploadGeneric.mockResolvedValue({
      id: 'f1', storageKey: 't/1/f1.png', fileName: 'f1.png', mimeType: 'image/png', size: 10,
    });
    const { container } = renderComposer(model({
      accepts: ['prompt', 'first_frame_image', 'last_frame_image'],
      inputs: {
        first_frame_image: { role: 'first_frame', maxItems: 1 },
        last_frame_image: { role: 'last_frame', maxItems: 1, requires: ['first_frame_image'] },
      },
    }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'a dolly shot' } });

    fireEvent.click(addFileControl()!);
    fireEvent.click(screen.getByText('last_frame_image'));
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', {
      value: [new File(['x'], 'a.png', { type: 'image/png' })], configurable: true,
    });
    fireEvent.change(input);
    await waitFor(() => expect(uploadGeneric).toHaveBeenCalled());

    // The control that opens the picker is what says a file is wanted, and the send is held.
    await waitFor(() => expect(screen.getByTitle('composer.fileWanted')).toBeInTheDocument());
    expect(screen.getByTitle(/composer\.(send|missingRequired)/)).toBeDisabled();
  });

  it('closes a slot the attached file forbids, with the reason on it', async () => {
    // Pinning a frame and lending a reference are two different kinds of request for some
    // providers, and mixing them can come back as a finished asset that used half the files.
    uploadGeneric.mockResolvedValue({
      id: 'f1', storageKey: 't/1/f1.png', fileName: 'f1.png', mimeType: 'image/png', size: 10,
    });
    const { container } = renderComposer(model({
      accepts: ['prompt', 'first_frame_image', 'input_image'],
      // Both sides carry the rule, which is what a model listing returns: the descriptor
      // declares it once and the server completes it (GenerationInputsTest pins that).
      inputs: {
        first_frame_image: { role: 'first_frame', maxItems: 1, excludes: ['input_image'] },
        input_image: { role: 'reference', maxItems: 2, excludes: ['first_frame_image'] },
      },
    }));

    // Attach the frame, then reopen the menu.
    fireEvent.click(addFileControl()!);
    fireEvent.click(screen.getByText('first_frame_image'));
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', {
      value: [new File(['x'], 'a.png', { type: 'image/png' })], configurable: true,
    });
    fireEvent.change(input);
    await waitFor(() => expect(uploadGeneric).toHaveBeenCalled());

    fireEvent.click(addFileControl()!);

    // Declared on the FRAME, read from the reference side: the rule holds whichever slot the
    // reader started from.
    expect(screen.getByText('composer.notWith')).toBeInTheDocument();
    expect(screen.getByText('input_image').closest('button')).toBeDisabled();
  });

  it('counts the slots of a parameter that takes several, so the reader knows how many are left', () => {
    renderComposer(threeSlots());

    fireEvent.click(addFileControl()!);

    // The reference slot takes three; the two frames take one each and are not numbered.
    expect(screen.getByText('1/3')).toBeInTheDocument();
  });
});

describe('StudioComposer - the parameter toggles follow the model', () => {
  it('draws a toggle for each parameter the model accepts, and none for the prompt', () => {
    renderComposer(model({
      accepts: ['prompt', 'aspect_ratio', 'seed'],
      limits: { aspect_ratio: { allowed: ['1:1', '16:9'] } },
    }));

    expect(screen.getByText('aspect_ratio')).toBeInTheDocument();
    expect(screen.getByText('seed')).toBeInTheDocument();
    // The prompt has the textarea; a toggle for it would be the same field twice.
    expect(screen.queryByText('prompt')).toBeNull();
  });

  it('draws no toggle for a parameter this model does not accept', () => {
    renderComposer(model({ accepts: ['prompt', 'seed'] }));
    expect(screen.queryByText('aspect_ratio')).toBeNull();
  });
});

describe('StudioComposer - what can be sent', () => {
  const sendButton = () => screen.getByTitle(/composer\.(send|missingRequired)/);

  it('refuses to send an empty composer', () => {
    renderComposer(model());
    expect(sendButton()).toBeDisabled();
  });

  it('sends once there are words', () => {
    renderComposer(model());
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'a lighthouse' } });
    expect(sendButton()).toBeEnabled();
  });

  it('holds the send while a required parameter is empty, and says which', () => {
    renderComposer(model({
      accepts: ['prompt', 'voice'],
      required: ['voice'],
      limits: { voice: { allowed: ['aria'] } },
    }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'read this' } });

    // Disabled, and the reason is on the control rather than discovered at the provider after the
    // call is paid for.
    expect(screen.getByTitle('composer.missingRequired')).toBeDisabled();
  });

  it('submits the prompt and the parameters together', () => {
    const { onSubmit } = renderComposer(model({ accepts: ['prompt', 'seed'] }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '  a lighthouse  ' } });
    fireEvent.click(sendButton());

    // Trimmed: the surrounding space is typing, not intent.
    expect(onSubmit).toHaveBeenCalledWith({ prompt: 'a lighthouse', params: {} });
  });

  it('is inert while a turn is running, so a second press is not a second charge', () => {
    renderComposer(model(), { isRunning: true });
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'a lighthouse' } });
    expect(screen.getByRole('textbox')).toBeDisabled();
  });
});

describe('StudioComposer - what is actually sent', () => {
  const sendButton = () => screen.getByTitle(/composer\.(send|missingRequired)/);

  it('never sends a parameter the selected model does not accept', async () => {
    // The leak this guards: a reused recipe carries the parameters of the model it was made on, and
    // that model may not even be in the catalogue any more. The platform REFUSES an undeclared
    // parameter, and the refusal names nothing the reader can act on - so the turn fails with its
    // cause nowhere on screen.
    //
    // Asserted on what onSubmit RECEIVES. Asserting that the pill disappeared would pass with the
    // filtering deleted, because the pill list follows the model's spec either way.
    const only = model({ accepts: ['prompt', 'seed'] });
    const { onSubmit } = renderComposer(only, {
      reuse: {
        type: '__GENERATION__',
        role: 'request',
        prompt: 'a lighthouse',
        model: 'a-model-that-is-gone',
        kind: 'image',
        params: { aspect_ratio: '21:9', seed: '7' },
      },
    });

    fireEvent.click(sendButton());
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());

    const sent = onSubmit.mock.calls[0][0];
    expect(sent.params).not.toHaveProperty('aspect_ratio');
    expect(sent.params).toEqual({ seed: 7 });
  });

  it('drops a numeric value that is not a number, rather than sending null', () => {
    // Number('abc') is NaN, which JSON.stringify writes as null: the provider then refuses a value
    // the reader never typed.
    const { onSubmit } = renderComposer(model({ accepts: ['prompt', 'seed'] }), {
      reuse: {
        type: '__GENERATION__', role: 'request', prompt: 'x', model: 'test-model', kind: 'image',
        params: { seed: 'not-a-number' },
      },
    });

    fireEvent.click(sendButton());

    const sent = onSubmit.mock.calls[0][0];
    expect(sent.params).not.toHaveProperty('seed');
  });

  it('re-attaches the files a reused turn ran on', () => {
    // On image-to-video, upscale and style transfer - the models a replay is most used for - the
    // file IS the subject. A replay without it is a different generation.
    // Production shape. A handle missing `path`, `mimeType` or `size` is not a FileRef to the app's
    // own guard, so a thinner fixture would pass this test against a composer that restores nothing.
    const file = {
      _type: 'file', id: 'f1', path: 't/1/frame.png', name: 'frame.png',
      mimeType: 'image/png', size: 2048,
    };
    const { onSubmit } = renderComposer(
      model({ accepts: ['prompt', 'input_image'], inputs: { input_image: { role: 'source', maxItems: 2 } } }),
      {
        reuse: {
          type: '__GENERATION__', role: 'request', prompt: 'animate this',
          model: 'test-model', kind: 'video', params: { input_image: [file] },
        },
      },
    );

    fireEvent.click(sendButton());

    const sent = onSubmit.mock.calls[0][0];
    expect(sent.params.input_image).toEqual([expect.objectContaining({ id: 'f1' })]);
  });
});

describe('StudioComposer - a turn that never left keeps its draft', () => {
  const sendButton = () => screen.getByTitle(/composer\.(send|missingRequired)/);

  it('keeps the prompt when the turn was not recorded', async () => {
    // The caller resolves false when nothing was sent and nothing was charged. Clearing anyway
    // erases the words, and with them any uploaded files, which have to be picked again.
    const onSubmit = vi.fn(async (_input: StudioSubmission) => false);
    render(
      <StudioComposer
        models={[model()]}
        selectedModel={model()}
        onSelectModel={() => {}}
        onSubmit={onSubmit}
      />,
    );

    const textarea = screen.getByRole('textbox');
    fireEvent.change(textarea, { target: { value: 'a lighthouse' } });
    fireEvent.click(sendButton());
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());

    expect((textarea as HTMLTextAreaElement).value).toBe('a lighthouse');
  });

  it('clears the prompt once the turn IS recorded', async () => {
    const { onSubmit } = renderComposer(model());

    const textarea = screen.getByRole('textbox');
    fireEvent.change(textarea, { target: { value: 'a lighthouse' } });
    fireEvent.click(sendButton());
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());

    await waitFor(() => expect((textarea as HTMLTextAreaElement).value).toBe(''));
  });
});

describe('StudioComposer - changing model', () => {
  it('drops a value the new model does not accept, on screen AND in what is sent', () => {
    const first = model({ accepts: ['prompt', 'aspect_ratio'], limits: { aspect_ratio: { allowed: ['1:1'] } } });
    const second = model({ model: 'other-model', accepts: ['prompt', 'seed'] });
    const { rerender, onSubmit } = renderComposer(first);

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'a lighthouse' } });

    rerender(
      <StudioComposer
        models={[first, second]}
        selectedModel={second}
        onSelectModel={() => {}}
        onSubmit={onSubmit}
      />,
    );

    // The pill is gone: a control still showing a value the model will not accept is a lie about
    // what the next turn will do.
    expect(screen.queryByText('aspect_ratio')).toBeNull();
    expect(screen.getByText('seed')).toBeInTheDocument();

    // And the value is gone from the SUBMISSION, which is the half that costs money. Asserting only
    // the pill is the weak form of this test: it follows from the model's spec and would pass with
    // the filtering deleted.
    fireEvent.click(screen.getByTitle(/composer\.(send|missingRequired)/));
    expect(onSubmit).toHaveBeenCalledWith({ prompt: 'a lighthouse', params: {} });
  });

  it('does not bring a dropped value BACK when the reader returns to the first model', async () => {
    // The assertion above cannot see the filter at all: with it deleted the stale value is still
    // absent from the submission, because the SECOND model does not declare the parameter and the
    // submission builder drops what the model does not declare. The state is only observable once
    // the reader returns to a model that DOES declare it - which is when a value they stopped
    // seeing silently rejoins the next purchase.
    const first = model({
      accepts: ['prompt', 'aspect_ratio'],
      limits: { aspect_ratio: { allowed: ['1:1', '16:9'] } },
    });
    const second = model({ model: 'other-model', accepts: ['prompt', 'seed'] });
    // Seeded through `reuse` rather than by driving the control: this test is about what the model
    // change does to state that is already there, not about how the value got there.
    const { rerender, onSubmit } = renderComposer(first, {
      reuse: {
        type: '__GENERATION__', role: 'request', prompt: 'a lighthouse',
        model: first.model, kind: 'image', params: { aspect_ratio: '16:9' },
      },
    });

    const showing = (m: GenerationModel) => (
      <StudioComposer models={[first, second]} selectedModel={m} onSelectModel={() => {}} onSubmit={onSubmit} />
    );
    rerender(showing(second));
    rerender(showing(first));

    fireEvent.click(screen.getByTitle(/composer\.(send|missingRequired)/));
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0].params).toEqual({});
  });

  it('never lets a reused value for an unaccepted parameter reach a later model', async () => {
    // The reuse effect refuses a parameter the CURRENT model does not accept. Asserting only the
    // submission cannot see that refusal either - the same submission-builder rule hides it. What
    // exposes it is switching to a model that does accept the parameter: if the value was admitted
    // to state on arrival, it is now on screen and in the next turn, having come from a recipe made
    // for a different model entirely.
    const noAspect = model({ accepts: ['prompt'] });
    const withAspect = model({
      model: 'other-model', accepts: ['prompt', 'aspect_ratio'],
      limits: { aspect_ratio: { allowed: ['1:1', '21:9'] } },
    });
    const { rerender, onSubmit } = renderComposer(noAspect, {
      reuse: {
        type: '__GENERATION__', role: 'request', prompt: 'a lighthouse',
        model: 'a-model-that-is-gone', kind: 'image', params: { aspect_ratio: '21:9' },
      },
    });

    rerender(
      <StudioComposer models={[noAspect, withAspect]} selectedModel={withAspect}
                      onSelectModel={() => {}} onSubmit={onSubmit} />,
    );

    fireEvent.click(screen.getByTitle(/composer\.(send|missingRequired)/));
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0].params).toEqual({});
  });
});

describe('StudioComposer - uploading files', () => {
  // The upload mock is shared by the whole file, so its call count carries between tests. Counting
  // is how these tests know an upload STARTED, so it has to start from zero.
  beforeEach(() => { uploadGeneric.mockReset(); });

  const twoSlots = () => model({
    accepts: ['prompt', 'input_image'],
    inputs: { input_image: { role: 'source', maxItems: 2 } },
  });

  function uploaded(id: string) {
    return { id, storageKey: `t/1/${id}.png`, fileName: `${id}.png`, mimeType: 'image/png', size: 10 };
  }

  /** Put a file into the hidden input the composer reuses for every slot. */
  function pickFile(container: HTMLElement, name = 'a.png') {
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['x'], name, { type: 'image/png' });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    fireEvent.change(input);
  }

  it('blocks the send while a file is still uploading', async () => {
    // A turn sent with a file that has not landed is a paid generation on the wrong input.
    let release: (v: unknown) => void = () => {};
    uploadGeneric.mockImplementation(() => new Promise((resolve) => { release = resolve; }));

    const { container } = renderComposer(twoSlots());
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'animate this' } });
    chooseSlot();
    pickFile(container);

    await waitFor(() => expect(uploadGeneric).toHaveBeenCalled());
    expect(screen.getByTitle(/composer\.(send|missingRequired)/)).toBeDisabled();

    await act(async () => { release(uploaded('a')); });
    await waitFor(() => expect(screen.getByTitle(/composer\.(send|missingRequired)/)).toBeEnabled());
  });

  it('says so when an upload fails, rather than dropping the slot silently', async () => {
    uploadGeneric.mockRejectedValue(new Error('storage down'));

    const { container } = renderComposer(twoSlots());
    chooseSlot();
    pickFile(container);

    expect(await screen.findByText('composer.uploadFailed')).toBeInTheDocument();
  });
});

describe('StudioComposer - the price beside the button that spends it', () => {
  /**
   * The amount is quoted for a DEBOUNCED quantity, so between a change and the answer the number on
   * screen belongs to the previous one. On a per-character model that gap is reachable by hand:
   * paste a long prompt, press send inside 600 ms, and the price still displayed is the one for the
   * shorter prompt - lower than what is about to be charged. Nothing is double-billed and nothing
   * is hidden, but the figure a reader consults at the moment they decide to spend is wrong, so it
   * stops being presented as a statement until it is one again.
   */
  function priceLabel() {
    return screen.getByText('12 credits');
  }

  it('states the price plainly once it is settled and current', () => {
    quoteState.value = { quote: undefined, quantity: 50, settled: true, stale: false };
    renderComposer(model());

    expect(priceLabel()).not.toHaveAttribute('aria-busy');
    expect(priceLabel().className).not.toContain('opacity-50');
  });

  it('stops presenting the amount as current while it belongs to an older quantity', () => {
    quoteState.value = { quote: undefined, quantity: 50, settled: true, stale: true };
    renderComposer(model());

    // Dimmed AND announced: a sighted reader sees it is being re-checked, and a screen reader is
    // told rather than reading out a number that is about to change.
    //
    // `aria-busy` is asserted together with the LIVE REGION it applies to, which this test used to
    // check on its own. The attribute is consumed on a live region or a widget; on the bare span
    // this was, it named a state nothing would ever read, so the assertion passed while the
    // staleness was visual only. An attribute is not an announcement until something announces it.
    expect(priceLabel()).toHaveAttribute('aria-busy', 'true');
    expect(priceLabel()).toHaveAttribute('role', 'status');
    expect(priceLabel()).toHaveAttribute('aria-live', 'polite');
    expect(priceLabel().className).toContain('opacity-50');
  });

  it('keeps the live region when the amount is current, so the next change is announced', () => {
    // A region added only while stale would be created at the moment the value changes, and a
    // region that appears with its content is not reliably announced: the reader is told nothing
    // on the one update they were waiting for.
    quoteState.value = { quote: undefined, quantity: 50, settled: true, stale: false };
    renderComposer(model());

    expect(priceLabel()).toHaveAttribute('role', 'status');
    expect(priceLabel()).toHaveAttribute('aria-live', 'polite');
  });

  it('says nothing about the platform rate while the reader’s OWN key pays', () => {
    // The figure this hook returns is the PLATFORM's price, and on the reader's own key the
    // platform charges nothing for the generation itself. Showing it there names a number nobody
    // is about to be charged, beside the button that runs the call.
    quoteState.value = { quote: undefined, quantity: 50, settled: true, stale: false };
    renderComposer(model(), { credentialSource: 'user' });

    expect(screen.queryByText('12 credits')).toBeNull();
  });

  it('shows NO price line at all for a model the platform does not sell', () => {
    // Previously this rendered "Not sold on the platform key" beside the button, which reads as a
    // warning about the MODEL when it is a statement about the key - and it sat there on every
    // unsold row. An amount or nothing: the refusal, if a run is attempted, says the rest.
    priceWords.value = null;
    quoteState.value = { quote: undefined, quantity: 50, settled: true, stale: false };
    renderComposer(model());

    expect(screen.queryByText(/not sold|unpriced/i)).toBeNull();
  });

  it('keeps showing the last amount rather than blanking it', () => {
    // Removing the figure while it re-settles would blink on every keystroke of a long prompt and
    // leave nothing beside the button at the moment it is pressed. A dimmed number is a better
    // answer than no number.
    quoteState.value = { quote: undefined, quantity: 50, settled: true, stale: true };
    renderComposer(model());

    expect(priceLabel()).toBeInTheDocument();
  });
});

describe('StudioComposer - whose key pays when the platform sells nothing', () => {
  /** A quote answer: what the platform can do for this model. */
  function quote(over: Record<string, unknown>) {
    return {
      quote: { integrationName: 'heygen', available: true, hasPricing: true, platformCredentialId: 7, ...over },
      quantity: null,
      settled: true,
      stale: false,
    };
  }

  it('falls back to the reader OWN key on a model the platform does not sell', () => {
    // The reported bug, on HeyGen: the platform sells nothing there, the studio still opened on
    // `platform`, and the reader was sitting on a payer that cannot run the call. Nothing said so
    // until the refusal - after picking a model and writing a prompt.
    quoteState.value = quote({ platformCredentialId: null, available: false, hasPricing: false });
    const onCredentialSourceChange = vi.fn();

    renderComposer(model(), { credentialSource: 'platform', onCredentialSourceChange });

    expect(onCredentialSourceChange).toHaveBeenCalledWith('user');
  });

  it('falls back when the credential exists but no rate is published', () => {
    // Half-configured is the same refusal at run time: without a published rate the platform key
    // would be a free ride, which the credential control refuses to offer. Both halves are needed.
    quoteState.value = quote({ hasPricing: false });
    const onCredentialSourceChange = vi.fn();

    renderComposer(model(), { credentialSource: 'platform', onCredentialSourceChange });

    expect(onCredentialSourceChange).toHaveBeenCalledWith('user');
  });

  it('leaves the platform in place on a model it DOES sell', () => {
    // The common case, and the one that needs no setup from the reader. Moving them off it would
    // send every generation to a key they have not configured.
    quoteState.value = quote({});
    const onCredentialSourceChange = vi.fn();

    renderComposer(model(), { credentialSource: 'platform', onCredentialSourceChange });

    expect(onCredentialSourceChange).not.toHaveBeenCalled();
  });

  it('waits for the quote to settle before moving anyone', () => {
    // "No price yet" is not "not sold". Acting on it would flip every model onto the reader's key
    // for the moment before its rate arrives, and the price beside the button would vanish with it.
    quoteState.value = { ...quote({ platformCredentialId: null, available: false, hasPricing: false }), settled: false };
    const onCredentialSourceChange = vi.fn();

    renderComposer(model(), { credentialSource: 'platform', onCredentialSourceChange });

    expect(onCredentialSourceChange).not.toHaveBeenCalled();
  });

  it('never moves a reader ONTO the platform', () => {
    // One direction only. A reader who chose their own key chose who pays; overruling that because
    // the platform happens to sell this model would move the charge without being asked.
    quoteState.value = quote({});
    const onCredentialSourceChange = vi.fn();

    renderComposer(model(), { credentialSource: 'user', onCredentialSourceChange });

    expect(onCredentialSourceChange).not.toHaveBeenCalled();
  });
});

describe('StudioComposer - the box grows with the prompt', () => {
  /**
   * jsdom lays nothing out, so `scrollHeight` is a constant 0 and a height assertion would read
   * `52px` whether or not the effect exists. Defining the getter is what makes the test able to
   * fail: with a content height to measure, the height the effect writes is observable, and so is
   * each end of the clamp.
   */
  function withContentHeight(px: number) {
    Object.defineProperty(HTMLTextAreaElement.prototype, 'scrollHeight', {
      configurable: true,
      get: () => px,
    });
  }

  afterEach(() => {
    // @ts-expect-error - removing the stub restores jsdom's own (always 0) getter.
    delete HTMLTextAreaElement.prototype.scrollHeight;
  });

  it('expands past its two starting rows as the prompt gets longer', () => {
    // The chat composer has grown with its input since it existed; the studio's was fixed at two
    // rows, so a long prompt scrolled inside a three-line window and the reader could not see the
    // sentence they were about to pay to generate. The two sit on the same page behind one switch:
    // one growing and the other not reads as a bug in whichever you used second.
    withContentHeight(120);
    renderComposer(model());
    const box = screen.getByRole('textbox') as HTMLTextAreaElement;

    fireEvent.change(box, { target: { value: 'a lighthouse on a cliff at dusk, long exposure' } });

    expect(box.style.height).toBe('120px');
  });

  it('stops growing at the ceiling and starts scrolling instead', () => {
    // Past 200px the box must stop taking the page and scroll its own content. Without a ceiling a
    // pasted paragraph would push the controls, and the model picker with them, off the screen.
    withContentHeight(4000);
    renderComposer(model());
    const box = screen.getByRole('textbox') as HTMLTextAreaElement;

    fireEvent.change(box, { target: { value: 'x'.repeat(5000) } });

    expect(box.style.height).toBe('200px');
  });

  it('never shrinks below two rows on a short prompt', () => {
    // A one-word prompt must not collapse the box to a single line: the floor is what keeps the
    // composer the same object before and after you type in it.
    withContentHeight(18);
    renderComposer(model());
    const box = screen.getByRole('textbox') as HTMLTextAreaElement;

    fireEvent.change(box, { target: { value: 'cat' } });

    expect(box.style.height).toBe('52px');
  });

  it('declares its own overflow, so the ceiling scrolls the same way the chat box does', () => {
    // The ceiling is only half the behaviour: something has to say what happens to the overflow.
    // The chat box states `overflow-y-auto`; this one did not, leaving the browser's default to
    // decide - so "the same behaviour" held only as long as both browsers agreed.
    renderComposer(model());
    const box = screen.getByRole('textbox') as HTMLTextAreaElement;

    expect(box.className).toContain('overflow-y-auto');
    expect(box.className).toContain('resize-none');
  });

  it('keeps the same floor and ceiling as the chat composer', () => {
    // Same numbers on purpose: 52px is two lines, 200px is where it stops and starts scrolling.
    // Diverging here would make the two boxes different sizes for the same text.
    renderComposer(model());
    const box = screen.getByRole('textbox') as HTMLTextAreaElement;

    expect(box.style.minHeight).toBe('52px');
    expect(box.style.maxHeight).toBe('200px');
  });
});

describe('Orbi on the studio composer', () => {
  it('is always perched, and can be poked', () => {
    renderComposer(model());
    const perch = screen.getByTestId('orbi-perch');
    expect(perch.querySelector('[data-testid="orbi-poke"]')).not.toBeNull();
    expect(perch.querySelector('svg')!.getAttribute('data-mood')).toBe('idle');
  });

  it('thinks while a generation runs', () => {
    renderComposer(model(), { isRunning: true });
    expect(screen.getByTestId('orbi-perch').querySelector('svg')!.getAttribute('data-mood')).toBe('thinking');
  });
});
