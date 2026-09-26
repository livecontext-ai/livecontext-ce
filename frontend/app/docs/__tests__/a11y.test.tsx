// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent, within, waitFor, act } from '@testing-library/react';

const nav = vi.hoisted(() => ({ path: '/' }));
vi.mock('next/navigation', () => ({ usePathname: () => nav.path }));
vi.mock('next/link', () => {
  const React = require('react');
  return {
    default: ({ href, children, ...rest }: { href: unknown; children: React.ReactNode }) =>
      // preventDefault: jsdom cannot navigate, and following the link is not under test.
      React.createElement('a', { href: typeof href === 'string' ? href : '#', ...rest, onClick: (e: { preventDefault: () => void }) => { e.preventDefault(); (rest as { onClick?: (e: unknown) => void }).onClick?.(e); } }, children),
  };
});
vi.mock('@/components/landing/LandingThemeProvider', () => ({ useLandingTheme: () => ({ theme: 'light' }) }));

import { Callout } from '../_components/Callout';
import { CodeBlock } from '../_components/CodeBlock';
import { DocsTable } from '../_components/DocsTable';
import { Steps, Step } from '../_components/Steps';
import { DocsNav } from '../_components/DocsNav';
import { DocsMobileNav } from '../_components/DocsMobileNav';
import { DocsToc } from '../_components/DocsToc';
import { DocsPrevNext } from '../_components/DocsPrevNext';
import { docsStyles } from '../_components/docsStyles';
import { contrastRatio } from '../_components/accessibleCodeTheme';
import { filterDocsNav, DOCS_NAV, DOCS_PAGES } from '../_nav';

const hex = (h: string) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16)) as [number, number, number];

const originalScrollIntoView = Element.prototype.scrollIntoView;

afterEach(() => {
  cleanup();
  // Restore every global a test may have replaced, even when its assertions failed.
  vi.unstubAllGlobals();
  vi.useRealTimers();
  Element.prototype.scrollIntoView = originalScrollIntoView;
  nav.path = '/';
  window.history.replaceState(null, '', '#');
  // Undo any overflow simulation.
  delete (HTMLElement.prototype as { scrollWidth?: number }).scrollWidth;
  document.body.style.overflow = '';
});

/** jsdom has no layout: make every element report content wider than its box. */
function simulateOverflow() {
  Object.defineProperty(HTMLElement.prototype, 'scrollWidth', { configurable: true, get: () => 999 });
}

describe('Callout', () => {
  it('names its kind in visible text, not only through colour and icon', () => {
    render(<Callout variant="warn">Loops have two outputs.</Callout>);
    const note = screen.getByRole('note');
    expect(within(note).getByText('Warning')).toBeInTheDocument();
    expect(note).toHaveTextContent('Loops have two outputs.');
  });

  it('defaults the label per variant and accepts a custom title', () => {
    render(
      <>
        <Callout>a</Callout>
        <Callout variant="tip">b</Callout>
        <Callout variant="info" title="Community Edition">c</Callout>
      </>,
    );
    expect(screen.getByText('Note')).toBeInTheDocument();
    expect(screen.getByText('Tip')).toBeInTheDocument();
    expect(screen.getByText('Community Edition')).toBeInTheDocument();
  });
});

describe('DocsTable', () => {
  it('marks header cells as column headers', () => {
    render(<DocsTable head={['Prefix', 'Kind']} rows={[['trigger:', 'Entry point']]} />);
    expect(screen.getAllByRole('columnheader')).toHaveLength(2);
    expect(screen.getByRole('columnheader', { name: 'Prefix' })).toHaveAttribute('scope', 'col');
  });

  it('stays a plain table (no extra landmark, no useless Tab stop) when it fits', () => {
    const { container } = render(<DocsTable head={['Prefix', 'Kind']} rows={[['core:', 'Control']]} />);
    expect(screen.queryByRole('region')).toBeNull();
    expect(container.querySelector('.docs-table-wrap')).not.toHaveAttribute('tabindex');
  });

  it('becomes a focusable region named after its headers when it scrolls sideways', async () => {
    simulateOverflow();
    render(<DocsTable head={['Prefix', 'Kind']} rows={[['trigger:', 'Entry point']]} />);
    const region = await screen.findByRole('region', { name: 'Table: Prefix, Kind' });
    expect(region).toHaveAttribute('tabindex', '0');
  });

  it('uses the caption as the accessible name of the table and of its scroll region', async () => {
    simulateOverflow();
    render(<DocsTable caption="Node prefixes" head={['Prefix']} rows={[['core:']]} />);
    expect(screen.getByRole('table', { name: 'Node prefixes' })).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'Node prefixes' })).toBeInTheDocument();
  });

  it('renders row headers for key/value tables and names an empty corner header', () => {
    render(<DocsTable rowHeaders head={['', 'Cloud']} rows={[['Hosting', 'Managed']]} />);
    expect(screen.getByRole('rowheader', { name: 'Hosting' })).toHaveAttribute('scope', 'row');
    expect(screen.getAllByRole('columnheader')[0]).toHaveTextContent('Item');
  });
});

describe('CodeBlock', () => {
  it('leaves a code block that fits out of the Tab order', () => {
    const { container } = render(<CodeBlock language="bash">{'npx livecontext@latest'}</CodeBlock>);
    expect(container.querySelector('pre')).not.toHaveAttribute('tabindex');
    expect(screen.queryByRole('region')).toBeNull();
  });

  it('lets the keyboard reach a <pre> that scrolls, as a labelled region', async () => {
    simulateOverflow();
    const { container } = render(<CodeBlock language="bash" title="Install">{'npx livecontext@latest'}</CodeBlock>);
    const region = await screen.findByRole('region', { name: 'Code example: Install' });
    expect(region.tagName).toBe('PRE');
    expect(container.querySelector('pre')).toHaveAttribute('tabindex', '0');
  });

  it('announces a successful copy once, through a polite live region, with a stable button name', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    render(<CodeBlock>{'echo hi'}</CodeBlock>);
    const button = screen.getByRole('button', { name: 'Copy code' });
    fireEvent.click(button);
    expect(await screen.findByText('Code copied to clipboard')).toBeInTheDocument();
    expect(writeText).toHaveBeenCalledWith('echo hi');
    // The button keeps its name, so the success is not announced a second time.
    expect(screen.getByRole('button', { name: 'Copy code' })).toBe(button);
  });
});

describe('CodeBlock repeat copy', () => {
  it('announces a second copy again, by clearing the message before setting it', async () => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
    render(<CodeBlock>{'echo hi'}</CodeBlock>);
    const button = screen.getByRole('button', { name: 'Copy code' });
    fireEvent.click(button);
    expect(await screen.findByText('Code copied to clipboard')).toBeInTheDocument();
    fireEvent.click(button);
    // The live region empties first, then says it again: a real change to announce.
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(''));
    expect(await screen.findByText('Code copied to clipboard')).toBeInTheDocument();
  });
});

describe('CodeBlock timers', () => {
  it('leaves no pending timer behind when it unmounts in the middle of a copy', async () => {
    vi.useFakeTimers();
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
    const { unmount } = render(<CodeBlock>{'echo hi'}</CodeBlock>);
    fireEvent.click(screen.getByRole('button', { name: 'Copy code' }));
    await act(async () => {}); // let the clipboard promise resolve and the timer be set
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('ScrollRegion', () => {
  it('goes back to a plain wrapper when a resize removes the overflow', async () => {
    let resize: () => void = () => {};
    vi.stubGlobal('ResizeObserver', class {
      constructor(cb: () => void) { resize = cb; }
      observe() {}
      disconnect() {}
    });
    simulateOverflow();
    render(<DocsTable caption="Wide table" head={['A']} rows={[['1']]} />);
    expect(await screen.findByRole('region', { name: 'Wide table' })).toBeInTheDocument();
    delete (HTMLElement.prototype as { scrollWidth?: number }).scrollWidth;
    act(() => resize());
    expect(screen.queryByRole('region')).toBeNull();
    vi.unstubAllGlobals();
  });
});

describe('DocsToc', () => {
  function renderArticle() {
    return render(
      <>
        <div className="docs-prose">
          <h2>First section</h2>
          <h3>A detail</h3>
          <h2 id="second">Second section</h2>
        </div>
        <DocsToc />
      </>,
    );
  }

  it('marks the section in view with aria-current="location"', () => {
    let fire: (entries: Partial<IntersectionObserverEntry>[]) => void = () => {};
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(cb: (e: Partial<IntersectionObserverEntry>[]) => void) {
          fire = cb;
        }
        observe() {}
        disconnect() {}
      },
    );
    nav.path = '/runs';
    renderArticle();
    const second = document.getElementById('second')!;
    act(() => fire([{ isIntersecting: true, target: second, boundingClientRect: { top: 10 } as DOMRectReadOnly }]));
    expect(screen.getByRole('link', { name: 'Second section' })).toHaveAttribute('aria-current', 'location');
    expect(screen.getByRole('link', { name: 'First section' })).not.toHaveAttribute('aria-current');
    vi.unstubAllGlobals();
  });

  it('rebuilds itself from the new page on a client-side navigation, with no stale highlight', () => {
    let fire: (entries: Partial<IntersectionObserverEntry>[]) => void = () => {};
    vi.stubGlobal('IntersectionObserver', class {
      constructor(cb: (e: Partial<IntersectionObserverEntry>[]) => void) { fire = cb; }
      observe() {}
      disconnect() {}
    });
    nav.path = '/runs';
    const page = (a: string, b: string) => (
      <>
        <div className="docs-prose">
          <h2 id="shared">{a}</h2>
          <h2>{b}</h2>
        </div>
        <DocsToc />
      </>
    );
    const { rerender } = render(page('Run history', 'Statuses'));
    act(() => fire([{ isIntersecting: true, target: document.getElementById('shared')!, boundingClientRect: { top: 1 } as DOMRectReadOnly }]));
    expect(screen.getByRole('link', { name: 'Run history' })).toHaveAttribute('aria-current', 'location');

    nav.path = '/agents';
    rerender(page('Create an agent', 'Tools'));
    expect(screen.queryByRole('link', { name: 'Run history' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Create an agent' })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('link', { name: 'Tools' })).toBeInTheDocument();
  });

  it('still renders the table of contents when the URL fragment is malformed', () => {
    vi.stubGlobal('IntersectionObserver', class { observe() {} disconnect() {} });
    window.history.replaceState(null, '', '#%E0%A4%A');
    // The URL really carries the malformed sequence that makes decodeURIComponent throw.
    expect(window.location.hash).toBe('#%E0%A4%A');
    expect(() => decodeURIComponent(window.location.hash.slice(1))).toThrow(URIError);
    renderArticle();
    expect(screen.getByRole('navigation', { name: 'On this page' })).toBeInTheDocument();
  });

  it('renders no landmark at all on a page with fewer than two headings', () => {
    vi.stubGlobal('IntersectionObserver', class { observe() {} disconnect() {} });
    render(
      <>
        <div className="docs-prose"><h2>Only one</h2></div>
        <DocsToc />
      </>,
    );
    expect(screen.queryByRole('navigation', { name: 'On this page' })).toBeNull();
    vi.unstubAllGlobals();
  });

  it('never gives a heading a generated id that is already used elsewhere on the page', () => {
    vi.stubGlobal('IntersectionObserver', class { observe() {} disconnect() {} });
    render(
      <>
        <div className="docs-prose">
          <h2>Errors</h2>
          <h2>Details</h2>
          <p id="errors">An anchor the prose links to.</p>
        </div>
        <DocsToc />
      </>,
    );
    const ids = Array.from(document.querySelectorAll('[id]'), (el) => el.id);
    expect(new Set(ids).size).toBe(ids.length);
    vi.unstubAllGlobals();
  });

  it('scrolls to a deep-linked #section once heading ids exist (the browser could not, before hydration)', () => {
    vi.stubGlobal('IntersectionObserver', class { observe() {} disconnect() {} });
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    window.history.replaceState(null, '', '#first-section');
    renderArticle();
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(document.getElementById('first-section'));
    window.history.replaceState(null, '', '#');
    vi.unstubAllGlobals();
  });
});

describe('docs CSS', () => {
  it('underlines every article link, so links are not told apart by colour alone (WCAG 1.4.1)', () => {
    expect(docsStyles).toMatch(/\.landing-root \.docs-prose a \{\s*text-decoration: underline;/);
  });

  it('draws a visible focus ring on every interactive element (WCAG 2.4.7)', () => {
    for (const sel of ['a:focus-visible', 'button:focus-visible', 'input:focus-visible', '[tabindex="0"]:focus-visible']) {
      expect(docsStyles).toContain(`.landing-root .docs-layout ${sel}`);
    }
    expect(docsStyles).toMatch(/outline: 2px solid var\(--expression-color\)/);
  });

  // The ratios quoted in docsStyles.ts, recomputed from the tokens it actually sets.
  it.each([
    ['light muted text on the tertiary surface', 'light', '--text-muted', '#eceff3'],
    ['light muted text on the footer surface', 'light', '--text-muted', '#f5f6f8'],
    ['dark muted text on the tertiary surface', 'dark', '--text-muted', '#2a2925'],
    ['dark muted text on the page', 'dark', '--text-muted', '#171614'],
  ])('%s reaches 4.5:1', (_label, theme, token, background) => {
    const block = theme === 'light' ? docsStyles.match(/\.landing-root \{([^}]*)\}/)![1] : docsStyles.match(/\.landing-root\.dark \{([^}]*)\}/)![1];
    const value = block.match(new RegExp(`${token}: (#[0-9a-f]{6})`))![1];
    expect(contrastRatio(hex(value), hex(background))).toBeGreaterThanOrEqual(4.5);
  });

  it.each([
    ['light', '--docs-tip', '#eceff3'],
    ['light', '--docs-warn', '#eceff3'],
    ['dark', '--docs-tip', '#2a2925'],
    ['dark', '--docs-warn', '#2a2925'],
  ])('%s %s callout accent reaches 3:1 as a graphical object (WCAG 1.4.11)', (theme, token, background) => {
    const block = theme === 'light' ? docsStyles.match(/\.landing-root \{([^}]*)\}/)![1] : docsStyles.match(/\.landing-root\.dark \{([^}]*)\}/)![1];
    const value = block.match(new RegExp(`${token}: (#[0-9a-f]{6})`))![1];
    expect(contrastRatio(hex(value), hex(background))).toBeGreaterThanOrEqual(3);
  });
});

describe('DocsPrevNext', () => {
  it('links the previous and next pages in reading order', () => {
    nav.path = '/runs';
    render(<DocsPrevNext />);
    const pager = screen.getByRole('navigation', { name: 'Previous and next page' });
    expect(within(pager).getByRole('link', { name: /Previous\s*Interfaces & apps/ })).toHaveAttribute('href', '/interfaces');
    expect(within(pager).getByRole('link', { name: /Next\s*Agents/ })).toHaveAttribute('href', '/agents');
  });

  it('shows only a next link on the first page and only a previous link on the last one', () => {
    nav.path = '/';
    const { unmount } = render(<DocsPrevNext />);
    expect(screen.queryByRole('link', { name: /Previous/ })).toBeNull();
    expect(screen.getByRole('link', { name: /Next/ })).toBeInTheDocument();
    unmount();
    nav.path = DOCS_PAGES[DOCS_PAGES.length - 1].href;
    render(<DocsPrevNext />);
    expect(screen.queryByRole('link', { name: /Next/ })).toBeNull();
    expect(screen.getByRole('link', { name: /Previous/ })).toBeInTheDocument();
  });
});

describe('Steps', () => {
  it('is an ordered list whose decorative numbers are hidden from assistive tech', () => {
    const { container } = render(
      <Steps>
        <Step n={1} title="Describe the job" />
        <Step n={2} title="Run it" />
      </Steps>,
    );
    const list = screen.getByRole('list');
    expect(list.tagName).toBe('OL');
    expect(within(list).getAllByRole('listitem')).toHaveLength(2);
    container.querySelectorAll('.docs-step-num').forEach((el) => expect(el).toHaveAttribute('aria-hidden', 'true'));
  });
});

describe('filterDocsNav', () => {
  it('returns the whole IA for an empty query', () => {
    expect(filterDocsNav('  ')).toBe(DOCS_NAV);
  });

  it('matches page keywords, not only titles', () => {
    const fixture = [
      { title: 'Build', icon: DOCS_NAV[0].icon, items: [{ title: 'Triggers', href: '/triggers', keywords: ['webhook', 'cron'] }, { title: 'Chat', href: '/chat' }] },
    ];
    const result = filterDocsNav('WEBHOOK', fixture);
    expect(result).toHaveLength(1);
    expect(result[0].items.map((i) => i.title)).toEqual(['Triggers']);
  });

  it('finds real pages by topic through the shipped keywords', () => {
    const titles = filterDocsNav('telegram').flatMap((s) => s.items.map((i) => i.title));
    expect(titles).toContain('Chat channels');
  });

  it('drops sections left without any match', () => {
    expect(filterDocsNav('zzz-no-such-page')).toEqual([]);
  });
});

describe('DocsNav', () => {
  it('announces the number of matching pages while filtering', () => {
    nav.path = '/';
    render(<DocsNav />);
    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('');
    fireEvent.change(screen.getByRole('searchbox', { name: 'Filter documentation' }), { target: { value: 'marketplace' } });
    expect(status).toHaveTextContent(/1 page matches/);
  });

  it('says "No matches." and announces zero when nothing matches the filter', () => {
    nav.path = '/';
    render(<DocsNav />);
    fireEvent.change(screen.getByRole('searchbox', { name: 'Filter documentation' }), { target: { value: 'zzz-nothing' } });
    expect(screen.getByText('No matches.')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('0 pages match');
    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });

  it('groups links in labelled lists', () => {
    nav.path = '/';
    render(<DocsNav />);
    expect(screen.getByRole('list', { name: /Get started/ })).toBeInTheDocument();
  });

  it('keeps every id unique when the sidebar and the mobile drawer render together', () => {
    nav.path = '/';
    const { container } = render(
      <>
        <DocsNav />
        <DocsMobileNav />
      </>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Menu' }));
    const ids = Array.from(container.querySelectorAll('[id]')).map((el) => el.id);
    expect(new Set(ids).size).toBe(ids.length);
    // Each copy's search box points at its OWN status line.
    const drawer = screen.getByRole('dialog');
    const input = within(drawer).getByRole('searchbox');
    const status = document.getElementById(input.getAttribute('aria-describedby')!);
    expect(drawer.contains(status)).toBe(true);
  });
});

describe('DocsMobileNav', () => {
  it('moves focus into the drawer, keeps Tab inside it both ways, and returns focus on Escape', () => {
    nav.path = '/';
    render(<DocsMobileNav />);
    const toggle = screen.getByRole('button', { name: 'Menu' });
    fireEvent.click(toggle);

    const dialog = screen.getByRole('dialog', { name: 'Documentation menu' });
    expect(toggle).toHaveAttribute('aria-controls', dialog.id);
    const close = within(dialog).getByRole('button', { name: 'Close menu' });
    expect(close).toHaveFocus();

    // Shift+Tab from the first element wraps to the last one.
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    const last = document.activeElement as HTMLElement;
    expect(dialog.contains(last)).toBe(true);
    expect(last).not.toBe(close);
    // Tab from the last element wraps back to the first one.
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(close).toHaveFocus();

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(toggle).toHaveFocus();
  });

  it('locks page scroll while open and restores it on close', () => {
    nav.path = '/';
    document.body.style.overflow = 'auto';
    render(<DocsMobileNav />);
    fireEvent.click(screen.getByRole('button', { name: 'Menu' }));
    expect(document.body.style.overflow).toBe('hidden');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(document.body.style.overflow).toBe('auto');
  });

  it('closes when the backdrop is clicked and returns focus to the menu button', () => {
    nav.path = '/';
    const { container } = render(<DocsMobileNav />);
    const toggle = screen.getByRole('button', { name: 'Menu' });
    fireEvent.click(toggle);
    fireEvent.click(container.querySelector('.docs-drawer-backdrop')!);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(toggle).toHaveFocus();
  });

  it('closes after a link is followed and moves focus to the article, not back to the menu button', async () => {
    nav.path = '/';
    render(
      <>
        <DocsMobileNav />
        <div id="docs-article" tabIndex={-1}>Article</div>
      </>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Menu' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('link', { name: 'Agents' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => expect(document.getElementById('docs-article')).toHaveFocus());
  });
});
