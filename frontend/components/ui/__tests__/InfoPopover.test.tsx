// @vitest-environment jsdom
/**
 * The app's one "i": what it must do, and what it must never do again.
 *
 * <p>Before it existed, an info icon opened on HOVER in some places (Radix tooltips, a CSS
 * `group-hover`) and on CLICK in others, and several click versions were hand-rolled
 * portals at z-[9998]/z-[9999] that opened BEHIND the modal hosting them. Each test below
 * pins one of those failure modes on the shared component, so a regression shows up here
 * rather than as an icon that silently shows nothing.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render as rtlRender, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import { InfoPopover } from '../info-popover';

/** The real catalogue: the accessible name is a message, and a missing key must fail here. */
const Intl = ({ children, locale = 'en' }: { children: React.ReactNode; locale?: string }) => (
  <NextIntlClientProvider locale={locale} messages={locale === 'fr' ? fr : en}>{children}</NextIntlClientProvider>
);
const render = (ui: React.ReactElement) => rtlRender(ui, { wrapper: Intl });

beforeAll(() => {
  // Radix positioning (@floating-ui) needs ResizeObserver, absent from jsdom.
  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal('ResizeObserver', ResizeObserverStub);
});

afterEach(() => cleanup());

const trigger = (subject = 'Temperature') => screen.getByRole('button', { name: `About ${subject}` });
const panel = () => screen.queryByRole('dialog');

/**
 * Radix opens on `pointerdown` for a mouse and on `click` for a keyboard / assistive tech,
 * depending on the primitive. A popover trigger toggles on `click`, which is what a real
 * mouse click, a tap and Enter/Space all end in.
 */
function click(el: HTMLElement) {
  act(() => {
    fireEvent.pointerDown(el);
    fireEvent.mouseDown(el);
    fireEvent.pointerUp(el);
    fireEvent.mouseUp(el);
    fireEvent.click(el);
  });
}

describe('InfoPopover', () => {
  it('renders a named icon button and keeps the explanation out of the DOM until asked', () => {
    render(<InfoPopover label="Temperature">Higher is more creative.</InfoPopover>);

    expect(trigger()).toHaveAttribute('type', 'button');
    expect(screen.queryByText('Higher is more creative.')).toBeNull();
  });

  it('does NOT open on hover or focus: the norm is click, a hover "i" is unreachable on touch', () => {
    render(<InfoPopover label="Temperature">Higher is more creative.</InfoPopover>);

    act(() => {
      fireEvent.pointerEnter(trigger());
      fireEvent.mouseEnter(trigger());
      fireEvent.mouseOver(trigger());
      fireEvent.focus(trigger());
    });

    expect(panel()).toBeNull();
    expect(screen.queryByText('Higher is more creative.')).toBeNull();
  });

  it('opens on click, and a second click on the same "i" closes it', () => {
    render(<InfoPopover label="Temperature">Higher is more creative.</InfoPopover>);

    click(trigger());
    expect(panel()).toHaveTextContent('Higher is more creative.');
    expect(trigger()).toHaveAttribute('aria-expanded', 'true');

    click(trigger());
    expect(panel()).toBeNull();
  });

  it('closes on Escape', () => {
    render(<InfoPopover label="Temperature">Higher is more creative.</InfoPopover>);
    click(trigger());
    expect(panel()).not.toBeNull();

    act(() => {
      fireEvent.keyDown(panel()!, { key: 'Escape' });
    });
    expect(panel()).toBeNull();
  });

  it('paints above every modal / select layer it is opened from, and under the tooltip layer', () => {
    // The regression: portalled to <body>, its z-index competes with the whole page. The
    // hand-rolled builder popovers (z-[9999]) opened under the share modal, and the stock
    // popover layer (z-[64]) sits under every dialog. 100000 is the plan-comparison dialog
    // and the pickers' SelectContent; 100002 is the tooltip layer, which must stay ABOVE an
    // info panel so a badge tooltip inside one is still visible.
    render(<InfoPopover label="Temperature">Higher is more creative.</InfoPopover>);
    click(trigger());

    const classes = panel()!.className;
    const z = Number(/(?:^|\s)z-\[(\d+)\]/.exec(classes)?.[1] ?? 0);
    expect(z).toBeGreaterThan(100000);
    expect(z).toBeLessThan(100002);
    expect(classes).not.toMatch(/(?:^|\s)z-(50|\[64\]|\[9999\])(?:\s|$)/);
  });

  it('never lets a click on the "i" or inside its panel reach a clickable host', () => {
    // Hosts are often clickable: a card that opens its resource, a collapsible header, a
    // hand-rolled modal that closes on an outside mousedown. The panel is portalled, but
    // React still bubbles its events through the component tree.
    const hostClick = vi.fn();
    const hostMouseDown = vi.fn();
    render(
      <div onClick={hostClick} onMouseDown={hostMouseDown}>
        <InfoPopover label="Temperature">
          <span>Higher is more creative.</span>
        </InfoPopover>
      </div>,
    );

    click(trigger());
    act(() => {
      fireEvent.mouseDown(screen.getByText('Higher is more creative.'));
      fireEvent.click(screen.getByText('Higher is more creative.'));
    });

    expect(panel()).not.toBeNull();
    expect(hostClick).not.toHaveBeenCalled();
    expect(hostMouseDown).not.toHaveBeenCalled();
  });

  it('closes an open "i" when another one is clicked, so two panels never stack', () => {
    // Why the trigger must NOT stop `pointerdown`: Radix dismisses an open panel from a
    // document-level pointerdown, and swallowing it would leave both panels open.
    render(
      <>
        <InfoPopover label="Temperature">Higher is more creative.</InfoPopover>
        <InfoPopover label="Max tokens">Caps the answer length.</InfoPopover>
      </>,
    );

    click(trigger('Temperature'));
    expect(screen.getByText('Higher is more creative.')).toBeInTheDocument();

    click(trigger('Max tokens'));
    expect(screen.queryByText('Higher is more creative.')).toBeNull();
    expect(screen.getByText('Caps the answer length.')).toBeInTheDocument();
  });

  it('names the panel after the label, sets a plain string as a paragraph and lets the caller size it', () => {
    render(
      <InfoPopover label="Temperature" contentClassName="w-[300px]" contentTestId="temp-info">
        Higher is more creative.
      </InfoPopover>,
    );
    click(trigger());

    const content = screen.getByTestId('temp-info');
    expect(content).toHaveAttribute('aria-label', 'About Temperature');
    expect(screen.getByText('Higher is more creative.').tagName).toBe('P');
    // twMerge must resolve the width conflict in favour of the caller.
    expect(content.className).toContain('w-[300px]');
    expect(content.className).not.toMatch(/(?:^|\s)w-72(?:\s|$)/);
  });

  it('never shares its accessible name with the control it explains, in any locale', () => {
    // The field's own switch is named "Mailbox"; an "i" also named "Mailbox" made the two
    // indistinguishable to a screen reader (and to getByLabelText).
    const { unmount } = render(
      <label>
        Mailbox <input type="checkbox" aria-label="Mailbox" />
        <InfoPopover label="Mailbox">Lets the agent send email.</InfoPopover>
      </label>,
    );
    expect(screen.getAllByLabelText('Mailbox')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'About Mailbox' })).toBeInTheDocument();
    unmount();

    rtlRender(
      <Intl locale="fr">
        <InfoPopover label="Boîte mail">Permet à l'agent d'envoyer des e-mails.</InfoPopover>
      </Intl>,
    );
    expect(screen.getByRole('button', { name: 'À propos de Boîte mail' })).toBeInTheDocument();
  });

  it('takes a complete phrase as the name as given, without wrapping it in "About"', () => {
    render(
      <InfoPopover label="Price per turn" accessibleName="See the price per turn">
        Six credits.
      </InfoPopover>,
    );
    expect(screen.getByRole('button', { name: 'See the price per turn' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^About / })).toBeNull();
  });

  it('lets a caller that brings its own trigger name the panel verbatim', () => {
    render(
      <InfoPopover label="Details for Nightly report" trigger={<button type="button">details</button>}>
        Created by Ada.
      </InfoPopover>,
    );
    click(screen.getByRole('button', { name: 'details' }));
    expect(panel()).toHaveAttribute('aria-label', 'Details for Nightly report');
  });

  it('forwards contentProps to the panel, with its click isolation intact', () => {
    // ModelInfo relies on this: the composer menu keeps itself open for any target inside a
    // `data-model-selector-keep-open` element, so the tag must reach the portalled panel.
    const hostMouseDown = vi.fn();
    render(
      <div onMouseDown={hostMouseDown}>
        <InfoPopover
          label="Model"
          contentProps={{ 'data-model-selector-keep-open': true, sideOffset: 6 }}
        >
          <span>Context window 200k.</span>
        </InfoPopover>
      </div>,
    );
    click(trigger('Model'));

    expect(panel()).toHaveAttribute('data-model-selector-keep-open', 'true');
    act(() => {
      fireEvent.mouseDown(screen.getByText('Context window 200k.'));
    });
    expect(hostMouseDown).not.toHaveBeenCalled();
  });

  it('keeps a caller-supplied trigger from leaking its click to the host', () => {
    // The isolation sits on the trigger slot, not only on the default button, so a caller
    // that brings its own trigger cannot forget it.
    const hostClick = vi.fn();
    render(
      <div onClick={hostClick}>
        <InfoPopover label="Details" trigger={<button type="button">details</button>}>
          Created by Ada.
        </InfoPopover>
      </div>,
    );
    click(screen.getByRole('button', { name: 'details' }));
    expect(panel()).not.toBeNull();
    expect(hostClick).not.toHaveBeenCalled();
  });

  it('supports controlled mode for a host that must know whether the panel is open', () => {
    const onOpenChange = vi.fn();
    const { rerender } = render(
      <InfoPopover label="Temperature" open={false} onOpenChange={onOpenChange}>
        Higher is more creative.
      </InfoPopover>,
    );

    click(trigger());
    expect(onOpenChange).toHaveBeenCalledWith(true);
    expect(panel()).toBeNull();

    rerender(
      <InfoPopover label="Temperature" open onOpenChange={onOpenChange}>
        Higher is more creative.
      </InfoPopover>,
    );
    expect(panel()).toHaveTextContent('Higher is more creative.');
  });
});
