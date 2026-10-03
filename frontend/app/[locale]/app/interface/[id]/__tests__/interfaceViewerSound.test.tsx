// @vitest-environment jsdom
/**
 * The volume of a page you are looking at.
 *
 * What is pinned: the page arrives SILENT. Opening a page from the list is a look, not a
 * decision to be spoken to, and a page that starts playing on its own has taken that decision
 * for the reader - the same reason an application's preview starts muted. Claiming the volume
 * is also what earns the control: the viewer only silences the page because it hands the reader
 * a way to give the sound back, and it only offers that way when there is a sound to give.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';

const preview = vi.hoisted(() => ({ props: [] as Record<string, unknown>[] }));

vi.mock('@/components/InterfacePreview', () => ({
  InterfacePreview: (props: Record<string, unknown>) => {
    preview.props.push(props);
    return <div data-testid="interface-preview" />;
  },
}));
vi.mock('@/components/chat/CreateInterfaceModal', () => ({ CreateInterfaceModal: () => null }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div data-testid="spinner" /> }));
// Frozen for the same reason as the auth mock: the page's fetch callback is keyed on the
// router, so a fresh object per render re-runs the fetch forever.
const ROUTER = { push: vi.fn() };
// The page keeps the item on screen in the address (`?page=`), so it also reads the pathname
// and the query: those come from the shared in-memory URL, the router stays the frozen one.
vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return { ...mod.fakeFolderRouter.nextNavigationModule(), useRouter: () => ROUTER };
});
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
// One frozen object: a fresh one per render changes `user`'s identity every time, and any
// effect keyed on it then re-runs forever.
const AUTH = { user: { sub: 'u1' }, isAuthenticated: true, isAuthChecking: false };
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => AUTH }));
vi.mock('@/app/workflows/builder/utils/htmlTemplateResolver', () => ({
  resolveTemplateWithData: (html: string) => html,
  resolveHtmlTemplate: (html: string) => html,
}));

const api = vi.hoisted(() => ({ getInterface: vi.fn(), renderInterfaceWithDatasource: vi.fn() }));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getInterface: api.getInterface,
    renderInterfaceWithDatasource: api.renderInterfaceWithDatasource,
  },
}));

import {
  emitInterfaceViewerControlsToggle,
  onInterfaceViewerControls,
} from '@/lib/interfaces/interfaceViewerBus';
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
import InterfaceDetailPage from '../page';

const VIEWER_PATH = '/en/app/interface/i1';

/** The page reads its id through React's `use()`, which needs a settled promise. */
const params = Promise.resolve({ id: 'i1' });

/** What the last render handed the preview. */
const lastPreview = () => preview.props[preview.props.length - 1];

/** Play the frame reporting what it found once it loaded. */
const reportAudio = (hasAudio: boolean) =>
  act(() => {
    (lastPreview().onMediaAudioPresence as (has: boolean) => void)(hasAudio);
  });

beforeEach(() => {
  fakeFolderRouter.reset(VIEWER_PATH);
  preview.props = [];
  api.getInterface.mockResolvedValue({
    id: 'i1',
    tenantId: 't1',
    name: 'Launch page',
    htmlTemplate: '<video src="x.mp4"></video>',
    isPublic: false,
    isActive: true,
  });
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

async function renderViewer() {
  // The page reads its route params through React's `use()`, so it suspends until that promise
  // settles: without a boundary the tree renders nothing at all.
  // Rendering INSIDE act lets the suspended `use()` read settle and the tree commit; outside
  // it the boundary stays on its fallback forever.
  await act(async () => {
    render(
      <React.Suspense fallback={<div data-testid="suspended" />}>
        <InterfaceDetailPage params={params} />
      </React.Suspense>,
    );
  });
  await waitFor(() => expect(screen.getByTestId('interface-preview')).toBeInTheDocument());
}

describe('the page arrives silent', () => {
  it('silences it on the very first render, before anyone can hear it', async () => {
    await renderViewer();

    expect(lastPreview().mediaMuted).toBe(true);
  });

  it('claims the volume rather than leaving the page as authored, which is what earns a control', async () => {
    await renderViewer();

    // Undefined here would mean "play as authored" - the behaviour every surface WITHOUT a
    // sound control keeps.
    expect(lastPreview().mediaMuted).not.toBeUndefined();
  });
});

describe('the control the reader gets in exchange', () => {
  it('tells the header there is nothing to control before the page has said otherwise', async () => {
    // Subscribed BEFORE the page mounts: the first thing it announces is what the header acts
    // on, and a page is silent until its frame reports otherwise.
    const heard: boolean[] = [];
    const stop = onInterfaceViewerControls((s) => heard.push(s.available));

    await renderViewer();
    stop();

    expect(heard[0]).toBe(false);
  });

  it('goes back to offering nothing when the page stops having audio', async () => {
    await renderViewer();
    await reportAudio(true);

    const heard: boolean[] = [];
    const stop = onInterfaceViewerControls((s) => heard.push(s.available));
    await reportAudio(false);
    stop();

    expect(heard).toContain(false);
  });

  it('tells the header there is something to control once the page reports audio', async () => {
    await renderViewer();

    const heard: boolean[] = [];
    const stop = onInterfaceViewerControls((s) => heard.push(s.available));
    await reportAudio(true);
    stop();

    expect(heard).toContain(true);
  });

  it('opens the controls when the header button is pressed', async () => {
    await renderViewer();
    await reportAudio(true);

    act(() => emitInterfaceViewerControlsToggle());

    expect(screen.getByTestId('interface-sound-toggle')).toBeInTheDocument();
  });

  it('closes them when it is pressed again', async () => {
    await renderViewer();
    await reportAudio(true);
    act(() => emitInterfaceViewerControlsToggle());

    act(() => emitInterfaceViewerControlsToggle());

    expect(screen.queryByTestId('interface-sound-toggle')).toBeNull();
  });

  it('gives the sound back when the reader asks, and takes it away again', async () => {
    await renderViewer();
    await reportAudio(true);
    act(() => emitInterfaceViewerControlsToggle());

    act(() => { screen.getByTestId('interface-sound-toggle').click(); });
    await waitFor(() => expect(lastPreview().mediaMuted).toBe(false));

    act(() => { screen.getByTestId('interface-sound-toggle').click(); });
    await waitFor(() => expect(lastPreview().mediaMuted).toBe(true));
  });

  it('stops offering controls once the reader has left the page', async () => {
    // Without this the header would go on showing a button for a page that is gone.
    await renderViewer();
    await reportAudio(true);

    const heard: boolean[] = [];
    const stop = onInterfaceViewerControls((s) => heard.push(s.available));
    cleanup();
    stop();

    expect(heard[heard.length - 1]).toBe(false);
  });

  it('closes on Escape, the way everything else that floats over a page does', async () => {
    await renderViewer();
    await reportAudio(true);
    act(() => emitInterfaceViewerControlsToggle());
    expect(screen.getByTestId('interface-sound-toggle')).toBeInTheDocument();

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });

    expect(screen.queryByTestId('interface-sound-toggle')).toBeNull();
  });

  it('ignores Escape when there is no panel to close', async () => {
    await renderViewer();
    await reportAudio(true);

    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });

    // Nothing to close, and nothing thrown on the way past.
    expect(screen.queryByTestId('interface-sound-toggle')).toBeNull();
  });

  it('does not strand an open panel on a page that turns out to have nothing to control', async () => {
    await renderViewer();
    await reportAudio(true);
    act(() => emitInterfaceViewerControlsToggle());
    expect(screen.getByTestId('interface-sound-toggle')).toBeInTheDocument();

    // The page changed under it (a re-render, an edit) and no longer plays anything.
    await reportAudio(false);

    expect(screen.queryByTestId('interface-sound-toggle')).toBeNull();
  });
});

describe('the item on screen lives in the address', () => {
  /** A datasource-backed interface with `total` items, one per page. */
  const withItems = (total: number) => {
    api.getInterface.mockResolvedValue({
      id: 'i1', tenantId: 't1', name: 'Launch page', htmlTemplate: '<p>{{title}}</p>',
      dataSourceId: 5, isPublic: false, isActive: true,
    });
    api.renderInterfaceWithDatasource.mockResolvedValue({
      htmlTemplate: '<p>{{title}}</p>',
      items: [{ data: { title: 'row' } }],
      pagination: { totalItems: total, totalPages: total },
    });
  };
  /** The two arrows around the "n / total" counter: [older, newer]. */
  const arrows = (counter: string) =>
    Array.from(screen.getByText(counter).parentElement!.querySelectorAll('button'));

  it('opened on ?page=3, asks for the third item first, which is what a reload does', async () => {
    withItems(5);
    fakeFolderRouter.navigate(`${VIEWER_PATH}?page=3`, 'replace');

    await renderViewer();

    await waitFor(() => expect(api.renderInterfaceWithDatasource).toHaveBeenCalled());
    // The address counts from 1, the request from 0. The FIRST request already carries it.
    expect(api.renderInterfaceWithDatasource.mock.calls[0]).toEqual(['i1', { page: 2, size: 1 }]);
  });

  it('writes the item to the address when the reader steps to an older one, and drops it on the newest', async () => {
    withItems(5);
    await renderViewer();
    await waitFor(() => expect(screen.getByText('5 / 5')).toBeInTheDocument());

    await act(async () => arrows('5 / 5')[0].click());

    expect(fakeFolderRouter.search()).toBe('page=2');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('replace');
    await waitFor(() => expect(api.renderInterfaceWithDatasource).toHaveBeenLastCalledWith('i1', { page: 1, size: 1 }));

    await act(async () => arrows('4 / 5')[1].click());
    expect(fakeFolderRouter.search()).toBe('');
  });

  it('lands on the last item when the address asks for one past the end', async () => {
    withItems(5);
    fakeFolderRouter.navigate(`${VIEWER_PATH}?page=9`, 'replace');

    await renderViewer();

    await waitFor(() => expect(fakeFolderRouter.search()).toBe('page=5'));
    await waitFor(() => expect(api.renderInterfaceWithDatasource).toHaveBeenLastCalledWith('i1', { page: 4, size: 1 }));
  });
});
