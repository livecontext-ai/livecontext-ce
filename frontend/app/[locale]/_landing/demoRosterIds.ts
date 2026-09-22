/**
 * The ids of the two demo datasets the landing's live product replicas render.
 *
 * <p><strong>They live in a module of their own because a Server Component reads them and
 * both replicas are client components.</strong> Next replaces every export of a `'use client'`
 * module with a client REFERENCE when a server module imports it, so a plain array imported
 * that way is not an array at render time: it fails with `.map is not a function`, at runtime
 * only. TypeScript types it correctly, and a vitest import gets the real module, so neither
 * the typecheck nor the suite can see it. That is exactly how it shipped to a dev server and
 * was caught by opening the page.
 *
 * <p>Both lists are joined to their component's own data BY POSITION (`AgentsShowcase` pairs
 * `DEMO_AGENTS[index % length]` with the translated entry at the same index), and to the
 * `LandingHome.agentTeam` / `LandingHome.agendaSchedules` messages BY ID. Tests pin both
 * directions, because a rename or a reorder puts one agent's avatar, model and badges on
 * another agent's name with nothing failing.
 */

/** In the order of `DEMO_AGENTS` in AgentsShowcase. */
export const DEMO_AGENT_IDS = ['nova', 'atlas', 'scout', 'ember', 'orion', 'sol', 'helix', 'aurora', 'midas', 'drift', 'sensei', 'fizz'] as const;

/** In the order of `SCHEDULES` in AgendaShowcase. */
export const DEMO_SCHEDULE_IDS = ['triage', 'leads', 'standup', 'digest', 'dashboard', 'research', 'social', 'community', 'backup', 'invoices'] as const;
