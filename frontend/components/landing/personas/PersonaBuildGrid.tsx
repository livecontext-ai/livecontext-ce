import CreatorBuildStudio from './CreatorBuildStudio';
import MarketingBuildStudio from './MarketingBuildStudio';
import OpsBuildStudio from './OpsBuildStudio';
import RecruitingBuildStudio from './RecruitingBuildStudio';
import SalesBuildStudio from './SalesBuildStudio';
import SupportBuildStudio from './SupportBuildStudio';
import type { PersonaKey } from './personas';

/**
 * What this persona can build, beyond the one story the hero plays.
 *
 * <p>The hero runs a single example end to end, which answers "how does it work" and
 * leaves "what else" unanswered. This section is the answer, and every persona now shows
 * the product rather than describing it: creator its posts, operations the four artefacts a
 * run leaves behind, support one request through the four things that happen to it, sales
 * the quote and the row it leaves, marketing one brief leaving in three directions,
 * recruiting the people it is about.
 *
 * <p>It replaced a grid of three text cards per persona, each listing a trigger, an agent
 * step and an approval as chips. The copy was accurate and nobody read it: three paragraphs
 * side by side are exactly what a visitor skips, and the page was asking them to take on
 * trust the one thing it could have shown.
 *
 * <p>Six personas, six sections, deliberately: a shared one was written first and every
 * card in it was the same drawing with different words, which is the failure mode this
 * whole section exists to avoid.
 */
export default async function PersonaBuildGrid({ persona, locale }: { persona: PersonaKey; locale: string }) {
  // Creator is CALLED rather than mounted: awaiting it here keeps ONE async boundary for
  // the section, so a test that awaits this component gets its markup too instead of an
  // unresolved element. The others are client components and mount normally.
  if (persona === 'creator') return CreatorBuildStudio({ locale });
  if (persona === 'ops') return <OpsBuildStudio />;
  if (persona === 'support') return <SupportBuildStudio />;
  if (persona === 'sales') return <SalesBuildStudio />;
  if (persona === 'marketing') return <MarketingBuildStudio />;
  return <RecruitingBuildStudio />;
}
