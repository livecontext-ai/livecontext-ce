/**
 * @vitest-environment jsdom
 *
 * PanelResizeHandle renders a vertical handle on the panel's left edge for the
 * right dock (resizes width, ew-resize) and a horizontal handle on the top edge
 * for the bottom dock (resizes height, ns-resize).
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { PanelResizeHandle } from '../PanelResizeHandle';

afterEach(() => cleanup());

describe('PanelResizeHandle orientation', () => {
  it('right orientation (default): left-edge handle, ew-resize, offset by right', () => {
    const { container } = render(
      <PanelResizeHandle panelWidth={400} isResizing={false} onResizeStart={() => {}} />,
    );
    const handle = container.firstChild as HTMLElement;
    expect(handle.style.cursor).toBe('ew-resize');
    expect(handle.style.right).toBe('398px');
    expect(handle.className).toContain('top-0');
    expect(handle.className).toContain('bottom-0');
  });

  it('bottom orientation: top-edge handle, ns-resize, offset by bottom', () => {
    const { container } = render(
      <PanelResizeHandle panelWidth={300} isResizing={false} onResizeStart={() => {}} orientation="bottom" />,
    );
    const handle = container.firstChild as HTMLElement;
    expect(handle.style.cursor).toBe('ns-resize');
    expect(handle.style.bottom).toBe('298px');
    expect(handle.className).toContain('left-0');
    expect(handle.className).toContain('right-0');
  });

  it('fires onResizeStart on mousedown', () => {
    const onResizeStart = vi.fn();
    const { container } = render(
      <PanelResizeHandle panelWidth={300} isResizing={false} onResizeStart={onResizeStart} orientation="bottom" />,
    );
    fireEvent.mouseDown(container.firstChild as HTMLElement);
    expect(onResizeStart).toHaveBeenCalledOnce();
  });
});
