'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Info, type LucideIcon } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';

/**
 * The app's ONE "i": an info icon that opens its explanation on CLICK.
 *
 * <p><b>Click, never hover.</b> The app used to mix the two: some "i" opened on hover
 * (a Radix tooltip, or a CSS `group-hover`), some on click, so a reader could not know
 * which gesture a given icon wanted, and a hover "i" was simply unreachable on a touch
 * screen. Every info icon now goes through this component, and the icon opens on click
 * (or Enter / Space) and closes on a second click, Escape, or a click anywhere else.
 * A hover tooltip remains the right tool for naming an icon-only BUTTON; it is not the
 * tool for an explanation.
 *
 * <p><b>Above every surface it can be opened from.</b> The content is portalled to
 * `document.body`, so its z-index competes with the whole page rather than with its
 * trigger's container. It is opened from inside modals (z-[9999] and up), the composer
 * menus (z-[10000] / z-[99999]), the model pickers' SelectContent and the plan-comparison
 * dialog (z-[100000]); the old hand-rolled builder popovers sat at z-[9998]/z-[9999] and
 * opened BEHIND the share modal, and the stock popover layer (z-[64]) sits under every
 * dialog. So it takes z-[100001], the layer the model (i) card had already proven: above
 * all of those, and exactly one step UNDER the tooltip layer (z-[100002]), so a hover
 * tooltip on a badge INSIDE an info panel still paints on top of the panel.
 *
 * <p><b>Clicks stay inside.</b> Hosts are often clickable themselves (a card that opens
 * its resource, a collapsible header, a row), and the content is portalled but React
 * still bubbles its events through the component tree. So the trigger stops `click` and
 * `mousedown`, and the content stops `click`, `mousedown` and `pointerdown`: opening the
 * "i" or selecting text inside it never also opens the card, toggles the section, or
 * reads as an outside click to a hand-rolled modal. The trigger deliberately does NOT
 * stop `pointerdown`: Radix closes an already-open "i" on a document `pointerdown`, and
 * swallowing it would leave two panels open at once. And it never calls
 * `preventDefault`: Radix skips its own toggle when the event is already
 * default-prevented, so the icon would open nothing, silently.
 */
export interface InfoPopoverProps {
  /** The explanation. A plain string is set as a paragraph; any node is rendered as is. */
  children: React.ReactNode;
  /**
   * What the "i" explains, usually the label of the field beside it. The trigger and the
   * panel are named "About {label}" (`common.infoAbout`), NOT the bare label: the field's
   * own control (a switch, an input) already carries that name, and two controls with one
   * name are indistinguishable to a screen reader. Required: an icon-only button with no
   * name reads as "button". With a custom `trigger`, the caller names its own button and
   * `label` names the panel as given.
   */
  label: string;
  /**
   * Overrides the "About {label}" name when the caller already has a complete phrase for
   * the button (e.g. "See the price per turn"), so it is not wrapped a second time.
   */
  accessibleName?: string;
  side?: 'top' | 'right' | 'bottom' | 'left';
  align?: 'start' | 'center' | 'end';
  /** Icon size: `xs` 10px, `sm` 12px (inspector labels), `md` 14px (default), `lg` 20px. */
  size?: 'xs' | 'sm' | 'md' | 'lg';
  /** The glyph. Defaults to lucide's `Info`, the "i". */
  icon?: LucideIcon;
  /** Controlled mode, for a host that must know whether the panel is open. */
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  /**
   * Replaces the default icon button. It must be a single element that accepts a ref
   * and an `onClick` (a `<button>`): Radix clones it as the trigger.
   */
  trigger?: React.ReactNode;
  triggerClassName?: string;
  iconClassName?: string;
  /** Merged over the default panel classes, so a `w-*` or `p-*` here wins. */
  contentClassName?: string;
  /**
   * Extra props for the panel (a `sideOffset`, a `data-*` tag a host's outside-click
   * handler recognises, ...). The three handlers the panel uses for its click isolation are
   * left out of the type, so a caller cannot pass one that would be silently replaced.
   */
  contentProps?: Omit<
    React.ComponentPropsWithoutRef<typeof PopoverContent>,
    'children' | 'className' | 'side' | 'align' | 'onClick' | 'onMouseDown' | 'onPointerDown'
  > & Record<`data-${string}`, unknown>;
  'data-testid'?: string;
  contentTestId?: string;
}

const ICON_SIZE: Record<NonNullable<InfoPopoverProps['size']>, string> = {
  xs: 'h-2.5 w-2.5',
  sm: 'h-3 w-3',
  md: 'h-3.5 w-3.5',
  lg: 'h-5 w-5',
};

const stop = (e: React.SyntheticEvent) => e.stopPropagation();

export function InfoPopover({
  children,
  label,
  accessibleName: nameOverride,
  side = 'top',
  align = 'center',
  size = 'md',
  icon: Icon = Info,
  open,
  onOpenChange,
  trigger,
  triggerClassName,
  iconClassName,
  contentClassName,
  contentProps,
  'data-testid': testId,
  contentTestId,
}: InfoPopoverProps) {
  const t = useTranslations('common');
  // A caller that hands in its own `trigger` names that trigger itself, and `label` is then
  // the panel's name verbatim (it is already a full phrase such as "Details for Report").
  const accessibleName = nameOverride ?? (trigger ? label : t('infoAbout', { label }));
  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      {/* Stopped on the trigger ITSELF, so the isolation holds for a caller's own `trigger`
          too. Radix Slot composes these with the child's handlers and still toggles, since
          `stop` never calls preventDefault. */}
      <PopoverTrigger asChild onClick={stop} onMouseDown={stop}>
        {trigger ?? (
          <button
            type="button"
            aria-label={accessibleName}
            data-testid={testId}
            className={cn(
              'inline-flex flex-shrink-0 items-center justify-center rounded-md p-0.5 align-middle',
              'text-theme-muted transition-colors hover:bg-[var(--bg-secondary)] hover:text-[var(--text-primary)]',
              'data-[state=open]:text-[var(--text-primary)]',
              triggerClassName,
            )}
          >
            <Icon className={cn(ICON_SIZE[size], 'flex-shrink-0', iconClassName)} aria-hidden="true" />
          </button>
        )}
      </PopoverTrigger>
      <PopoverContent
        {...contentProps}
        side={side}
        align={align}
        aria-label={accessibleName}
        data-testid={contentTestId}
        onClick={stop}
        onMouseDown={stop}
        onPointerDown={stop}
        className={cn(
          'z-[100001] w-72 p-3 text-sm leading-relaxed text-theme-secondary',
          // Long explanations scroll inside the panel instead of running off screen.
          'max-h-[min(28rem,var(--radix-popover-content-available-height,28rem))] overflow-y-auto',
          contentClassName,
        )}
      >
        {typeof children === 'string' ? <p className="whitespace-pre-line">{children}</p> : children}
      </PopoverContent>
    </Popover>
  );
}

export default InfoPopover;
