import AboutInformationContent from '@/components/about/AboutInformationContent';
import { LandingShell } from '@/components/landing/LandingShell';
import { IS_CE } from '@/lib/edition';
import { socialCard } from '@/lib/seo/socialCard';

export const metadata = {
  title: 'About',
  description: 'Learn about LiveContext, contact the team, and find answers to common questions.',
  alternates: { canonical: '/about' },
  ...socialCard({ title: 'About', description: 'Learn about LiveContext, contact the team, and find answers to common questions.', path: '/about' }),
  // Self-hosted deployments must never index marketing pages.
  robots: IS_CE ? { index: false, follow: false } : undefined,
};

export default function AboutPage() {
  return (
    <LandingShell>
      <div className="mx-auto w-full max-w-4xl px-3 py-4 sm:px-6 md:py-8">
        <AboutInformationContent />
      </div>
    </LandingShell>
  );
}
