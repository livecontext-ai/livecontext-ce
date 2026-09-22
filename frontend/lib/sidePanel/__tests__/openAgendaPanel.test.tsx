// @vitest-environment jsdom
import React from 'react';
import { waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('@/components/app/AgendaPanelContent', () => ({
  AgendaPanelContent: () => <div>agenda</div>,
}));

import { AGENDA_PANEL_TAB_ID } from '@/lib/sidePanel/tabResource';
import { openAgendaPanel } from '@/lib/sidePanel/openAgendaPanel';

describe('openAgendaPanel', () => {
  it('opens one wide agenda tab with the caller locale label', async () => {
    const openTab = vi.fn();

    openAgendaPanel({ openTab }, 'Agenda');

    await waitFor(() => expect(openTab).toHaveBeenCalledTimes(1));
    expect(openTab).toHaveBeenCalledWith(expect.objectContaining({
      id: AGENDA_PANEL_TAB_ID,
      label: 'Agenda',
      preferredWidth: 0.65,
    }));
  });
});
