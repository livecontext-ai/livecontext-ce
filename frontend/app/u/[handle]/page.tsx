import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { LandingShell } from '@/components/landing/LandingShell';
import { IS_CE } from '@/lib/edition';
import { fetchPublicProfile, fetchPublicationsByPublisher } from '@/lib/marketplace/publicProfiles';
import PublicationCardSsr from '@/app/marketplace/_components/PublicationCardSsr';
import { fetchPublicBadges, publicBadgeName } from '@/lib/marketplace/publicBadges';
import { PublicBadgeShowcase } from '@/components/badges/PublicBadgeShowcase';
import { VerifiedBadgeIcon } from '@/components/profile/VerifiedBadgeIcon';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

// Same window as the listing pages: a profile changes about as often as the
// listings it shows.
export const revalidate = 900;

export async function generateMetadata({
  params,
}: {
  params: Promise<{ handle: string }>;
}): Promise<Metadata> {
  const { handle } = await params;
  const profile = await fetchPublicProfile(handle);
  if (!profile) return {};

  const name = profile.displayName ?? `@${profile.handle}`;
  const title = `${name} - LiveContext`;
  const description = profile.bio ?? `Apps and automations published by ${name} on LiveContext.`;

  return {
    title,
    description,
    alternates: { canonical: `${SITE_URL}/u/${profile.handle}` },
    openGraph: {
      siteName: 'LiveContext',
      title,
      description,
      url: `${SITE_URL}/u/${profile.handle}`,
      type: 'profile',
      images: [
        { url: '/og-image.jpg', width: 1200, height: 630, alt: name },
      ],
    },
    twitter: { card: 'summary_large_image', title, description, images: ['/og-image.jpg'] },
    // Indexing follows the owner's explicit choice: only the PUBLIC visibility
    // state opts a profile in. UNLISTED (the default, and what every
    // pre-existing account was migrated to) keeps a working, linkable page that
    // search engines are told to skip. `follow` stays on regardless, so the
    // listings linked from the page still pass link equity.
    robots:
      IS_CE || !profile.searchIndexable ? { index: false, follow: true } : undefined,
  };
}

export default async function PublicProfilePage({
  params,
}: {
  params: Promise<{ handle: string }>;
}) {
  const { handle } = await params;
  const profile = await fetchPublicProfile(handle);

  // Unknown handle and PRIVATE profile are indistinguishable here, exactly as
  // the backend intends: a probe must not be able to tell them apart.
  if (!profile) notFound();

  // Both reads are independent and both degrade to an empty list, so one slow
  // service cannot delay the other half of the page.
  const [publications, badges] = await Promise.all([
    fetchPublicationsByPublisher(profile.userId),
    fetchPublicBadges(profile.userId),
  ]);
  const name = profile.displayName ?? `@${profile.handle}`;

  return (
    <LandingShell>
      <div className="mx-auto w-full max-w-5xl px-3 py-6 sm:px-6 md:py-10">
        <header className="mb-8">
          <div className="flex min-w-0 items-center gap-2">
            <h1 className="text-2xl font-semibold text-[var(--text-primary)] md:text-3xl">{name}</h1>
            <VerifiedBadgeIcon verified={profile.verified} size="lg" />
          </div>
          <p className="mt-1 text-sm text-[var(--text-muted)]">@{profile.handle}</p>
          {profile.bio && (
            <p className="mt-4 max-w-2xl text-sm text-[var(--text-secondary)]">{profile.bio}</p>
          )}
        </header>

        <PublicBadgeShowcase badges={badges} nameFor={publicBadgeName} heading="Trophies" />

        <h2 className="mb-4 text-lg font-semibold text-[var(--text-primary)]">
          Published apps
        </h2>

        {publications.length === 0 ? (
          <p className="text-sm text-[var(--text-secondary)]">
            {name} has not published anything yet.
          </p>
        ) : (
          // Same grid rhythm as the marketplace index: the card grew a
          // thumbnail, and a 1rem gap left the covers touching.
          <div className="grid grid-cols-1 gap-x-5 gap-y-8 sm:grid-cols-2 lg:grid-cols-3">
            {publications.map((publication) => (
              // h3: this grid already sits under the "Published apps" h2.
              // Every card on this page is by the profile owner, so their badge is
              // already resolved - no per-card lookup.
              <PublicationCardSsr
                key={publication.id}
                publication={publication}
                headingLevel="h3"
                publisherVerified={profile.verified}
              />
            ))}
          </div>
        )}
      </div>
    </LandingShell>
  );
}
