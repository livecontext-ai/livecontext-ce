import * as React from 'react';
import type { PaletteCategoryNode } from '../../nodes/nodeClasses';
import type { ApiSystem } from '../../hooks/useMcpData';
import type { DataSource } from '../../hooks/useDataSourceData';
import type { NavigationLevel } from '../../hooks/useSharedNavigation';

export type BreadcrumbItem = {
  label: string;
  onClick?: () => void;
  isActive?: boolean;
};

type UseBreadcrumbsParams = {
  navigationLevel: NavigationLevel;
  selectedCategoryId: string | null;
  selectedApiId: string | null;
  selectedDataSourceId: number | null;
  selectedType: string | null;
  apis: ApiSystem[];
  dataSources: DataSource[];
  findCategoryNode: (id: string | null) => PaletteCategoryNode | null;
  // Navigation setters
  setNavigationLevel: (level: NavigationLevel) => void;
  setSelectedCategoryId: (id: string | null) => void;
  setSelectedApiId: (id: string | null) => void;
  setSelectedDataSourceId: (id: number | null) => void;
  setSelectedType: (type: string | null) => void;
  setSearchQuery: (query: string) => void;
  setToolPage: (page: number | ((prev: number) => number)) => void;
};

/**
 * Hook to build breadcrumb navigation items based on current navigation state.
 */
export function useBreadcrumbs({
  navigationLevel,
  selectedCategoryId,
  selectedApiId,
  selectedDataSourceId,
  selectedType,
  apis,
  dataSources,
  findCategoryNode,
  setNavigationLevel,
  setSelectedCategoryId,
  setSelectedApiId,
  setSelectedDataSourceId,
  setSelectedType,
  setSearchQuery,
  setToolPage,
}: UseBreadcrumbsParams): BreadcrumbItem[] {
  return React.useMemo(() => {
    const items: BreadcrumbItem[] = [];

    // Home button (palette icon)
    const goHome = () => {
      setNavigationLevel('categories');
      setSelectedCategoryId(null);
      setSelectedApiId(null);
      setSelectedDataSourceId(null);
      setSelectedType(null);
      setSearchQuery('');
      setToolPage(0);
    };

    items.push({ label: '', onClick: goHome });

    // Category path
    if (selectedCategoryId && navigationLevel === 'categories') {
      const categoryNode = findCategoryNode(selectedCategoryId);
      if (categoryNode) {
        if (categoryNode.parentId) {
          const parentNode = findCategoryNode(categoryNode.parentId);
          if (parentNode) {
            items.push({
              label: parentNode.name,
              onClick: () => {
                setSelectedCategoryId(parentNode.id);
                setSearchQuery('');
              },
            });
          }
        }
        items.push({
          label: categoryNode.name,
          onClick: categoryNode.parentId
            ? () => { setSelectedCategoryId(categoryNode.parentId!); setSearchQuery(''); }
            : () => { setSelectedCategoryId(null); setSearchQuery(''); },
        });
      }
    }

    // MCP/APIs path
    if (navigationLevel === 'apis') {
      items.push({ label: 'MCPs', isActive: true });
    }

    if (navigationLevel === 'tools' && selectedApiId) {
      const api = apis.find(a => a.slug === selectedApiId);
      items.push({
        label: 'MCPs',
        onClick: () => {
          setNavigationLevel('apis');
          setSelectedApiId(null);
          setSelectedType(null);
          setSearchQuery('');
          setToolPage(0);
        },
      });
      items.push({ label: api?.apiName || selectedApiId, isActive: true });
    }

    // Triggers path
    if (navigationLevel === 'types' && selectedType === 'triggers') {
      items.push({ label: 'Trigger', isActive: true });
    }

    // DataSources path
    if (navigationLevel === 'datasources') {
      buildDataSourcesBreadcrumbs(items, {
        selectedType,
        setNavigationLevel,
        setSelectedCategoryId,
        setSelectedDataSourceId,
        setSelectedType,
        setSearchQuery,
      });
    }

    // Tables path
    if (navigationLevel === 'tables' && selectedDataSourceId) {
      const dataSource = dataSources.find(ds => ds.id === selectedDataSourceId);
      buildTablesBreadcrumbs(items, {
        selectedType,
        dataSource,
        setNavigationLevel,
        setSelectedCategoryId,
        setSelectedDataSourceId,
        setSelectedType,
        setSearchQuery,
      });
    }

    // Workflows path
    if (navigationLevel === 'workflows') {
      items.push({
        label: 'Trigger',
        onClick: () => {
          setNavigationLevel('types');
          setSelectedType('triggers');
          setSearchQuery('');
        },
      });
      items.push({ label: 'Workflows', isActive: true });
    }

    // Interfaces path
    if (navigationLevel === 'interfaces') {
      items.push({
        label: 'Core',
        onClick: () => {
          setNavigationLevel('categories');
          setSelectedCategoryId('core');
          setSearchQuery('');
        },
      });
      items.push({ label: 'Interface', isActive: true });
    }

    // Agents path
    if (navigationLevel === 'agents') {
      items.push({
        label: 'AI',
        onClick: () => {
          setNavigationLevel('categories');
          setSelectedCategoryId('ai');
          setSearchQuery('');
        },
      });
      items.push({ label: 'Agents', isActive: true });
    }

    // Mark last item as active
    if (items.length > 1 && !items[items.length - 1].isActive) {
      items[items.length - 1].isActive = true;
    }

    return items;
  }, [
    navigationLevel,
    selectedCategoryId,
    selectedApiId,
    selectedDataSourceId,
    selectedType,
    apis,
    dataSources,
    findCategoryNode,
    setNavigationLevel,
    setSelectedCategoryId,
    setSelectedApiId,
    setSelectedDataSourceId,
    setSelectedType,
    setSearchQuery,
    setToolPage,
  ]);
}

// Helper for CRUD labels
const CRUD_LABELS: Record<string, string> = {
  'create-row': 'Row',
  'create-column': 'Column',
  'read-row': 'Row',
  'update-row': 'Row',
  'delete-row': 'Row',
  'find-row': 'Rows',
};

function buildDataSourcesBreadcrumbs(
  items: BreadcrumbItem[],
  params: {
    selectedType: string | null;
    setNavigationLevel: (level: NavigationLevel) => void;
    setSelectedCategoryId: (id: string | null) => void;
    setSelectedDataSourceId: (id: number | null) => void;
    setSelectedType: (type: string | null) => void;
    setSearchQuery: (query: string) => void;
  }
) {
  const { selectedType, setNavigationLevel, setSelectedCategoryId, setSelectedDataSourceId, setSelectedType, setSearchQuery } = params;

  if (selectedType === 'triggers') {
    items.push({
      label: 'Trigger',
      onClick: () => {
        setNavigationLevel('types');
        setSelectedDataSourceId(null);
        setSelectedType('triggers');
        setSearchQuery('');
      },
    });
  } else if (isCrudRowOperation(selectedType)) {
    const crudLabel = CRUD_LABELS[selectedType!] || 'Row';
    const parentLabel = getParentLabel(selectedType);

    items.push({
      label: 'Core',
      onClick: () => {
        setNavigationLevel('categories');
        setSelectedCategoryId('core');
        setSelectedDataSourceId(null);
        setSelectedType(null);
        setSearchQuery('');
      },
    });
    items.push({
      label: 'Data',
      onClick: () => {
        setNavigationLevel('categories');
        setSelectedCategoryId('data');
        setSelectedDataSourceId(null);
        setSelectedType(null);
        setSearchQuery('');
      },
    });
    items.push({ label: parentLabel });
    items.push({ label: crudLabel });
  } else if (selectedType?.startsWith('data')) {
    const crudOperation = selectedType.replace('data-', '');
    items.push({
      label: 'Data',
      onClick: () => {
        setNavigationLevel('categories');
        setSelectedCategoryId('data');
        setSelectedDataSourceId(null);
        setSelectedType(null);
        setSearchQuery('');
      },
    });
    if (crudOperation !== 'data' && crudOperation) {
      const opLabels: Record<string, string> = { read: 'Read', update: 'Update', delete: 'Delete' };
      if (opLabels[crudOperation]) {
        items.push({ label: opLabels[crudOperation] });
      }
    }
  } else {
    items.push({
      label: 'MCP',
      onClick: () => {
        setNavigationLevel('categories');
        setSelectedCategoryId(null);
        setSelectedDataSourceId(null);
        setSelectedType(null);
        setSearchQuery('');
      },
    });
    items.push({ label: 'Data Sources', isActive: true });
  }
}

function buildTablesBreadcrumbs(
  items: BreadcrumbItem[],
  params: {
    selectedType: string | null;
    dataSource: DataSource | undefined;
    setNavigationLevel: (level: NavigationLevel) => void;
    setSelectedCategoryId: (id: string | null) => void;
    setSelectedDataSourceId: (id: number | null) => void;
    setSelectedType: (type: string | null) => void;
    setSearchQuery: (query: string) => void;
  }
) {
  const { selectedType, dataSource, setNavigationLevel, setSelectedCategoryId, setSelectedDataSourceId, setSelectedType, setSearchQuery } = params;

  if (selectedType === 'triggers') {
    items.push({
      label: 'Trigger',
      onClick: () => {
        setNavigationLevel('types');
        setSelectedDataSourceId(null);
        setSelectedType('triggers');
        setSearchQuery('');
      },
    });
    items.push({
      label: 'Data Sources',
      onClick: () => {
        setNavigationLevel('datasources');
        setSelectedDataSourceId(null);
        setSearchQuery('');
      },
    });
    items.push({ label: dataSource?.name || 'Data Source', isActive: true });
  } else if (isCrudRowOperation(selectedType)) {
    const crudLabel = CRUD_LABELS[selectedType!] || 'Row';
    const parentLabel = getParentLabel(selectedType);

    items.push({
      label: 'Core',
      onClick: () => {
        setNavigationLevel('categories');
        setSelectedCategoryId('core');
        setSelectedDataSourceId(null);
        setSelectedType(null);
        setSearchQuery('');
      },
    });
    items.push({
      label: 'Data',
      onClick: () => {
        setNavigationLevel('categories');
        setSelectedCategoryId('data');
        setSelectedDataSourceId(null);
        setSelectedType(null);
        setSearchQuery('');
      },
    });
    items.push({ label: parentLabel });
    items.push({
      label: crudLabel,
      onClick: () => {
        setNavigationLevel('datasources');
        setSelectedDataSourceId(null);
        setSearchQuery('');
      },
    });
    items.push({ label: dataSource?.name || 'Data Source', isActive: true });
  } else {
    items.push({
      label: 'MCP',
      onClick: () => {
        setNavigationLevel('categories');
        setSelectedCategoryId(null);
        setSelectedDataSourceId(null);
        setSelectedType(null);
        setSearchQuery('');
      },
    });
  }
}

function isCrudRowOperation(type: string | null): boolean {
  return type === 'create-row' || type === 'create-column' || type === 'read-row' || type === 'update-row' || type === 'delete-row' || type === 'find-row';
}

function getParentLabel(type: string | null): string {
  if (type?.startsWith('create-')) return 'Create';
  if (type?.startsWith('read-')) return 'Get';
  if (type?.startsWith('delete-')) return 'Delete';
  if (type?.startsWith('find-')) return 'Find';
  return 'Update';
}

export default useBreadcrumbs;
