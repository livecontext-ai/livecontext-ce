// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Pins the composer leading-control decision: agent conversation -> avatar button
// (click opens the agent config panel); otherwise -> the model selector. Also pins
// the gate where an agent id is known but its avatar hasn't resolved yet.
vi.mock('@/components/agents', () => ({
  AvatarDisplay: ({ name }: { name?: string }) => <div data-testid="avatar">{name}</div>,
}));

import { ComposerLeadingControl } from '../ComposerLeadingControl';

afterEach(cleanup);

const MODEL_SELECTOR = <div data-testid="model-selector">selector</div>;

describe('ComposerLeadingControl', () => {
  it('agent conversation: renders the avatar (not the model selector); clicking it opens the panel', () => {
    const onOpen = vi.fn();
    render(
      <ComposerLeadingControl
        agentId="a1"
        agentName="Research"
        agentAvatarUrl="/a.png"
        onOpenAgentPanel={onOpen}
        modelSelector={MODEL_SELECTOR}
      />,
    );
    const btn = screen.getByTestId('composer-agent-avatar');
    expect(btn).toBeInTheDocument();
    expect(screen.queryByTestId('model-selector')).not.toBeInTheDocument();
    fireEvent.click(btn);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('non-agent conversation: renders the model selector, not the avatar', () => {
    render(
      <ComposerLeadingControl
        agentId={null}
        agentName={null}
        agentAvatarUrl={null}
        onOpenAgentPanel={vi.fn()}
        modelSelector={MODEL_SELECTOR}
      />,
    );
    expect(screen.getByTestId('model-selector')).toBeInTheDocument();
    expect(screen.queryByTestId('composer-agent-avatar')).not.toBeInTheDocument();
  });

  it('agent id known but avatar not yet resolved: keeps the model selector', () => {
    render(
      <ComposerLeadingControl
        agentId="a1"
        agentName="Research"
        agentAvatarUrl={null}
        onOpenAgentPanel={vi.fn()}
        modelSelector={MODEL_SELECTOR}
      />,
    );
    expect(screen.getByTestId('model-selector')).toBeInTheDocument();
    expect(screen.queryByTestId('composer-agent-avatar')).not.toBeInTheDocument();
  });
});
