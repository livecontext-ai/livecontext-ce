export interface DefaultSkill {
  id: string;
  name: string;
  description: string;
  icon: string;
  instructions: string;
}

/**
 * Frontend reference for default skills, used only as a display fallback when
 * the API has not (yet) returned the tenant's seeded defaults.
 *
 * Intentionally empty: the former "Deep Research" built-in was removed (global
 * skills are now distributed from the cloud via the signed skill bundle), so
 * the backend no longer seeds any hard-coded default. Keep the constant so the
 * fallback merge in CreateAgentModal stays a no-op instead of crashing.
 */
export const DEFAULT_SKILLS: DefaultSkill[] = [];
