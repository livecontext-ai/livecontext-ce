'use client';

import { useParams } from 'next/navigation';
import { ProjectDetailView } from '@/components/views/project/ProjectDetailView';

export default function ProjectDetailPage() {
  const params = useParams();
  const projectId = params.projectId as string;

  return <ProjectDetailView projectId={projectId} />;
}
