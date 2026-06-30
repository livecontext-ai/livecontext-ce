import { redirect } from 'next/navigation';

/**
 * The standalone task board was a pure duplicate of the aggregated Board's Tasks tab
 * (both render <TaskBoardPage />). The /app/tasks route now redirects to the Board so
 * there is a single task board. Task notifications point straight at the Board too.
 */
export default function AppTasksPage() {
  redirect('/app/board?resource=task');
}
