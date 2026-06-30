/**
 * Conversation Hooks
 *
 * Split from the monolithic useConversationHistory for better maintainability:
 *
 * - useConversationList: Conversation list with React Query, pagination, search
 * - useMessages: Message loading, pagination, local operations
 * - useConversationMutations: Create, update, delete operations
 *
 * For backward compatibility, useConversationHistory in the parent folder
 * still provides the combined API.
 */

export { useConversationList, type UseConversationListOptions, type UseConversationListReturn } from './useConversationList';
export { useMessages, type UseMessagesReturn } from './useMessages';
export { useConversationMutations, type UseConversationMutationsOptions, type UseConversationMutationsReturn } from './useConversationMutations';
