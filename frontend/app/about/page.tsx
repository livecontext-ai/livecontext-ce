import AboutInformationContent from '@/components/about/AboutInformationContent';
import { LandingShell } from '@/components/landing/LandingShell';

export const metadata = {
  title: 'About - LiveContext',
  description: 'Learn about LiveContext, contact the team, and find answers to common questions.',
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
