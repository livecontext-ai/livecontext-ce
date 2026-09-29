// @vitest-environment jsdom
/**
 * A 403 from a rerun or an approval means the workspace role is read-only (VIEWER).
 * The rerun used to fall into the generic path (the backend's English on screen) and the
 * approval was swallowed into a console line. Both now report `read_only`, which the
 * builder turns into its translated read-only toast. 409 / 400 stay `rerun_refused`.
 */
import { describe, expect, it, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { ApiError } from '@/lib/api/api-client';
import { useStepByStepHandlers, type ExecutionError } from '../useStepByStepHandlers';

function setup(overrides: Record<string, unknown>) {
  const errors: ExecutionError[] = [];
  const actions = {
    pause: vi.fn(), resume: vi.fn(), reset: vi.fn(), setMode: vi.fn(), setExecutionMode: vi.fn(),
    executeStep: vi.fn(), executeCore: vi.fn(), rerunStep: vi.fn(), resolveApproval: vi.fn(),
    ...overrides,
  };
  const { result } = renderHook(() => useStepByStepHandlers({
    pauseResumeState: { mode: 'automatic', isPaused: false } as never,
    pauseResumeActions: actions as never,
    onExecutionError: (e) => errors.push(e),
  }));
  return { result, errors };
}

describe('useStepByStepHandlers - read-only role', () => {
  it('rerun refused with 403 reports read_only', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { result, errors } = setup({ rerunStep: vi.fn().mockRejectedValue(new ApiError('Forbidden', 403)) });

    await act(async () => { await result.current.handleRerunStep('mcp:a'); });

    expect(errors).toEqual([{ type: 'read_only', message: 'Forbidden' }]);
  });

  it('rerun refused with 409 stays rerun_refused', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { result, errors } = setup({ rerunStep: vi.fn().mockRejectedValue(new ApiError('busy', 409)) });

    await act(async () => { await result.current.handleRerunStep('mcp:a'); });

    expect(errors[0].type).toBe('rerun_refused');
  });

  it('approval refused with 403 reports read_only instead of failing silently', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { result, errors } = setup({ resolveApproval: vi.fn().mockRejectedValue(new ApiError('Forbidden', 403)) });

    await act(async () => { await result.current.handleResolveApproval('core:ok', 'APPROVED'); });

    expect(errors).toEqual([{ type: 'read_only', message: 'Forbidden' }]);
  });

  it('an approval that fails for another reason still reports nothing new', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { result, errors } = setup({ resolveApproval: vi.fn().mockRejectedValue(new ApiError('gone', 404)) });

    await act(async () => { await result.current.handleResolveApproval('core:ok', 'APPROVED'); });

    expect(errors).toEqual([]);
  });
});
