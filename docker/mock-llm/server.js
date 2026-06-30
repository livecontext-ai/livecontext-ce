// Minimal OpenAI-compatible mock LLM for e2e slots (profile: e2e-mock-llm).
//
// Why it exists: live-LLM e2e (compaction lifecycle, agent runtime) normally
// needs a real provider key. For pipeline-shaped tests - does OUR machinery
// (post-turn hook, summariser gate/lock, json-completion hop, guarded JSONB
// persist, recall injection) behave end-to-end? - the model's actual prose is
// irrelevant, so this mock answers deterministically and free of charge.
//
// Contract (mirrors DeepSeekProvider / OpenAI chat-completions):
//  - POST <any path> with {model, messages, stream?}
//  - stream:false → {"choices":[{"message":{role,content[,tool_calls]},"finish_reason"}],"usage":{…}}
//  - stream:true  → SSE: role delta, content|tool_calls delta, stop/tool_calls chunk, usage chunk, [DONE]
//  - Routing:
//    * a system message containing "conversation-summarisation specialist"
//      (ColdSummarizerPromptBuilder.SYSTEM_PROMPT) → fixed COLD-summary envelope JSON.
//    * a user message matching __REQ_CRED__<service> or __REQ_CRED_FORCE__<service> AND no
//      prior tool result in the thread → emit a request_credential tool_call (drives the
//      credential-approval agent path deterministically, keyless). Once a tool result is
//      present (role:"tool"), reply with a short final text so the loop stops COMPLETED.
//    * anything else → echo the last user message so marker-based chat asserts keep working.
//  - GET /health → 200 (compose healthcheck).

const http = require('node:http');

const PORT = Number(process.env.PORT || 8095);

const SUMMARY_ENVELOPE = JSON.stringify({
  decisions: [
    { turn: 1, decision: 'Chose PostgreSQL 16 for the Atlas tenant store' },
  ],
  ids_resolved: { staging_gateway_token: 'GW-STG-1-7741' },
  errors_resolved: [
    { error: 'duplicate webhook deliveries', resolution: 'dedupe on the delivery id' },
  ],
  user_intents: ['Consolidate the three legacy CRMs into Atlas before Q3'],
  helped_actions: ['catalog.search'],
});

// Marker → request_credential tool call. `__REQ_CRED__gmail` (no force) or
// `__REQ_CRED_FORCE__gmail` (force=true). Letters/digits/underscore service token.
const REQ_CRED_MARKER = /__REQ_CRED(_FORCE)?__([a-z0-9_]+)/i;

// Extract the function names from an OpenAI-style request `tools` array.
function toolNamesOf(tools) {
  if (!Array.isArray(tools)) return [];
  return tools
    .map((t) => (t && t.function && t.function.name) ? t.function.name : (t && t.name) || null)
    .filter(Boolean);
}

// Decide the assistant turn. Returns either { content } (text) or { toolCalls } (tool turn).
// `tools` is the request's OpenAI tools array - used by the __ECHO_TOOLS__ probe below.
function decideResponse(messages, tools) {
  const list = Array.isArray(messages) ? messages : [];

  const isSummariser = list.some((m) => m
    && (m.role === 'system' || m.role === 'developer')
    && typeof m.content === 'string'
    && m.content.includes('conversation-summarisation specialist'));
  if (isSummariser) return { content: SUMMARY_ENVELOPE };

  // Second turn of a tool-calling exchange: a tool result is already in the thread →
  // stop with a short final text instead of re-emitting the tool call (avoids a loop).
  const hasToolResult = list.some((m) => m && m.role === 'tool');

  let lastUser = null;
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i];
    if (m && m.role === 'user' && typeof m.content === 'string' && m.content.trim()) {
      lastUser = m.content;
      break;
    }
  }

  if (lastUser) {
    const marker = lastUser.match(REQ_CRED_MARKER);
    if (marker && !hasToolResult) {
      const force = Boolean(marker[1]);
      const service = marker[2].toLowerCase();
      const args = { services: [service], reason: 'e2e credential check' };
      if (force) args.force = true;
      return {
        toolCalls: [{
          id: 'call_reqcred_1',
          type: 'function',
          function: { name: 'request_credential', arguments: JSON.stringify(args) },
        }],
      };
    }
    if (marker && hasToolResult) {
      return { content: 'Credential check handled.' };
    }
    // Tool-scope probe (keyless e2e): when the prompt carries __ECHO_TOOLS__, echo the NAMES
    // of the tools the request advertised so a test can assert toolsConfig scoping at the LLM
    // boundary - e.g. a workflow agent node with mode=none must NOT advertise `catalog`, while
    // mode=all does. The names ride in the assistant content (the mock has no host port and
    // returns a fixed usage), so the test reads them straight off the node/chat output.
    if (lastUser.includes('__ECHO_TOOLS__')) {
      return { content: `Echo: ${lastUser.slice(0, 500)} ||__TOOLNAMES__:${JSON.stringify(toolNamesOf(tools))}` };
    }
    return { content: `Echo: ${lastUser.slice(0, 2000)}` };
  }
  return { content: 'Echo: (no user message)' };
}

function usage() {
  return { prompt_tokens: 50, completion_tokens: 20, total_tokens: 70 };
}

function sendJson(res, resp, model) {
  const message = resp.toolCalls
    ? { role: 'assistant', content: null, tool_calls: resp.toolCalls }
    : { role: 'assistant', content: resp.content };
  const body = JSON.stringify({
    id: 'mock-cmpl-1',
    object: 'chat.completion',
    model: model || 'mock',
    choices: [{
      index: 0,
      message,
      finish_reason: resp.toolCalls ? 'tool_calls' : 'stop',
    }],
    usage: usage(),
  });
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(body);
}

function sendSse(res, resp, model) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  const chunk = (payload) => res.write(`data: ${JSON.stringify(payload)}\n\n`);
  const base = (delta, extra) => ({
    id: 'mock-cmpl-1', object: 'chat.completion.chunk', model: model || 'mock',
    choices: [{ index: 0, delta, ...(extra || {}) }],
  });

  chunk(base({ role: 'assistant' }));
  if (resp.toolCalls) {
    // OpenAI streaming tool_calls: index-keyed; whole call in one delta is valid.
    chunk(base({
      tool_calls: resp.toolCalls.map((tc, i) => ({
        index: i, id: tc.id, type: tc.type,
        function: { name: tc.function.name, arguments: tc.function.arguments },
      })),
    }));
    chunk(base({}, { finish_reason: 'tool_calls' }));
  } else {
    chunk(base({ content: resp.content }));
    chunk(base({}, { finish_reason: 'stop' }));
  }
  // stream_options.include_usage shape: final data chunk with empty choices.
  chunk({ id: 'mock-cmpl-1', object: 'chat.completion.chunk', model: model || 'mock',
    choices: [], usage: usage() });
  res.write('data: [DONE]\n\n');
  res.end();
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{"status":"ok","mock":"llm"}');
    return;
  }
  if (req.method !== 'POST') {
    res.writeHead(405).end();
    return;
  }
  let raw = '';
  req.on('data', (d) => { raw += d; });
  req.on('end', () => {
    let body = {};
    try { body = JSON.parse(raw || '{}'); } catch { /* keep {} */ }
    const resp = decideResponse(body.messages, body.tools);
    const summary = resp.toolCalls ? `tool_call:${resp.toolCalls[0].function.name}` : resp.content.slice(0, 80);
    console.log(`[mock-llm] ${body.stream ? 'SSE' : 'JSON'} model=${body.model} → ${summary}`);
    if (body.stream) sendSse(res, resp, body.model);
    else sendJson(res, resp, body.model);
  });
});

server.listen(PORT, () => console.log(`[mock-llm] listening on :${PORT}`));
