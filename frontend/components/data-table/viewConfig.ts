/**
 * Configuration de vue pour simplifier la logique conditionnelle de DataTable
 * Centralise toutes les règles de détermination des colonnes fixes et visibles
 */

export type ViewMode = 'dataSource' | 'workflow' | 'workflowModal';

export interface ViewConfig {
  mode: ViewMode;
  /**
   * True while drilling into a nested JSON path. Columns are then derived from
   * the DATA itself, so a field named like a system column (`id`, `value`, ...)
   * is real user content and must be rendered, not suppressed as a duplicate.
   */
  isNestedNavigation: boolean;
  /**
   * Does `id` name the ROW's identity in this view, or a field of the data?
   *
   * The single answer both the grid and the exports read, so a cell and its exported column can
   * never disagree about which of the two a column called `id` is. True means the view owns the
   * name (a pinned identity lane, or the checkbox lane that prints the row id inside it); false
   * means it is ordinary content and is rendered and exported like any other column.
   */
  idIsRowLevel: boolean;
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
      isNestedNavigation,
      // A snapshot builds no lanes at all, so an authored column called `id` is the publisher's own
      // data; the synthesized row ordinal is exactly the noise this branch sets out to avoid.
      idIsRowLevel: false,
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
    // ID lane: enabled when the caller asks for it. WorkflowStepTable (run Logs, inspector, run
    // modal) asks at every depth, so input/output keep the pinned #ID lane too.
    return {
      mode: isModal ? 'workflowModal' : 'workflow',
      isNestedNavigation,
      // At root the backend emits `id` as the step's row index, and as its first column, so it is
      // the identity there whether or not the view asked for a lane. Nested, it is item data.
      idIsRowLevel: showIdColumn || !isNestedNavigation,
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
    isNestedNavigation,
    // At root the checkbox lane prints the row id inside it, so the name is taken. Nested, `id`
    // belongs to the navigated item.
    idIsRowLevel: !isNestedNavigation,
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
 * Is `id` already shown INSIDE another lane, so a column of its own would only add an empty one?
 *
 * True only where the checkbox lane prints the row id inside itself (the tables page at root).
 * Everywhere else `id` is either its own identity lane or ordinary data, and gets a column.
 */
export function idIsHiddenBehindCheckbox(config: ViewConfig): boolean {
  return config.showCheckbox && config.idIsRowLevel;
}

/**
 * The fields an export writes out of the ROW rather than out of `row.data`, i.e. the ones its base
 * columns already carry. Everything else is data and must reach the file as its own column.
 *
 * Deliberately NOT the whole fixed set: `value` and `array_index` have no base column, and
 * excluding them once exported an array of primitives with no content at all. `id` follows
 * {@link ViewConfig.idIsRowLevel}, so the grid cell and the exported column always agree.
 */
export function getRowLevelExportFields(config: ViewConfig): string[] {
  return [
    'checkbox',
    ...(config.idIsRowLevel ? ['id'] : []),
    ...(config.showPriority ? ['priority'] : []),
    ...(config.showCreatedAt ? ['created_at'] : []),
  ];
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

  // Pour la colonne id, vérifier spécifiquement showIdColumn.
  // (Only reached from the add-row form, which renders at root level only - a nested `id` is data
  // and is decided by getAllColumns, not here.)
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

