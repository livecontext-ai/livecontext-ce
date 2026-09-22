'use client';

import dynamic from 'next/dynamic';
import type { CSSProperties, ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';
import TelegramApprovalPhone from './TelegramApprovalPhone';
import { BUSINESS_PREVIEW_VIEWPORT, type BusinessExampleKey, type BusinessPersona } from './personas';
import type { SnapshotTableData } from '@/components/data-table/types';

const InterfaceThumbnail = dynamic(
  () => import('@/app/workflows/builder/components/interface/InterfaceThumbnail').then((m) => m.InterfaceThumbnail),
  { ssr: false },
);
const DataTable = dynamic(() => import('@/components/DataTable'), { ssr: false });

/**
 * The pieces every persona's "what you can build" section is made of.
 *
 * <p>Five personas now show the product rather than describing it, and they were drifting
 * into five copies of the same card shell, the same phone scaling and the same table
 * compression, each with its own typo to find. What differs between them is the DRAWING
 * (which artefact leads a card, how the tiles are placed, which way a row leans); what does
 * not differ is how a screen, a phone or a table is mounted, so that part lives here.
 *
 * <p>The phone and the table each need a class of their own per section, because their CSS
 * is scoped by it, hence the `prefix` both take.
 */
export const STUDIO_CARD = { background: 'var(--bg-primary)', border: '1px solid var(--border-color)', boxShadow: 'var(--landing-card-shadow)' } satisfies CSSProperties;
export const STUDIO_PANEL = { background: 'var(--bg-primary)', border: '1px solid var(--border-color)', boxShadow: '0 10px 24px rgba(16,22,38,.12)' } satisfies CSSProperties;

/** The card shell: the stage takes the room, the copy sits under it in a quiet line. */
export function StudioCard({ icon: Icon, label, title, summary, stage, children }: {
  icon: LucideIcon;
  label: string;
  title: string;
  summary: string;
  stage: CSSProperties;
  children: ReactNode;
}) {
  return (
    <article className="flex h-full flex-col overflow-hidden rounded-3xl" style={STUDIO_CARD}>
      <div className="flex flex-1 items-center justify-center px-5 py-8 md:px-7 md:py-10" style={stage}>{children}</div>
      <div className="p-5 md:p-7">
        <span className="inline-flex items-center gap-2 rounded-full px-2.5 py-1 text-xs font-semibold" style={{ background: 'var(--bg-tertiary)', color: 'var(--text-secondary)' }}>
          <Icon className="h-3.5 w-3.5" aria-hidden="true" />
          {label}
        </span>
        <h3 className="mt-3 text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>{title}</h3>
        <p className="mt-1.5 max-w-2xl text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{summary}</p>
      </div>
    </article>
  );
}

/**
 * A review screen, rendered from the same HTML the hero's interface node renders.
 *
 * @param width a number for the fixed widths the studios lay out by hand, or a CSS length
 *   (`'100%'`) for a card that fills its stage, which is what the home page's role grid needs:
 *   its cards are 2/5 and 3/5 of a row, so no fixed pixel width is right in both.
 * @param viewport the shape the interface was AUTHORED at. Defaults to the business
 *   workspace; Creator's story is portrait, and handing it the landscape default would
 *   letterbox a document that is not that shape.
 */
export function ScreenTile({ node, html, width, lift, viewport = BUSINESS_PREVIEW_VIEWPORT }: {
  node: string;
  html: string;
  width: number | string;
  lift: string;
  viewport?: { width: number; height: number };
}) {
  return (
    <div
      data-node={node}
      className="studio-tile relative z-10 shrink-0 overflow-hidden rounded-2xl"
      style={{ ['--lift' as string]: lift, width, aspectRatio: `${viewport.width} / ${viewport.height}`, border: '1px solid var(--border-color)', background: 'var(--bg-primary)', boxShadow: '0 14px 32px rgba(16,22,38,.16)' }}
    >
      <InterfaceThumbnail htmlTemplate={html} viewport={viewport} fit="contain" dropJs />
    </div>
  );
}

/**
 * The approval phone, scaled at its OWN size from its top left.
 *
 * <p>It paints 286x514 whatever box it is given, so the height belongs to the wrapper,
 * which reserves the painted space and is what a wire attaches to. A height on the scaled
 * element is what cut the message in half.
 */
export function PhoneTile({ node, prefix, persona, example, lift }: {
  node: string;
  prefix: string;
  persona: BusinessPersona;
  example: BusinessExampleKey;
  lift: string;
}) {
  return (
    <div className="studio-tile relative z-10 shrink-0 overflow-hidden" style={{ ['--lift' as string]: lift, width: 150, height: 270 }}>
      <div data-node={node} className={`${prefix}-phone`}>
        <TelegramApprovalPhone persona={persona} creatorExample="product" businessExample={example} phase="waiting" />
      </div>
    </div>
  );
}

/** The product's own table, built from the snapshot the hero's side panel opens on. */
export function HistoryTable({ node, prefix, snapshot, height }: { node: string; prefix: string; snapshot: SnapshotTableData; height: number }) {
  return (
    <div data-node={node} className={`${prefix}-history relative z-10 w-full overflow-hidden rounded-2xl`} style={{ ...STUDIO_PANEL, height }}>
      <DataTable snapshotData={snapshot} readOnly embedded className="h-full" />
    </div>
  );
}

/**
 * The CSS a section needs for its phone and its table, plus the stagger every tile uses.
 *
 * <p>The table's paging bar is not hidden by a selector (it carries no stable hook, only
 * utility classes): the inner box is given more height than the card shows, so the bar
 * falls below the cut, which survives a restyle of the table. The row height comes from
 * the CELL's own control, not from the row, which is why the inner div is what shrinks.
 */
export function studioCss(prefix: string, { tableBody = 430, tableWidth = 820 } = {}) {
  return `
/* The stagger is a desktop affordance: on a phone the tiles wrap and an offset would only
   punch holes in the column. */
@media(min-width:640px){.studio-tile{transform:translateY(var(--lift,0px))}}
.${prefix}-phone{width:286px;height:514px;transform:scale(.525);transform-origin:top left}
.${prefix}-history>*{height:${tableBody}px}
.${prefix}-history table{min-width:${tableWidth}px}
.${prefix}-history tbody tr{height:44px}
.${prefix}-history table :is(th,td){padding-top:2px!important;padding-bottom:2px!important}
/* Also hidden: the drag grip drawn in every cell and revealed on hover, which at this row
   height sits beside the avatar and reads as a broken image, and the cell's max-height
   scroller, which paints a scrollbar there for the same reason. A landing card is not a
   place to drag rows from. */
.${prefix}-history tbody td>div{height:38px!important;padding-left:8px!important;padding-right:8px!important;overflow:hidden!important}
.${prefix}-history .grip-handle,.${prefix}-history table td .opacity-0{display:none!important}
.${prefix}-history table td:has(img) .h-14,.${prefix}-history table td img{height:30px!important;width:30px!important}
.${prefix}-history table td:has(img)>div{padding-left:0!important;padding-right:0!important;justify-content:center}
`;
}
