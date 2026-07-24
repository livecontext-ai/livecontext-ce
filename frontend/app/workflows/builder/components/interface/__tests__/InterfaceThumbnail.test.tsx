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

  it('contain mode: centres the letterboxed frame on BOTH axes', () => {
    // In contain mode the whole interface fits by construction, so top-aligning it dumps the
    // entire letterbox margin at the bottom. Most visible on a vertical interface, which is
    // narrow and leaves a lot of empty box around it.
    stubBoundingRect(400, 250);
    const { container } = render(<InterfaceThumbnail htmlTemplate="<h1>hi</h1>" fit="contain" />);
    const outer = container.firstChild as HTMLElement;
    expect(outer.className).toContain('items-center');
    expect(outer.className).toContain('justify-center');
    expect(outer.className).not.toContain('items-start');
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

  it('contain mode: frameClassName sizes the exact-format frame, frameStyle paints an OVERLAY above the content', () => {
    // The frame is the ONLY element whose box is exactly the interface's declared format,
    // so a caller decorating it (rounded clipping, status ring) hugs the real shape even
    // when the parent box is letterboxed. The ring must be an overlay ABOVE the content:
    // painted on the frame element itself it sits below the iframe (CSS paint order) and
    // vanishes over any opaque interface background; painted outward it gets clipped by
    // the overflow-hidden ancestors. Both regressions were caught by audit/e2e.
    stubBoundingRect(400, 250);
    const { container } = render(
      <InterfaceThumbnail
        htmlTemplate="<h1>hi</h1>"
        fit="contain"
        frameClassName="ring-frame"
        frameStyle={{ boxShadow: 'inset 0 0 0 2px red' }}
      />
    );
    const frame = container.querySelector('.ring-frame') as HTMLElement;
    expect(frame).toBeTruthy();
    // 1280x800 contained in 400x250 -> scale 0.3125 -> the frame IS 400x250.
    expect(frame.style.width).toBe('400px');
    expect(frame.style.height).toBe('250px');
    const overlay = frame.querySelector('[data-testid="frame-ring-overlay"]') as HTMLElement;
    expect(overlay).toBeTruthy();
    expect(overlay.style.boxShadow).toBe('inset 0 0 0 2px red');
    // Painted ABOVE the content: the overlay is the frame's LAST child (after the
    // scaled content wrapper), absolutely positioned, and inert to the pointer.
    expect(frame.lastElementChild).toBe(overlay);
    expect(overlay.style.position).toBe('absolute');
    expect(overlay.style.pointerEvents).toBe('none');
    expect(overlay.style.borderRadius).toBe('inherit');
  });

  it('contain mode: no ring overlay is mounted when frameStyle is absent', () => {
    stubBoundingRect(400, 250);
    const { container } = render(
      <InterfaceThumbnail htmlTemplate="<h1>hi</h1>" fit="contain" frameClassName="ring-frame" />
    );
    expect(container.querySelector('[data-testid="frame-ring-overlay"]')).toBeNull();
  });

  it('contain mode: a portrait viewport frame hugs the format inside a landscape box', () => {
    stubBoundingRect(400, 250);
    const { container } = render(
      <InterfaceThumbnail
        htmlTemplate="<h1>hi</h1>"
        fit="contain"
        viewport={{ width: 1080, height: 1920 }}
        frameClassName="ring-frame"
      />
    );
    const frame = container.querySelector('.ring-frame') as HTMLElement;
    // scale = min(400/1080, 250/1920) = 0.13020833 -> ~140.6 x 250
    expect(parseFloat(frame.style.width)).toBeCloseTo(1080 * (250 / 1920), 3);
    expect(parseFloat(frame.style.height)).toBeCloseTo(250, 6);
  });

  it('width-fit mode ignores frameClassName/frameStyle (documented contain-only contract)', () => {
    stubBoundingRect(640, 0);
    const { container } = render(
      <InterfaceThumbnail htmlTemplate="<h1>hi</h1>" frameClassName="ring-frame" frameStyle={{ boxShadow: '0 0 0 2px red' }} />
    );
    expect(container.querySelector('.ring-frame')).toBeNull();
  });

  it('actionMapping + triggerData default to undefined - does not poison the 8 other consumers (FlowNode, InterfacePreviewNode in edit mode, …) that never set them', () => {
    stubBoundingRect(640, 0);
    render(<InterfaceThumbnail htmlTemplate="<h1>hi</h1>" />);
    const props = shadowSpy.mock.calls[0][0];
    expect(props.actionMapping).toBeUndefined();
    expect(props.triggerData).toBeUndefined();
  });
});
