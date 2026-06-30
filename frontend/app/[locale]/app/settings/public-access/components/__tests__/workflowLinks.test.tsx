// @vitest-environment jsdom
/**
 * Regression: the public-access trigger cards / delete dialog linked to
 * `/app/workflows/<id>` (PLURAL), a route that does not exist, so clicking the
 * workflow link (e.g. on a schedule trigger) 404'd. The canonical route is the
 * SINGULAR `/app/workflow/<id>`. These tests pin the singular href.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
// href-preserving Link mock (the common stub drops href)
vi.mock('next/link', () => ({
  default: ({ children, href, ...rest }: any) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock('@/lib/utils/dateFormatters', () => ({ formatUtcDate: () => 'date' }));

import { TriggerCard } from '../TriggerCard';
import { DeleteTriggerDialog } from '../DeleteTriggerDialog';

describe('public-access workflow links point at the singular /app/workflow route', () => {
  it('TriggerCard links to /app/workflow/<id> (not /app/workflows/<id>)', () => {
    const { container } = render(
      <TriggerCard name="Daily digest" isActive workflowId="wf-42" workflowName="My WF" actions={[]} />
    );
    const link = container.querySelector('a') as HTMLAnchorElement;
    expect(link).toBeTruthy();
    expect(link.getAttribute('href')).toBe('/app/workflow/wf-42');
    expect(link.getAttribute('href')).not.toContain('/app/workflows/');
  });

  it('DeleteTriggerDialog "view workflow" links to /app/workflow/<id>', () => {
    render(
      <DeleteTriggerDialog
        open
        onOpenChange={vi.fn()}
        onConfirm={vi.fn()}
        triggerName="Daily digest"
        triggerType="schedule"
        workflowId="wf-99"
        workflowName="My WF"
      />
    );
    const link = screen.getByRole('link') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/app/workflow/wf-99');
    expect(link.getAttribute('href')).not.toContain('/app/workflows/');
  });
});
