// @vitest-environment node
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it, expect } from 'vitest';

import {
  MAX_PRICE_FACTOR, priceFactorDependsOnRuntime, priceMultiplierFor, priceFactorReasons,
} from '../priceModifiers';
import type { GenerationModel } from '@/lib/api/orchestrator/generation.service';

/**
 * The client half of a price factor.
 *
 * <p>It is not the billing path - the amount charged is resolved again on the
 * server from the real parameters - but it IS the number the reader sees before
 * they decide to spend, and the two have to agree. Both read the same declared
 * table shipped on the model row; these tests pin this side of it against the
 * same cases `GenerationPriceModifierTest` pins on the other.
 */

/** A video model shaped like the shipped ones: a priced resolution and a file slot. */
function model(modifiers: NonNullable<GenerationModel['price']['modifiers']>): GenerationModel {
  return {
    model: 'demo-1.0',
    kind: 'video',
    label: 'Demo',
    provider: 'Demo',
    iconSlug: null,
    apiToolId: 't-1',
    integrationName: 'demo',
    accepts: ['prompt', 'duration_seconds', 'resolution', 'reference_image'],
    required: ['prompt'],
    limits: {},
    billedOn: 'duration_seconds',
    measuredUnit: 'second',
    defaultQuantity: '5',
    async: false,
    price: { unit: 'second', baseCredits: '0', unitCredits: '100', modifiers },
  } as GenerationModel;
}

const RESOLUTION_AND_FILES = model({
  resolution: { by_value: { '480p': 1, '720p': 2, '1080p': 4 } },
  reference_image: { per_file: 0.1 },
});

describe('priceMultiplierFor', () => {
  it('is 1 for a model that declares nothing, which is most of them', () => {
    const plain = model({});
    delete plain.price.modifiers;
    expect(priceMultiplierFor(plain, { resolution: '1080p' })).toBe(1);
  });

  it('is 1 when there is no model yet', () => {
    expect(priceMultiplierFor(null, { resolution: '1080p' })).toBe(1);
  });

  it('reads the factor declared for the chosen value', () => {
    expect(priceMultiplierFor(RESOLUTION_AND_FILES, { resolution: '1080p' })).toBe(4);
  });

  it('bills an OMITTED value at the reference tier rather than guessing', () => {
    expect(priceMultiplierFor(RESOLUTION_AND_FILES, { prompt: 'a cat' })).toBe(1);
    expect(priceMultiplierFor(RESOLUTION_AND_FILES, null)).toBe(1);
  });

  it('matches a value however it was written', () => {
    expect(priceMultiplierFor(RESOLUTION_AND_FILES, { resolution: ' 1080P ' })).toBe(4);
  });

  it('matches a numeric value as a NUMBER, so 10.0 finds the entry keyed 10', () => {
    // A miss here would quote the reference tier for a call the server bills at
    // the expensive one, which is the one failure this whole path exists to stop.
    const quality = model({ quality: { by_value: { '5': 1, '10': 3 } } });
    expect(priceMultiplierFor(quality, { quality: 10.0 })).toBe(3);
    expect(priceMultiplierFor(quality, { quality: '10' })).toBe(3);
  });

  it('counts each file attached in a slot', () => {
    expect(priceMultiplierFor(RESOLUTION_AND_FILES, {
      reference_image: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
    })).toBe(1.3);
  });

  it('counts a single handle as one file', () => {
    expect(priceMultiplierFor(RESOLUTION_AND_FILES, { reference_image: { id: 'a' } })).toBe(1.1);
  });

  it('charges nothing extra for an empty slot', () => {
    expect(priceMultiplierFor(RESOLUTION_AND_FILES, { reference_image: [] })).toBe(1);
    expect(priceMultiplierFor(RESOLUTION_AND_FILES, { reference_image: [undefined] })).toBe(1);
  });

  it('multiplies the factors, so 1080p with two files is 4 x 1.2', () => {
    expect(priceMultiplierFor(RESOLUTION_AND_FILES, {
      resolution: '1080p',
      reference_image: [{ id: 'a' }, { id: 'b' }],
    })).toBe(4.8);
  });

  it('rounds the product, so a factor that did not change does not move a cache key', () => {
    // 1.1 x 2 lands at 2.2000000000000002 in binary floating point, and the
    // value goes into a react-query key: an unrounded one mints a new entry and
    // a second request for the same call.
    const value = priceMultiplierFor(RESOLUTION_AND_FILES, {
      resolution: '720p', reference_image: [{ id: 'a' }],
    });
    expect(value).toBe(2.2);
  });

  it('ignores a factor a hand-edited row made absurd rather than zeroing the price', () => {
    const broken = model({ resolution: { by_value: { '480p': 1, '720p': 0 } } });
    expect(priceMultiplierFor(broken, { resolution: '720p' })).toBe(1);
  });
});

describe('priceFactorReasons', () => {
  it('names only what actually moved the price', () => {
    expect(priceFactorReasons(RESOLUTION_AND_FILES, {
      resolution: '480p',                       // the reference tier
      reference_image: [{ id: 'a' }],
    })).toEqual([{ param: 'reference_image', factor: 1.1 }]);
  });

  it('is empty when the call sits at the published rate', () => {
    expect(priceFactorReasons(RESOLUTION_AND_FILES, { resolution: '480p' })).toEqual([]);
  });

  it('is empty for a model that declares nothing', () => {
    const plain = model({});
    delete plain.price.modifiers;
    expect(priceFactorReasons(plain, { resolution: '1080p' })).toEqual([]);
  });
});

/**
 * When the surface CANNOT know the factor, and has to say so instead of guessing.
 *
 * <p>The workflow inspector's fields accept expressions. A `resolution` bound to
 * `{{trigger:webhook.output.res}}` is a string that matches no entry in the model's `by_value`
 * table, so the estimate falls to the reference tier and quotes the published rate for a step the
 * server may bill at four times it. A file slot bound to one template counts as one file however
 * many it resolves to, understating a per-file surcharge the same way. Neither is fixable by
 * computing harder: the value does not exist until the run reaches that step.
 */
describe('priceFactorDependsOnRuntime', () => {
  it('is true for a priced VALUE bound to an expression', () => {
    expect(priceFactorDependsOnRuntime(
      model({ resolution: { by_value: { '720p': 1, '1080p': 4 } } }),
      { resolution: '{{trigger:webhook.output.res}}' },
    )).toBe(true);
  });

  it('is true for a priced FILE slot holding an expression', () => {
    // One template, any number of files at run time. The count is what a per-file rate multiplies.
    expect(priceFactorDependsOnRuntime(
      model({ reference_image: { per_file: 0.1 } }),
      { reference_image: ['{{core:pick.output.result.images}}'] },
    )).toBe(true);
  });

  it('is FALSE for a literal, which is every studio call', () => {
    // The composer's fields hold values, so it must never pay for this hedge.
    expect(priceFactorDependsOnRuntime(
      model({ resolution: { by_value: { '720p': 1, '1080p': 4 } } }),
      { resolution: '1080p' },
    )).toBe(false);
  });

  it('is FALSE for an expression on a parameter the model does not price', () => {
    // Only a priced parameter can move the factor. Hedging on a templated prompt would put the
    // note on nearly every workflow step and teach the reader to ignore it.
    expect(priceFactorDependsOnRuntime(
      model({ resolution: { by_value: { '720p': 1, '1080p': 4 } } }),
      { resolution: '1080p', prompt: '{{trigger:webhook.output.text}}' },
    )).toBe(false);
  });

  it('is FALSE for a model that declares no modifiers at all', () => {
    expect(priceFactorDependsOnRuntime(model({}), { resolution: '{{x}}' })).toBe(false);
    expect(priceFactorDependsOnRuntime(null, { resolution: '{{x}}' })).toBe(false);
  });

  it('is FALSE for a half-written template, which is not one yet', () => {
    // A reader mid-keystroke. Treating `{{` alone as a template would flip the note on and off
    // while they type.
    expect(priceFactorDependsOnRuntime(
      model({ resolution: { by_value: { '720p': 1, '1080p': 4 } } }),
      { resolution: '{{trig' },
    )).toBe(false);
  });
});

/**
 * The branches where this file DISAGREES with the server, and what that costs.
 *
 * <p>Everything here is a display: the amount charged is resolved again server-side. But a display
 * that is wrong in the cheap direction is the expensive one, because the reader finds out by being
 * charged more than the screen said.
 */
describe('priceMultiplierFor - the branches that can understate', () => {
  it('bills an UNLISTED value at the reference tier, which the parser is what prevents', () => {
    // The branch that quotes 1x for a call the server may bill at 4x. It is unreachable through a
    // descriptor this platform accepts - the parser refuses a `multiply` map that does not price
    // every ALLOWED value, and requires the parameter - but this function is handed whatever the
    // catalogue serves, including a row imported before that gate existed.
    expect(priceMultiplierFor(
      model({ resolution: { by_value: { '720p': 1, '1080p': 4 } } }),
      { resolution: '4k' },
    )).toBe(1);
  });

  it('reads a factor written as a STRING, because JSON from the wire often is', () => {
    expect(priceMultiplierFor(
      model({ resolution: { by_value: { '720p': 1, '1080p': '2' } } as never }),
      { resolution: '1080p' },
    )).toBe(2);
  });

  it('reads a per-file rate written as a STRING too', () => {
    expect(priceMultiplierFor(
      model({ reference_image: { per_file: '0.1' } as never }),
      { reference_image: ['a', 'b'] },
    )).toBe(1.2);
  });

  it('lets the per-file rate win when a malformed entry carries BOTH shapes', () => {
    // The Java parser refuses this outright ("needs exactly one of"), so it cannot ship. Pinned so
    // the behaviour on a row that reached the client anyway is a decision rather than an accident,
    // and so the divergence from the server is written down where someone comparing them looks.
    expect(priceMultiplierFor(
      model({ reference_image: { per_file: 0.1, by_value: { a: 9 } } as never }),
      { reference_image: ['a'] },
    )).toBe(1.1);
  });
});

/**
 * The client's ceiling, which mirrors the server's.
 *
 * <p>The server drops a factor above it from a quote and the parser refuses a descriptor that can
 * reach it. This function is handed whatever the catalogue serves, including a row imported before
 * that gate existed - and unbounded it produced the one case in this feature where the number on
 * screen is LOWER than the charge: the server dropped the factor and quoted the published rate
 * while the billing path applied the whole product.
 */
describe('priceMultiplierFor - the ceiling', () => {
  it('answers the BASE RATE above the ceiling, which is what the server charges there', () => {
    // Not a clamp to the ceiling. The server's charging path drops an over-ceiling factor
    // entirely and bills the base rate, so clamping here would send 100, have the quote door
    // accept it, and show up to a hundred times the real amount - inverting the error.
    expect(priceMultiplierFor(
      model({ resolution: { by_value: { a: 1, b: 5000 } } }),
      { resolution: 'b' },
    )).toBe(1);
  });

  it('judges the PRODUCT, not each factor, which is what the parser bounds', () => {
    // Two factors a parser would accept alone and refuse together.
    expect(priceMultiplierFor(
      model({ resolution: { by_value: { a: 1, b: 40 } }, reference_image: { per_file: 10 } }),
      { resolution: 'b', reference_image: ['x', 'y', 'z'] },
    )).toBe(1);
  });

  it('leaves an ordinary factor exactly as it was', () => {
    // The half that pays: a ceiling that clamped real factors would bill every modulated call at
    // the wrong rate while satisfying the two tests above.
    expect(priceMultiplierFor(
      model({ resolution: { by_value: { '720p': 1, '1080p': 2 } } }),
      { resolution: '1080p' },
    )).toBe(2);
    expect(priceMultiplierFor(
      model({ reference_image: { per_file: 0.05 } }),
      { reference_image: ['a', 'b', 'c', 'd'] },
    )).toBe(1.2);
  });

  it('agrees with the number the SERVER declares, read from the server source', () => {
    // This compared the constant to a literal 100 and called that "asserted rather than assumed",
    // which is a tautology: raising the Java ceiling would leave it green while the client kept
    // clamping at the old one. Read the Java file instead, the way quoteKeyCallSites.test.ts reads
    // call sites and StudioLookContrast.test.ts reads globals.css.
    const java = readFileSync(join(
      process.cwd(), '..', 'backend', 'common-lib', 'src', 'main', 'java', 'com',
      'apimarketplace', 'common', 'web', 'BillingContextHeaders.java',
    ), 'utf8');
    const declared = /MAX_GENERATION_MULTIPLIER\s*=\s*[\w.]*\.valueOf\((\d+)\)/.exec(java);

    expect(declared, 'the server must still declare MAX_GENERATION_MULTIPLIER').not.toBeNull();
    expect(MAX_PRICE_FACTOR).toBe(Number((declared as RegExpExecArray)[1]));
  });
});
