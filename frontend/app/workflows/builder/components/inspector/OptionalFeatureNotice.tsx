'use client';

import * as React from 'react';
import { AlertTriangle } from 'lucide-react';

/**
 * Compact amber inspector banner shown when a node option depends on an
 * OPTIONAL deployment component (screenshot/PDF renderer, browser agent)
 * that is not running on this installation. The message is translated;
 * the optional `command` is the literal enable command (never translated).
 */
export function OptionalFeatureNotice({ message, command }: {
  message: string;
  command?: string;
}) {
  return (
    <div className="mt-2 flex items-start gap-2 rounded-lg bg-amber-500/10 p-2.5">
      <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-500" />
      <div className="min-w-0">
        <p className="text-xs text-slate-600 dark:text-slate-300">{message}</p>
        {command && (
          <code className="mt-1 block break-all rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-700 dark:bg-slate-800 dark:text-slate-300">
            {command}
          </code>
        )}
      </div>
    </div>
  );
}
