import { LandingShell } from '@/components/landing/LandingShell';
import { UnderConstruction } from '@/components/landing/UnderConstruction';

export const metadata = {
  title: 'Changelog - LiveContext',
  description: 'What we shipped, when. Product updates and release notes.',
};

export default function ChangelogPage() {
  return (
    <LandingShell>
      <UnderConstruction
        title="Changelog"
        description="We're putting this together. Check back soon for product updates and release notes."
      />
    </LandingShell>
  );
}
