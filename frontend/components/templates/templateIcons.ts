/**
 * Explicit slug -> lucide component map.
 *
 * Deliberately NOT a dynamic `import(\`lucide-react/\${name}\`)` or an index
 * lookup on the barrel: either would defeat tree-shaking and pull the whole
 * icon set into the bundle of the list pages.
 */

import {
  AlertTriangle,
  Bot,
  CalendarDays,
  Gauge,
  GitBranch,
  List,
  ListChecks,
  Monitor,
  Play,
  ShieldCheck,
  Split,
  Table,
  Users,
  Workflow,
  type LucideIcon,
} from 'lucide-react';

export const TEMPLATE_ICONS: Record<string, LucideIcon> = {
  AlertTriangle,
  Bot,
  CalendarDays,
  Gauge,
  GitBranch,
  List,
  ListChecks,
  Monitor,
  Play,
  ShieldCheck,
  Split,
  Table,
  Users,
  Workflow,
};

/** Falls back to a neutral icon so an unmapped slug never renders a hole. */
export function templateIcon(name: string): LucideIcon {
  return TEMPLATE_ICONS[name] ?? Workflow;
}
