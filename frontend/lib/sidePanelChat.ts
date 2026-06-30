/**
 * Cross-component bridge for programmatically dispatching a message to the
 * side-panel AI Chat tab (`ChatPanelContent`).
 *
 * The chat tab may not be mounted at the moment a caller wants to send a
 * message (e.g. the user just clicked a button that opens the panel for the
 * first time). The queue holds the message until a subscriber registers, so
 * either ordering works:
 *   - subscribe → queue   → listener invoked immediately
 *   - queue   → subscribe → listener invoked on subscribe
 */

type Listener = (message: string) => void;

let pendingMessage: string | null = null;
const listeners = new Set<Listener>();

/** Send a message into the side-panel AI Chat. */
export function queueAiChatMessage(message: string): void {
  if (listeners.size > 0) {
    listeners.forEach((l) => l(message));
  } else {
    pendingMessage = message;
  }
}

/** Subscribe to programmatic messages. Returns an unsubscribe function. */
export function subscribeAiChatMessages(listener: Listener): () => void {
  listeners.add(listener);
  if (pendingMessage !== null) {
    const msg = pendingMessage;
    pendingMessage = null;
    listener(msg);
  }
  return () => {
    listeners.delete(listener);
  };
}
