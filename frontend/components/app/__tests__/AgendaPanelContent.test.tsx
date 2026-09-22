// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('@/components/views/AgendaView', () => ({
  AgendaView: ({ embedded }: { embedded?: boolean }) => (
    <div data-testid="agenda-view" data-embedded={String(embedded)} />
  ),
}));

import { AgendaPanelContent } from '@/components/app/AgendaPanelContent';

describe('AgendaPanelContent', () => {
  it('uses the embedded agenda layout inside the side panel', () => {
    render(<AgendaPanelContent />);
    expect(screen.getByTestId('agenda-view')).toHaveAttribute('data-embedded', 'true');
  });
});
