'use client';

import React from 'react';

/**
 * One setting: its title and explanation on the left, its control on the right.
 *
 * <p>The one spelling of that row for the settings pages. It used to be written out by hand at
 * each call site with its own control width (200px, 260px, 12rem) and nothing keeping the control
 * from shrinking, so on the Preferences tab a long explanation squeezed its select down to
 * "Left to.." while the one above kept its full width, and the column of controls never lined up.
 *
 * <p>The row goes side by side from the `@lg` CONTAINER width (32rem), not a viewport width: the
 * settings column is a container (see the settings layout), and on a 768px window with the app
 * sidebar open that column is far narrower than the window, which stacked nothing and broke every
 * explanation one word per line. 32rem rather than more because the settings menu moves beside
 * the page at a 48rem layout column, leaving the page 34rem: a larger threshold would stack the
 * rows again the moment the window grows past that point.
 *
 * <p>Its title is an h3: the tab or section above it is titled by an h2 (PageHeader).
 */
export function SettingRow({
  title,
  description,
  children,
  'data-testid': testId,
}: {
  title: React.ReactNode;
  /** The explanation under the title; any extra hint lines go here too. */
  description?: React.ReactNode;
  /** The control: stretched to the control column's width. */
  children: React.ReactNode;
  'data-testid'?: string;
}) {
  return (
    <div
      className="flex flex-col gap-2 @lg:flex-row @lg:items-center @lg:justify-between @lg:gap-6"
      data-testid={testId}
    >
      <div className="min-w-0">
        <h3 className="font-medium text-theme-primary">{title}</h3>
        {typeof description === 'string' ? (
          <p className="text-sm text-theme-secondary">{description}</p>
        ) : (
          description
        )}
      </div>
      <div className="w-full shrink-0 @lg:w-60" data-slot="setting-control">
        {children}
      </div>
    </div>
  );
}

export default SettingRow;
