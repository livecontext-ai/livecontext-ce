import { MessagesThreadClient } from '@/components/dm/MessagesThreadClient';

interface MessagesThreadPageProps {
  params: Promise<{ threadId: string }>;
}

/** A single DM conversation (the live thread view). */
export default async function MessagesThreadPage({ params }: MessagesThreadPageProps) {
  const { threadId } = await params;
  return <MessagesThreadClient threadId={threadId} />;
}
