import * as React from 'react';
import type { WorkflowSuggestion } from '../constants/workflowSuggestions';

interface UseTypingSuggestionResult {
  typingSuggestionId: string | null;
  chatInput: string;
  setChatInput: React.Dispatch<React.SetStateAction<string>>;
  handleSuggestionClick: (suggestion: WorkflowSuggestion) => void;
  handleChatInputChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => void;
  stopTypingAnimation: () => void;
}

export function useTypingSuggestion(): UseTypingSuggestionResult {
  const [typingSuggestionId, setTypingSuggestionId] = React.useState<string | null>(null);
  const [chatInput, setChatInput] = React.useState('');
  const typingIntervalRef = React.useRef<NodeJS.Timeout | null>(null);

  const stopTypingAnimation = React.useCallback(() => {
    if (typingIntervalRef.current) {
      clearInterval(typingIntervalRef.current);
      typingIntervalRef.current = null;
    }
    setTypingSuggestionId(null);
  }, []);

  const handleSuggestionClick = React.useCallback((suggestion: WorkflowSuggestion) => {
    if (typingIntervalRef.current) {
      clearInterval(typingIntervalRef.current);
      typingIntervalRef.current = null;
    }

    setTypingSuggestionId(suggestion.id);
    setChatInput('');

    const text = suggestion.prompt;
    let currentIndex = 0;

    typingIntervalRef.current = setInterval(() => {
      if (currentIndex < text.length) {
        const charsToAdd = Math.random() > 0.7 ? 3 : Math.random() > 0.4 ? 2 : 1;
        const nextIndex = Math.min(currentIndex + charsToAdd, text.length);
        setChatInput(text.substring(0, nextIndex));
        currentIndex = nextIndex;
      } else {
        if (typingIntervalRef.current) {
          clearInterval(typingIntervalRef.current);
          typingIntervalRef.current = null;
        }
        setTypingSuggestionId(null);
        // Open the chat panel, then deliver the prompt. The two events must be
        // separated: if the right side panel is closed, AppHeader opens it via
        // a state update, but `WorkflowPanelContent` (which listens for
        // `workflowSuggestionPrompt`) only mounts on the next render. Firing
        // both synchronously loses the prompt event. Mirrors the pattern in
        // `BuilderCanvas.handleCanvasSendMessage`.
        window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', {
          detail: { isOpen: true, view: 'chat' }
        }));
        setTimeout(() => {
          window.dispatchEvent(new CustomEvent('workflowSuggestionPrompt', {
            detail: { prompt: text }
          }));
        }, 100);
      }
    }, 15);
  }, []);

  const handleChatInputChange = React.useCallback((e: React.ChangeEvent<HTMLTextAreaElement>) => {
    stopTypingAnimation();
    setChatInput(e.target.value);
  }, [stopTypingAnimation]);

  // Cleanup typing interval on unmount
  React.useEffect(() => {
    return () => {
      if (typingIntervalRef.current) {
        clearInterval(typingIntervalRef.current);
      }
    };
  }, []);

  return {
    typingSuggestionId,
    chatInput,
    setChatInput,
    handleSuggestionClick,
    handleChatInputChange,
    stopTypingAnimation,
  };
}
