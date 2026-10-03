// @vitest-environment jsdom
/**
 * The settings pill tab bar shows what fits its column.
 *
 * <p>AI Providers has eight tabs on cloud. Its own copy of the bar switched labels on at the `sm`
 * WINDOW width, so up to a 1440px screen "Execution links" and "Your keys" sat past the right
 * edge of the settings column, behind a scroll with no visible bar. The shared bar measures
 * hidden copies against its own width instead: every label, else the active one, else icons.
 */
import React from 'react';
import fs from 'node:fs';
import path from 'node:path';
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { AdaptiveTabBar } from '../AdaptiveTabBar';

const TABS = [
  { id: 'api_key', label: 'API Keys', icon: <svg /> },
  { id: 'models', label: 'Models', icon: <svg /> },
  { id: 'execution_links', label: 'Execution links', icon: <svg /> },
  { id: 'your_keys', label: 'Your keys', icon: <svg /> },
] as const;
type Id = (typeof TABS)[number]['id'];

let barWidth = 1000;
const ALL_LABELS_WIDTH = 760;
const ACTIVE_LABEL_WIDTH = 330;
let observers: Array<() => void> = [];

beforeEach(() => {
  barWidth = 1000;
  observers = [];
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
    return this.dataset.testid === 'bar' ? barWidth : 0;
  });
  const realRect = HTMLElement.prototype.getBoundingClientRect;
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const width = this.dataset.testid === 'bar-measure-all' ? ALL_LABELS_WIDTH
      : this.dataset.testid === 'bar-measure-active' ? ACTIVE_LABEL_WIDTH
        : null;
    return width === null ? realRect.call(this) : ({ width } as DOMRect);
  });
  vi.stubGlobal('ResizeObserver', class {
    constructor(cb: () => void) { observers.push(cb); }
    observe() {}
    disconnect() {}
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function Harness({ initial = 'models' as Id }) {
  const [value, setValue] = React.useState<Id>(initial);
  return <AdaptiveTabBar tabs={TABS} value={value} onChange={setValue} data-testid="bar" />;
}

const tab = (label: string) => within(screen.getByTestId('bar')).getByRole('button', { name: label });
const hidden = (label: string) => within(tab(label)).getByText(label).className.includes('sr-only');

describe('AdaptiveTabBar', () => {
  it('shows every label when they fit, with no redundant tooltip', () => {
    render(<Harness />);
    expect(screen.getByTestId('bar')).toHaveAttribute('data-labels', 'all');
    for (const t of TABS) {
      expect(hidden(t.label)).toBe(false);
      expect(tab(t.label)).not.toHaveAttribute('title');
    }
  });

  it('regression - a column too narrow for every label keeps the last tabs reachable, labelled by the active one only', () => {
    barWidth = 700; // AI Providers at 1280px with the app sidebar: the labelled bar needs more
    render(<Harness />);
    expect(screen.getByTestId('bar')).toHaveAttribute('data-labels', 'active');
    expect(hidden('Models')).toBe(false);
    for (const label of ['API Keys', 'Execution links', 'Your keys']) {
      expect(hidden(label)).toBe(true);
      expect(tab(label)).toHaveAttribute('title', label);
    }
  });

  it('falls back to icons alone when even one label does not fit', () => {
    barWidth = 300;
    render(<Harness />);
    expect(screen.getByTestId('bar')).toHaveAttribute('data-labels', 'none');
    for (const t of TABS) expect(hidden(t.label)).toBe(true);
  });

  it('marks and reports the chosen tab, and the label follows it', () => {
    barWidth = 700;
    render(<Harness />);
    expect(tab('Models')).toHaveAttribute('aria-current', 'true');

    fireEvent.click(tab('Execution links'));

    expect(tab('Execution links')).toHaveAttribute('aria-current', 'true');
    expect(tab('Models')).not.toHaveAttribute('aria-current');
    expect(hidden('Execution links')).toBe(false);
    expect(hidden('Models')).toBe(true);
  });

  it('follows its column when it is resized', () => {
    render(<Harness />);
    barWidth = 700;
    act(() => observers.forEach((cb) => cb()));
    expect(screen.getByTestId('bar')).toHaveAttribute('data-labels', 'active');
    barWidth = 1000;
    act(() => observers.forEach((cb) => cb()));
    expect(screen.getByTestId('bar')).toHaveAttribute('data-labels', 'all');
  });

  it('takes its width from the column in any parent, so it can shrink on a phone', () => {
    // A guard: if the labelled tabs ever set the root's width (a flex parent's `min-width:
    // auto`), the bar would measure itself as always fitting.
    render(<Harness />);
    const root = screen.getByTestId('bar').parentElement!;
    expect(root.className.split(/\s+/)).toEqual(expect.arrayContaining(['min-w-0', 'w-full']));
  });

  it('keeps data-tab-id on each button, which the e2e specs select by', () => {
    render(<Harness />);
    expect(tab('Your keys')).toHaveAttribute('data-tab-id', 'your_keys');
  });
});

describe('the settings pages with many tabs use the shared bar', () => {
  const root = path.resolve(__dirname, '../../..');
  it.each([
    'app/[locale]/app/settings/ai-providers/page.tsx',
    'app/[locale]/app/settings/organization/page.tsx',
    'app/[locale]/app/settings/overview/page.tsx',
    'app/[locale]/app/settings/public-access/components/TriggerTypeTabs.tsx',
  ])('%s has no hand-rolled copy left', (file) => {
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    expect(source).toContain('<AdaptiveTabBar');
    // The copy's signature: a window-breakpoint label and its own slider state.
    expect(source).not.toMatch(/hidden sm:inline whitespace-nowrap|tabSliderStyle|sliderStyle/);
  });
});
