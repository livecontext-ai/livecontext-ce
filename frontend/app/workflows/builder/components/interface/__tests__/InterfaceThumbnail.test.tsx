// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, act } from '@testing-library/react';

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

// Spy on InterfaceShadowPreview so we can assert exact props + count.
const shadowSpy = vi.fn();
vi.mock('../InterfaceShadowPreview', () => ({
  InterfaceShadowPreview: (props: any) => {
    shadowSpy(props);
    return <div data-testid="shadow" />;
  },
}));

// ResizeObserver: track subscriptions and let tests push a content rect synchronously.
let roInstances: Array<{ cb: ResizeObserverCallback; targets: Element[] }> = [];
class MockResizeObserver {
  cb: ResizeObserverCallback;
  targets: Element[] = [];
  constructor(cb: ResizeObserverCallback) {
    this.cb = cb;
    roInstances.push(this);
  }
  observe(el: Element) { this.targets.push(el); }
  unobserve() {}
  disconnect() {
    roInstances = roInstances.filter(i => i !== this);
  }
}
(globalThis as any).ResizeObserver = MockResizeObserver;

function pushBox(width: number, height: number) {
  const ro = roInstances[roInstances.length - 1];
  if (!ro) return;
  const entry = { contentRect: { width, height } as DOMRectReadOnly } as ResizeObserverEntry;
  ro.cb([entry], ro as unknown as ResizeObserver);
}

function stubBoundingRect(width: number, height: number) {
  Object.defineProperty(HTMLElement.prototype, 'getBoundingClientRect', {
    configurable: true,
    value: function () {
      return { width, height, top: 0, left: 0, right: width, bottom: height, x: 0, y: 0, toJSON() {} } as DOMRect;
    },
  });
}

beforeEach(() => {
  shadowSpy.mockClear();
  roInstances = [];
  stubBoundingRect(0, 0);
});

afterEach(() => {
  cleanup();
});

// Import AFTER mocks so the module picks them up.
import { InterfaceThumbnail } from '../InterfaceThumbnail';

// Virtual viewport constants mirrored from the primitive for readability.
const VV_W = 1280;
const VV_H = 800;

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('InterfaceThumbnail (centralized thumbnail primitive)', () => {
  it('width-fit mode: seeds dimensions synchronously from getBoundingClientRect (no flash)', () => {
    stubBoundingRect(800, 0);
    render(<InterfaceThumbnail htmlTemplate="<h1>hi</h1>" />);
    // Shadow should already have been rendered once on mount because layout effect
    // seeded width=800 synchronously before the first commit.
    expect(shadowSpy).toHaveBeenCalled();
    const props = shadowSpy.mock.calls[0][0];
    expect(props.style.width).toBe(VV_W);
    expect(props.style.height).toBe(VV_H);
  });

  it('width-fit mode: re-renders shadow on ResizeObserver fire', () => {
    stubBoundingRect(0, 0);
    render(<InterfaceThumbnail htmlTemplate="<h1>hi</h1>" />);
    // Zero box: shadow not rendered yet.
    expect(shadowSpy).not.toHaveBeenCalled();

    act(() => { pushBox(1000, 0); });
    expect(shadowSpy).toHaveBeenCalled();
  });

  it('contain mode: scales to fit min(w/1280, h/800) - letterboxed', () => {
    stubBoundingRect(400, 250);
    render(<InterfaceThumbnail htmlTemplate="<h1>hi</h1>" fit="contain" />);
    expect(shadowSpy).toHaveBeenCalled();
    // Shadow always renders at virtual viewport dims regardless of fit mode.
    const props = shadowSpy.mock.calls[0][0];
    expect(props.style.width).toBe(VV_W);
    expect(props.style.height).toBe(VV_H);
  });

  it('contain mode: outer wrapper fills 100% of parent', () => {
    stubBoundingRect(400, 250);
    const { container } = render(
      <InterfaceThumbnail htmlTemplate="<h1>hi</h1>" fit="contain" />
    );
    const outer = container.firstChild as HTMLElement;
    expect(outer.style.width).toBe('100%');
    expect(outer.style.height).toBe('100%');
  });

  it('contain mode: zero-width box renders ref-holder only (no crash)', () => {
    stubBoundingRect(0, 0);
    const { container } = render(
      <InterfaceThumbnail htmlTemplate="<h1>hi</h1>" fit="contain" />
    );
    // No shadow yet - placeholder div is mounted so the observer can fire.
    expect(shadowSpy).not.toHaveBeenCalled();
    expect(container.firstChild).toBeTruthy();
  });

  it('forwards mode + htmlTemplate + resolvedData to shadow unchanged', () => {
    stubBoundingRect(640, 0);
    render(
      <InterfaceThumbnail
        htmlTemplate="<p>{{name|guest}}</p>"
        mode="run"
        resolvedData={{ name: 'Alice' }}
      />
    );
    const props = shadowSpy.mock.calls[0][0];
    expect(props.mode).toBe('run');
    expect(props.htmlTemplate).toBe('<p>{{name|guest}}</p>');
    expect(props.resolvedData).toEqual({ name: 'Alice' });
  });

  it('dropJs strips jsTemplate AND sets removeScripts=true on shadow', () => {
    stubBoundingRect(640, 0);
    render(
      <InterfaceThumbnail
        htmlTemplate="<h1 onclick='evil()'>hi</h1>"
        jsTemplate="alert(1)"
        dropJs
      />
    );
    const props = shadowSpy.mock.calls[0][0];
    expect(props.jsTemplate).toBeUndefined();
    expect(props.removeScripts).toBe(true);
  });

  it('dropJs=false leaves jsTemplate intact and removeScripts falsy', () => {
    stubBoundingRect(640, 0);
    render(
      <InterfaceThumbnail
        htmlTemplate="<h1>hi</h1>"
        jsTemplate="console.log(1)"
      />
    );
    const props = shadowSpy.mock.calls[0][0];
    expect(props.jsTemplate).toBe('console.log(1)');
    expect(props.removeScripts).toBeFalsy();
  });

  it('blank htmlTemplate + emptyLabel: renders label, does NOT mount shadow', () => {
    stubBoundingRect(400, 250);
    const { getByText } = render(
      <InterfaceThumbnail htmlTemplate="   " emptyLabel="No preview" fit="contain" />
    );
    expect(getByText('No preview')).toBeTruthy();
    expect(shadowSpy).not.toHaveBeenCalled();
  });

  it('blank htmlTemplate without emptyLabel renders nothing', () => {
    const { container } = render(<InterfaceThumbnail htmlTemplate="" />);
    expect(container.firstChild).toBeNull();
    expect(shadowSpy).not.toHaveBeenCalled();
  });

  it('width-fit mode: maxHeight clips natural 16:10 height', () => {
    stubBoundingRect(1000, 0);
    const { container } = render(
      <InterfaceThumbnail htmlTemplate="<h1>hi</h1>" maxHeight={400} />
    );
    const outer = container.firstChild as HTMLElement;
    // width=1000 → natural height = 1000 × 800/1280 = 625, clipped to 400.
    expect(outer.style.height).toBe('400px');
  });

  it('width-fit mode: no maxHeight → natural 16:10 height', () => {
    stubBoundingRect(1280, 0);
    const { container } = render(
      <InterfaceThumbnail htmlTemplate="<h1>hi</h1>" />
    );
    const outer = container.firstChild as HTMLElement;
    expect(outer.style.height).toBe('800px');
  });

  it('forwards actionMapping + triggerData to shadow - regression: marketplace card never injected the bridge prefill, leaving textarea + selects blank', () => {
    stubBoundingRect(640, 0);
    const actionMapping = { '#myForm': 'trigger:image_prompt:submit' };
    const triggerData = {
      'trigger:image_prompt': { prompt: 'A futuristic city at sunset', openai_model: 'gpt-image-1-mini' },
    };
    render(
      <InterfaceThumbnail
        htmlTemplate='<form id="myForm"><textarea name="prompt"></textarea></form>'
        mode="run"
        actionMapping={actionMapping}
        triggerData={triggerData}
      />
    );
    const props = shadowSpy.mock.calls[0][0];
    expect(props.actionMapping).toEqual(actionMapping);
    expect(props.triggerData).toEqual(triggerData);
  });

  it('actionMapping + triggerData default to undefined - does not poison the 8 other consumers (FlowNode, InterfacePreviewNode in edit mode, …) that never set them', () => {
    stubBoundingRect(640, 0);
    render(<InterfaceThumbnail htmlTemplate="<h1>hi</h1>" />);
    const props = shadowSpy.mock.calls[0][0];
    expect(props.actionMapping).toBeUndefined();
    expect(props.triggerData).toBeUndefined();
  });
});
