/**
 * Configuration de vue pour simplifier la logique conditionnelle de DataTable
 * Centralise toutes les règles de détermination des colonnes fixes et visibles
 */

export type ViewMode = 'dataSource' | 'workflow' | 'workflowModal';

export interface ViewConfig {
  mode: ViewMode;
  showIdColumn: boolean;
  showCheckbox: boolean;
  showPriority: boolean;
  showCreatedAt: boolean;
  showArrayIndex: boolean;
  showValue: boolean;
  allowColumnManagement: boolean; // Drag & drop, add column, etc.
}

/**
 * Crée une configuration de vue basée sur les props
 */
export function createViewConfig(
  workflowContext?: { workflowId: string; runId: string; stepId?: number; stepAlias?: string },
  showIdColumn: boolean = false,
  jsonPath?: string,
  readOnly: boolean = false,
  isSnapshot: boolean = false
): ViewConfig {
  const isWorkflowMode = !!workflowContext;
  const isModal = showIdColumn; // showIdColumn est principalement utilisé dans les modales
  const isNestedNavigation = Boolean(jsonPath);

  if (isSnapshot) {
    // Marketplace snapshot - synthesized IDs/timestamps/priorities would surface as
    // noise, and selection/priority actions all require a live dataSourceId we don't
    // have. Render only the authored columns.
    return {
      mode: 'dataSource',
      showIdColumn: false,
      showCheckbox: false,
      showPriority: false,
      showCreatedAt: false,
      showArrayIndex: isNestedNavigation,
      showValue: isNestedNavigation,
      allowColumnManagement: false,
    };
  }

  if (isWorkflowMode) {
    // In workflow mode, columns are fully data-driven (backend detailed endpoint
    // at root level, frontend-derived from navigated content at nested level).
    // System fixed columns (Index, Value) would show empty cells - disable them.
    // ID column is enabled when explicitly requested (modal / jsonPath sub-tables).
    return {
      mode: isModal ? 'workflowModal' : 'workflow',
      showIdColumn: showIdColumn,
      showCheckbox: false,
      showPriority: false,
      showCreatedAt: false,
      showArrayIndex: false,
      showValue: false,
      allowColumnManagement: false,
    };
  }

  // Mode DataSource
  return {
    mode: 'dataSource',
    showIdColumn: false,
    showCheckbox: true, // Always show checkbox/ID column (IDs displayed inside)
    showPriority: !isNestedNavigation,
    showCreatedAt: !isNestedNavigation,
    showArrayIndex: isNestedNavigation,
    showValue: isNestedNavigation,
    allowColumnManagement: !isNestedNavigation,
  };
}

/**
 * Liste des colonnes fixes selon la configuration
 */
export function getFixedColumns(config: ViewConfig): string[] {
  const fixed: string[] = [];

  if (config.showCheckbox) {
    fixed.push('checkbox');
  }
  if (config.showIdColumn) {
    fixed.push('id');
  }
  if (config.showPriority) {
    fixed.push('priority');
  }
  if (config.showCreatedAt) {
    fixed.push('created_at');
  }
  if (config.showArrayIndex) {
    fixed.push('array_index');
  }
  if (config.showValue) {
    fixed.push('value');
  }

  return fixed;
}

/**
 * Vérifie si une colonne est fixe selon la configuration
 */
export function isFixedColumn(field: string, config: ViewConfig): boolean {
  return getFixedColumns(config).includes(field);
}

/**
 * Vérifie si une colonne doit être visible selon la configuration
 */
export function isColumnVisible(field: string, config: ViewConfig): boolean {
  // Les colonnes fixes sont toujours visibles si configurées
  if (isFixedColumn(field, config)) {
    return true;
  }

  // Pour la colonne id, vérifier spécifiquement showIdColumn
  if (field === 'id') {
    return config.showIdColumn;
  }

  // Toutes les autres colonnes sont visibles
  return true;
}

/**
 * Filtre les colonnes selon la configuration
 */
export function filterVisibleColumns<T extends { field: string }>(
  columns: T[],
  config: ViewConfig
): T[] {
  return columns.filter(col => isColumnVisible(col.field, config));
}

