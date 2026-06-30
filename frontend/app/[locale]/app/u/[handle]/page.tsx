import ProfileContent from '@/components/profile/ProfileContent';

interface AppProfilePageProps {
  params: Promise<{ handle: string }>;
}

/**
 * In-app user profile: /app/u/{handle}. Keyed by the public @handle - a chosen, URL-safe slug
 * (never the numeric user/tenant id, never the real first/last name, never the raw OAuth account
 * username). Lives inside the authenticated app shell, and the profile read endpoint requires a
 * JWT, so logged-out visitors cannot view profiles. The page shows the display name + @handle.
 */
export default async function AppProfilePage({ params }: AppProfilePageProps) {
  const { handle } = await params;
  // Message-history layout: the scroll container fills the app shell's height and the
  // centered max-w-4xl column stretches, so sparse profiles (no apps yet) still occupy
  // the page instead of collapsing into a condensed strip.
  return (
    <div className="flex-1 min-h-0 overflow-y-auto py-6">
      <div className="mx-auto flex min-h-full w-full max-w-4xl flex-col px-4">
        <ProfileContent handle={handle} />
      </div>
    </div>
  );
}
