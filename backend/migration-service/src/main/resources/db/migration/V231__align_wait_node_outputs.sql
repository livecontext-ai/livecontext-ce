UPDATE node_type_documentation
SET outputs = '{
  "waited_ms": {
    "type": "number",
    "description": "Duration waited in milliseconds"
  },
  "status": {
    "type": "string",
    "description": "Final wait status"
  },
  "started_at": {
    "type": "string",
    "description": "ISO timestamp when wait started"
  },
  "completed_at": {
    "type": "string",
    "description": "ISO timestamp when wait completed"
  },
  "duration_ms": {
    "type": "number",
    "description": "Duration in milliseconds while a long wait is awaiting its timer signal"
  },
  "expires_at": {
    "type": "string",
    "description": "ISO timestamp when a long wait timer expires"
  }
}'::jsonb,
    updated_at = NOW()
WHERE type = 'wait';
