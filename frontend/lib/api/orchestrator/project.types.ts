/**
 * Project Types
 *
 * Type definitions for the project system.
 */

export type ProjectRole = 'OWNER' | 'EDITOR' | 'VIEWER';

export interface Project {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  color: string;
  icon: string;
  ownerId: string;
  isArchived: boolean;
  createdAt: string;
  updatedAt: string;
  currentUserRole: ProjectRole;
  resourceCounts?: ProjectResourceCounts;
}

export interface ProjectResourceCounts {
  workflows: number;
  interfaces: number;
  agents: number;
  datasources: number;
  applications: number;
  files: number;
}

export interface ProjectResources {
  workflows: any[];
  interfaces: any[];
  agents: any[];
  datasources: any[];
  /** Per-datasource row count (id-as-string → count) - feeds the Tables tab card preview. */
  datasourceRowCounts?: Record<string, number>;
  /** Per-datasource first-N sample rows (id-as-string → row data) - feeds the Tables tab card mini-table. */
  datasourceSampleRows?: Record<string, Array<Record<string, unknown>>>;
  applications: any[];
  files: any[];
}

export interface CreateProjectRequest {
  name: string;
  description?: string;
  color?: string;
  icon?: string;
}

export interface UpdateProjectRequest {
  name?: string;
  description?: string;
  color?: string;
  icon?: string;
  isArchived?: boolean;
}

export interface AssignResourceRequest {
  resourceType: 'workflow' | 'interface' | 'datasource' | 'agent' | 'application' | 'file';
  resourceId: string;
}

export interface ProjectListResponse {
  projects: Project[];
  count: number;
}

export interface ProjectDetailResponse extends Project {
  resourceCounts: ProjectResourceCounts;
}
