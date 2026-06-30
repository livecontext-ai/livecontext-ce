/**
 * Centralisation des Query Keys pour eviter les requetes multiples
 * Toutes les query keys doivent etre definies ici pour assurer la coherence
 */

export const queryKeys = {
  // User data queries
  user: {
    status: (userId?: string) => ['user', 'status', userId || 'anonymous'],
    profile: (userId?: string) => ['user', 'profile', userId || 'anonymous'],
    monetization: (userId?: string) => ['user', 'monetization', userId || 'anonymous'],
    apis: (userId?: string) => ['user', 'apis', userId || 'anonymous'],
    subscription: (userId?: string) => ['user', 'subscription', userId || 'anonymous'],
    creditBalance: (userId?: string) => ['user', 'creditBalance', userId || 'anonymous'],
  },
  
  // API specific queries
  api: {
    byId: (apiId: string) => ['api', 'byId', apiId],
    tools: (apiId: string) => ['api', 'tools', apiId],
    details: (apiId: string) => ['api', 'details', apiId],
  },
  
  // Plans and billing
  plans: {
    all: () => ['plans', 'all'],
    byCode: (planCode: string) => ['plans', 'byCode', planCode],
  },

  // Billing - PAYG one-time top-up surface (V250)
  billing: {
    paygTiers: () => ['billing', 'payg-tiers'],
  },

  // Agent execution metrics
  agent: {
    executions: (agentId: string) => ['agent', 'executions', agentId],
    execution: (execId: string) => ['agent', 'execution', execId],
    conversation: (execId: string) => ['agent', 'conversation', execId],
    toolCalls: (execId: string) => ['agent', 'toolCalls', execId],
    toolStats: (agentId: string) => ['agent', 'toolStats', agentId],
    iterations: (execId: string) => ['agent', 'iterations', execId],
  },

  // Projects
  project: {
    all: () => ['projects', 'all'],
    detail: (projectId: string) => ['projects', 'detail', projectId],
    resources: (projectId: string) => ['projects', 'resources', projectId],
    members: (projectId: string) => ['projects', 'members', projectId],
    invitations: (projectId: string) => ['projects', 'invitations', projectId],
  },
} as const;
