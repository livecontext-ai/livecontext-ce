import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/edition', () => ({ IS_MANAGED_CLOUD: true }));
vi.mock('server-only', () => ({}));

import { MAX_IDS_PER_REQUEST } from '../verifiedUsers';
import { VERIFIED_HANDLES_PER_REQUEST } from '@/lib/marketplace/publicPublications';

/**
 * The verified-badge batch cap exists in three places: once in Java, and once in each
 * of the two clients that chunk their requests to stay under it.
 *
 * Drift here is silent and total. Lower the Java cap alone and every over-sized
 * request answers 400; both clients swallow that (`catch {}` in the batch loader,
 * `getJson`'s catch in the SSR reader) because a badge lookup must never take a page
 * down. The result is every badge on the platform disappearing with no error anywhere
 * - green, and wrong.
 *
 * So this reads the Java constant rather than restating the number.
 */
const SERVICE_JAVA = path.resolve(
  __dirname,
  '../../../../backend/auth-service/src/main/java/com/apimarketplace/auth/service/VerifiedAccountService.java',
);

function backendCap(): number {
  const source = fs.readFileSync(SERVICE_JAVA, 'utf8');
  const match = source.match(/MAX_BATCH_SIZE\s*=\s*(\d+)\s*;/);
  if (!match) {
    throw new Error(
      `MAX_BATCH_SIZE not found in ${SERVICE_JAVA}. If the constant was renamed or moved, `
      + 'update this guard rather than deleting it - the three copies still have to agree.',
    );
  }
  return Number(match[1]);
}

describe('verified-badge batch cap', () => {
  it('the id-keyed client chunks at exactly the cap the backend enforces', () => {
    expect(MAX_IDS_PER_REQUEST).toBe(backendCap());
  });

  it('the handle-keyed SSR reader chunks at the same cap', () => {
    expect(VERIFIED_HANDLES_PER_REQUEST).toBe(backendCap());
  });
});
