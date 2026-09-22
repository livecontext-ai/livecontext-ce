/**
 * @vitest-environment jsdom
 *
 * The palette must scroll as ONE list.
 *
 * "Frequently Used" used to be a pinned sibling ABOVE the only scrollable region.
 * That is invisible in the right dock, where the panel is full height, and fatal
 * in the bottom dock, where it defaults to 40% of the screen (min 240px): the
 * pinned grid alone is taller than the space left over, so the categories were
 * squeezed to nothing and no scrollbar could reach them - the scroll container
 * was the empty sliver underneath. The user saw Frequently Used and nothing else.
 *
 * The assertion is structural on purpose: the bug IS the DOM relationship
 * (pinned sibling vs scrolling child), and jsdom computes no layout, so asserting
 * heights here would prove nothing.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
// The panel now also asks which nodes this account's plan includes (usePlanFeatureGate).
// An unresolved answer means "nothing is locked", which is the state these
// structural assertions want anyway.
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
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
// `useOptionalTheme` too: the palette's icons render through `useThemeSafely`,
// which reads the context OPTIONALLY because the same icons render on the public
// marketplace outside any ThemeProvider. A mock missing it throws at render.
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

const panel = () => document.querySelector('[data-node-creator-panel]');
/** The one region that scrolls. */
const scrollArea = () => panel()?.querySelector('.overflow-y-auto') ?? null;

afterEach(cleanup);

describe('NodeCreatorPanel - the palette scrolls as one list', () => {
  it('puts Frequently Used INSIDE the scroll area, not pinned above it', () => {
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);

    const heading = screen.getByText('frequentlyUsed');
    const scroller = scrollArea();
    expect(scroller, 'the palette body should have a scrollable region').toBeTruthy();
    // Pinned, this grid eats a short panel whole and the rest is unreachable.
    expect(scroller!.contains(heading), 'Frequently Used must scroll with the categories').toBe(true);
  });

  it('keeps the search pinned - it is a control over the list, not part of it', () => {
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);

    const search = panel()!.querySelector('input');
    expect(search, 'the palette should expose a search input').toBeTruthy();
    expect(scrollArea()!.contains(search!), 'the search must stay reachable while scrolling').toBe(false);
  });

  it('puts Frequently Used FIRST inside the scroller, above the categories', () => {
    // A containment check alone is satisfied by moving the block anywhere inside,
    // including BELOW the categories, which is not the product's reading order.
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);
    const scroller = scrollArea()!;
    const heading = screen.getByText('frequentlyUsed');
    const position = scroller.firstElementChild!.compareDocumentPosition(heading);
    // CONTAINED_BY (16) or the heading IS the first child.
    expect(position & Node.DOCUMENT_POSITION_CONTAINED_BY || scroller.firstElementChild === heading).toBeTruthy();
  });

  it('returns to the top of the list when drilling into a category', () => {
    // Entering a category unmounts Frequently Used from inside the scroller, so a
    // retained scrollTop lands the child list part-way down instead of at item one.
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);
    const scroller = scrollArea()! as HTMLElement;
    scroller.scrollTop = 250;

    fireEvent.click(screen.getByText('Flow'));

    expect(scroller.scrollTop).toBe(0);
  });

  it('has exactly ONE scroll container, so there is a single scrollbar', () => {
    // Two nested scrollers is the other way this breaks: the inner one swallows
    // the wheel and the outer one looks stuck.
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);
    expect(panel()!.querySelectorAll('.overflow-y-auto')).toHaveLength(1);
  });
});
