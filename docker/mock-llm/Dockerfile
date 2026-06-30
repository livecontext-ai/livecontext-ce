# Mock OpenAI-compatible LLM for e2e slots (compose profile: e2e-mock-llm).
# Zero npm dependencies - plain node:http - so the build never touches the
# network (TLS-interception hosts build it offline).
FROM node:20-alpine
WORKDIR /app
COPY server.js .
EXPOSE 8095
CMD ["node", "server.js"]
