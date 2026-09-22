import { describe, expect, it } from 'vitest';
import {
  buildStudioRequest,
  buildStudioResult,
  isStudioMessage,
  parseStudioEnvelope,
  STUDIO_MESSAGE_TYPE,
} from '../studioMessage';
import type { GenerationResult } from '@/lib/api/orchestrator/generation.service';

/**
 * How a studio turn is written into a conversation, and - more importantly - what is NOT mistaken
 * for one.
 *
 * <p>The parser is handed every message in a thread. A message that merely looks structured, or
 * that belongs to another feature using the same mechanism, must come back as "not a studio turn"
 * rather than as a half-built card.
 */

describe('parseStudioEnvelope', () => {
  it('reads back a request it wrote', () => {
    const envelope = parseStudioEnvelope(buildStudioRequest({
      prompt: 'a lighthouse at dusk',
      model: 'flux-1',
      kind: 'image',
      provider: 'flux',
      params: { aspect_ratio: '16:9' },
    }));
    expect(envelope).toMatchObject({
      type: STUDIO_MESSAGE_TYPE,
      role: 'request',
      prompt: 'a lighthouse at dusk',
      model: 'flux-1',
      kind: 'image',
      params: { aspect_ratio: '16:9' },
    });
  });

  it.each([
    ['nothing', ''],
    ['null', null],
    ['undefined', undefined],
    ['ordinary prose', 'Make me an image of a lighthouse'],
    ['prose that happens to mention JSON', 'the answer is {"a":1} apparently'],
    ['malformed JSON', '{"type":"__GENERATION__",'],
    ['a JSON array', '[1,2,3]'],
    ['a JSON scalar', '"just a string"'],
  ])('returns null for %s', (_label, content) => {
    expect(parseStudioEnvelope(content as string | null | undefined)).toBeNull();
  });

  it('leaves another feature using the same mechanism alone', () => {
    // Workflows and data sources encode themselves in `content` the same way. Claiming one of those
    // would replace a workflow card with a broken generation card.
    expect(parseStudioEnvelope('{"type":"__WORKFLOW__","nodes":[],"edges":[]}')).toBeNull();
    expect(parseStudioEnvelope('{"type":"__DATASOURCE__","dataSourceId":7}')).toBeNull();
  });

  it('refuses an envelope with no model - a turn that cannot say what produced it is unreadable', () => {
    expect(parseStudioEnvelope('{"type":"__GENERATION__","role":"result","success":true}')).toBeNull();
  });

  it('refuses an unknown role rather than rendering it as one of the two', () => {
    expect(parseStudioEnvelope(
      '{"type":"__GENERATION__","role":"progress","model":"flux-1"}',
    )).toBeNull();
  });

  it('tolerates surrounding whitespace, which storage round-trips add', () => {
    expect(parseStudioEnvelope('\n  {"type":"__GENERATION__","role":"result","success":true,"model":"m","kind":"image"}  '))
      .toMatchObject({ role: 'result', model: 'm' });
  });
});

describe('isStudioMessage', () => {
  it('is the dispatch a thread needs, and agrees with the parser', () => {
    expect(isStudioMessage(buildStudioRequest({ prompt: 'x', model: 'm', kind: 'image' }))).toBe(true);
    expect(isStudioMessage('hello')).toBe(false);
  });
});

describe('buildStudioRequest', () => {
  it('omits params entirely when there are none, rather than writing an empty object', () => {
    const envelope = parseStudioEnvelope(buildStudioRequest({ prompt: 'x', model: 'm', kind: 'image' }));
    expect(envelope).not.toHaveProperty('params');
  });

  it('keeps file handles in params, so a turn can be replayed with the very same file', () => {
    const file = { _type: 'file', id: 'file-1', name: 'frame.png' };
    const envelope = parseStudioEnvelope(buildStudioRequest({
      prompt: 'animate this',
      model: 'runway-1',
      kind: 'video',
      params: { input_image: [file] },
    }));
    expect(envelope).toMatchObject({ params: { input_image: [file] } });
  });
});

describe('buildStudioResult', () => {
  const request = { model: 'flux-1', kind: 'image', provider: 'flux' };

  it('carries the asset and what was billed', () => {
    const result: GenerationResult = {
      success: true,
      data: {
        model: 'flux-1',
        kind: 'image',
        provider: 'flux',
        file: { id: 'file-9', name: 'out.png', mimeType: 'image/png' },
        billed_quantity: 1,
        billed_unit: 'image',
        billed_credits: 78,
      },
    };
    expect(parseStudioEnvelope(buildStudioResult(result, request))).toMatchObject({
      role: 'result',
      success: true,
      file: { id: 'file-9' },
      billedQuantity: 1,
      billedUnit: 'image',
      // What it COST, not only the size it was charged on. The turn card states it beside the
      // history cards under it, and this mapping is the only thing that carries it there: deleted,
      // the thread silently stops pricing the generation the reader just ran and every other suite
      // stays green.
      billedCredits: 78,
    });
  });

  it('carries NO charge for a turn the platform did not bill, and none for a zero', () => {
    // The reader's own provider key paid, or the endpoint carries no platform price. Both arrive
    // as an absent field, and a zero must not be turned into one either: on the card an amount of
    // nothing reads as "this was free", which is a claim about money rather than a missing value.
    const base = { model: 'flux-1', kind: 'image', provider: 'flux' };

    expect(parseStudioEnvelope(buildStudioResult(
      { success: true, data: { ...base } } as GenerationResult, request,
    ))).not.toHaveProperty('billedCredits');

    expect(parseStudioEnvelope(buildStudioResult(
      { success: true, data: { ...base, billed_credits: 0 } } as GenerationResult, request,
    ))).not.toHaveProperty('billedCredits');
  });

  it('carries the factor that moved the price, with the reasons the server gave', () => {
    // The third number in the charge. The size says what was produced and the amount says what it
    // cost; without this, the two do not multiply out and the card reads as an arithmetic error.
    const result = {
      success: true,
      data: {
        model: 'seedance-2.0',
        kind: 'video',
        billed_quantity: 10,
        billed_unit: 'second',
        billed_credits: 240,
        billed_multiplier: 1.2,
        billed_multiplier_reasons: ['resolution x1.2'],
      },
    } as GenerationResult;

    expect(parseStudioEnvelope(buildStudioResult(result, request))).toMatchObject({
      billedMultiplier: 1.2,
      billedMultiplierReasons: ['resolution x1.2'],
    });
  });

  it('carries a factor BELOW one, because a discount is a fact about the charge too', () => {
    // The composer's badge fires on `!== 1` and this carried only `> 1`, so a model with a cheaper
    // tier (the descriptor parser refuses only factors <= 0, so 0.5 is legal) announced "x0.5"
    // before the run and nothing after it. The card then stated a size and an amount off by half
    // with no third number to reconcile them, which is the arithmetic-error reading the factor
    // exists to prevent.
    const result = {
      success: true,
      data: {
        model: 'seedance-2.0', kind: 'video',
        billed_quantity: 10, billed_unit: 'second', billed_credits: 60,
        billed_multiplier: 0.5, billed_multiplier_reasons: ['quality x0.5'],
      },
    } as GenerationResult;

    expect(parseStudioEnvelope(buildStudioResult(result, request))).toMatchObject({
      billedMultiplier: 0.5,
      billedMultiplierReasons: ['quality x0.5'],
    });
  });

  it('carries no factor for a NON-POSITIVE one, which is not a price at all', () => {
    // Zero would read as a free generation and a negative one is not a factor. Neither can come
    // from a descriptor this platform accepts, so the honest reading is that nothing was said.
    const base = { model: 'flux-1', kind: 'image', billed_quantity: 1, billed_unit: 'image' };

    expect(parseStudioEnvelope(buildStudioResult(
      { success: true, data: { ...base, billed_multiplier: 0 } } as GenerationResult, request,
    ))).not.toHaveProperty('billedMultiplier');

    expect(parseStudioEnvelope(buildStudioResult(
      { success: true, data: { ...base, billed_multiplier: -2 } } as GenerationResult, request,
    ))).not.toHaveProperty('billedMultiplier');
  });

  it('carries NO factor for a call at the published rate', () => {
    // A factor of 1 is the ordinary case, not a fact about this turn. Carried, it would put a
    // "x1" on every card in the thread, next to every amount that is exactly the published rate.
    const base = { model: 'flux-1', kind: 'image', billed_quantity: 1, billed_unit: 'image' };

    expect(parseStudioEnvelope(buildStudioResult(
      { success: true, data: { ...base, billed_multiplier: 1 } } as GenerationResult, request,
    ))).not.toHaveProperty('billedMultiplier');

    expect(parseStudioEnvelope(buildStudioResult(
      { success: true, data: { ...base } } as GenerationResult, request,
    ))).not.toHaveProperty('billedMultiplier');
  });

  it('records a failure as a turn, with the endpoint words verbatim', () => {
    // A thread that silently drops what did not work invites the reader to send it again, which is
    // a second charge.
    const result: GenerationResult = { success: false, error: 'Not enough credits to run this model.' };
    expect(parseStudioEnvelope(buildStudioResult(result, request))).toMatchObject({
      role: 'result',
      success: false,
      error: 'Not enough credits to run this model.',
    });
  });

  it('falls back to the REQUEST model and kind, because a failure answers with neither', () => {
    const result: GenerationResult = { success: false, error: 'refused' };
    expect(parseStudioEnvelope(buildStudioResult(result, request))).toMatchObject({
      model: 'flux-1',
      kind: 'image',
      provider: 'flux',
    });
  });

  it('prefers what actually ran over what was asked for', () => {
    // The response is the authority: an alias, or a model resolved server-side, must be what the
    // history records.
    const result: GenerationResult = {
      success: true,
      data: { model: 'flux-1-pro', kind: 'image', provider: 'flux', file: { id: 'f' } },
    };
    expect(parseStudioEnvelope(buildStudioResult(result, request))).toMatchObject({ model: 'flux-1-pro' });
  });

  it('omits the file on a failure instead of writing an empty handle', () => {
    const envelope = parseStudioEnvelope(
      buildStudioResult({ success: false, error: 'refused' }, request),
    );
    expect(envelope).not.toHaveProperty('file');
  });
});

describe('buildStudioResult - a failure that was CHARGED is not a refusal', () => {
  /**
   * `success: false` covers two opposite outcomes and the difference is money.
   *
   * <p>A REFUSAL never reached the provider (no credits, no published price, a rejected parameter)
   * and cost nothing. A CHARGED FAILURE ran upstream and was billed: billing commits before the
   * asset is fetched and stored, so a transient fetch failure is a paid generation with no file
   * row. The endpoint tells them apart by what it attaches - a refusal answers with no data, a
   * charged failure answers with the provider's own short-lived link and its raw response, because
   * that link is the only route left to the thing the reader bought.
   *
   * <p>The response is gone by the time the thread is read back, so if the envelope does not record
   * this, nothing can: the reader is left to infer "was I charged" from the wording of an error.
   */
  const request = { model: 'seedance-2', kind: 'video', provider: 'seedance' };

  it('marks a failure that carries the wreckage of a real run as charged', () => {
    const envelope = parseStudioEnvelope(buildStudioResult({
      success: false,
      error: 'The generation ran but no asset could be retrieved.',
      data: { asset_url: 'https://provider.example/clip.mp4?exp=1', provider_response: {} },
    } as never, request));

    expect(envelope).toMatchObject({ chargedAnyway: true });
  });

  it('keeps the provider link, which is the ONLY route to what was paid for', () => {
    // Nothing was stored, so there is no file row and no second copy. Dropping this is how a
    // charged asset becomes a sentence.
    const envelope = parseStudioEnvelope(buildStudioResult({
      success: false,
      error: 'no asset',
      data: { asset_url: 'https://provider.example/clip.mp4?exp=1' },
    } as never, request));

    expect(envelope).toMatchObject({ assetUrl: 'https://provider.example/clip.mp4?exp=1' });
  });

  it('does NOT mark a plain refusal as charged', () => {
    // The other direction, and the one that matters for trust: telling a reader they may have paid
    // when they certainly did not is its own defect.
    const envelope = parseStudioEnvelope(buildStudioResult(
      { success: false, error: 'No published price for this model' } as never, request));

    expect(envelope).not.toHaveProperty('chargedAnyway');
    expect(envelope).not.toHaveProperty('assetUrl');
  });

  it('treats an EMPTY data object as a refusal, not as a charge', () => {
    // `GenerationResult.failed(error)` builds exactly this. Reading "data is present" rather than
    // "data has anything in it" would warn about a charge on every ordinary refusal.
    const envelope = parseStudioEnvelope(
      buildStudioResult({ success: false, error: 'refused', data: {} } as never, request));

    expect(envelope).not.toHaveProperty('chargedAnyway');
  });

  it('never marks a SUCCESS as charged-anyway - a success has its asset', () => {
    // The flag means "billed with nothing to show for it". On a success the file is right there,
    // and warning about the charge beside a delivered asset is noise that teaches readers to
    // ignore the warning when it is real.
    const envelope = parseStudioEnvelope(buildStudioResult({
      success: true,
      data: { file: { id: 'f1' }, billed_quantity: 6, billed_unit: 'second' },
    } as never, request));

    expect(envelope).not.toHaveProperty('chargedAnyway');
  });

  it('carries no link when the run was charged but the provider gave none', () => {
    // The endpoint says so explicitly in that case: the asset URL was not where the descriptor
    // said. The turn must still be marked charged - that half is what stops a second purchase.
    const envelope = parseStudioEnvelope(buildStudioResult({
      success: false,
      error: 'no asset URL was found',
      data: { provider_response: { job: 'x' } },
    } as never, request));

    expect(envelope).toMatchObject({ chargedAnyway: true });
    expect(envelope).not.toHaveProperty('assetUrl');
  });
});
