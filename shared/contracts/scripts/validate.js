#!/usr/bin/env node

/**
 * Node Contracts Validation Script
 *
 * Validates the node-contracts.schema.json and reports on:
 * - Schema integrity
 * - Misaligned fields
 * - Required actions
 * - Progress towards full alignment
 *
 * Usage:
 *   node validate.js [--strict] [--report]
 *
 * Options:
 *   --strict  Exit with error code 1 if any misalignments found
 *   --report  Generate detailed report file
 */

const fs = require('fs');
const path = require('path');

const SCHEMA_PATH = path.join(__dirname, '..', 'node-contracts.schema.json');

// ANSI colors for terminal output
const colors = {
  reset: '\x1b[0m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  cyan: '\x1b[36m',
  bold: '\x1b[1m',
};

function log(color, message) {
  console.log(`${colors[color]}${message}${colors.reset}`);
}

function loadSchema() {
  try {
    const content = fs.readFileSync(SCHEMA_PATH, 'utf-8');
    return JSON.parse(content);
  } catch (error) {
    log('red', `Error loading schema: ${error.message}`);
    process.exit(1);
  }
}

function validateSchema(schema) {
  const errors = [];
  const warnings = [];

  if (!schema.nodes || !Array.isArray(schema.nodes)) {
    errors.push('Schema must have a "nodes" array');
    return { errors, warnings };
  }

  const nodeIds = new Set();

  for (const node of schema.nodes) {
    // Check required fields
    if (!node.id) {
      errors.push(`Node missing "id" field: ${JSON.stringify(node).slice(0, 50)}...`);
      continue;
    }

    if (nodeIds.has(node.id)) {
      errors.push(`Duplicate node id: ${node.id}`);
    }
    nodeIds.add(node.id);

    if (!node.name) {
      warnings.push(`Node "${node.id}" missing "name" field`);
    }

    if (!node.category) {
      warnings.push(`Node "${node.id}" missing "category" field`);
    }

    if (!node.parameters || !Array.isArray(node.parameters)) {
      errors.push(`Node "${node.id}" missing "parameters" array`);
    }

    if (!node.outputs || !Array.isArray(node.outputs)) {
      errors.push(`Node "${node.id}" missing "outputs" array`);
    }

    // Validate fields
    const allFields = [...(node.parameters || []), ...(node.outputs || [])];
    for (const field of allFields) {
      if (!field.name) {
        errors.push(`Node "${node.id}" has field without "name"`);
      }
      if (!field.type) {
        errors.push(`Node "${node.id}" field "${field.name}" missing "type"`);
      }
    }
  }

  return { errors, warnings };
}

function analyzeAlignment(schema) {
  const stats = {
    totalNodes: 0,
    totalFields: 0,
    aligned: 0,
    frontendOnly: 0,
    backendOnly: 0,
    misaligned: 0,
    deprecated: 0,
    noStatus: 0,
  };

  const issues = [];
  const actions = {
    keep: [],
    add_frontend: [],
    add_backend: [],
    rename: [],
    remove: [],
    standardize: [],
  };

  for (const node of schema.nodes) {
    stats.totalNodes++;

    const allFields = [...(node.parameters || []), ...(node.outputs || [])];

    for (const field of allFields) {
      stats.totalFields++;

      const status = field.status || 'unknown';
      const action = field.action || 'unknown';

      switch (status) {
        case 'aligned':
          stats.aligned++;
          break;
        case 'frontend_only':
          stats.frontendOnly++;
          issues.push({
            node: node.id,
            field: field.name,
            status,
            action: field.action,
            notes: field.notes,
          });
          break;
        case 'backend_only':
          stats.backendOnly++;
          issues.push({
            node: node.id,
            field: field.name,
            status,
            action: field.action,
            notes: field.notes,
          });
          break;
        case 'misaligned':
          stats.misaligned++;
          issues.push({
            node: node.id,
            field: field.name,
            status,
            action: field.action,
            frontendName: field.frontendName,
            backendName: field.backendName,
            notes: field.notes,
          });
          break;
        case 'deprecated':
          stats.deprecated++;
          break;
        default:
          stats.noStatus++;
      }

      if (action && actions[action]) {
        actions[action].push({
          node: node.id,
          field: field.name,
          notes: field.notes,
        });
      }
    }
  }

  return { stats, issues, actions };
}

function printReport(validation, analysis, options) {
  console.log('');
  log('bold', '═══════════════════════════════════════════════════════════════');
  log('bold', '                    NODE CONTRACTS VALIDATION                    ');
  log('bold', '═══════════════════════════════════════════════════════════════');
  console.log('');

  // Validation Results
  log('cyan', '📋 SCHEMA VALIDATION');
  console.log('───────────────────────────────────────────────────────────────');

  if (validation.errors.length === 0) {
    log('green', '✓ Schema structure is valid');
  } else {
    log('red', `✗ ${validation.errors.length} errors found:`);
    validation.errors.forEach(e => log('red', `  - ${e}`));
  }

  if (validation.warnings.length > 0) {
    log('yellow', `⚠ ${validation.warnings.length} warnings:`);
    validation.warnings.forEach(w => log('yellow', `  - ${w}`));
  }

  console.log('');

  // Alignment Stats
  log('cyan', '📊 ALIGNMENT STATISTICS');
  console.log('───────────────────────────────────────────────────────────────');

  const { stats } = analysis;
  const alignmentPercent = ((stats.aligned / stats.totalFields) * 100).toFixed(1);

  console.log(`  Total Nodes:      ${stats.totalNodes}`);
  console.log(`  Total Fields:     ${stats.totalFields}`);
  console.log('');

  const alignedBar = '█'.repeat(Math.round(alignmentPercent / 5)) + '░'.repeat(20 - Math.round(alignmentPercent / 5));
  log('green', `  ✓ Aligned:        ${stats.aligned.toString().padStart(3)} [${alignedBar}] ${alignmentPercent}%`);

  if (stats.frontendOnly > 0) {
    log('yellow', `  ⚠ Frontend Only:  ${stats.frontendOnly.toString().padStart(3)}`);
  }
  if (stats.backendOnly > 0) {
    log('yellow', `  ⚠ Backend Only:   ${stats.backendOnly.toString().padStart(3)}`);
  }
  if (stats.misaligned > 0) {
    log('red', `  ✗ Misaligned:     ${stats.misaligned.toString().padStart(3)}`);
  }
  if (stats.deprecated > 0) {
    log('yellow', `  ⊘ Deprecated:     ${stats.deprecated.toString().padStart(3)}`);
  }

  console.log('');

  // Issues by Node
  if (analysis.issues.length > 0) {
    log('cyan', '🔍 ISSUES BY NODE');
    console.log('───────────────────────────────────────────────────────────────');

    const byNode = {};
    analysis.issues.forEach(issue => {
      if (!byNode[issue.node]) byNode[issue.node] = [];
      byNode[issue.node].push(issue);
    });

    Object.entries(byNode).forEach(([nodeId, nodeIssues]) => {
      log('yellow', `\n  ${nodeId}:`);
      nodeIssues.forEach(issue => {
        const statusIcon = issue.status === 'misaligned' ? '✗' : '⚠';
        const actionStr = issue.action ? ` → ${issue.action}` : '';
        console.log(`    ${statusIcon} ${issue.field} (${issue.status})${actionStr}`);
        if (issue.frontendName) console.log(`      FE: ${issue.frontendName}`);
        if (issue.backendName) console.log(`      BE: ${issue.backendName}`);
        if (issue.notes) console.log(`      Note: ${issue.notes}`);
      });
    });

    console.log('');
  }

  // Required Actions
  log('cyan', '🔧 REQUIRED ACTIONS');
  console.log('───────────────────────────────────────────────────────────────');

  const { actions } = analysis;

  if (actions.add_frontend.length > 0) {
    log('blue', `\n  Add to Frontend (${actions.add_frontend.length}):`);
    actions.add_frontend.forEach(a => console.log(`    - ${a.node}.${a.field}`));
  }

  if (actions.add_backend.length > 0) {
    log('blue', `\n  Add to Backend (${actions.add_backend.length}):`);
    actions.add_backend.forEach(a => console.log(`    - ${a.node}.${a.field}`));
  }

  if (actions.standardize.length > 0) {
    log('yellow', `\n  Standardize (${actions.standardize.length}):`);
    actions.standardize.forEach(a => console.log(`    - ${a.node}.${a.field}${a.notes ? ` (${a.notes})` : ''}`));
  }

  if (actions.rename.length > 0) {
    log('yellow', `\n  Rename (${actions.rename.length}):`);
    actions.rename.forEach(a => console.log(`    - ${a.node}.${a.field}`));
  }

  if (actions.remove.length > 0) {
    log('red', `\n  Remove (${actions.remove.length}):`);
    actions.remove.forEach(a => console.log(`    - ${a.node}.${a.field}`));
  }

  console.log('');
  log('bold', '═══════════════════════════════════════════════════════════════');

  // Summary
  const totalIssues = stats.frontendOnly + stats.backendOnly + stats.misaligned;
  if (totalIssues === 0) {
    log('green', '✓ All fields are aligned! No action required.');
  } else {
    log('yellow', `⚠ ${totalIssues} fields need attention.`);
  }

  console.log('');

  return totalIssues;
}

function generateReportFile(validation, analysis) {
  const report = {
    timestamp: new Date().toISOString(),
    validation,
    analysis,
  };

  const reportPath = path.join(__dirname, '..', 'validation-report.json');
  fs.writeFileSync(reportPath, JSON.stringify(report, null, 2));
  log('green', `Report saved to: ${reportPath}`);
}

// Main
const args = process.argv.slice(2);
const strictMode = args.includes('--strict');
const generateReport = args.includes('--report');

const schema = loadSchema();
const validation = validateSchema(schema);
const analysis = analyzeAlignment(schema);

const issueCount = printReport(validation, analysis, { strictMode });

if (generateReport) {
  generateReportFile(validation, analysis);
}

if (strictMode && (validation.errors.length > 0 || issueCount > 0)) {
  process.exit(1);
}
