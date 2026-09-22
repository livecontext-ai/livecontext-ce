import { describe, expect, it } from 'vitest';
import {
  assetFields,
  buildParamSpec,
  forbiddenWith,
  buildSubmissionParams,
  hasSubmittableInput,
  MAX_ASSET_SLOTS,
  missingRequired,
  packAssetsByMaxItems,
  totalAssetSlots,
} from '../paramSpec';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

/**
 * The controls a model gets, derived from what the model itself declares.
 *
 * <p>The tests that matter here are the ones about ABSENCE: a control drawn for a parameter the
 * model does not accept can only produce a refusal, and it produces it after the reader has filled
 * it in. So most of these assert what is NOT offered.
 */

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

describe('buildParamSpec', () => {
  it('offers nothing at all when there is no model yet', () => {
    expect(buildParamSpec(null)).toEqual([]);
    expect(buildParamSpec(undefined)).toEqual([]);
  });

  it('leaves the prompt out - every surface gives it its own place', () => {
    const spec = buildParamSpec(model({ accepts: ['prompt', 'seed'] }));
    expect(spec.map((f) => f.name)).toEqual(['seed']);
  });

  it('only offers what the model accepts, because anything else is refused', () => {
    // aspect_ratio is a real unified parameter, but not for THIS model.
    const spec = buildParamSpec(model({ accepts: ['prompt', 'seed'] }));
    expect(spec.map((f) => f.name)).not.toContain('aspect_ratio');
  });

  it('types a parameter with declared values as a closed choice', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'aspect_ratio'],
      limits: { aspect_ratio: { allowed: ['1:1', '16:9'] } },
    }));
    expect(spec[0]).toMatchObject({
      name: 'aspect_ratio',
      kind: 'choice',
      choices: ['1:1', '16:9'],
      choicesAreSuggestions: false,
    });
  });

  it('marks a suggested list as suggestions, so a surface does not forbid accepted values', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'style'],
      limits: { style: { allowed: ['anime'], allowedEnforced: false } },
    }));
    expect(spec[0].choicesAreSuggestions).toBe(true);
  });

  it('keeps a fetch-only parameter a choice with no values yet, not a dead empty dropdown', () => {
    // An ElevenLabs voice belongs to the account holding the key: the values exist, but only the
    // provider can name them. Typing it as free text would lose the fetch; shipping it as an empty
    // closed choice would be a control with nothing in it.
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'voice'],
      limits: { voice: { optionsAvailable: true } },
    }));
    expect(spec[0]).toMatchObject({ name: 'voice', kind: 'choice', optionsMustBeFetched: true });
    expect(spec[0].choices).toEqual([]);
  });

  it('types the numeric parameters as numbers and the rest as text', () => {
    const spec = buildParamSpec(model({ accepts: ['prompt', 'seed', 'negative_prompt'] }));
    expect(spec.find((f) => f.name === 'seed')?.kind).toBe('number');
    expect(spec.find((f) => f.name === 'negative_prompt')?.kind).toBe('text');
  });

  it('draws one picker per file the model takes, carrying what each file IS to it', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'input_image'],
      inputs: { input_image: { role: 'first_frame', maxItems: 3 } },
    }));
    expect(spec[0]).toMatchObject({
      name: 'input_image', kind: 'asset', slots: 3, role: 'first_frame', accept: 'image/*',
    });
  });

  it('gives an asset parameter one slot when the model does not say how many', () => {
    const spec = buildParamSpec(model({ accepts: ['prompt', 'input_audio'] }));
    expect(spec[0]).toMatchObject({ name: 'input_audio', slots: 1 });
  });

  it('caps the slots a descriptor can ask for', () => {
    // maxItems comes from a provider descriptor. Trusting it without bound draws whatever number
    // lands there, on screen, for as long as the descriptor says so.
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'input_image'],
      inputs: { input_image: { role: 'source_image', maxItems: 400 } },
    }));
    expect(spec[0].slots).toBe(MAX_ASSET_SLOTS);
  });

  it('never draws fewer than one slot, even on a descriptor claiming zero', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'input_image'],
      inputs: { input_image: { role: 'source_image', maxItems: 0 } },
    }));
    expect(spec[0].slots).toBe(1);
  });

  it('marks required parameters, which is what blocks a submit', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'input_image', 'seed'],
      required: ['input_image'],
    }));
    expect(spec.find((f) => f.name === 'input_image')?.required).toBe(true);
    expect(spec.find((f) => f.name === 'seed')?.required).toBe(false);
  });

  it('puts the files first: on a model whose subject is an image, the image leads', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'aspect_ratio', 'seed', 'negative_prompt', 'input_image'],
      limits: { aspect_ratio: { allowed: ['1:1'] } },
    }));
    expect(spec.map((f) => f.name)).toEqual([
      'input_image', 'aspect_ratio', 'seed', 'negative_prompt',
    ]);
  });
});

describe('a model that takes several files at once', () => {
  /** xAI 1.5: one call, a first frame, a last frame and up to three references. */
  const threeSlots = () => model({
    accepts: ['prompt', 'input_image', 'last_frame_image', 'reference_image'],
    inputs: {
      input_image: { role: 'first_frame', maxItems: 1 },
      last_frame_image: { role: 'last_frame', maxItems: 1 },
      reference_image: { role: 'reference', maxItems: 3 },
    },
  } as Partial<GenerationModel>);

  it('draws one field per slot, each keeping the role that says what it is for', () => {
    // With one image parameter for the whole platform, two of these three had nowhere to go: the
    // model advertised them and no surface could offer them.
    const fields = assetFields(buildParamSpec(threeSlots()));

    expect(fields.map((f) => [f.name, f.role, f.slots])).toEqual([
      ['input_image', 'first_frame', 1],
      ['last_frame_image', 'last_frame', 1],
      ['reference_image', 'reference', 3],
    ]);
  });

  it('offers a picker for every file the slot takes, and no more', () => {
    expect(totalAssetSlots(buildParamSpec(threeSlots()))).toBe(5);
  });

  it('carries the pairing rules onto the field, since that is where the choice is made', () => {
    const paired = buildParamSpec(model({
      accepts: ['prompt', 'first_frame_image', 'last_frame_image', 'input_image'],
      inputs: {
        first_frame_image: { role: 'first_frame', maxItems: 1, excludes: ['input_image'] },
        last_frame_image: {
          role: 'last_frame', maxItems: 1,
          requires: ['first_frame_image'], excludes: ['input_image'],
        },
        input_image: { role: 'reference', maxItems: 4 },
      },
    } as Partial<GenerationModel>));

    const closing = paired.find((f) => f.name === 'last_frame_image');
    expect(closing?.requiresFields).toEqual(['first_frame_image']);
    expect(closing?.excludesFields).toEqual(['input_image']);
  });

  it('drops a rule naming a slot this model does not take', () => {
    // The server narrows these too, but a surface that trusts the list without checking prints a
    // sentence about a field the reader cannot see.
    const narrowed = buildParamSpec(model({
      accepts: ['prompt', 'last_frame_image'],
      inputs: {
        last_frame_image: {
          role: 'last_frame', maxItems: 1,
          requires: ['first_frame_image'], excludes: ['input_image'],
        },
      },
    } as Partial<GenerationModel>));

    expect(narrowed.find((f) => f.name === 'last_frame_image')?.requiresFields).toEqual([]);
    expect(narrowed.find((f) => f.name === 'last_frame_image')?.excludesFields).toEqual([]);
  });

  it('sees a forbidden pair from EITHER side, because the server publishes both', () => {
    // The descriptor states the rule once, on whichever slot its author was writing, and the
    // server completes it: this fixture is the shape a model listing actually returns, and that
    // symmetry is pinned server-side by GenerationInputsTest. Re-deriving it here would be a
    // second implementation of a shared contract, and the copy that kept working would hide the
    // one that stopped.
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'first_frame_image', 'input_image'],
      inputs: {
        first_frame_image: { role: 'first_frame', maxItems: 1, excludes: ['input_image'] },
        input_image: { role: 'reference', maxItems: 4, excludes: ['first_frame_image'] },
      },
    } as Partial<GenerationModel>));

    expect(forbiddenWith(spec, 'input_image')).toEqual(['first_frame_image']);
    expect(forbiddenWith(spec, 'first_frame_image')).toEqual(['input_image']);
  });

  it('shows an image picker for each of them, rather than every file the reader owns', () => {
    const fields = assetFields(buildParamSpec(threeSlots()));
    expect(fields.map((f) => f.accept)).toEqual(['image/*', 'image/*', 'image/*']);
  });
});

describe('assetFields / totalAssetSlots', () => {
  it('reports no file slots for a model that takes no file', () => {
    // This is what removes the attachment control entirely: offering one here can only produce a
    // refusal.
    const spec = buildParamSpec(model({ accepts: ['prompt', 'seed'] }));
    expect(assetFields(spec)).toEqual([]);
    expect(totalAssetSlots(spec)).toBe(0);
  });

  it('counts every slot across every file parameter', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'input_image', 'input_audio'],
      inputs: {
        input_image: { role: 'first_frame', maxItems: 2 },
        input_audio: { role: 'source_audio', maxItems: 1 },
      },
    }));
    expect(assetFields(spec).map((f) => f.name)).toEqual(['input_image', 'input_audio']);
    expect(totalAssetSlots(spec)).toBe(3);
  });
});

describe('missingRequired', () => {
  const spec = buildParamSpec(model({
    accepts: ['prompt', 'input_image', 'voice', 'seed'],
    required: ['input_image', 'voice'],
    inputs: { input_image: { role: 'source_image', maxItems: 4 } },
    limits: { voice: { optionsAvailable: true } },
  }));

  it('names every required field still empty', () => {
    expect(missingRequired(spec, {}, {}).sort()).toEqual(['input_image', 'voice']);
  });

  it('is satisfied by the FIRST file only - a 4-file parameter requires one', () => {
    const missing = missingRequired(spec, { voice: 'aria' }, { input_image: [{ id: 'f1' }] });
    expect(missing).toEqual([]);
  });

  it('is not satisfied by a later slot when the first is empty', () => {
    const missing = missingRequired(spec, { voice: 'aria' }, { input_image: [undefined, { id: 'f2' }] });
    expect(missing).toEqual(['input_image']);
  });

  it('treats whitespace as empty - a space is not an answer', () => {
    const missing = missingRequired(spec, { voice: '   ' }, { input_image: [{ id: 'f1' }] });
    expect(missing).toEqual(['voice']);
  });

  it('ignores optional fields left empty', () => {
    const missing = missingRequired(spec, { voice: 'aria' }, { input_image: [{ id: 'f1' }] });
    expect(missing).not.toContain('seed');
  });
});

/**
 * What a turn actually SENDS.
 *
 * <p>These are the assertions that carry money, and they are made here rather than through the
 * composer for a reason found the hard way: the composer filters at three points (on reuse, on model
 * change, on submit) and any two of them MASK the third, so a component test could delete any one
 * filter and still pass. Handed the state directly, each rule is reachable.
 */
describe('missingRequired and slots that come in pairs', () => {
  const paired = () => buildParamSpec(model({
    accepts: ['prompt', 'first_frame_image', 'last_frame_image'],
    inputs: {
      first_frame_image: { role: 'first_frame', maxItems: 1 },
      last_frame_image: { role: 'last_frame', maxItems: 1, requires: ['first_frame_image'] },
    },
  } as Partial<GenerationModel>));

  it('reports the missing half of a pair, so the turn is held rather than refused', () => {
    // The provider refuses this call and the platform refuses it first, for free and with a
    // sentence. Sending it anyway spends a round trip to deliver something the composer could
    // have shown while the reader was still attaching files.
    expect(missingRequired(paired(), {}, { last_frame_image: [{ id: 'f1' }] }))
      .toEqual(['first_frame_image']);
  });

  it('says nothing when both halves are there', () => {
    expect(missingRequired(paired(), {}, {
      first_frame_image: [{ id: 'f1' }],
      last_frame_image: [{ id: 'f2' }],
    })).toEqual([]);
  });

  it('says nothing when NEITHER is there, because the pair is optional as a whole', () => {
    // A model that takes an optional pair is perfectly runnable with no file at all; holding
    // the turn then would forbid the ordinary text-to-video case.
    expect(missingRequired(paired(), {}, {})).toEqual([]);
  });

  it('reads a multi-file partner as filled when its FIRST file was the one removed', () => {
    // Removing a file leaves a hole rather than shifting the rest down, and the payload is
    // packed with filter(Boolean). Reading index 0 alone would hold the turn for a slot the
    // request is about to send two files from.
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'reference_image', 'last_frame_image'],
      inputs: {
        reference_image: { role: 'reference', maxItems: 3 },
        last_frame_image: { role: 'last_frame', maxItems: 1, requires: ['reference_image'] },
      },
    } as Partial<GenerationModel>));

    expect(missingRequired(spec, {}, {
      reference_image: [undefined, { id: 'f2' }],
      last_frame_image: [{ id: 'f1' }],
    })).toEqual([]);
  });

  it('does not report the same slot twice when it is both required and a partner', () => {
    const spec = buildParamSpec(model({
      accepts: ['prompt', 'first_frame_image', 'last_frame_image'],
      required: ['first_frame_image'],
      inputs: {
        first_frame_image: { role: 'first_frame', maxItems: 1 },
        last_frame_image: { role: 'last_frame', maxItems: 1, requires: ['first_frame_image'] },
      },
    } as Partial<GenerationModel>));

    expect(missingRequired(spec, {}, { last_frame_image: [{ id: 'f1' }] }))
      .toEqual(['first_frame_image']);
  });
});

describe('buildSubmissionParams', () => {
  const spec = buildParamSpec(model({
    accepts: ['prompt', 'seed', 'aspect_ratio', 'input_image'],
    limits: { aspect_ratio: { allowed: ['1:1', '16:9'] } },
    inputs: { input_image: { role: 'source', maxItems: 2 } },
  }));

  it('drops a value the model does not declare', () => {
    // The platform REFUSES an undeclared parameter, and the refusal names nothing the reader can act
    // on: the turn fails with its cause nowhere on screen. A value can reach here from a reused
    // recipe made on another model, or from a model switch.
    const params = buildSubmissionParams(spec, { seed: '7', resolution: '4k' }, {});
    expect(params).toEqual({ seed: 7 });
  });

  it('casts a declared numeric parameter, and drops one that is not a number', () => {
    // Number('abc') is NaN, which JSON.stringify writes as null: the provider then refuses a value
    // the reader never typed.
    expect(buildSubmissionParams(spec, { seed: '7' }, {})).toEqual({ seed: 7 });
    expect(buildSubmissionParams(spec, { seed: 'abc' }, {})).toEqual({});
  });

  it('drops empty and whitespace values rather than sending them', () => {
    expect(buildSubmissionParams(spec, { aspect_ratio: '   ', seed: '' }, {})).toEqual({});
  });

  it('packs the files of a declared parameter, holes removed', () => {
    const a = { id: 'a' };
    const b = { id: 'b' };
    expect(buildSubmissionParams(spec, {}, { input_image: [undefined, a, b] }))
      .toEqual({ input_image: [a, b] });
  });

  it('caps the files at what the model takes', () => {
    // More handles than the parameter accepts is a refusal, and the reader paid nothing to learn it.
    const files = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
    expect(buildSubmissionParams(spec, {}, { input_image: files }))
      .toEqual({ input_image: [files[0], files[1]] });
  });

  it('drops files under a parameter the model does not declare', () => {
    // The state a model SWITCH leaves behind: a file attached on the previous model.
    expect(buildSubmissionParams(spec, {}, { input_audio: [{ id: 'a' }] })).toEqual({});
  });

  it('sends nothing for an empty composer', () => {
    expect(buildSubmissionParams(spec, {}, {})).toEqual({});
  });
});

describe('hasSubmittableInput', () => {
  const imageOnly = buildParamSpec(model({
    accepts: ['prompt', 'input_image'],
    inputs: { input_image: { role: 'source', maxItems: 1 } },
  }));
  const noFiles = buildParamSpec(model({ accepts: ['prompt', 'seed'] }));

  it('is true for a file the model takes', () => {
    expect(hasSubmittableInput(imageOnly, {}, { input_image: [{ id: 'a' }] })).toBe(true);
  });

  it('is FALSE for a file left over from another model', () => {
    // Counting raw state instead would let a file attached on the previous model unblock the send
    // button on one that takes none - and the turn would go out with an empty prompt.
    expect(hasSubmittableInput(noFiles, {}, { input_image: [{ id: 'a' }] })).toBe(false);
  });

  it('is false for an empty composer', () => {
    expect(hasSubmittableInput(imageOnly, {}, {})).toBe(false);
  });
});

/**
 * The files, shaped once for both the submission and the price.
 *
 * <p><b>The bug.</b> The chat's generation dialog kept its files in their own state and shaped them
 * inline, at the one place that SENDS them. The price was computed from the values alone, so a form
 * holding three reference images was quoted at the published rate and billed the per-file surcharge
 * by the server, which measures the body it actually received. The studio composer had the same bug
 * from the same cause: two answers to "how many files is this call".
 *
 * <p>These pin the rule that makes one answer possible. They are worth more than they look: the
 * shape is what a per-file price COUNTS, so a slot packed wrongly here is money, not markup.
 */
describe('packAssetsByMaxItems', () => {
  const file = (id: string) => ({ id });

  it('sends ONE file for a slot that takes one, not a list holding it', () => {
    // A single-slot parameter given an array is refused by the provider, after the call is paid for.
    const packed = packAssetsByMaxItems(model({ inputs: { input_image: { role: 'source', maxItems: 1 } } }), {
      input_image: [file('a')],
    });

    expect(packed).toEqual({ input_image: { id: 'a' } });
  });

  it('sends the LIST for a slot that takes several', () => {
    const packed = packAssetsByMaxItems(model({ inputs: { reference_image: { role: 'source', maxItems: 4 } } }), {
      reference_image: [file('a'), file('b'), file('c')],
    });

    expect(packed).toEqual({ reference_image: [{ id: 'a' }, { id: 'b' }, { id: 'c' }] });
  });

  it('closes the holes, so an empty slot between two files is not counted as a file', () => {
    // The reader filled slot 1 and slot 3. What is SENT is two files, so what is PRICED must be
    // two: a hole counted as a file is a surcharge for an attachment that does not exist.
    const packed = packAssetsByMaxItems(model({ inputs: { reference_image: { role: 'source', maxItems: 4 } } }), {
      reference_image: [file('a'), undefined, file('c')],
    });

    expect(packed).toEqual({ reference_image: [{ id: 'a' }, { id: 'c' }] });
  });

  it('omits a slot with nothing in it, rather than sending an empty list', () => {
    // Absent and empty are different statements to the provider, and an empty list is the one it
    // has no rule for.
    expect(packAssetsByMaxItems(model({ inputs: { input_image: { role: 'source', maxItems: 1 } } }), {
      input_image: [undefined, undefined],
    })).toEqual({});
  });

  it('CAPS the list at what the slot accepts, which the comment claimed and the code did not', () => {
    // Reachable when a model's maxItems shrinks under retained asset state: pick a model taking
    // four references, attach four, switch to one taking two. The submission sent four, the
    // provider refused the call, and a per-file surcharge had already counted all four.
    const packed = packAssetsByMaxItems(model({ inputs: { reference_image: { role: 'reference', maxItems: 2 } } }), {
      reference_image: [file('a'), file('b'), file('c'), file('d')],
    });

    expect(packed).toEqual({ reference_image: [{ id: 'a' }, { id: 'b' }] });
  });

  it('caps a single-file slot at one, however many are held', () => {
    expect(packAssetsByMaxItems(model({ inputs: { input_image: { role: 'source', maxItems: 1 } } }), {
      input_image: [file('a'), file('b'), file('c')],
    })).toEqual({ input_image: { id: 'a' } });
  });

  it('treats a slot the model declares nothing about as taking one file', () => {
    // A model whose catalogue row has no `inputs` entry for a parameter the form drew anyway. One
    // is the conservative reading: a list sent to a single-file provider is refused outright.
    expect(packAssetsByMaxItems(model({ inputs: {} }), { input_image: [file('a'), file('b')] }))
      .toEqual({ input_image: { id: 'a' } });
  });
});
