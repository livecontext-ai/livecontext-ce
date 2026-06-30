import { describe, it, expect, vi, beforeEach } from 'vitest';
import { cloudLinkService } from '../cloud-link.service';
import { apiClient } from '../api-client';

vi.mock('../api-client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const mockedGet = vi.mocked(apiClient.get);
const mockedPost = vi.mocked(apiClient.post);

describe('cloudLinkService - TLS-intercept trust', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('probeTlsIntercept GETs the configured-cloud probe endpoint and returns the result', async () => {
    const probe = {
      intercepted: true,
      reachable: true,
      host: 'auth.livecontext.ai',
      caSubject: 'CN=Corp Proxy Root',
      caIssuer: 'CN=Corp Proxy Root',
      caSha256: 'abc123',
      caPem: '-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----',
    };
    mockedGet.mockResolvedValueOnce(probe as any);

    const result = await cloudLinkService.probeTlsIntercept();

    // No caller-supplied target (no SSRF surface): the probe always hits the fixed endpoint.
    expect(mockedGet).toHaveBeenCalledWith('/ce/tls/probe');
    expect(result).toEqual(probe);
  });

  it('trustInterceptCa POSTs the PEM to the trust endpoint and returns the trusted identity', async () => {
    mockedPost.mockResolvedValueOnce({ trusted: true, subject: 'CN=Corp Proxy Root', sha256: 'abc123' } as any);
    const pem = '-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----';

    const result = await cloudLinkService.trustInterceptCa(pem);

    expect(mockedPost).toHaveBeenCalledWith('/ce/tls/trust', { pem });
    expect(result.trusted).toBe(true);
    expect(result.sha256).toBe('abc123');
  });

  it('propagates a rejected trust request (e.g. invalid certificate) to the caller', async () => {
    mockedPost.mockRejectedValueOnce(new Error('invalid_certificate'));

    await expect(cloudLinkService.trustInterceptCa('garbage')).rejects.toThrow('invalid_certificate');
  });
});
