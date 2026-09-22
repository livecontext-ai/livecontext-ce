/**
 * @vitest-environment jsdom
 *
 * Two things the add-node palette gained, and the failure each one fixes.
 *
 * 1. SEARCH REACHES NODES. The box used to filter only what the CURRENT screen
 *    listed, which at the root is five category cards. Typing "webhook", "sub-workflow"
 *    or "delete row" therefore found nothing at all - the node existed, one or two
 *    levels down, and the only way to it was knowing which card to open. The search
 *    now filters a flat index of every node the palette can create.
 *
 * 2. RANKED INTEGRATIONS. A section under its own rule and title listing the catalogue
 *    in platform-usage order, so the palette offers what people build with instead of
 *    requiring a name to be guessed into the search box first. It shows the ORDER only:
 *    a usage number next to an integration would read as a recommendation and would put
 *    a cross-tenant volume on a builder's screen.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
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
// `useOptionalTheme` too: the palette's icons render through `useThemeSafely`,
// which reads the context OPTIONALLY because the same icons render on the public
// marketplace outside any ThemeProvider. A mock missing it throws at render.
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light', setTheme: vi.fn(), resolvedTheme: 'light' }),
  useOptionalTheme: () => ({ theme: 'light', setTheme: vi.fn(), resolvedTheme: 'light' }),
}));

// Two integrations, ranked. `runCount` is deliberately present on the fixture and must
// never reach the screen - see the last test.
const POPULAR_PAGE = {
  pages: [{
    content: [
      { slug: 'slack', apiName: 'Slack', description: 'Team messaging', toolsCount: 12, iconSlug: 'slack', runCount: 4242 },
      { slug: 'stripe', apiName: 'Stripe', description: 'Payments', toolsCount: 30, iconSlug: 'stripe', runCount: 17 },
    ],
    totalElements: 2, totalPages: 1, number: 0, size: 20, last: true,
  }],
};

const SLACK_TOOLS = [
  { slug: 'slack-post-message', name: 'Post Message', description: 'Send a message', method: 'POST', apiSlug: 'slack' },
];

vi.mock('../../hooks/useMcpData', () => ({
  // Empty on purpose: this list is loaded by the MCP screen and by a search, NEVER at
  // the root, which is where the ranked integrations section lives.
  useMcpApis: () => ({ data: undefined, fetchNextPage: vi.fn(), hasNextPage: false, isFetching: false, isLoading: false }),
  useMcpApiTools: (apiSlug: string | null) => ({ data: apiSlug === 'slack' ? SLACK_TOOLS : undefined, isLoading: false }),
  usePopularApis: () => ({ data: POPULAR_PAGE, fetchNextPage: vi.fn(), hasNextPage: false, isFetching: false, isLoading: false }),
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
const search = (text: string) => fireEvent.change(searchBox(), { target: { value: text } });

afterEach(cleanup);

describe('NodeCreatorPanel - search reaches every node', () => {
  it('finds a trigger that lives two levels down', () => {
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);
    expect(screen.queryByText('Webhook'), 'not listed before searching').toBeNull();

    search('webhook');

    expect(screen.getByText('paletteNodes'), 'results get their own heading').toBeTruthy();
    expect(screen.getByText('Webhook')).toBeTruthy();
  });

  it('finds a node buried in a subcategory', () => {
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);

    // core > Tables > Delete Row: two levels down, and unreachable by search before.
    search('delete row');

    expect(screen.getByText('Delete Row')).toBeTruthy();
  });

  it('matches on the description too, not just the name', () => {
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);

    search('cron');

    expect(screen.getByText('Scheduler'), 'the word only appears in the description').toBeTruthy();
  });

  it('offers a name match before a description match', () => {
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);

    search('webhook');

    const labels = Array.from(panel().querySelectorAll('*'))
      .filter((el) => el.children.length === 0 && el.textContent?.trim())
      .map((el) => el.textContent!.trim());
    const webhookAt = labels.indexOf('Webhook');
    const respondAt = labels.indexOf('Respond to Webhook');
    expect(webhookAt).toBeGreaterThanOrEqual(0);
    if (respondAt >= 0) {
      expect(webhookAt, 'the node actually named Webhook comes first').toBeLessThan(respondAt);
    }
  });

  it('drops a plain node straight onto the canvas', () => {
    const onSelectNode = vi.fn();
    render(<NodeCreatorPanel embedded isOpen onSelectNode={onSelectNode} />);

    search('webhook');
    fireEvent.click(screen.getByText('Webhook'));

    expect(onSelectNode).toHaveBeenCalled();
  });

  it('opens the picker for a result that needs one, instead of dropping a half-built node', () => {
    // Sub-Workflow has to be told WHICH workflow. Reached from a search result it used
    // to have no handler at all, so it would have landed on the canvas pointing nowhere.
    const onSelectNode = vi.fn();
    render(<NodeCreatorPanel embedded isOpen onSelectNode={onSelectNode} />);

    search('sub-workflow');
    fireEvent.click(screen.getByText('Sub-Workflow'));

    expect(onSelectNode, 'a picker opens; nothing is added yet').not.toHaveBeenCalled();
    expect(searchBox().value, 'the search is cleared on navigation').toBe('');
  });

  it('says nothing when nothing matches, rather than listing every node', () => {
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);

    search('zzzzz-no-such-node');

    expect(screen.queryByText('paletteNodes')).toBeNull();
  });
});

describe('NodeCreatorPanel - ranked integrations', () => {
  it('lists integrations under their own heading, below the categories', () => {
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);

    const integrations = screen.getByText('paletteIntegrations');
    const categories = screen.getByText('paletteCategories');
    expect(screen.getByText('Slack')).toBeTruthy();
    expect(screen.getByText('Stripe')).toBeTruthy();
    // DOCUMENT_POSITION_FOLLOWING = 4: the section comes after the categories.
    expect(categories.compareDocumentPosition(integrations) & 4).toBeTruthy();
  });

  it('keeps the ranking the server sent - it IS the feature', () => {
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);

    const slack = screen.getByText('Slack');
    const stripe = screen.getByText('Stripe');
    expect(slack.compareDocumentPosition(stripe) & 4, 'the most-run integration stays first').toBeTruthy();
  });

  it('never prints a usage number', () => {
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);

    expect(screen.queryByText(/4242/), 'no run count on the row').toBeNull();
    expect(screen.queryByText(/\b17\b/)).toBeNull();
  });

  it('hides the section while searching, where the MCP results answer instead', () => {
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);
    expect(screen.getByText('paletteIntegrations')).toBeTruthy();

    search('webhook');

    expect(screen.queryByText('paletteIntegrations')).toBeNull();
  });

  it('carries the integration onto the node when a tool is picked from this section', () => {
    // The tools screen used to resolve its parent API from the MCP list alone, which is
    // empty at the root: a tool opened from here produced a node with a blank apiName and
    // no icon, silently, because both fields are optional.
    const onSelectNode = vi.fn();
    render(<NodeCreatorPanel embedded isOpen onSelectNode={onSelectNode} />);

    fireEvent.click(screen.getByText('Slack'));
    fireEvent.click(screen.getByText('Post Message'));

    expect(onSelectNode).toHaveBeenCalledTimes(1);
    const node = onSelectNode.mock.calls[0][0];
    expect(node.toolData.apiName, 'the integration name must survive the hop').toBe('Slack');
    expect(node.apiData.iconSlug).toBe('slack');
  });

  it('hides the section once a category is opened', () => {
    render(<NodeCreatorPanel embedded isOpen onSelectNode={vi.fn()} />);

    fireEvent.click(within(panel()).getByText('Flow'));

    expect(screen.queryByText('paletteIntegrations'), 'one level down the palette is about that category').toBeNull();
    expect(screen.queryByText('paletteCategories'), 'and the breadcrumb already names it').toBeNull();
  });
});
