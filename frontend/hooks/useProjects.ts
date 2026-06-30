'use client';

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/api/query-keys';
import { projectService } from '@/lib/api/orchestrator/project.service';
import type {
  Project,
  ProjectDetailResponse,
  CreateProjectRequest,
  UpdateProjectRequest,
} from '@/lib/api/orchestrator/project.types';

/**
 * Hook to fetch all user's projects.
 */
export function useProjects() {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: queryKeys.project.all(),
    queryFn: () => projectService.getProjects(),
    staleTime: 2 * 60 * 1000, // 2 minutes
  });

  return {
    projects: data || [],
    loading: isLoading,
    error: error?.message || null,
    refetch,
  };
}

/**
 * Hook to fetch a single project with members and resource counts.
 */
export function useProject(projectId: string | null) {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: queryKeys.project.detail(projectId || ''),
    queryFn: () => projectService.getProject(projectId!),
    enabled: !!projectId,
    staleTime: 60 * 1000, // 1 minute
  });

  return {
    project: data || null,
    loading: isLoading,
    error: error?.message || null,
    refetch,
  };
}

/**
 * Hook to fetch all resources for a project.
 */
export function useProjectResources(projectId: string | null) {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: queryKeys.project.resources(projectId || ''),
    queryFn: () => projectService.getProjectResources(projectId!),
    enabled: !!projectId,
    staleTime: 60 * 1000,
  });

  return {
    resources: data || null,
    loading: isLoading,
    error: error?.message || null,
    refetch,
  };
}

/**
 * Hook to get permission-based UI flags for a project.
 */
export function useProjectPermissions(project: Project | ProjectDetailResponse | null) {
  const role = project?.currentUserRole;

  return {
    canEdit: role === 'OWNER' || role === 'EDITOR',
    canDelete: role === 'OWNER',
    canAssignResources: role === 'OWNER' || role === 'EDITOR',
    isViewer: role === 'VIEWER',
    isOwner: role === 'OWNER',
    role: role || null,
  };
}

/**
 * Hook for project mutations (create, update, delete).
 */
export function useProjectMutations() {
  const queryClient = useQueryClient();

  const invalidateProjects = () => {
    queryClient.invalidateQueries({ queryKey: queryKeys.project.all() });
  };

  const createProject = useMutation({
    mutationFn: (data: CreateProjectRequest) => projectService.createProject(data),
    onSuccess: invalidateProjects,
  });

  const updateProject = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateProjectRequest }) =>
      projectService.updateProject(id, data),
    onSuccess: (_, variables) => {
      invalidateProjects();
      queryClient.invalidateQueries({ queryKey: queryKeys.project.detail(variables.id) });
    },
  });

  const deleteProject = useMutation({
    mutationFn: (id: string) => projectService.deleteProject(id),
    onSuccess: invalidateProjects,
  });

  return { createProject, updateProject, deleteProject };
}

/**
 * Hook for resource assignment mutations.
 */
export function useResourceAssignment(projectId: string) {
  const queryClient = useQueryClient();

  const assignResource = useMutation({
    mutationFn: (data: { resourceType: 'workflow' | 'interface' | 'datasource' | 'agent' | 'application' | 'file'; resourceId: string }) =>
      projectService.assignResource(projectId, data),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.project.detail(projectId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.project.resources(projectId) });
      queryClient.invalidateQueries({ queryKey: ['resources', variables.resourceType] });
    },
  });

  const removeResource = useMutation({
    mutationFn: (data: { resourceType: string; resourceId: string }) =>
      projectService.removeResource(projectId, data.resourceType, data.resourceId),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.project.detail(projectId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.project.resources(projectId) });
      queryClient.invalidateQueries({ queryKey: ['resources', variables.resourceType] });
    },
  });

  return { assignResource, removeResource };
}
