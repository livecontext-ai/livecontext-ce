import AboutInformationContent from '@/components/about/AboutInformationContent';
import VersionCard from '@/components/settings/VersionCard';
import { IS_CE } from '@/lib/edition';

export default function InformationPage() {
  // The Version card is shown in the CE (self-hosted) edition ONLY - cloud users
  // do not see a version. (It is also in-app only: the shared AboutInformationContent
  // is reused on the public About page, so the card lives here in the wrapper.)
  return (
    <div className="space-y-8">
      {IS_CE && <VersionCard />}
      <AboutInformationContent />
    </div>
  );
}
