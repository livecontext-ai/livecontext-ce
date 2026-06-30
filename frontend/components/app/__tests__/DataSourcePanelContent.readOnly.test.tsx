/**
 * @vitest-environment jsdom
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';

type CapturedDataTableProps = {
  dataSourceId?: number;
  embedded?: boolean;
  readOnly?: boolean;
};

const dataTableProps = vi.hoisted(() => [] as CapturedDataTableProps[]);
const sharedConversation = vi.hoisted(() => ({ current: null as unknown }));
const orgMutationGate = vi.hoisted(() => ({ canMutate: true }));

vi.mock('@/components/DataTable', () => ({
  default: (props: CapturedDataTableProps) => {
    dataTableProps.push(props);
    return <div data-testid="data-table" />;
  },
}));

vi.mock('@/contexts/SharedConversationContext', () => ({
  useSharedConversation: () => sharedConversation.current,
}));

vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  usePublicationSnapshot: () => null,
}));

vi.mock('@/lib/datatable/snapshot-adapter', () => ({
  snapshotToDataTable: vi.fn(),
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => orgMutationGate.canMutate,
}));

import { DataSourcePanelContent } from '@/components/app/DataSourcePanelContent';

describe('DataSourcePanelContent readOnly mode', () => {
  afterEach(() => {
    dataTableProps.length = 0;
    sharedConversation.current = null;
    orgMutationGate.canMutate = true;
    cleanup();
  });

  it('opens table side-panel tabs editable by default', () => {
    render(<DataSourcePanelContent dataSourceId="42" />);

    expect(dataTableProps[0]).toMatchObject({
      dataSourceId: 42,
      embedded: true,
      readOnly: false,
    });
  });

  it('keeps shared table tabs read-only', () => {
    sharedConversation.current = { shareToken: 'share-1' };

    render(<DataSourcePanelContent dataSourceId="42" />);

    expect(dataTableProps[0]).toMatchObject({
      dataSourceId: 42,
      embedded: true,
      readOnly: true,
    });
  });

  it('keeps viewer workspace table tabs read-only', () => {
    orgMutationGate.canMutate = false;

    render(<DataSourcePanelContent dataSourceId="42" />);

    expect(dataTableProps[0]).toMatchObject({
      dataSourceId: 42,
      embedded: true,
      readOnly: true,
    });
  });
});
