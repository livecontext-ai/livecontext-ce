/**
 * reportExplicitLocaleChoice: the language switchers tell the backend about an explicit pick,
 * fire-and-forget on cloud, and never on a self-hosted install.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

const editionMock = vi.hoisted(() => ({ isCe: false }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() { return editionMock.isCe; },
}));

const apiMock = vi.hoisted(() => ({ reportExplicitLocale: vi.fn() }));
vi.mock('@/lib/api/unified-api-service', () => ({ unifiedApiService: apiMock }));

import { reportExplicitLocaleChoice } from '../localeChoice';

describe('reportExplicitLocaleChoice', () => {
  beforeEach(() => {
    apiMock.reportExplicitLocale.mockReset();
    apiMock.reportExplicitLocale.mockResolvedValue(undefined);
    editionMock.isCe = false;
  });

  it('reports the picked locale on cloud and returns without waiting for it', () => {
    apiMock.reportExplicitLocale.mockReturnValue(new Promise(() => {}));

    expect(reportExplicitLocaleChoice('de')).toBeUndefined();
    expect(apiMock.reportExplicitLocale).toHaveBeenCalledWith('de');
  });

  it('sends nothing on a self-hosted install', () => {
    editionMock.isCe = true;

    reportExplicitLocaleChoice('fr');

    expect(apiMock.reportExplicitLocale).not.toHaveBeenCalled();
  });
});
