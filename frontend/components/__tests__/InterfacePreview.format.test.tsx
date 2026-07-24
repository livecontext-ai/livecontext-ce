/**
 * @vitest-environment jsdom
 *
 * Pins the format branch of the shared InterfacePreview - the surface behind the fullscreen
 * interface view, the app side panel and the marketplace preview.
 *
 * The trap this guards: the branch renders the interface at its declared viewport and CSS-scales
 * it itself, so it MUST forward autoFit. InterfaceShadowPreview defaults autoFit to true, which
 * injects a second, in-iframe scaler - stacking two scalings on exactly the surfaces this exists
 * to fix, and only for interfaces that declare a format.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import * as React from 'react';

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
(globalThis as unknown as { ResizeObserver: typeof ResizeObserverStub }).ResizeObserver = ResizeObserverStub;

// jsdom computes no layout: give every element a real box so the letterbox measures non-zero.
// 400x600 against the 1080x1920 "vertical" preset gives contain scale = min(0.37, 0.3125).
vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(
  () => ({ width: 400, height: 600, top: 0, left: 0, right: 400, bottom: 600, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect,
);

const shadowProps: Record<string, unknown>[] = [];
vi.mock('@/app/workflows/builder/components/interface/InterfaceShadowPreview', () => ({
  InterfaceShadowPreview: (props: Record<string, unknown>) => {
    shadowProps.push(props);
    return <div data-testid="shadow-stub" />;
  },
}));

import { InterfacePreview } from '../InterfacePreview';

const HTML = '<div>page</div>';

afterEach(() => {
  cleanup();
  shadowProps.length = 0;
});

describe('InterfacePreview - declared format', () => {
  it('forwards autoFit={false} so the caller does not get a second, in-iframe scaler', () => {
    render(<InterfacePreview htmlTemplate={HTML} format="vertical" autoFit={false} />);

    expect(shadowProps).toHaveLength(1);
    expect(shadowProps[0].autoFit).toBe(false);
  });

  it('renders the interface at the format viewport exactly (1080x1920), not the container size', () => {
    render(<InterfacePreview htmlTemplate={HTML} format="vertical" autoFit={false} />);

    expect(shadowProps[0].style).toMatchObject({ width: 1080, height: 1920 });
  });

  it('resolves a custom WxH the same way', () => {
    render(<InterfacePreview htmlTemplate={HTML} format="800x400" autoFit={false} />);

    expect(shadowProps[0].style).toMatchObject({ width: 800, height: 400 });
  });

  it('keeps the native path untouched when the interface declares no format', () => {
    // The historical behaviour: no viewport wrapper, autoFit forwarded as given.
    render(<InterfacePreview htmlTemplate={HTML} autoFit={false} className="w-full h-full" />);

    expect(shadowProps[0].autoFit).toBe(false);
    expect(shadowProps[0].style).toBeUndefined();
  });

  it('treats an unresolvable format as no format (native path), never breaking the render', () => {
    render(<InterfacePreview htmlTemplate={HTML} format="garbage" autoFit={false} className="w-full h-full" />);

    expect(shadowProps[0].style).toBeUndefined();
  });

  it('centres the letterboxed frame in a NON-flex container', () => {
    // `items-center justify-center` are no-ops unless the container is itself a flex box. Without
    // it the frame pins to the top-left of the panel instead of sitting centred - the "please
    // centre the vertical view" complaint, most visible on a narrow vertical interface.
    const { container } = render(
      <InterfacePreview htmlTemplate={HTML} format="vertical" autoFit={false} className="h-full" />,
    );
    const outer = container.firstChild as HTMLElement;

    expect(outer.className).toContain('flex');
    expect(outer.className).toContain('items-center');
    expect(outer.className).toContain('justify-center');
  });

  // Passes on the pre-change code too: the flex-1 path was already centred. It is a forward guard
  // for the path that was NOT broken, not a second data point for the fix above.
  it('still centres when the consumer drives a flex layout (flex-1)', () => {
    const { container } = render(
      <InterfacePreview htmlTemplate={HTML} format="vertical" autoFit={false} className="flex-1" />,
    );
    const outer = container.firstChild as HTMLElement;

    expect(outer.className).toContain('items-center');
    expect(outer.className).toContain('justify-center');
    expect(outer.className).toContain('flex-1');
  });
});
