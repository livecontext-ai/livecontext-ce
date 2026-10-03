import { describe, expect, it } from 'vitest';
import { proxiedAvatarUrl } from '../avatarUrl';

describe('proxiedAvatarUrl', () => {
  it('serves a backend avatar path through the app proxy, once, and leaves anything else as it is', () => {
    expect(proxiedAvatarUrl('/api/users/42/avatar')).toBe('/api/proxy/users/42/avatar');
    expect(proxiedAvatarUrl('/api/proxy/users/42/avatar')).toBe('/api/proxy/users/42/avatar');
    expect(proxiedAvatarUrl('https://cdn.example/a.png')).toBe('https://cdn.example/a.png');
    expect(proxiedAvatarUrl('/examples/a.webp')).toBe('/examples/a.webp');
    expect(proxiedAvatarUrl('')).toBeNull();
    expect(proxiedAvatarUrl(null)).toBeNull();
    expect(proxiedAvatarUrl(7)).toBeNull();
  });
});
