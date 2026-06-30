-- Add similarity search (RAG) parameter to find_rows and get_rows node documentation
SET search_path TO orchestrator;

-- Update find_rows: add similarity parameter and RAG concepts/examples
UPDATE node_type_documentation
SET
  description = 'Query a data table and return matching rows as an items[] array. Supports vector similarity search (RAG) via similarity parameter. This is a simple collection node - it does NOT split/spawn parallel contexts. To iterate per-row, connect a Split node after this node.',
  parameters = parameters || '{
    "similarity": {"type": "object", "required": false, "description": "Vector nearest-neighbor search (RAG): {column (vector column name), queryVector (float[] or template expression like {{mcp:embed.output.embedding}}), topK (int, default 5, max 100), threshold (float, optional min similarity score)}. Can combine with where for hybrid filtering."}
  }'::jsonb,
  concepts = concepts || '["Supports vector similarity search via similarity={column, queryVector, topK?, threshold?} for RAG patterns", "RAG flow: Embed query → Find with similarity → Agent uses results as context", "Hybrid search: combine similarity with where for filtered vector search"]'::jsonb,
  examples = examples || '["RAG pattern: workflow_builder(action=''add_node'', type=''find_rows'', label=''Search KB'', params={similarity: {column: ''embedding'', queryVector: ''{{mcp:embed.output.embedding}}'', topK: 5}}, connect_after=''Embed Query'')"]'::jsonb,
  updated_at = NOW()
WHERE type = 'find_rows';

-- Update get_rows: add similarity parameter
UPDATE node_type_documentation
SET
  parameters = parameters || '{
    "similarity": {"type": "object", "required": false, "description": "Vector similarity search: {column, queryVector, topK?, threshold?}. See find_rows help for full documentation."}
  }'::jsonb,
  updated_at = NOW()
WHERE type = 'get_rows';
