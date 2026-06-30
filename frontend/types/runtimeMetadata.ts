export interface ToolRuntimeOperation {
  kind?: string;
  name?: string;
  displayName?: string | null;
}

export interface ToolRuntimeClient {
  module?: string;
  className?: string;
  runtime?: string;
  service?: string;
}

export interface ToolRuntimeMetadata {
  type: string;
  status?: string;
  baseUrl?: string;
  client?: ToolRuntimeClient;
  operation?: ToolRuntimeOperation;
  sourceFiles?: string[];
  notes?: string[];
  [key: string]: unknown;
}
