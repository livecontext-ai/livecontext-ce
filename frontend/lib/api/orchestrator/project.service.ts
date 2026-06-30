/**
 * Project Service
 *
 * Handles project CRUD and resource assignment.
 */

import { apiClient } from '../api-client';
import type {
  Project,
  ProjectListResponse,
  ProjectDetailResponse,
  CreateProjectRequest,
  UpdateProjectRequest,
  AssignResourceRequest,
  ProjectResourceCounts,
  ProjectResources,
} from './project.types';

export class ProjectService {
  // ===================== Project CRUD =====================

  async getProjects(): Promise<Project[]> {
    const data = await apiClient.get<ProjectListResponse>('/projects');
    return data.projects || [];
  }

  async getProject(id: string): Promise<ProjectDetailResponse> {
    return apiClient.get<ProjectDetailResponse>(`/projects/${id}`);
  }

  async createProject(request: CreateProjectRequest): Promise<Project> {
    return apiClient.post<Project>('/projects', request);
  }

  async updateProject(id: string, request: UpdateProjectRequest): Promise<Project> {
    return apiClient.put<Project>(`/projects/${id}`, request);
  }

  async deleteProject(id: string): Promise<void> {
    await apiClient.delete(`/projects/${id}`);
  }

  // ===================== Resources =====================

  async assignResource(projectId: string, request: AssignResourceRequest): Promise<void> {
    await apiClient.post(`/projects/${projectId}/resources`, request);
  }

  async removeResource(projectId: string, resourceType: string, resourceId: string): Promise<void> {
    await apiClient.delete(`/projects/${projectId}/resources/${resourceType}/${resourceId}`);
  }

  async getResourceCounts(projectId: string): Promise<ProjectResourceCounts> {
    return apiClient.get<ProjectResourceCounts>(`/projects/${projectId}/resources`);
  }

  async getProjectResources(projectId: string): Promise<ProjectResources> {
    return apiClient.get<ProjectResources>(`/projects/${projectId}/resources/details`);
  }
}

export const projectService = new ProjectService();
