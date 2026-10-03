'use client';

import React from 'react';
import type { PartnerDashboardResponse } from '@/lib/api/services/partner-program-api.service';
import { PartnerDashboard } from './PartnerDashboard';
import { PartnerJoinBand, PartnerPendingBand } from './PartnerStatusBands';
import { PartnerTermsNotice } from './PartnerTermsNotice';

/**
 * The partner page for one answer of the partner endpoint: the dashboard for a partner (with the
 * terms to accept when there are), the review track for an applicant, and for everyone else what
 * the program pays and the way to apply on /partners. Pure: the settings page fetches, this draws.
 */
export function PartnerProgramView({ data, onCopy }: { data: PartnerDashboardResponse; onCopy: (text: string) => void }) {
  const partner = (data.state === 'active' || data.state === 'inactive') ? data.partner : null;
  return (
    <div className="space-y-6">
      {partner && <PartnerTermsNotice agreement={data.agreement} />}
      {partner && (
        <PartnerDashboard
          partner={partner}
          active={data.state === 'active'}
          onCopy={onCopy}
          settleDays={data.terms?.tier_settle_days ?? null}
          tiers={data.terms?.tiers ?? []}
        />
      )}
      {data.state === 'pending' && data.application && <PartnerPendingBand application={data.application} />}
      {(data.state === 'none' || data.state === 'rejected') && (
        <PartnerJoinBand terms={data.terms} rejected={data.state === 'rejected' ? data.application : null} />
      )}
    </div>
  );
}
