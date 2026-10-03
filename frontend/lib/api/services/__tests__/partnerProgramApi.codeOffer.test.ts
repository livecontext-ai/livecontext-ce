import { beforeEach, describe, expect, it, vi } from 'vitest';

const get = vi.fn();
vi.mock('../../api-client', () => ({ apiClient: { get: (...args: unknown[]) => get(...args) } }));

import { partnerProgramApi } from '../partner-program-api.service';

describe('partnerProgramApi.codeOffer', () => {
  beforeEach(() => get.mockReset().mockResolvedValue({ code: 'NORTHWIND', credits: 8000 }));

  it('asks the public endpoint anonymously: the visitor of a partner link has no account yet', async () => {
    await partnerProgramApi.codeOffer('NORTHWIND');

    expect(get).toHaveBeenCalledWith('/public/partner-program/codes/NORTHWIND', { skipAuth: true });
  });

  it('encodes the code: a crafted value cannot reach another path', async () => {
    await partnerProgramApi.codeOffer('A/B?x=1');

    expect(get).toHaveBeenCalledWith('/public/partner-program/codes/A%2FB%3Fx%3D1', { skipAuth: true });
  });
});
