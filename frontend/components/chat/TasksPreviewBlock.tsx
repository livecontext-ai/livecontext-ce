'use client';

import React from 'react';

interface Task {
  id: number;
  description: string;
  status: string;  // 'pending' | 'in_progress' | 'completed' from backend
  result?: string;
}

interface TasksData {
  tasks: Task[];
  focusedTaskId?: number;
  completedCount: number;
  totalCount: number;
}

interface TasksPreviewBlockProps {
  tasksData: TasksData;
}

/**
 * Minimal inline visualization for tasks.
 * Matches the style of CallTimelineItem in GroupedToolCard.
 */
export function TasksPreviewBlock({ tasksData }: TasksPreviewBlockProps) {
  const { tasks, focusedTaskId } = tasksData;

  if (!tasks || tasks.length === 0) {
    return null;
  }

  return (
    <div>
      {tasks.map((task) => (
        <TaskItem
          key={task.id}
          task={task}
          isFocused={task.id === focusedTaskId}
        />
      ))}
    </div>
  );
}

interface TaskItemProps {
  task: Task;
  isFocused: boolean;
}

function TaskItem({ task, isFocused }: TaskItemProps) {
  const isCompleted = task.status === 'completed';
  const isInProgress = task.status === 'in_progress';

  // Dot style based on status (color + animation)
  const getDotStyle = () => {
    if (isCompleted) return 'bg-green-500';
    if (isInProgress) return 'bg-blue-500 animate-pulse';
    return 'bg-slate-400 dark:bg-slate-500';
  };

  // Text style based on status
  const getTextStyle = () => {
    if (isCompleted) return 'text-slate-400 dark:text-slate-500 line-through';
    if (isFocused) return 'text-slate-700 dark:text-slate-200';
    return 'text-slate-700 dark:text-slate-200';
  };

  return (
    <div className="mb-2 flex items-center gap-2">
      {/* Status dot */}
      <div className={`h-1.5 w-1.5 rounded-full flex-shrink-0 ${getDotStyle()}`} />
      {/* Task content */}
      <span className={`text-sm ${getTextStyle()}`}>
        {task.description}
      </span>
    </div>
  );
}

export default TasksPreviewBlock;
