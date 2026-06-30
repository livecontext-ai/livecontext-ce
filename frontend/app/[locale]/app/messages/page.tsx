import { redirect } from 'next/navigation';

/**
 * Messages has no standalone page - it is a pure sidebar *view*. The Chats⇄Messages toggle
 * flips the sidebar list while the main panel stays on Home (new chat); only a specific
 * thread carries a URL (/app/messages/[threadId]). Any stale bare /app/messages link
 * (old bookmark, deep link) therefore lands back on Home instead of an empty placeholder.
 */
export default function MessagesIndexPage() {
  redirect('/app/chat');
}
