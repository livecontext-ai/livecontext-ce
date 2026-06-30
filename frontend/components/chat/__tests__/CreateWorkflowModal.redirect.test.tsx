// @vitest-environment jsdom
/**
 * After a workflow is created the caller must jump straight into the new builder, so the
 * modal has to hand the new workflow's id back to `onWorkflowCreated`. This pins that the
 * id used in the saved plan is exactly the one passed to the callback (the redirect target).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';

const saveWorkflowPlan = vi.fn().mockResolvedValue({});
vi.mock('@/lib/api', () => ({ orchestratorApi: { saveWorkflowPlan: (b: unknown) => saveWorkflowPlan(b) } }));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/components/Toast', () => ({
  __esModule: true,
  default: () => null,
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));

import { CreateWorkflowModal } from '../CreateWorkflowModal';
import { recallWorkflowName } from '@/lib/workflows/recentWorkflowNames';

beforeEach(() => {
  saveWorkflowPlan.mockClear();
  vi.spyOn(crypto, 'randomUUID').mockReturnValue('11111111-1111-1111-1111-111111111111' as `${string}-${string}-${string}-${string}-${string}`);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('CreateWorkflowModal - hands the new id to the caller', () => {
  it('calls onWorkflowCreated with the created workflow id (the redirect target)', async () => {
    const onWorkflowCreated = vi.fn();
    render(<CreateWorkflowModal onClose={() => {}} onWorkflowCreated={onWorkflowCreated} />);

    fireEvent.change(screen.getByPlaceholderText('namePlaceholder'), { target: { value: 'My flow' } });
    fireEvent.click(screen.getByRole('button', { name: 'create' }));

    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalledWith('11111111-1111-1111-1111-111111111111'));
    // The id passed back is the same one persisted in the plan.
    const body = JSON.parse(saveWorkflowPlan.mock.calls[0][0].planJson);
    expect(body.id).toBe('11111111-1111-1111-1111-111111111111');
  });

  it('regression: sends workflowId as a top-level request field (the backend ignores plan.id and would otherwise create the row under a server-generated id, 404ing the redirect)', async () => {
    const onWorkflowCreated = vi.fn();
    render(<CreateWorkflowModal onClose={() => {}} onWorkflowCreated={onWorkflowCreated} />);

    fireEvent.change(screen.getByPlaceholderText('namePlaceholder'), { target: { value: 'My flow' } });
    fireEvent.click(screen.getByRole('button', { name: 'create' }));

    await waitFor(() => expect(saveWorkflowPlan).toHaveBeenCalled());
    // WorkflowPlanRequest.workflowId is the only id channel the backend honors.
    expect(saveWorkflowPlan.mock.calls[0][0].workflowId).toBe('11111111-1111-1111-1111-111111111111');
  });

  it('regression: redirects to (and primes the name under) the id echoed by the save response when it differs from the client-generated one', async () => {
    saveWorkflowPlan.mockResolvedValueOnce({ workflowId: '22222222-2222-2222-2222-222222222222' });
    const onWorkflowCreated = vi.fn();
    render(<CreateWorkflowModal onClose={() => {}} onWorkflowCreated={onWorkflowCreated} />);

    fireEvent.change(screen.getByPlaceholderText('namePlaceholder'), { target: { value: 'My flow' } });
    fireEvent.click(screen.getByRole('button', { name: 'create' }));

    // The server's id is authoritative - redirecting to the client id would 404.
    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalledWith('22222222-2222-2222-2222-222222222222'));
    expect(recallWorkflowName('22222222-2222-2222-2222-222222222222')).toBe('My flow');
  });

  it('primes the new workflow name before redirecting (so the breadcrumb shows it immediately)', async () => {
    // Capture what is recallable at the moment of redirect - priming must happen first.
    let recallableAtRedirect: string | undefined;
    const onWorkflowCreated = vi.fn((id: string) => { recallableAtRedirect = recallWorkflowName(id); });
    render(<CreateWorkflowModal onClose={() => {}} onWorkflowCreated={onWorkflowCreated} />);

    fireEvent.change(screen.getByPlaceholderText('namePlaceholder'), { target: { value: '  My flow  ' } });
    fireEvent.click(screen.getByRole('button', { name: 'create' }));

    await waitFor(() => expect(onWorkflowCreated).toHaveBeenCalled());
    // Name is trimmed and already cached when the caller redirects.
    expect(recallableAtRedirect).toBe('My flow');
    expect(recallWorkflowName('11111111-1111-1111-1111-111111111111')).toBe('My flow');
  });

  it('does nothing when the name is blank', () => {
    const onWorkflowCreated = vi.fn();
    render(<CreateWorkflowModal onClose={() => {}} onWorkflowCreated={onWorkflowCreated} />);
    fireEvent.click(screen.getByRole('button', { name: 'create' }));
    expect(saveWorkflowPlan).not.toHaveBeenCalled();
    expect(onWorkflowCreated).not.toHaveBeenCalled();
  });
});
