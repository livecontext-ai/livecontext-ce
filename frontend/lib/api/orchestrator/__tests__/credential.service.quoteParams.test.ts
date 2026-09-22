// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: api }));

import { credentialService } from '../credential.service';

/**
 * What a generation quote actually PUTS ON THE WIRE.
 *
 * <p>The component tests assert these fields on the argument handed to a
 * MOCKED service, so they pin the caller and not the request. Deleting a field
 * inside this assembly left 136 of them green: `modelId`, `quantity`,
 * `generation` and `quantityUnit` could all vanish from the real query string
 * with nothing to notice. Every one of them changes the answer, and two of
 * them can only ever turn a quote OFF, so a silent loss means the surface
 * shows a price the server would refuse.
 *
 * <p>Driven through the real service against a mocked HTTP client, which is
 * the only layer below the component where the params still exist as data.
 */
beforeEach(() => {
  vi.clearAllMocks();
  api.get.mockResolvedValue({ integrationName: 'seedance', available: true, hasPricing: true });
});

describe('getPlatformCredentialPublicInfo: what reaches the request', () => {
  it('sends every field a generation quote depends on', async () => {
    await credentialService.getPlatformCredentialPublicInfo('seedance', 't-1', {
      modelId: 'seedance-2.0',
      quantity: 10,
      generation: true,
      quantityUnit: 'second',
    });

    expect(api.get).toHaveBeenCalledWith(
      '/platform-credentials/seedance/public-info',
      {
        params: {
          apiToolId: 't-1',
          modelId: 'seedance-2.0',
          quantity: '10',
          generation: 'true',
          quantityUnit: 'second',
        },
      },
    );
  });

  it('omits what the caller could not state, so an old caller keeps its answer', async () => {
    // Absent means "this surface cannot say". Sending an empty value instead
    // would be a statement, and both `generation` and `quantityUnit` can only
    // turn a quote off: inventing them would suppress prices that are real.
    await credentialService.getPlatformCredentialPublicInfo('seedance', 't-1');

    expect(api.get).toHaveBeenCalledWith(
      '/platform-credentials/seedance/public-info',
      { params: { apiToolId: 't-1' } },
    );
  });

  it('a quantity of zero is still sent, because the server decides what zero means', async () => {
    // The client must not silently drop it: zero is "no size stated" to the
    // biller, and a quote that omitted it would be answered as if no size had
    // been asked about, which is a different question.
    await credentialService.getPlatformCredentialPublicInfo('seedance', 't-1', {
      modelId: 'seedance-2.0',
      quantity: 0,
      generation: true,
      quantityUnit: 'second',
    });

    const params = api.get.mock.calls.at(-1)![1].params;
    expect(params.quantity).toBe('0');
  });

  it('an empty-string quantity is NOT sent, because it is a blank form field and not a size', async () => {
    await credentialService.getPlatformCredentialPublicInfo('seedance', 't-1', {
      modelId: 'seedance-2.0',
      quantity: '',
      generation: true,
    });

    const params = api.get.mock.calls.at(-1)![1].params;
    expect(params).not.toHaveProperty('quantity');
  });

  it('sends the price factor when the call sits off the published rate', async () => {
    // Without it the amount on screen is the 720p one beside a button that
    // spends the 1080p one: the biller applies the factor whether or not the
    // quote asked about it.
    await credentialService.getPlatformCredentialPublicInfo('seedance', 't-1', {
      modelId: 'seedance-2.0',
      quantity: 10,
      generation: true,
      quantityUnit: 'second',
      priceMultiplier: 2.4,
    });

    const params = api.get.mock.calls.at(-1)![1].params;
    expect(params.priceMultiplier).toBe('2.4');
  });

  it('omits a factor of 1, which is the same statement as no factor', async () => {
    await credentialService.getPlatformCredentialPublicInfo('seedance', 't-1', {
      modelId: 'seedance-2.0', quantity: 10, generation: true, priceMultiplier: 1,
    });

    expect(api.get.mock.calls.at(-1)![1].params).not.toHaveProperty('priceMultiplier');
  });

  it('omits a factor that is zero, negative or not a number', async () => {
    // None of those can come from a declared modifier, and a zero sent to the
    // quote would advertise a free generation the server then charges for.
    for (const priceMultiplier of [0, -2, Number.NaN]) {
      await credentialService.getPlatformCredentialPublicInfo('seedance', 't-1', {
        modelId: 'seedance-2.0', quantity: 10, generation: true, priceMultiplier,
      });
      expect(api.get.mock.calls.at(-1)![1].params).not.toHaveProperty('priceMultiplier');
    }
  });

  it('percent-encodes the integration name so it cannot escape the path', async () => {
    await credentialService.getPlatformCredentialPublicInfo('a/b', null);

    expect(api.get).toHaveBeenCalledWith('/platform-credentials/a%2Fb/public-info', undefined);
  });
});
