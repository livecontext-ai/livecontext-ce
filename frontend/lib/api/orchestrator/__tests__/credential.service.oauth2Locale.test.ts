// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: api }));

import { credentialService } from '../credential.service';

// Minimal request shapes - only the post URL is under test here.
const req = { credential_template_id: 'tmpl-1' } as never;
const simpleReq = { credential_template_id: 'tmpl-1' } as never;

beforeEach(() => {
  vi.clearAllMocks();
  api.post.mockResolvedValue({ authorization_url: 'https://x', state: 's' });
});

describe('credentialService OAuth2 initiate: app-locale forwarding', () => {
  it('initiateOAuth2 appends ?locale= when a locale is given', async () => {
    await credentialService.initiateOAuth2(req, 'fr');
    expect(api.post).toHaveBeenCalledWith('/credentials/oauth2/initiate?locale=fr', req);
  });

  it('initiateOAuth2 omits the query entirely when no locale is given (unchanged behaviour)', async () => {
    await credentialService.initiateOAuth2(req);
    expect(api.post).toHaveBeenCalledWith('/credentials/oauth2/initiate', req);
  });

  it('initiateOAuth2 omits the query when the locale is an empty string (falsy)', async () => {
    await credentialService.initiateOAuth2(req, '');
    expect(api.post).toHaveBeenCalledWith('/credentials/oauth2/initiate', req);
  });

  it('initiateOAuth2 URL-encodes the locale (region-qualified value forwarded safely)', async () => {
    await credentialService.initiateOAuth2(req, 'zh-Hans');
    expect(api.post).toHaveBeenCalledWith('/credentials/oauth2/initiate?locale=zh-Hans', req);
  });

  it('initiateOAuth2Simple appends ?locale= when a locale is given', async () => {
    await credentialService.initiateOAuth2Simple(simpleReq, 'de');
    expect(api.post).toHaveBeenCalledWith('/credentials/oauth2/initiate-simple?locale=de', simpleReq);
  });

  it('initiateOAuth2Simple omits the query when no locale is given', async () => {
    await credentialService.initiateOAuth2Simple(simpleReq);
    expect(api.post).toHaveBeenCalledWith('/credentials/oauth2/initiate-simple', simpleReq);
  });
});
