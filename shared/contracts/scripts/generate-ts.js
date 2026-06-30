#!/usr/bin/env node

/**
 * TypeScript Interface Generator
 *
 * Generates TypeScript interfaces from node-contracts.schema.json
 *
 * Usage:
 *   node generate-ts.js [--output <path>]
 */

const fs = require('fs');
const path = require('path');

const SCHEMA_PATH = path.join(__dirname, '..', 'node-contracts.schema.json');
const DEFAULT_OUTPUT = path.join(__dirname, '..', '..', '..', 'frontend', 'app', 'workflows', 'builder', 'contracts', 'node-contracts.generated.ts');

function loadSchema() {
  const content = fs.readFileSync(SCHEMA_PATH, 'utf-8');
  return JSON.parse(content);
}

function mapType(schemaType, itemType) {
  switch (schemaType) {
    case 'string':
      return 'string';
    case 'number':
      return 'number';
    case 'boolean':
      return 'boolean';
    case 'datetime':
      return 'string'; // ISO string
    case 'array':
      if (itemType === 'object') return 'Record<string, any>[]';
      if (itemType === 'string') return 'string[]';
      return 'any[]';
    case 'object':
      return 'Record<string, any>';
    case 'any':
    default:
      return 'any';
  }
}

function toPascalCase(str) {
  return str
    .split(/[-_]/)
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join('');
}

function toCamelCase(str) {
  const pascal = toPascalCase(str);
  return pascal.charAt(0).toLowerCase() + pascal.slice(1);
}

function generateNestedTypes(nestedTypes, nodeName) {
  if (!nestedTypes) return '';

  let output = '';

  for (const [typeName, fields] of Object.entries(nestedTypes)) {
    output += `export interface ${nodeName}${typeName} {\n`;
    for (const field of fields) {
      const optional = field.required ? '' : '?';
      const type = field.enum
        ? field.enum.map(v => `'${v}'`).join(' | ')
        : mapType(field.type, field.itemType);
      output += `  ${field.name}${optional}: ${type};\n`;
    }
    output += '}\n\n';
  }

  return output;
}

function generateNodeInterface(node) {
  const nodeName = toPascalCase(node.id);
  let output = '';

  // Generate nested types first
  output += generateNestedTypes(node.nestedTypes, nodeName);

  // Parameters interface
  output += `/**\n * Parameters for ${node.name}\n * ${node.description || ''}\n */\n`;
  output += `export interface ${nodeName}Parameters {\n`;

  for (const param of node.parameters || []) {
    if (param.name.startsWith('{')) continue; // Skip dynamic fields

    const optional = param.required ? '' : '?';
    const type = param.enum
      ? param.enum.map(v => `'${v}'`).join(' | ')
      : mapType(param.type, param.itemType);

    if (param.description) {
      output += `  /** ${param.description} */\n`;
    }
    output += `  ${toCamelCase(param.name)}${optional}: ${type};\n`;
  }

  output += '}\n\n';

  // Outputs interface
  output += `/**\n * Outputs produced by ${node.name}\n */\n`;
  output += `export interface ${nodeName}Outputs {\n`;

  const dynamicFields = [];
  const staticFields = [];
  for (const outputField of node.outputs || []) {
    if (outputField.name.startsWith('{')) {
      dynamicFields.push(outputField);
    } else {
      staticFields.push(outputField);
    }
  }

  if (dynamicFields.length > 0) {
    const comment = dynamicFields
      .map(f => `Dynamic fields: ${f.name} - ${f.notes || ''}`)
      .join('; ');
    output += `  /** ${comment} */\n`;
    output += `  [key: string]: any;\n`;
  }

  for (const outputField of staticFields) {
    const type = outputField.enum
      ? outputField.enum.map(v => `'${v}'`).join(' | ')
      : mapType(outputField.type, outputField.itemType);

    if (outputField.notes) {
      output += `  /** ${outputField.notes} */\n`;
    }
    output += `  ${outputField.name}?: ${type};\n`;
  }

  output += '}\n\n';

  return output;
}

function generateTypeGuards(nodes) {
  let output = '// Type guards for node types\n\n';

  for (const node of nodes) {
    const nodeName = toPascalCase(node.id);
    const nodeId = node.id;

    output += `export function is${nodeName}(nodeId: string): boolean {\n`;
    output += `  return nodeId === '${nodeId}' || nodeId.startsWith('${nodeId}-');\n`;
    output += '}\n\n';
  }

  return output;
}

function generateNodeRegistry(nodes) {
  let output = '// Node type registry\n\n';

  output += 'export const NODE_TYPES = {\n';
  for (const node of nodes) {
    output += `  '${node.id}': {\n`;
    output += `    id: '${node.id}',\n`;
    output += `    name: '${node.name}',\n`;
    output += `    category: '${node.category}',\n`;
    output += `  },\n`;
  }
  output += '} as const;\n\n';

  output += 'export type NodeTypeId = keyof typeof NODE_TYPES;\n\n';

  return output;
}

function generate(schema, outputPath) {
  let output = `/**
 * AUTO-GENERATED FILE - DO NOT EDIT DIRECTLY
 *
 * Generated from: shared/contracts/node-contracts.schema.json
 *
 * To regenerate: npm run contracts:generate:ts
 */

`;

  // Generate interfaces for each node
  for (const node of schema.nodes) {
    output += `// ═══════════════════════════════════════════════════════════════\n`;
    output += `// ${node.name.toUpperCase()}\n`;
    output += `// ═══════════════════════════════════════════════════════════════\n\n`;
    output += generateNodeInterface(node);
  }

  // Generate type guards
  output += '// ═══════════════════════════════════════════════════════════════\n';
  output += '// TYPE GUARDS\n';
  output += '// ═══════════════════════════════════════════════════════════════\n\n';
  output += generateTypeGuards(schema.nodes);

  // Generate node registry
  output += '// ═══════════════════════════════════════════════════════════════\n';
  output += '// NODE REGISTRY\n';
  output += '// ═══════════════════════════════════════════════════════════════\n\n';
  output += generateNodeRegistry(schema.nodes);

  // Ensure output directory exists
  const outputDir = path.dirname(outputPath);
  if (!fs.existsSync(outputDir)) {
    fs.mkdirSync(outputDir, { recursive: true });
  }

  fs.writeFileSync(outputPath, output);
  console.log(`Generated TypeScript interfaces: ${outputPath}`);
}

// Main
const args = process.argv.slice(2);
const outputIndex = args.indexOf('--output');
const outputPath = outputIndex !== -1 && args[outputIndex + 1]
  ? args[outputIndex + 1]
  : DEFAULT_OUTPUT;

const schema = loadSchema();
generate(schema, outputPath);
