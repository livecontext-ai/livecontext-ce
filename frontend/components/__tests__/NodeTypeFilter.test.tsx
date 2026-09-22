// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { cleanup, render, screen, fireEvent, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';
import { NodeTypeFilter } from '../NodeTypeFilter';

/**
 * The node-type picker. Rendered against the REAL en.json, so a missing or
 * misspelled translation key fails here rather than shipping a raw
 * `nodeTypeFilter.trigger` string into the toolbar.
 *
 * <p>What the tests hold onto: the options come from the facets (never a static
 * list of every node type), the search field narrows them, a ticked option
 * cannot be hidden by that search, and toggling reports the ANY-of selection
 * back to the page.
 */

const FACETS = [
  { value: 'mcp:gmail', count: 4 },
  { value: 'mcp:slack', count: 2 },
  { value: 'core:loop', count: 7 },
  { value: 'trigger:webhook', count: 1 },
];

function renderFilter(props: Partial<React.ComponentProps<typeof NodeTypeFilter>> = {}) {
  const onChange = vi.fn();
  render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <NodeTypeFilter facets={FACETS} value={[]} onChange={onChange} {...props} />
    </NextIntlClientProvider>
  );
  return { onChange };
}

/** Open the picker. Its options only exist once the popover is open. */
function open() {
  fireEvent.click(screen.getByRole('button', { name: 'Node types' }));
}

/** Type into the picker's search field. */
function search(text: string) {
  fireEvent.change(screen.getByRole('textbox', { name: 'Search node types' }), {
    target: { value: text },
  });
}

afterEach(cleanup);

describe('NodeTypeFilter', () => {
  it('offers one option per facet, labelled the way the canvas names the node', () => {
    renderFilter();
    open();

    expect(screen.getByRole('checkbox', { name: /Gmail/ })).toBeInTheDocument();
    // 'While', not 'Loop': the picker must call a node what the canvas calls it.
    expect(screen.getByRole('checkbox', { name: /While/ })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /Webhook/ })).toBeInTheDocument();
  });

  it('groups the options by family', () => {
    renderFilter();
    open();

    expect(screen.getByText('Triggers')).toBeInTheDocument();
    expect(screen.getByText('Integrations')).toBeInTheDocument();
    expect(screen.getByText('Core nodes')).toBeInTheDocument();
  });

  it('shows how many rows are behind each option, so no choice leads to an empty page', () => {
    renderFilter();
    open();

    expect(within(screen.getByRole('checkbox', { name: /Gmail/ })).getByText('4')).toBeInTheDocument();
  });

  it('narrows the options as you type in the search field', () => {
    renderFilter();
    open();

    search('gmail');

    expect(screen.getByRole('checkbox', { name: /Gmail/ })).toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /Slack/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /While/ })).not.toBeInTheDocument();
  });

  it('searches the raw token too, so a whole family can be pulled up by its prefix', () => {
    renderFilter();
    open();

    search('mcp:');

    expect(screen.getByRole('checkbox', { name: /Gmail/ })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /Slack/ })).toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /While/ })).not.toBeInTheDocument();
  });

  it('finds a node by the name the canvas gives it, not only by its stored type', () => {
    // core:loop is labelled "While". Searching "while" has to find it: the label
    // is what the user sees, so it is the word they will type.
    renderFilter();
    open();

    search('while');

    expect(screen.getByRole('checkbox', { name: /While/ })).toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /Gmail/ })).not.toBeInTheDocument();
  });

  it('says so when nothing matches, rather than showing an empty panel', () => {
    renderFilter();
    open();

    search('zzzz');

    expect(screen.getByText('No node type matches your search')).toBeInTheDocument();
  });

  it('keeps a ticked option visible through a search that excludes it', () => {
    // Otherwise a filter could be active with no way to see or undo it.
    renderFilter({ value: ['mcp:gmail'] });
    open();

    search('loop');

    expect(screen.getByRole('checkbox', { name: /Gmail/ })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: /While/ })).toBeInTheDocument();
  });

  it('adds a type to the selection when clicked', () => {
    const { onChange } = renderFilter({ value: ['core:loop'] });
    open();

    fireEvent.click(screen.getByRole('checkbox', { name: /Gmail/ }));

    expect(onChange).toHaveBeenCalledWith(['core:loop', 'mcp:gmail']);
  });

  it('removes a type when its ticked option is clicked again', () => {
    const { onChange } = renderFilter({ value: ['core:loop', 'mcp:gmail'] });
    open();

    fireEvent.click(screen.getByRole('checkbox', { name: /Gmail/ }));

    expect(onChange).toHaveBeenCalledWith(['core:loop']);
  });

  it('clears the whole selection in one click', () => {
    const { onChange } = renderFilter({ value: ['core:loop', 'mcp:gmail'] });
    open();

    fireEvent.click(screen.getByRole('button', { name: 'Clear filter' }));

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it('offers no way to clear when nothing is selected', () => {
    renderFilter();
    open();

    expect(screen.queryByRole('button', { name: 'Clear filter' })).not.toBeInTheDocument();
  });

  it('still offers a selected type the facets no longer report', () => {
    // The last workflow using it was deleted while the filter was on: the option
    // has to remain, or the page stays filtered by something invisible.
    renderFilter({ facets: [{ value: 'core:loop', count: 1 }], value: ['mcp:gmail'] });
    open();

    expect(screen.getByRole('checkbox', { name: /Gmail/ })).toBeChecked();
  });

  it('says the workspace has no node types when there is nothing to offer', () => {
    renderFilter({ facets: [] });
    open();

    expect(screen.getByText('No node types yet')).toBeInTheDocument();
  });
});
