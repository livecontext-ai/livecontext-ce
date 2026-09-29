/**
 * The two tabs of the agent side panel, kept apart from `AgentPanelContent` so a
 * caller can name a tab without importing the panel itself, which pulls in the
 * agent fleet canvas and the whole conversation UI.
 */
export const AGENT_CONVERSATION_TAB = '__conversation__';
export const AGENT_CONFIGURATION_TAB = '__configuration__';

export type AgentPanelTab = typeof AGENT_CONVERSATION_TAB | typeof AGENT_CONFIGURATION_TAB;
