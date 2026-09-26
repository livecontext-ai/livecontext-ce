import type { ReactNode } from 'react';
import { ScrollRegion } from './ScrollRegion';

/**
 * Compact reference table styled with the docs tokens. Server component.
 *
 * Header cells carry `scope="col"`. Pass `caption` to give the table an
 * accessible name; `rowHeaders` marks the first cell of each row as its header
 * (`<th scope="row">`), for key/value and comparison tables. When the table is
 * wider than the page, its scroll wrapper (`ScrollRegion`) becomes a focusable,
 * labelled region; tables that fit stay plain.
 */
export function DocsTable({
  head,
  rows,
  caption,
  rowHeaders = false,
}: {
  head: ReactNode[];
  rows: ReactNode[][];
  caption?: string;
  rowHeaders?: boolean;
}) {
  // Without a caption, name the region after its text column headers.
  const textHead = head.filter((cell): cell is string => typeof cell === 'string' && cell.trim() !== '');
  const regionLabel = caption ?? (textHead.length ? `Table: ${textHead.join(', ')}` : 'Table');
  return (
    <ScrollRegion className="docs-table-wrap" label={regionLabel}>
      <table>
        {caption ? <caption className="sr-only">{caption}</caption> : null}
        <thead>
          <tr>
            {head.map((cell, i) => (
              <th key={i} scope="col">
                {cell === '' ? <span className="sr-only">Item</span> : cell}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, ri) => (
            <tr key={ri}>
              {row.map((cell, ci) =>
                rowHeaders && ci === 0 ? (
                  <th key={ci} scope="row">
                    {cell}
                  </th>
                ) : (
                  <td key={ci}>{cell}</td>
                ),
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </ScrollRegion>
  );
}
