import { describe, it, expect } from 'vitest';
import { isJwtShapedToken } from '../jwtShape';

describe('isJwtShapedToken', () => {
  it('is true for a JWS compact JWT (eyJ prefix + 3 segments)', () => {
    expect(isJwtShapedToken('eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig')).toBe(true);
    // Real-world-ish access token shape.
    expect(isJwtShapedToken('eyJraWQiOiJaYkUifQ.eyJ1c2VySWQiOjF9.abc-_123')).toBe(true);
  });

  it('is false for an opaque/UUID resource token (the invitation/email/reset tokens)', () => {
    expect(isJwtShapedToken('8e8caa84-1f01-4c4e-bd88-7efe872aee91')).toBe(false);
    expect(isJwtShapedToken('e2e3aaaa-0000-4000-8000-proxyfixtest1')).toBe(false);
    expect(isJwtShapedToken('invite-token-ce-e2e')).toBe(false);
  });

  it('is false when only part of the JWT shape matches (the boundary, not just the prefix)', () => {
    expect(isJwtShapedToken('eyJabc.def')).toBe(false); // eyJ prefix but only 2 segments
    expect(isJwtShapedToken('eyJabc')).toBe(false); // eyJ prefix, no segments
    expect(isJwtShapedToken('abc.def.ghi')).toBe(false); // 3 segments but no eyJ prefix
  });

  it('is false for empty / null / undefined', () => {
    expect(isJwtShapedToken('')).toBe(false);
    expect(isJwtShapedToken(null)).toBe(false);
    expect(isJwtShapedToken(undefined)).toBe(false);
  });
});
