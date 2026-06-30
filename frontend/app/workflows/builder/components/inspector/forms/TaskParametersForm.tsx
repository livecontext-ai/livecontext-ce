'use client';

import * as React from 'react';
import { Info, Code2 } from 'lucide-react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { AvatarDisplay } from '@/components/agents';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import { taskService } from '@/lib/api/orchestrator/task.service';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import type { Agent as AgentEntity } from '@/lib/api/orchestrator/types';
import type { Task } from '@/lib/api/orchestrator/task.types';
import type { BuilderNodeData } from '../../../types';
import type { ConnectionProps } from '../ExpressionField';

interface TaskParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  connectionProps: ConnectionProps;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

const OPERATIONS = [
  { value: 'create_task', label: 'Create Task' },
  { value: 'get_task', label: 'Get Task' },
  { value: 'update_task', label: 'Update Task' },
  { value: 'delete_task', label: 'Delete Task' },
  { value: 'list_tasks', label: 'List Tasks' },
] as const;

export const NONE_SENTINEL = '__none__';

const PRIORITIES = [
  { value: NONE_SENTINEL, label: 'None' },
  { value: 'low', label: 'Low' },
  { value: 'normal', label: 'Normal' },
  { value: 'high', label: 'High' },
  { value: 'urgent', label: 'Urgent' },
] as const;

const STATUSES = [
  { value: NONE_SENTINEL, label: 'Any' },
  { value: 'pending', label: 'Pending' },
  { value: 'in_progress', label: 'In Progress' },
  { value: 'in_review', label: 'In Review' },
  { value: 'completed', label: 'Completed' },
  { value: 'failed', label: 'Failed' },
  { value: 'cancelled', label: 'Cancelled' },
] as const;

const isExpression = (value: string) => /\{\{[\s\S]*?\}\}/.test(value);

export function TaskParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  connectionProps,
  findUnknownVariables,
}: TaskParametersFormProps) {
  const t = useTranslations('workflowBuilder.taskNode');

  const operation: string = (data as any).taskOperation ?? 'create_task';
  const taskId: string = (data as any).taskTaskId ?? '';
  const title: string = (data as any).taskTitle ?? '';
  const instructions: string = (data as any).taskInstructions ?? '';
  const priority: string = (data as any).taskPriority ?? '';
  const agentId: string = (data as any).taskAgentId ?? '';
  const reviewerAgentId: string = (data as any).taskReviewerAgentId ?? '';
  const status: string = (data as any).taskStatus ?? '';
  const search: string = (data as any).taskSearch ?? '';
  const limit: number = (data as any).taskLimit ?? 50;
  const taskContextJson: string = (data as any).taskContextJson ?? '';

  const needsTaskId = operation === 'get_task' || operation === 'update_task' || operation === 'delete_task';
  const needsTitle = operation === 'create_task' || operation === 'update_task';
  const needsInstructions = operation === 'create_task' || operation === 'update_task';
  const needsPriority = operation !== 'delete_task';
  const needsAgentId = operation === 'create_task' || operation === 'update_task' || operation === 'list_tasks';
  const needsReviewer = operation === 'create_task';
  const needsStatus = operation === 'update_task' || operation === 'list_tasks';
  const needsSearch = operation === 'list_tasks';
  const needsLimit = operation === 'list_tasks';
  const needsTaskContext = operation === 'create_task';

  // Backend accepts taskContext only as a JSON object (Core.TaskConfig)
  const taskContextInvalid = React.useMemo(() => {
    if (!taskContextJson.trim()) return false;
    try {
      const parsed = JSON.parse(taskContextJson);
      return parsed === null || typeof parsed !== 'object' || Array.isArray(parsed);
    } catch {
      return true;
    }
  }, [taskContextJson]);

  // Agents - fetched once for select pickers
  const [agents, setAgents] = React.useState<AgentEntity[]>([]);
  const [agentsLoading, setAgentsLoading] = React.useState(false);
  const [agentsFetchKey, setAgentsFetchKey] = React.useState(0);
  const needAgents = needsAgentId || needsReviewer;
  React.useEffect(() => {
    if (!needAgents) return;
    let cancelled = false;
    setAgentsLoading(true);
    agentService.getAgents()
      .then((result) => { if (!cancelled) setAgents(result); })
      .catch(() => { if (!cancelled) setAgents([]); })
      .finally(() => { if (!cancelled) setAgentsLoading(false); });
    return () => { cancelled = true; };
  }, [needAgents, agentsFetchKey]);

  // Tasks - fetched once for the task ID picker
  const [tasks, setTasks] = React.useState<Task[]>([]);
  const [tasksLoading, setTasksLoading] = React.useState(false);
  const [tasksFetchKey, setTasksFetchKey] = React.useState(0);
  React.useEffect(() => {
    if (!needsTaskId) return;
    let cancelled = false;
    setTasksLoading(true);
    taskService.listTasks({ size: 200, sort: 'updated_at' })
      .then((result) => { if (!cancelled) setTasks(result.tasks ?? []); })
      .catch(() => { if (!cancelled) setTasks([]); })
      .finally(() => { if (!cancelled) setTasksLoading(false); });
    return () => { cancelled = true; };
  }, [needsTaskId, tasksFetchKey]);

  // Phase 6c (2026-05-19) - drop agent + task picker arrays on
  // workspace switch and refetch. The inspector stays mounted while
  // the workflow builder repaints; without this reset the previous
  // workspace's agents/tasks remain selectable (and a stale UUID can
  // be saved into the node).
  useOrgScopedReset(() => {
    setAgents([]);
    setTasks([]);
    setAgentsFetchKey((k) => k + 1);
    setTasksFetchKey((k) => k + 1);
  });

  // "Use expression" toggle state per UUID field - auto-on when value contains {{ }}
  const [taskIdAsExpr, setTaskIdAsExpr] = React.useState(() => isExpression(taskId));
  const [agentIdAsExpr, setAgentIdAsExpr] = React.useState(() => isExpression(agentId));
  const [reviewerAsExpr, setReviewerAsExpr] = React.useState(() => isExpression(reviewerAgentId));

  // Backend rule (AgentTaskService): assignee and reviewer cannot be the same agent.
  // Only flag the conflict when both sides are concrete UUIDs - expressions resolve at runtime.
  const sameAgentConflict =
    needsReviewer
    && !agentIdAsExpr && !reviewerAsExpr
    && !!agentId && !!reviewerAgentId
    && agentId === reviewerAgentId;

  // Re-sync when value flips between expression and literal externally (e.g. drag-drop link from another node)
  React.useEffect(() => { if (isExpression(taskId)) setTaskIdAsExpr(true); }, [taskId]);
  React.useEffect(() => { if (isExpression(agentId)) setAgentIdAsExpr(true); }, [agentId]);
  React.useEffect(() => { if (isExpression(reviewerAgentId)) setReviewerAsExpr(true); }, [reviewerAgentId]);

  const handleChange = React.useCallback(
    (field: string, value: string | number) => {
      if (isRunMode) return;
      onUpdate({ ...data, [field]: value } as BuilderNodeData);
    },
    [data, isRunMode, onUpdate],
  );

  const renderAgentSelectValue = (id: string) => {
    const agent = agents.find(a => a.id === id);
    if (!agent) return null;
    return (
      <div className="flex items-center gap-2 min-w-0">
        <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" className="w-6 h-6" />
        <span className="text-sm truncate">{agent.name}</span>
      </div>
    );
  };

  const ExprToggle = ({ active, onClick }: { active: boolean; onClick: () => void }) => (
    <button
      type="button"
      onClick={onClick}
      disabled={isRunMode}
      className={`inline-flex items-center gap-1 text-xs ${active ? 'text-blue-600 dark:text-blue-400' : 'text-slate-400 hover:text-slate-600 dark:hover:text-slate-300'} disabled:opacity-50`}
      title={active ? t('useSelect') : t('useExpression')}
    >
      <Code2 className="h-3 w-3" />
      {active ? t('useSelect') : t('useExpression')}
    </button>
  );

  return (
    <div className="space-y-4 pt-2">
      {/* Info header */}
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('title')}
        </span>
        <Popover>
          <PopoverTrigger asChild>
            <button className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-300">
              <Info className="h-3.5 w-3.5" />
            </button>
          </PopoverTrigger>
          <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
            <p className="font-semibold mb-1">{t('infoTitle')}</p>
            <p className="text-slate-500 dark:text-slate-400 text-xs">
              {t('infoDescription')}
            </p>
          </PopoverContent>
        </Popover>
      </div>

      {/* Operation selector */}
      <div className="space-y-1">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('operation')} <span className="text-red-500">*</span>
        </span>
        <Select
          value={operation}
          onValueChange={(v) => handleChange('taskOperation', v)}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full text-sm">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {OPERATIONS.map((op) => (
              <SelectItem key={op.value} value={op.value}>
                {op.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Task ID (for get/update/delete) - Select existing task or expression */}
      {needsTaskId && (
        <div className="flex flex-col gap-1.5">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
              {t('taskId')} <span className="text-red-500">*</span>
            </span>
            <ExprToggle active={taskIdAsExpr} onClick={() => setTaskIdAsExpr(v => !v)} />
          </div>
          {taskIdAsExpr ? (
            <ExpressionEditor
              value={taskId}
              onChange={(v) => handleChange('taskTaskId', v)}
              placeholder="{{core:previous_step.output.task.id}}"
              className="w-full"
              unknownVariables={findUnknownVariables({ taskId })}
              handleId={`task-taskid-${node.id}`}
              connections={connectionProps.connections}
              onHandleClick={connectionProps.handleHandleClick}
              draggingFromHandle={connectionProps.draggingFromHandle}
              onHandleMouseDown={connectionProps.handleHandleMouseDown}
              onHandleMouseUp={connectionProps.handleHandleMouseUp}
              hoveredTargetHandle={connectionProps.hoveredTargetHandle}
              onSetHandleRef={connectionProps.handleSetHandleRef}
              isRequired={true}
              readOnly={isRunMode}
            />
          ) : (
            <Select
              value={taskId || ''}
              onValueChange={(v) => handleChange('taskTaskId', v)}
              disabled={isRunMode || tasksLoading}
            >
              <SelectTrigger className="w-full text-sm">
                <SelectValue placeholder={tasksLoading ? t('loadingTasks') : t('taskIdPlaceholder')} />
              </SelectTrigger>
              <SelectContent>
                {tasks.length === 0 && !tasksLoading ? (
                  <div className="px-3 py-2 text-sm text-slate-400">{t('noTasks')}</div>
                ) : (
                  tasks.map((task) => (
                    <SelectItem key={task.id} value={task.id} description={`${task.status} · ${task.priority}`}>
                      <span className="text-sm truncate">{task.title || task.id}</span>
                    </SelectItem>
                  ))
                )}
              </SelectContent>
            </Select>
          )}
        </div>
      )}

      {/* Title (for create/update) */}
      {needsTitle && (
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('taskTitle')}
            {operation === 'create_task' && <span className="text-red-500"> *</span>}
          </span>
          <ExpressionEditor
            value={title}
            onChange={(v) => handleChange('taskTitle', v)}
            placeholder={t('taskTitlePlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ title })}
            handleId={`task-title-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            isRequired={operation === 'create_task'}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Instructions (for create/update) */}
      {needsInstructions && (
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('instructions')}
          </span>
          <ExpressionEditor
            value={instructions}
            onChange={(v) => handleChange('taskInstructions', v)}
            placeholder={t('instructionsPlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ instructions })}
            handleId={`task-instructions-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Priority */}
      {needsPriority && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('priority')}
          </span>
          <Select
            value={priority || NONE_SENTINEL}
            onValueChange={(v) => handleChange('taskPriority', v === NONE_SENTINEL ? '' : v)}
            disabled={isRunMode}
          >
            <SelectTrigger className="w-full text-sm">
              <SelectValue placeholder={t('priorityPlaceholder')} />
            </SelectTrigger>
            <SelectContent>
              {PRIORITIES.map((p) => (
                <SelectItem key={p.value} value={p.value}>
                  {p.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}

      {/* Agent ID (assignee) - Select agent or expression */}
      {needsAgentId && (
        <div className="flex flex-col gap-1.5">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
              {t('agentId')}
            </span>
            <ExprToggle active={agentIdAsExpr} onClick={() => setAgentIdAsExpr(v => !v)} />
          </div>
          {agentIdAsExpr ? (
            <ExpressionEditor
              value={agentId}
              onChange={(v) => handleChange('taskAgentId', v)}
              placeholder="{{core:previous_step.output.agent_id}}"
              className="w-full"
              unknownVariables={findUnknownVariables({ agentId })}
              handleId={`task-agentid-${node.id}`}
              connections={connectionProps.connections}
              onHandleClick={connectionProps.handleHandleClick}
              draggingFromHandle={connectionProps.draggingFromHandle}
              onHandleMouseDown={connectionProps.handleHandleMouseDown}
              onHandleMouseUp={connectionProps.handleHandleMouseUp}
              hoveredTargetHandle={connectionProps.hoveredTargetHandle}
              onSetHandleRef={connectionProps.handleSetHandleRef}
              readOnly={isRunMode}
            />
          ) : (
            <Select
              value={agentId || NONE_SENTINEL}
              onValueChange={(v) => handleChange('taskAgentId', v === NONE_SENTINEL ? '' : v)}
              disabled={isRunMode || agentsLoading}
            >
              <SelectTrigger className="w-full text-sm">
                {agentId && agents.find(a => a.id === agentId)
                  ? renderAgentSelectValue(agentId)
                  : <SelectValue placeholder={agentsLoading ? t('loadingAgents') : t('agentIdPlaceholder')} />}
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={NONE_SENTINEL}>{operation === 'list_tasks' ? t('anyAgent') : t('unassigned')}</SelectItem>
                {agents
                  .filter((agent) => !(needsReviewer && !reviewerAsExpr && reviewerAgentId && agent.id === reviewerAgentId))
                  .map((agent) => (
                    <SelectItem key={agent.id} value={agent.id}>
                      <div className="flex items-center gap-2">
                        <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" className="w-6 h-6" />
                        <span className="text-sm">{agent.name}</span>
                      </div>
                    </SelectItem>
                  ))}
              </SelectContent>
            </Select>
          )}
        </div>
      )}

      {/* Reviewer Agent ID (for create) - Select agent or expression */}
      {needsReviewer && (
        <div className="flex flex-col gap-1.5">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
              {t('reviewerAgentId')}
            </span>
            <ExprToggle active={reviewerAsExpr} onClick={() => setReviewerAsExpr(v => !v)} />
          </div>
          {reviewerAsExpr ? (
            <ExpressionEditor
              value={reviewerAgentId}
              onChange={(v) => handleChange('taskReviewerAgentId', v)}
              placeholder="{{core:previous_step.output.reviewer_id}}"
              className="w-full"
              unknownVariables={findUnknownVariables({ reviewerAgentId })}
              handleId={`task-reviewer-${node.id}`}
              connections={connectionProps.connections}
              onHandleClick={connectionProps.handleHandleClick}
              draggingFromHandle={connectionProps.draggingFromHandle}
              onHandleMouseDown={connectionProps.handleHandleMouseDown}
              onHandleMouseUp={connectionProps.handleHandleMouseUp}
              hoveredTargetHandle={connectionProps.hoveredTargetHandle}
              onSetHandleRef={connectionProps.handleSetHandleRef}
              readOnly={isRunMode}
            />
          ) : (
            <Select
              value={reviewerAgentId || NONE_SENTINEL}
              onValueChange={(v) => handleChange('taskReviewerAgentId', v === NONE_SENTINEL ? '' : v)}
              disabled={isRunMode || agentsLoading}
            >
              <SelectTrigger className="w-full text-sm">
                {reviewerAgentId && agents.find(a => a.id === reviewerAgentId)
                  ? renderAgentSelectValue(reviewerAgentId)
                  : <SelectValue placeholder={agentsLoading ? t('loadingAgents') : t('reviewerAgentIdPlaceholder')} />}
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={NONE_SENTINEL}>{t('noReviewer')}</SelectItem>
                {agents
                  .filter((agent) => !(!agentIdAsExpr && agentId && agent.id === agentId))
                  .map((agent) => (
                    <SelectItem key={agent.id} value={agent.id}>
                      <div className="flex items-center gap-2">
                        <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" className="w-6 h-6" />
                        <span className="text-sm">{agent.name}</span>
                      </div>
                    </SelectItem>
                  ))}
              </SelectContent>
            </Select>
          )}
          {sameAgentConflict && (
            <p className="text-xs text-red-500 dark:text-red-400">{t('reviewerSameAsAssignee')}</p>
          )}
        </div>
      )}

      {/* Task context (for create) - free-form JSON object attached to the task */}
      {needsTaskContext && (
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('taskContext')}
          </span>
          <Textarea
            value={taskContextJson}
            onChange={(e) => handleChange('taskContextJson', e.target.value)}
            placeholder={t('taskContextPlaceholder')}
            className="w-full text-sm font-mono min-h-[72px]"
            readOnly={isRunMode}
          />
          {taskContextInvalid && (
            <p className="text-xs text-red-500 dark:text-red-400">{t('taskContextInvalid')}</p>
          )}
        </div>
      )}

      {/* Status (for update/list) */}
      {needsStatus && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('status')}
          </span>
          <Select
            value={status || NONE_SENTINEL}
            onValueChange={(v) => handleChange('taskStatus', v === NONE_SENTINEL ? '' : v)}
            disabled={isRunMode}
          >
            <SelectTrigger className="w-full text-sm">
              <SelectValue placeholder={t('statusPlaceholder')} />
            </SelectTrigger>
            <SelectContent>
              {STATUSES.map((s) => (
                <SelectItem key={s.value} value={s.value}>
                  {s.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}

      {/* Search (for list) */}
      {needsSearch && (
        <div className="flex flex-col gap-1.5">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('search')}
          </span>
          <ExpressionEditor
            value={search}
            onChange={(v) => handleChange('taskSearch', v)}
            placeholder={t('searchPlaceholder')}
            className="w-full"
            unknownVariables={findUnknownVariables({ search })}
            handleId={`task-search-${node.id}`}
            connections={connectionProps.connections}
            onHandleClick={connectionProps.handleHandleClick}
            draggingFromHandle={connectionProps.draggingFromHandle}
            onHandleMouseDown={connectionProps.handleHandleMouseDown}
            onHandleMouseUp={connectionProps.handleHandleMouseUp}
            hoveredTargetHandle={connectionProps.hoveredTargetHandle}
            onSetHandleRef={connectionProps.handleSetHandleRef}
            readOnly={isRunMode}
          />
        </div>
      )}

      {/* Limit (for list) */}
      {needsLimit && (
        <div className="space-y-1">
          <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('limit')}
          </span>
          <Input
            type="number"
            value={limit}
            onChange={(e) => handleChange('taskLimit', parseInt(e.target.value, 10) || 50)}
            placeholder="50"
            className="w-full text-sm"
            readOnly={isRunMode}
            min={1}
            max={200}
          />
        </div>
      )}
    </div>
  );
}
