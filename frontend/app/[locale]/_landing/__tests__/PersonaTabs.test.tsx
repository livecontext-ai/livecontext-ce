// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

import PersonaTabs, { type Persona } from '../PersonaTabs';

afterEach(cleanup);

const PERSONAS: Persona[] = [
  {
    key: 'ops',
    icon: 'ops',
    label: 'Operations',
    intro: 'Ops intro line.',
    examples: [
      { title: 'Onboard clients', desc: 'Intake to kickoff.', output: 'A checklist app', outputKind: 'app' },
      { title: 'Sync inventory', desc: 'Orders update stock.', output: 'A live table', outputKind: 'table' },
    ],
  },
  {
    key: 'sales',
    icon: 'sales',
    label: 'Sales',
    intro: 'Sales intro line.',
    examples: [
      { title: 'Qualify leads', desc: 'Score and route.', output: 'A shared lead table', outputKind: 'table' },
    ],
  },
];

describe('PersonaTabs', () => {
  it('renders the first persona active by default, with its intro and example cards', () => {
    render(<PersonaTabs personas={PERSONAS} />);
    expect(screen.getByRole('tab', { name: /Operations/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('Ops intro line.')).toBeInTheDocument();
    expect(screen.getByText('Onboard clients')).toBeInTheDocument();
    expect(screen.getByText('A checklist app')).toBeInTheDocument();
    // The inactive persona's content is not rendered.
    expect(screen.queryByText('Qualify leads')).not.toBeInTheDocument();
  });

  it('clicking another tab swaps the panel to that persona', () => {
    render(<PersonaTabs personas={PERSONAS} />);
    fireEvent.click(screen.getByRole('tab', { name: /Sales/ }));
    expect(screen.getByRole('tab', { name: /Sales/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: /Operations/ })).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByText('Sales intro line.')).toBeInTheDocument();
    expect(screen.getByText('Qualify leads')).toBeInTheDocument();
    expect(screen.queryByText('Onboard clients')).not.toBeInTheDocument();
  });

  it('renders nothing when the persona list is empty', () => {
    const { container } = render(<PersonaTabs personas={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('implements the full ARIA tabs contract: roving tabindex, aria-controls, and a labelled tabpanel', () => {
    render(<PersonaTabs personas={PERSONAS} />);
    const opsTab = screen.getByRole('tab', { name: /Operations/ });
    const salesTab = screen.getByRole('tab', { name: /Sales/ });
    expect(opsTab).toHaveAttribute('tabindex', '0');
    expect(salesTab).toHaveAttribute('tabindex', '-1');
    expect(opsTab).toHaveAttribute('aria-controls', 'persona-panel-ops');

    const panel = screen.getByRole('tabpanel');
    expect(panel).toHaveAttribute('id', 'persona-panel-ops');
    expect(panel).toHaveAttribute('aria-labelledby', 'persona-tab-ops');
  });

  it('supports arrow-key navigation with wrap-around, plus Home and End', () => {
    render(<PersonaTabs personas={PERSONAS} />);
    const tablist = screen.getByRole('tablist');

    fireEvent.keyDown(tablist, { key: 'ArrowRight' });
    expect(screen.getByRole('tab', { name: /Sales/ })).toHaveAttribute('aria-selected', 'true');

    fireEvent.keyDown(tablist, { key: 'ArrowRight' }); // wraps past the end
    expect(screen.getByRole('tab', { name: /Operations/ })).toHaveAttribute('aria-selected', 'true');

    fireEvent.keyDown(tablist, { key: 'ArrowLeft' }); // wraps backwards
    expect(screen.getByRole('tab', { name: /Sales/ })).toHaveAttribute('aria-selected', 'true');

    fireEvent.keyDown(tablist, { key: 'Home' });
    expect(screen.getByRole('tab', { name: /Operations/ })).toHaveAttribute('aria-selected', 'true');

    fireEvent.keyDown(tablist, { key: 'End' });
    expect(screen.getByRole('tab', { name: /Sales/ })).toHaveAttribute('aria-selected', 'true');
  });
});
