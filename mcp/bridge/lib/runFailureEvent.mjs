// The one place the bridge announces a FAILED run on the conversation stream.
//
// A chat reads a stream `error` event as the END of the turn: it closes the bubble and
// stops listening to that stream. So the bridge may publish one only when the turn really
// is over for the person watching. It is not when the caller re-runs the failed turn
// elsewhere on the SAME stream: the execution-link fallback in agent-service retries a
// linked bridge run on the billed pair's direct API with the same streamId, and a bridge
// `error` published first made the whole retried reply stream to nobody. Such a caller
// publishes the stream's terminal event itself and says so with CALLER_PUBLISHES_FAILURE_KEY
// in the request credentials (AgentExecutionRequestDto.withCallerPublishingFailure). The
// HTTP answer is unchanged either way: the failure still reaches the caller, and that
// failure is what triggers its retry.
//
// Absent flag = today's behaviour: a caller with no fallback keeps the bridge's event.

/**
 * A CLI message one adapter could not handle is NOT a run failure: the CLI keeps going and
 * the turn usually completes, so it is never published on the stream (a chat reads `error`
 * as the end of the turn). It still has to be countable, because it can mean a lost chunk
 * or tool card. The bridge has no runtime counters (its /metrics renders scrape-time gauges
 * only), so this tag is the stable counting key: count the log lines that START with it.
 * It is the tag this line always carried, so an existing log query keeps matching.
 */
export const ADAPTER_HANDLER_ERROR_TAG = '[BRIDGE:handleMessage]';

/**
 * The one log line for an adapter handler error: the tag first, then provider, message type
 * and stream, then the full stack (hiding the stack is how the 2026-04-08 tool-double-execution
 * incident stayed invisible).
 */
export function adapterHandlerErrorLine({ provider, msgType, streamId, error }) {
  const detail = error && (error.stack || error.message) ? (error.stack || error.message) : String(error);
  return `${ADAPTER_HANDLER_ERROR_TAG} provider=${provider || 'adapter'} msg.type=${msgType} `
    + `stream=${streamId}: ${detail}`;
}

/** Credentials key, matched exactly. Spelled once, in AgentExecutionRequestDto, on the Java side. */
export const CALLER_PUBLISHES_FAILURE_KEY = '__callerPublishesFailure__';

/**
 * @param {object|null|undefined} credentials the request credentials map
 * @returns {boolean} true when the caller publishes the terminal event of a failed run itself.
 *   Accepts the boolean or its string form, like the other credential flags the Java side writes.
 */
export function isFailurePublishedByCaller(credentials) {
  const value = credentials ? credentials[CALLER_PUBLISHES_FAILURE_KEY] : undefined;
  return value === true || value === 'true';
}

/**
 * Publish the terminal `error` of a failed run, unless the caller publishes it itself.
 * Never throws: the run's outcome is already decided and the HTTP answer still has to go out.
 *
 * @param {{ publishError: (message: string) => Promise<unknown>, streamId?: string }} publisher
 * @param {string} message
 * @param {{ callerPublishesFailure?: boolean, log?: (line: string) => void }} [options]
 * @returns {Promise<boolean>} true when the error event was published
 */
export async function announceRunFailure(publisher, message, { callerPublishesFailure = false, log = console.warn } = {}) {
  if (callerPublishesFailure) {
    log(`[BRIDGE] run failed on stream ${publisher && publisher.streamId}: ${message} `
      + '(not announced on the stream: the caller publishes the failure itself)');
    return false;
  }
  try {
    await publisher.publishError(message);
  } catch {
    // Best-effort, as before: a Redis hiccup must never replace the HTTP failure answer.
  }
  return true;
}
