// Minimal RESP responder so the real server.mjs can boot in a test without a Redis server:
// PING -> PONG, INFO -> a "ready" info block, everything else -> OK / empty. Enough for ioredis
// to connect and for the bridge's boot-time pings; not a Redis.

import { createServer } from 'node:net';

function parseCommands(buffer) {
  // Returns [commands, rest]; each command is an array of strings. Handles RESP arrays only.
  const commands = [];
  let offset = 0;
  const text = buffer.toString('utf8');
  while (offset < text.length) {
    if (text[offset] !== '*') {
      const eol = text.indexOf('\r\n', offset);
      if (eol < 0) break;
      commands.push(text.slice(offset, eol).split(' '));
      offset = eol + 2;
      continue;
    }
    const eol = text.indexOf('\r\n', offset);
    if (eol < 0) break;
    const count = Number(text.slice(offset + 1, eol));
    let cursor = eol + 2;
    const parts = [];
    let complete = true;
    for (let i = 0; i < count; i++) {
      const lenEol = text.indexOf('\r\n', cursor);
      if (lenEol < 0) { complete = false; break; }
      const len = Number(text.slice(cursor + 1, lenEol));
      const start = lenEol + 2;
      if (text.length < start + len + 2) { complete = false; break; }
      parts.push(text.slice(start, start + len));
      cursor = start + len + 2;
    }
    if (!complete) break;
    commands.push(parts);
    offset = cursor;
  }
  return [commands, Buffer.from(text.slice(offset), 'utf8')];
}

function reply(cmd) {
  const name = String(cmd[0] || '').toUpperCase();
  if (name === 'PING') return '+PONG\r\n';
  if (name === 'INFO') {
    const body = '# Server\r\nredis_version:7.2.0\r\nloading:0\r\n';
    return `$${Buffer.byteLength(body)}\r\n${body}\r\n`;
  }
  if (name === 'GET' || name === 'HGET') return '$-1\r\n';
  if (name === 'EXISTS' || name === 'PUBLISH' || name === 'DEL' || name === 'EXPIRE') return ':0\r\n';
  return '+OK\r\n';
}

export function startFakeRedis() {
  return new Promise((resolve) => {
    const server = createServer((socket) => {
      let pending = Buffer.alloc(0);
      socket.on('data', (chunk) => {
        pending = Buffer.concat([pending, chunk]);
        const [commands, rest] = parseCommands(pending);
        pending = rest;
        for (const cmd of commands) socket.write(reply(cmd));
      });
      socket.on('error', () => {});
    });
    server.listen(0, '127.0.0.1', () => {
      resolve({ port: server.address().port, close: () => new Promise((d) => server.close(d)) });
    });
  });
}
