/**
 * reportExplicitLocaleChoice: the language switchers tell the backend about an explicit pick,
 * fire-and-forget, in BOTH editions - auth-service writes the notification emails in the stored
 * locale on a self-hosted install too, so skipping the report there left CE users with English
 * alerts and no way to change them.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const editionMock = vi.hoisted(() => ({ isCe: false }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() { return editionMock.isCe; },
}));

const apiMock = vi.hoisted(() => ({ reportExplicitLocale: vi.fn() }));
vi.mock('@/lib/api/unified-api-service', () => ({ unifiedApiService: apiMock }));

import { reportExplicitLocaleChoice } from '../localeChoice';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

describe('reportExplicitLocaleChoice', () => {
  beforeEach(() => {
    apiMock.reportExplicitLocale.mockReset();
    apiMock.reportExplicitLocale.mockResolvedValue(undefined);
    editionMock.isCe = false;
  });

  it('reports the picked locale and returns without waiting for it', () => {
    // A promise that never settles: if this helper awaited it, the call below would not return,
    // and the language switch would sit behind the network. Swallowing a FAILURE is the service
    // method's job (its own try/catch, covered in user-api.lifecycle.test.ts) - a second catch
    // here would be a path no test could tell apart from its absence, which is why there is none.
    apiMock.reportExplicitLocale.mockReturnValue(new Promise(() => {}));

    expect(reportExplicitLocaleChoice('de')).toBeUndefined();
    expect(apiMock.reportExplicitLocale).toHaveBeenCalledWith('de');
  });

  it('reports on a self-hosted install too, so its notification emails follow the pick', () => {
    // `editionMock.isCe` used to be set here and read by nothing: `localeChoice` no longer imports
    // IS_CE, so this was a restatement of the test above it whose setup guaranteed its assertion.
    // What makes it a distinct case is that there is NO edition gate left to find, asserted
    // directly on the module rather than through a mock it ignores.
    reportExplicitLocaleChoice('fr');

    expect(apiMock.reportExplicitLocale).toHaveBeenCalledWith('fr');
  });

  it('has no edition gate at all, which is why the case above is not a duplicate', () => {
    const source = readFileSync(
      join(process.cwd(), 'lib', 'lifecycle', 'localeChoice.ts'),
      'utf8',
    );

    expect(
      source,
      'a self-hosted install must report the pick too, or its own mails stay English',
    ).not.toContain('IS_CE');
  });

});
