export interface DefaultSkill {
  id: string;
  name: string;
  description: string;
  icon: string;
  instructions: string;
}

/**
 * Frontend reference for default skills.
 * These are seeded as real DB entities per tenant on first access.
 * This list is used as fallback display and for reset reference.
 * The actual skills come from the API (with real UUIDs and defaultKey set).
 */
export const DEFAULT_SKILLS: DefaultSkill[] = [
  {
    id: 'default:deep_research',
    name: 'Deep Research',
    description: 'Autonomous deep web research with iterative queries and synthesis',
    icon: 'search',
    instructions: '# Deep Research Skill\n\nConduct thorough, autonomous web research using web_search tool.',
  },
];
