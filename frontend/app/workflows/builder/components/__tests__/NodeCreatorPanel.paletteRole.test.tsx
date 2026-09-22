/**
 * @vitest-environment jsdom
 *
 * WHAT the palette tells you a row is, asserted on the panel that renders it.
 *
 * The hover card's badge is resolved from the props each row is given, and one
 * of those props (`paletteRole`) is DECLARED by the panel because nothing in the
 * others separates a folder of triggers from a trigger that drills down. Every
 * existing test for that resolution feeds `DraggableNodeItem` hand-written
 * props, so the resolution is certified and the wiring is not - and the wiring
 * is where it broke: the branch that renders a category's contents hardcoded
 * `paletteRole="category"`, which badged all 29 Core nodes "Category" and
 * withheld the drag hint from rows that visibly offer a grip, while the card
 * said "Click to add" two lines below the wrong badge.
 *
 * These tests therefore render the real panel and read the real card.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('../palette/../nodes/shared', () => ({ NodeIcon: () => null }));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn(), fetchQuery: vi.fn() }),
  useQuery: () => ({ data: undefined, isPending: false }),
  // Two real reasons, kept together: the panel creates a trigger shortcut through a mutation
  // and reads isPending/isError to render that row's spinner and error, and the palette reaches
  // a trigger node whose bell-automation hook module declares a mark-all-read mutation. A
  // partial mock of this package does not fail one assertion, it throws while rendering (or at
  // import) on whatever it left out, so every test in the file dies at once.
  useMutation: () => ({ mutate: vi.fn(), mutateAsync: vi.fn(async () => undefined), isPending: false, isError: false }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ isRunMode: false }) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getAgents: vi.fn().mockResolvedValue([]) } }));
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light', setTheme: vi.fn(), resolvedTheme: 'light' }),
  useOptionalTheme: () => ({ theme: 'light', setTheme: vi.fn(), resolvedTheme: 'light' }),
}));
vi.mock('../../hooks/useMcpData', () => ({
  useMcpApis: () => ({ data: undefined, fetchNextPage: vi.fn(), hasNextPage: false, isFetching: false, isLoading: false }),
  useMcpApiTools: () => ({ data: undefined, isLoading: false }),
  usePopularApis: () => ({ data: undefined, fetchNextPage: vi.fn(), hasNextPage: false, isFetching: false, isLoading: false }),
  POPULAR_APIS_PAGE_SIZE: 20,
}));
vi.mock('../../hooks/useDataSourceData', () => ({
  useDataSources: () => ({ data: [], isLoading: false }),
  useDataSourceTables: () => ({ data: [], isLoading: false }),
}));
vi.mock('../../hooks/useWorkflowsData', () => ({ useWorkflows: () => ({ data: [], isLoading: false }) }));
vi.mock('../../hooks/useInterfaces', () => ({ useInterfaces: () => ({ data: [], isLoading: false }) }));
vi.mock('@/components/chat/CreateInterfaceModal', () => ({ CreateInterfaceModal: () => null }));
vi.mock('@/components/chat/CreateAgentModal', () => ({ CreateAgentModal: () => null }));
vi.mock('@/components/chat/CreateDataSourceModal', () => ({ CreateDataSourceModal: () => null }));

import { NodeCreatorPanel } from '@/app/workflows/builder/components/NodeCreatorPanel';

const panel = () => document.querySelector<HTMLElement>('[data-node-creator-panel]')!;
const searchBox = () => panel().querySelector('input')!;

/**
 * Open the card for the row carrying `label` and return it.
 *
 * Radix opens a tooltip on pointer OR focus; jsdom has no real hover, so the
 * row gets both, the way the component-level suite does it.
 */
async function cardFor(label: string): Promise<HTMLElement> {
  const row = screen.getByText(label).closest('.group') as HTMLElement;
  expect(row, `no palette row rendered for "${label}"`).not.toBeNull();
  fireEvent.pointerEnter(row, { pointerType: 'mouse' });
  fireEvent.mouseEnter(row);
  fireEvent.focus(row);
  return waitFor(() => screen.getByRole('tooltip'));
}

/** The resolved kind, read as data: several row names contain a badge word. */
const badgeOf = (card: HTMLElement) =>
  card.querySelector('[data-palette-badge]')?.getAttribute('data-palette-badge');

afterEach(cleanup);

describe('NodeCreatorPanel - what each row says it is', () => {
  it('badges a node inside an opened category as a NODE, and offers the drag', async () => {
    // The regression: this row read "Category" with no drag hint, while the same
    // card said "Click to add" two lines below and the row shows a grip on hover.
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);
    fireEvent.click(screen.getByText('Core'));

    const card = await cardFor('Wait');

    expect(badgeOf(card)).toBe('node');
    expect(card.textContent).toContain('hintClickAdd');
    expect(card.textContent).toContain('hintDrag');
  });

  it('still badges the tile you opened it from as a CATEGORY', async () => {
    // The other half of the rule: the tiles at the root ARE folders, and they
    // are the reason a declared role exists at all.
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);

    const card = await cardFor('Core');

    expect(badgeOf(card)).toBe('category');
    expect(card.textContent).toContain('hintClickBrowse');
    // A folder is not draggable: dropping one leaves a nameless node behind.
    expect(card.textContent).not.toContain('hintDrag');
  });

  it('gives a drill-down trigger the same badge whichever route reached it', async () => {
    // Under the Triggers group it derived "Trigger"; as a search hit the panel
    // declared "Category" for it. Same row, two answers.
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);
    fireEvent.change(searchBox(), { target: { value: 'error' } });

    const card = await cardFor('Error');

    expect(badgeOf(card)).toBe('trigger');
  });
});
