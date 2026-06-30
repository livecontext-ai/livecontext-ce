# Node Contracts - Single Source of Truth

This directory contains the canonical definition of all workflow node parameters and outputs.

## Purpose

The `node-contracts.schema.json` file is the **single source of truth** for:
- Node parameters (configuration inputs)
- Node outputs (data produced after execution)
- Type definitions
- Sync status between Frontend and Backend

## Usage

### 1. Before Making Changes

Always check this schema before modifying node-related code:

```bash
# View current contract for a node
cat node-contracts.schema.json | jq '.nodes[] | select(.id == "ai-agent")'
```

### 2. After Making Changes

Update the schema first, then regenerate code:

```bash
# Validate schema
npm run contracts:validate

# Generate TypeScript interfaces
npm run contracts:generate:ts

# Generate Java records
npm run contracts:generate:java

# Generate documentation
npm run contracts:generate:docs
```

### 3. CI/CD Validation

The pipeline runs contract validation to ensure Frontend and Backend match:

```yaml
- name: Validate Node Contracts
  run: npm run contracts:validate
```

## File Structure

```
shared/contracts/
├── node-contracts.schema.json  # THE source of truth
├── README.md                   # This file
└── scripts/
    ├── validate.js             # Validates schema integrity
    ├── generate-ts.js          # Generates TypeScript interfaces
    ├── generate-java.js        # Generates Java records
    └── generate-docs.js        # Generates documentation
```

## Schema Structure

Each node in the schema has:

```json
{
  "id": "node-type-id",
  "name": "Human Readable Name",
  "category": "trigger|ai|control_flow|action",
  "description": "What this node does",
  "parameters": [
    {
      "name": "paramName",
      "type": "string|number|boolean|datetime|array|object|any",
      "required": true,
      "status": "aligned|frontend_only|backend_only|misaligned|deprecated",
      "action": "keep|add_frontend|add_backend|rename|remove|standardize",
      "frontendName": "differentNameInFE",
      "backendName": "differentNameInBE",
      "notes": "Additional context"
    }
  ],
  "outputs": [
    // Same structure as parameters
  ],
  "nestedTypes": {
    "TypeName": [
      // Field definitions
    ]
  }
}
```

## Status Values

| Status | Meaning |
|--------|---------|
| `aligned` | Same in Frontend and Backend |
| `frontend_only` | Only exists in Frontend |
| `backend_only` | Only exists in Backend |
| `misaligned` | Exists in both but different (name/type) |
| `deprecated` | Should be removed |

## Action Values

| Action | What to Do |
|--------|------------|
| `keep` | No changes needed |
| `add_frontend` | Add to Frontend code |
| `add_backend` | Add to Backend code |
| `rename` | Rename to match schema |
| `remove` | Remove from code |
| `standardize` | Align name/type between FE/BE |

## Example: Adding a New Output

1. **Update Schema**:
```json
{
  "name": "newOutput",
  "type": "string",
  "status": "aligned",
  "action": "keep"
}
```

2. **Regenerate**:
```bash
npm run contracts:generate
```

3. **Implement**:
   - Backend: Add to Node class output
   - Frontend: Add to Output component

4. **Validate**:
```bash
npm run contracts:validate
```

## Migration from Current State

The schema includes `status` and `action` fields that document what needs to change to achieve full alignment. Run:

```bash
# Show all misaligned fields
cat node-contracts.schema.json | jq '.nodes[].outputs[] | select(.status != "aligned")'

# Show all required actions
cat node-contracts.schema.json | jq '.nodes[] | {id, actions: [.parameters[], .outputs[]] | map(select(.action and .action != "keep")) | map({name, action})}'
```

## Maintenance

When modifying nodes:

1. **Check Schema First** - Is the field documented?
2. **Update Schema** - Add/modify the field definition
3. **Set Status** - Use appropriate status value
4. **Set Action** - Document what needs to happen
5. **Regenerate** - Run generation scripts
6. **Implement** - Make code changes
7. **Validate** - Run validation

## Questions?

See `/the project docs` for full documentation.

---

## Other contracts in this directory

### Cloud-link Phase 1 - API request/response schemas

Source of truth for the cloud-side `/api/ce-link/**` HTTP API (doc
`/the project docs`). Each schema mirrors a Java DTO in
`backend/auth-service/src/main/java/com/apimarketplace/auth/dto/` and the
matching TypeScript request/response type in the frontend cloud-link UI.

| Schema | Java DTO | Used by |
|---|---|---|
| `ce-link-register.schema.json` | `CeLinkRegisterRequest` | `POST /api/ce-link/register` body - sent by CE publication-service |
| `ce-link-heartbeat.schema.json` | `CeLinkHeartbeatRequest` | `POST /api/ce-link/{installId}/heartbeat` body - sent by CE every few minutes |
| `ce-link-summary.schema.json` | `CeLinkSummary` | One row of `GET /api/ce-link/mine` - rendered on cloud `/app/settings/cloud-link` |

When modifying any of these, update the schema AND the Java DTO AND the
frontend TS type in the same PR - otherwise the request fails Bean Validation
on the backend or the frontend renders garbage. The 3-layer drift is the
single biggest source of API regressions in this codebase.

### Agent stop-reason enum

`agent-stop-reason.json` + `scripts/generate-stop-reason.js` - separate
codegen flow for the `AgentStopReason` enum (Java + JS + TS). Not related to
the cloud-link schemas above.
