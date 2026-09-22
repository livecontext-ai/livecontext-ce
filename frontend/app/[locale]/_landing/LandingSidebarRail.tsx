'use client';

/**
 * The app's left icon rail, as the landing draws it inside a replica window.
 *
 * Shared by every landing showcase that frames a page of the product, so the
 * rail cannot say one thing in the agents showcase and another in the agenda
 * one. It is built from `SIDEBAR_NAV_ITEMS`, the SAME list the real sidebar
 * (rail, expanded panel and customize menu) reads, rather than a hand-copied
 * array: the previous copy lived inside AgentsShowcase and had already drifted,
 * missing the Agenda entry the product had gained since.
 *
 * It draws EVERY entry of that list. The real rail draws the ones the visitor
 * kept (three are hidden by default), which is a per-browser preference a
 * marketing page has no version of; showing the product's surfaces is the point
 * here.
 *
 * Labels are hardcoded English instead of `sidebar.nav.*`. That used to be because the
 * landing was one English page on every locale URL; it no longer is, and this rail is the
 * exception rather than the rule now. It survives for the reason below: the whole block is
 * aria-hidden, so these strings are never read out, and they are a picture of the product's
 * sidebar rather than copy addressed to the reader.
 *
 * The whole rail is decorative: it navigates nowhere, so it is aria-hidden as a
 * block, with `title` tooltips left for sighted visitors.
 */

import { User } from 'lucide-react';
import LogoAnimate from '@/components/LogoAnimate';
import { MARKETPLACE_NAV_ITEM, SIDEBAR_NAV_ITEMS, railNavItems, type SidebarNavItem } from '@/lib/sidebar/navItems';

/** The views a showcase can mark active: any sidebar entry, plus Marketplace. */
export type RailView = SidebarNavItem['view'] | typeof MARKETPLACE_NAV_ITEM.view;

const NAV_LABELS: Record<string, string> = {
  marketplace: 'Marketplace',
  board: 'Board',
  agenda: 'Agenda',
  agents: 'Agents',
  applications: 'Applications',
  workflows: 'Workflows',
  interfaces: 'Interfaces',
  tables: 'Tables',
  files: 'Files',
};

export default function LandingSidebarRail({
  activeView,
  hideOnMobile,
}: {
  activeView: RailView;
  /**
   * Drop the rail below `sm`. For a showcase that has to fit a seven-column
   * week on a phone, these 54px are the difference between a readable chip and
   * a clipped one; a showcase whose content reflows (the agents grid) keeps it.
   */
  hideOnMobile?: boolean;
}) {
  return (
    <div
      aria-hidden="true"
      // No border-r: the rail's own surface already separates it from the page
      // beside it, and the divider read as a hard seam inside the window. The
      // frame border around the whole window (.browser-frame) stays.
      className={`${hideOnMobile ? 'hidden sm:flex' : 'flex'} w-[54px] flex-shrink-0 flex-col items-center gap-1 py-2.5`}
      style={{ background: 'var(--bg-secondary)' }}
    >
      <div className="mb-1.5 flex h-8 w-8 items-center justify-center">
        <LogoAnimate size="sm" />
      </div>
      {railNavItems(SIDEBAR_NAV_ITEMS).map(({ icon: Icon, view, titleKey }) => {
        const active = view === activeView;
        return (
          <div
            key={titleKey}
            title={NAV_LABELS[titleKey] ?? titleKey}
            data-active={active ? 'true' : undefined}
            className="flex h-[34px] w-[34px] items-center justify-center rounded-[10px]"
            style={
              active
                ? { background: 'var(--bg-hover)', color: 'var(--text-primary)' }
                : { color: 'var(--text-muted)' }
            }
          >
            <Icon className="h-[17px] w-[17px]" strokeWidth={1.8} />
          </div>
        );
      })}
      <div className="flex-1" />
      <div
        title="Account"
        className="flex h-[30px] w-[30px] items-center justify-center rounded-full border"
        style={{
          borderColor: 'var(--border-color)',
          background: 'linear-gradient(135deg, var(--bg-tertiary), var(--bg-hover))',
          color: 'var(--text-secondary)',
        }}
      >
        <User className="h-4 w-4" strokeWidth={1.8} />
      </div>
    </div>
  );
}
