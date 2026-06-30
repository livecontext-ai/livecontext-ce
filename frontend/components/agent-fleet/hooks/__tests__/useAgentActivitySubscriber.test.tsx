// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook } from '@testing-library/react';

vi.mock('@/lib/websocket/use-channel', () => ({
  useChannel: vi.fn(),
}));

import { useAgentActivitySubscriber } from '../useAgentActivityStream';
import { useChannel } from '@/lib/websocket/use-channel';

describe('useAgentActivitySubscriber', () => {
  afterEach(() => {
    vi.mocked(useChannel).mockClear();
  });

  it('subscribes to agent:activity:{id} WITH requestSnapshot so a late subscribe recovers the running state', () => {
    // requestSnapshot is what lets the gateway replay execution_started for an
    // already-running agent - the fix for opening the page mid-run (esp. bridge agents).
    renderHook(() => useAgentActivitySubscriber('agent-42'));

    expect(useChannel).toHaveBeenCalledWith(
      'agent:activity:agent-42',
      expect.any(Function),
      { requestSnapshot: true },
    );
  });
});
