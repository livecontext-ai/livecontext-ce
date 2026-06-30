/**
 * Helper function to check if a message content represents a workflow
 */
export function isWorkflowMessage(content: string): boolean {
  try {
    const parsed = JSON.parse(content);
    return parsed.type === '__WORKFLOW__' && parsed.nodes && parsed.edges;
  } catch {
    return false;
  }
}

/**
 * Helper function to extract workflow data from message content
 * Returns inline nodes/edges if the message contains a workflow template
 */
export function extractWorkflowData(content: string): { nodes: any[]; edges: any[] } | null {
  try {
    const parsed = JSON.parse(content);
    if (parsed.type === '__WORKFLOW__' && parsed.nodes && parsed.edges) {
      return { nodes: parsed.nodes, edges: parsed.edges };
    }
  } catch {
    // Not a workflow message
  }
  return null;
}
