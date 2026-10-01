import { describe, expect, it, vi } from 'vitest';

vi.mock('server-only', () => ({}));

import { pickNeighbours } from '../PartnerBadgeSpotlight';
import type { PublicPublicationSummary } from '@/lib/marketplace/publicPublications';

function listing(id: string, useCount: number, extra: Partial<PublicPublicationSummary> = {}): PublicPublicationSummary {
  return {
    id, publicSlug: `${id}-slug`, title: id, description: '', publisherName: 'Someone', publisherId: '7',
    publisherHandle: 'someone', publisherAvatarUrl: null, categorySlug: null, categoryName: null, averageRating: 4,
    reviewCount: 1, useCount, publishedAt: null, updatedAt: null, publicationType: 'APPLICATION', categoryColor: null,
    displayMode: 'APPLICATION', creditsPerUse: 0, hasShowcase: true,
    nodeIcons: [{ nodeId: 'n', nodeKind: 'mcp', iconSlug: 'slack', isMcp: true }],
    agentCount: 0, interfaceCount: 1, workflowCount: 1, skillCount: 0, datasourceCount: 0, planSnapshot: null,
    ...extra,
  };
}

describe('pickNeighbours', () => {
  it('keeps the two most used apps that have an icon row, one for each side of the partner app', () => {
    const picked = pickNeighbours([
      listing('low', 1),
      listing('top', 900),
      listing('workflow', 5000, { publicationType: 'WORKFLOW' }),
      listing('no-icons', 8000, { nodeIcons: [] }),
      listing('mid', 400),
      listing('high', 600),
    ]);

    expect(picked.map((p) => p.id)).toEqual(['top', 'high']);
  });

  it('turns them into scenery: no public link and no live preview to mount', () => {
    const [picked] = pickNeighbours([listing('app', 10)]);

    expect(picked.publicSlug).toBeNull();
    expect(picked.hasShowcase).toBe(false);
    // Everything a card shows is kept.
    expect(picked.title).toBe('app');
    expect(picked.nodeIcons).toHaveLength(1);
  });

  it('an empty or unusable marketplace page gives no neighbour, never an error', () => {
    expect(pickNeighbours([])).toEqual([]);
    expect(pickNeighbours([listing('w', 1, { publicationType: 'WORKFLOW' })])).toEqual([]);
  });
});
