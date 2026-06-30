// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { CreateTaskDialog } from '../CreateTaskDialog';
import type { Agent } from '@/lib/api/orchestrator/types';
import type { TaskPerson } from '@/lib/api/orchestrator/task.types';

const mocks = vi.hoisted(() => ({
  taskService: { createTask: vi.fn() },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@/lib/api/orchestrator/task.service', () => ({
  taskService: mocks.taskService,
}));

vi.mock('@/components/agents', () => ({
  AvatarDisplay: ({ name }: { name?: string }) => <span>{name ?? 'Agent'}</span>,
}));

// Lightweight Select that renders all items + invokes onValueChange on click.
vi.mock('@/components/ui/select', async () => {
  const ReactModule = await import('react');
  const Ctx = ReactModule.createContext<{ onValueChange?: (v: string) => void }>({});
  return {
    Select: ({ onValueChange, children }: { onValueChange?: (v: string) => void; children: React.ReactNode }) => (
      <Ctx.Provider value={{ onValueChange }}><div>{children}</div></Ctx.Provider>
    ),
    SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectValue: ({ placeholder }: { placeholder?: string }) => <span>{placeholder}</span>,
    SelectContent: ({ children }: { children: React.ReactNode }) => <div role="listbox">{children}</div>,
    SelectGroup: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectLabel: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => {
      const ctx = ReactModule.useContext(Ctx);
      return <button type="button" role="option" onClick={() => ctx.onValueChange?.(value)}>{children}</button>;
    },
  };
});

const agent: Agent = {
  id: 'agent-1', name: 'Worker Agent', tenantId: 'tenant-1',
  description: 'd', avatarUrl: '',
};
const alice: TaskPerson = { userId: '42', displayName: 'Alice', avatarUrl: null, email: 'alice@x.com' };

function typeTitle() {
  fireEvent.change(screen.getByRole('textbox', { name: 'columns.title' }), { target: { value: 'Ship it' } });
}

describe('CreateTaskDialog - human assignee', () => {
  beforeEach(() => { vi.clearAllMocks(); mocks.taskService.createTask.mockResolvedValue({}); });
  afterEach(() => cleanup());

  it('assigning a teammate sends assigneeUserId (and no agentId)', async () => {
    render(<CreateTaskDialog agents={[agent]} people={[alice]} onClose={() => {}} onCreated={() => {}} />);

    typeTitle();
    fireEvent.click(screen.getByRole('option', { name: /Alice/ }));
    fireEvent.click(screen.getByRole('button', { name: 'actions.createTask' }));

    await waitFor(() => expect(mocks.taskService.createTask).toHaveBeenCalled());
    expect(mocks.taskService.createTask).toHaveBeenCalledWith(expect.objectContaining({
      assigneeUserId: '42',
      agentId: null,
      reviewerAgentId: null,
      reviewerUserId: null,
    }));
  });

  it('assigning an agent sends agentId (and no assigneeUserId)', async () => {
    render(<CreateTaskDialog agents={[agent]} people={[alice]} onClose={() => {}} onCreated={() => {}} />);

    typeTitle();
    fireEvent.click(screen.getByRole('option', { name: /Worker Agent/ }));
    fireEvent.click(screen.getByRole('button', { name: 'actions.createTask' }));

    await waitFor(() => expect(mocks.taskService.createTask).toHaveBeenCalled());
    expect(mocks.taskService.createTask).toHaveBeenCalledWith(expect.objectContaining({
      agentId: 'agent-1',
      assigneeUserId: null,
    }));
  });
});
