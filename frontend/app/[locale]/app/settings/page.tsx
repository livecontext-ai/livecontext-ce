import { redirect } from 'next/navigation';

/**
 * Settings page - Server-side redirect to overview
 * Using server redirect instead of client-side for faster navigation
 */
export default function AppSettingsPage() {
  redirect('/app/settings/overview');
}
