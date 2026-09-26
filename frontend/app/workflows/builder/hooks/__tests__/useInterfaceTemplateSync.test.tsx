// @vitest-environment jsdom
/**
 * An interface node renders from its own copy of the page, and that copy was filled from
 * the stored interface ONLY when it was empty. An edit made anywhere but this canvas's
 * inspector (the chat agent, the interface page, another tab) never reached the node, and
 * showed only after a reload emptied the copy. The copy now follows the stored content
 * whenever that content changes, except over an unsaved draft.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useInterfaceTemplateSync, type StoredInterfaceTemplate } from '../useInterfaceTemplateSync';

let queryClient: QueryClient;
const wrapper = ({ children }: { children: React.ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

type Props = {
  details: StoredInterfaceTemplate | undefined;
  data: Record<string, any>;
  enabled?: boolean;
};

function setup(initial: Props) {
  const onNodeUpdate = vi.fn();
  const hook = renderHook(
    ({ details, data, enabled = true }: Props) =>
      useInterfaceTemplateSync({
        enabled,
        interfaceId: 'if-1',
        interfaceDetails: details,
        isLoadingInterface: false,
        data,
        onNodeUpdate,
      }),
    { initialProps: initial, wrapper },
  );
  return { ...hook, onNodeUpdate };
}

/** Mounted on a copy equal to the stored page: the first look only records its signature. */
function setupInSync(html: string) {
  const mounted = setup({ details: stored(html), data: nodeData(html) });
  expect(mounted.onNodeUpdate).toHaveBeenCalledTimes(1);
  expect(mounted.onNodeUpdate.mock.calls[0][0].interfaceData).toMatchObject({ editorExpression: html, storedSignatureFor: 'if-1' });
  mounted.onNodeUpdate.mockClear();
  return mounted;
}

const stored = (html: string, css = 'body{}', js = ''): StoredInterfaceTemplate => ({
  htmlTemplate: html, cssTemplate: css, jsTemplate: js, dataSourceId: null,
});
const nodeData = (html: string, css = 'body{}', js = '', extra: Record<string, any> = {}) => ({
  interfaceData: { interfaceId: 'if-1', editorExpression: html, cssTemplate: css, jsTemplate: js },
  ...extra,
});

beforeEach(() => {
  queryClient = new QueryClient();
});

describe('useInterfaceTemplateSync', () => {
  it('regression: an edit stored elsewhere reaches the node without a reload', () => {
    const { rerender, onNodeUpdate } = setupInSync('<p>v1</p>');

    rerender({ details: stored('<p>v2</p>', 'body{color:red}'), data: nodeData('<p>v1</p>') });

    expect(onNodeUpdate).toHaveBeenCalledTimes(1);
    expect(onNodeUpdate.mock.calls[0][0].interfaceData).toMatchObject({
      editorExpression: '<p>v2</p>',
      cssTemplate: 'body{color:red}',
    });
  });

  it('keeps the copy on the first look: the cache may predate an inspector Save', () => {
    const { onNodeUpdate } = setup({ details: stored('<p>before save</p>'), data: nodeData('<p>after save</p>') });

    expect(onNodeUpdate).not.toHaveBeenCalled();
  });

  it('never overwrites an unsaved draft, and follows the next stored change once the draft is gone', () => {
    // A change stored DURING the draft is left to the inspector: its Save wins, and its
    // Cancel restores the current stored page (see InterfaceMappingsColumn).
    const draft = nodeData('<p>my draft</p>', 'body{}', '', { hasUnsavedInterfaceChanges: true });
    const { rerender, onNodeUpdate } = setupInSync('<p>v1</p>');
    rerender({ details: stored('<p>v1</p>'), data: draft });

    rerender({ details: stored('<p>v2</p>'), data: draft });
    expect(onNodeUpdate).not.toHaveBeenCalled();

    // The draft ends (saved as v2): at most the signature is recorded, the copy is untouched.
    rerender({ details: stored('<p>v2</p>'), data: nodeData('<p>v2</p>') });
    for (const [written] of onNodeUpdate.mock.calls) expect(written.interfaceData.editorExpression).toBe('<p>v2</p>');
    onNodeUpdate.mockClear();

    rerender({ details: stored('<p>v3</p>'), data: nodeData('<p>v2</p>') });
    expect(onNodeUpdate).toHaveBeenCalledTimes(1);
    expect(onNodeUpdate.mock.calls[0][0].interfaceData.editorExpression).toBe('<p>v3</p>');
  });

  it('never refills an emptied draft either', () => {
    const { rerender, onNodeUpdate } = setupInSync('<p>v1</p>');

    rerender({ details: stored('<p>v1</p>'), data: nodeData('', 'body{}', '', { hasUnsavedInterfaceChanges: true }) });

    expect(onNodeUpdate).not.toHaveBeenCalled();
  });

  it('fills an empty copy from the stored interface (fresh drop or reload)', () => {
    const { onNodeUpdate } = setup({ details: stored('<p>v1</p>', 'h1{}', 'x()'), data: nodeData('', '', '') });

    expect(onNodeUpdate).toHaveBeenCalledTimes(1);
    expect(onNodeUpdate.mock.calls[0][0].interfaceData).toMatchObject({
      editorExpression: '<p>v1</p>', cssTemplate: 'h1{}', jsTemplate: 'x()',
    });
  });

  it('writes nothing when the new stored content is what the node already holds (its own Save)', () => {
    const { rerender, onNodeUpdate } = setup({ details: stored('<p>v1</p>'), data: nodeData('<p>v2</p>') });

    rerender({ details: stored('<p>v2</p>'), data: nodeData('<p>v2</p>') });

    expect(onNodeUpdate).not.toHaveBeenCalled();
  });

  it('writes nothing while disabled (run canvas)', () => {
    const { rerender, onNodeUpdate } = setup({ details: stored('<p>v1</p>'), data: nodeData(''), enabled: false });

    rerender({ details: stored('<p>v2</p>'), data: nodeData(''), enabled: false });

    expect(onNodeUpdate).not.toHaveBeenCalled();
  });

  it('refetches the stored interface when the agent reports it modified', () => {
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    setup({ details: stored('<p>v1</p>'), data: nodeData('<p>v1</p>') });

    act(() => { window.dispatchEvent(new CustomEvent('interfaceModified')); });

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['interface', 'if-1'] });
  });

  it('regression: a change stored while the node was unmounted (off-screen) is adopted on remount', () => {
    // The canvas virtualises nodes, so a ref alone forgot what the node had adopted.
    const first = setup({ details: stored('<p>v1</p>'), data: nodeData('') });
    const written = first.onNodeUpdate.mock.calls[0][0];
    expect(written.interfaceData.storedSignature).toBeTruthy();
    first.unmount();

    const remounted = setup({ details: stored('<p>v2</p>'), data: written });

    expect(remounted.onNodeUpdate).toHaveBeenCalledTimes(1);
    expect(remounted.onNodeUpdate.mock.calls[0][0].interfaceData.editorExpression).toBe('<p>v2</p>');
  });

  it('a signature recorded for another interface is ignored (first look keeps the copy)', () => {
    const data = nodeData('<p>mine</p>');
    data.interfaceData = { ...data.interfaceData, storedSignature: 'x', storedSignatureFor: 'other-interface' } as any;

    const { onNodeUpdate } = setup({ details: stored('<p>stored</p>'), data });

    expect(onNodeUpdate).not.toHaveBeenCalled();
  });

  it('saving a draft does not flash the stored content that landed during the draft', () => {
    const draft = nodeData('<p>my draft</p>', 'body{}', '', { hasUnsavedInterfaceChanges: true });
    const { rerender, onNodeUpdate } = setupInSync('<p>v1</p>');
    rerender({ details: stored('<p>v1</p>'), data: draft });
    rerender({ details: stored('<p>from agent</p>'), data: draft });

    // The draft is saved: the node holds it, the cache still holds the agent's content.
    rerender({ details: stored('<p>from agent</p>'), data: nodeData('<p>my draft</p>') });
    expect(onNodeUpdate).not.toHaveBeenCalled();

    // The refetch then returns what was saved: nothing to write either.
    rerender({ details: stored('<p>my draft</p>'), data: nodeData('<p>my draft</p>') });
    expect(onNodeUpdate).not.toHaveBeenCalled();
  });

  it('treats an empty CSS/JS on the node and a null one in storage as the same', () => {
    const { rerender, onNodeUpdate } = setup({ details: stored('<p>v1</p>', '', ''), data: nodeData('<p>v2</p>', '', '') });

    rerender({ details: { htmlTemplate: '<p>v2</p>', cssTemplate: null, jsTemplate: null }, data: nodeData('<p>v2</p>', '', '') });

    expect(onNodeUpdate).not.toHaveBeenCalled();
  });
  it('regression: a node dropped already filled (palette) still adopts a change stored while it was off-screen', () => {
    // It arrives with a copy and no signature: the first look must record one on the node,
    // or the remount after scrolling back takes a first look again and keeps the stale copy.
    const first = setupInSync('<p>v1</p>');
    first.rerender({ details: stored('<p>v1</p>'), data: nodeData('<p>v1</p>') });
    const signedData = setup({ details: stored('<p>v1</p>'), data: nodeData('<p>v1</p>') }).onNodeUpdate.mock.calls[0][0];
    first.unmount();

    const remounted = setup({ details: stored('<p>v2</p>'), data: signedData });

    expect(remounted.onNodeUpdate).toHaveBeenCalledTimes(1);
    expect(remounted.onNodeUpdate.mock.calls[0][0].interfaceData.editorExpression).toBe('<p>v2</p>');
  });
  it('re-records the signature when another write in the same commit dropped it (format snap)', () => {
    const first = setupInSync('<p>v1</p>');

    // The snap spread the pre-record data, so the node again carries no signature.
    first.rerender({ details: stored('<p>v1</p>'), data: nodeData('<p>v1</p>') });

    expect(first.onNodeUpdate).toHaveBeenCalledTimes(1);
    expect(first.onNodeUpdate.mock.calls[0][0].interfaceData).toMatchObject({ storedSignatureFor: 'if-1' });
  });

  it('does not write again once the signature is on the node', () => {
    const first = setupInSync('<p>v1</p>');
    const signed = setup({ details: stored('<p>v1</p>'), data: nodeData('<p>v1</p>') }).onNodeUpdate.mock.calls[0][0];

    first.rerender({ details: stored('<p>v1</p>'), data: signed });

    expect(first.onNodeUpdate).not.toHaveBeenCalled();
  });
});
