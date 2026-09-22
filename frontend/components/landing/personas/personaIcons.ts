import { Clapperboard, Gauge, Headphones, Megaphone, Target, UserRoundCheck, type LucideIcon } from 'lucide-react';
import type { PersonaKey } from './personas';

/**
 * One icon per persona, shared by every surface that names a persona: the hero pill
 * nav on the homepage and on each persona page, and the persona page eyebrow. Kept
 * out of the client component so a server component can import it too.
 */
export const PERSONA_ICONS: Record<PersonaKey, LucideIcon> = {
  ops: Gauge,
  support: Headphones,
  creator: Clapperboard,
  sales: Target,
  marketing: Megaphone,
  recruiting: UserRoundCheck,
};
