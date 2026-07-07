import AboutInformationContent from '@/components/about/AboutInformationContent';
import VersionCard from '@/components/settings/VersionCard';
import OptionalComponentsCard from '@/components/settings/OptionalComponentsCard';
import { IS_CE } from '@/lib/edition';

export default function InformationPage() {
  // The Version and Optional-components cards are shown in the CE (self-hosted)
  // edition ONLY - cloud users see neither a version nor opt-in components.
  // (Also in-app only: the shared AboutInformationContent is reused on the
  // public About page, so the cards live here in the wrapper.)
  return (
    <div className="space-y-8">
      {IS_CE && <VersionCard />}
      {IS_CE && <OptionalComponentsCard />}
      <AboutInformationContent />
    </div>
  );
}
