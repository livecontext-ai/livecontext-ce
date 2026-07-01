import * as React from 'react';
import { useTranslations } from 'next-intl';
import { InspectorColumn } from './InspectorColumn';
import type { BuilderNodeData } from '../../types';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem, SelectGroup, SelectLabel, SelectSeparator } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { FileText, Save, Edit, X, Info, Plus, Trash2, ChevronRight } from 'lucide-react';
import { useInterfaceById } from '../../hooks/useInterfaces';
import { orchestratorApi } from '@/lib/api';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { getDefaultForType } from '../../utils/interfaceHtmlUtils';
import { useNodes, useEdges, type Node } from 'reactflow';
import { nodeRegistry } from '../../registry/nodeRegistry';
import { triggerKey } from '../../utils/labelNormalizer';
import { NodeSettingsSection } from './NodeSettingsSection';
import { nodeSupportsPolicy } from '../../utils/nodePolicy';

interface InterfaceMappingsColumnProps {
  node: { data: BuilderNodeData; id: string } | null;
  data: BuilderNodeData;
  onUpdate: (data: BuilderNodeData) => void;
  connections: any[];
  isRunMode?: boolean;
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e?: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, event: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, event: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, ref: HTMLDivElement | null) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  getEditorExpression: () => string;
  handleEditorExpressionChange: (value: string) => void;
}

const HTML_TEMPLATE_EXAMPLE = `<!DOCTYPE html>
<!--
  Interface Template Guide:

  Variables: Use {{variable_name}} or {{variable_name|default value}} syntax

  Images/Videos from download_file nodes:
    <img src="{{avatar}}" alt="Profile" />
    <video src="{{video_url}}" controls></video>
    (map 'avatar' to core:download_node.output.file in Variable Mapping - canonical FileRef)
    Auth token (or HMAC signature for marketplace/share preview) is injected automatically.

  JavaScript (JS template):
    All resolved variables are available via window.__RESOLVED_DATA__
    Example: const data = window.__RESOLVED_DATA__;
             document.getElementById('name').textContent = data.username;
    Complex objects (arrays, maps) are directly accessible - no JSON.parse needed.
-->
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Boarding Pass - {{flight_number|AB123}}</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        body {
            font-family: 'Courier New', monospace;
            background: #f0f0f0;
            padding: 20px;
            display: flex;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
        }
        .ticket {
            background: white;
            width: 100%;
            max-width: 600px;
            border-radius: 8px;
            box-shadow: 0 4px 20px rgba(0,0,0,0.15);
            overflow: hidden;
        }
        .ticket-header {
            background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
            color: white;
            padding: 20px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .airline-logo {
            font-size: 24px;
            font-weight: bold;
            letter-spacing: 2px;
        }
        .flight-number {
            font-size: 18px;
            font-weight: bold;
        }
        .ticket-body {
            padding: 25px;
        }
        .passenger-info {
            border-bottom: 2px dashed #ddd;
            padding-bottom: 20px;
            margin-bottom: 20px;
        }
        .passenger-name {
            font-size: 24px;
            font-weight: bold;
            color: #333;
            margin-bottom: 5px;
        }
        .passenger-details {
            color: #666;
            font-size: 14px;
        }
        .flight-details {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
            margin-bottom: 20px;
        }
        .detail-section {
            padding: 15px;
            background: #f8f9fa;
            border-radius: 6px;
        }
        .detail-label {
            font-size: 11px;
            text-transform: uppercase;
            color: #666;
            letter-spacing: 1px;
            margin-bottom: 8px;
        }
        .detail-value {
            font-size: 20px;
            font-weight: bold;
            color: #333;
        }
        .route {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 20px 0;
            border-top: 2px dashed #ddd;
            border-bottom: 2px dashed #ddd;
            margin: 20px 0;
        }
        .airport {
            text-align: center;
        }
        .airport-code {
            font-size: 32px;
            font-weight: bold;
            color: #1e3c72;
            margin-bottom: 5px;
        }
        .airport-name {
            font-size: 12px;
            color: #666;
        }
        .arrow {
            font-size: 24px;
            color: #999;
        }
        .time-info {
            display: flex;
            justify-content: space-between;
            margin-top: 10px;
        }
        .time {
            font-size: 14px;
            color: #333;
        }
        .seat-gate {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 15px;
            margin-top: 20px;
        }
        .seat-info, .gate-info {
            text-align: center;
            padding: 15px;
            background: #e8f4f8;
            border-radius: 6px;
        }
        .seat-label, .gate-label {
            font-size: 11px;
            text-transform: uppercase;
            color: #666;
            margin-bottom: 5px;
        }
        .seat-value, .gate-value {
            font-size: 24px;
            font-weight: bold;
            color: #1e3c72;
        }
        .barcode-section {
            background: #f8f9fa;
            padding: 20px;
            text-align: center;
            border-top: 2px dashed #ddd;
        }
        .barcode {
            font-family: 'Courier New', monospace;
            font-size: 18px;
            letter-spacing: 3px;
            color: #333;
            margin-bottom: 10px;
        }
        .ticket-number {
            font-size: 12px;
            color: #666;
        }
        .status-badge {
            display: inline-block;
            padding: 6px 12px;
            border-radius: 20px;
            font-size: 12px;
            font-weight: bold;
            margin-top: 10px;
        }
        .status-confirmed {
            background: #d4edda;
            color: #155724;
        }
        .status-boarding {
            background: #fff3cd;
            color: #856404;
        }
    </style>
</head>
<body>
    <div class="ticket">
        <div class="ticket-header">
            <div class="airline-logo">{{airline|AirLine}}</div>
            <div class="flight-number">{{flight_number|AB123}}</div>
        </div>

        <div class="ticket-body">
            <div class="passenger-info">
                <div class="passenger-name">{{passenger_name|John Doe}}</div>
                <div class="passenger-details">
                    Booking Reference: {{booking_reference|XYZ789}} | Class: {{class|Economy}}
                </div>
            </div>

            <div class="route">
                <div class="airport">
                    <div class="airport-code">{{departure_code|CDG}}</div>
                    <div class="airport-name">{{departure_city|Paris}}</div>
                    <div class="time-info">
                        <div class="time">{{departure_time|08:30}}</div>
                    </div>
                </div>
                <div class="arrow">→</div>
                <div class="airport">
                    <div class="airport-code">{{arrival_code|JFK}}</div>
                    <div class="airport-name">{{arrival_city|New York}}</div>
                    <div class="time-info">
                        <div class="time">{{arrival_time|14:45}}</div>
                    </div>
                </div>
            </div>

            <div class="flight-details">
                <div class="detail-section">
                    <div class="detail-label">Date</div>
                    <div class="detail-value">{{flight_date|2026-03-15}}</div>
                </div>
                <div class="detail-section">
                    <div class="detail-label">Duration</div>
                    <div class="detail-value">{{duration|8h 15m}}</div>
                </div>
            </div>

            <div class="seat-gate">
                <div class="seat-info">
                    <div class="seat-label">Seat</div>
                    <div class="seat-value">{{seat_number|14A}}</div>
                </div>
                <div class="gate-info">
                    <div class="gate-label">Gate</div>
                    <div class="gate-value">{{gate_number|B42}}</div>
                </div>
            </div>

            <div style="text-align: center; margin-top: 15px;">
                <span class="status-badge status-confirmed">{{status|Confirmed}}</span>
            </div>
        </div>

        <div class="barcode-section">
            <div class="barcode">{{barcode|XXXX XXXX XXXX}}</div>
            <div class="ticket-number">Ticket: {{ticket_number|TKT-001234}}</div>
        </div>
    </div>
</body>
</html>`;

export const InterfaceMappingsColumn = ({
  node,
  data,
  onUpdate,
  connections,
  isRunMode = false,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
  findUnknownVariables,
  getEditorExpression,
  handleEditorExpressionChange,
}: InterfaceMappingsColumnProps) => {
  const t = useTranslations('workflowBuilder.inspector.interfaceMappings');

  // Check if this interface comes from an existing interface (has interfaceId)
  const interfaceData = (node?.data as any)?.interfaceData || {};

  // Also check if node ID starts with 'interface-' which indicates it's from DB
  const nodeId = node?.id || '';
  const isInterfaceFromDb = nodeId.startsWith('interface-') && nodeId !== 'interface';
  const extractedInterfaceId = isInterfaceFromDb ? nodeId.replace('interface-', '').replace(/--\d+$/, '') : null;

  const hasInterfaceId = !!interfaceData.interfaceId || !!extractedInterfaceId;
  const interfaceId = interfaceData.interfaceId || extractedInterfaceId;
  const hasUnsavedInterfaceChanges = (node as any)?.data?.hasUnsavedInterfaceChanges || false;

  // Load interface details from DB if interfaceId exists
  const { data: interfaceDetails, isLoading: isLoadingInterface, refetch: refetchInterface } = useInterfaceById(interfaceId || null);

  // Edit mode state - default to false when we have an interfaceId (read-only mode)
  const [isEditMode, setIsEditMode] = React.useState(false);

  // Separate state for edited template (like in /app/interface/[id])
  const [editedHtmlTemplate, setEditedHtmlTemplate] = React.useState<string>('');
  const [editedCssTemplate, setEditedCssTemplate] = React.useState<string>('');
  const [editedJsTemplate, setEditedJsTemplate] = React.useState<string>('');

  // Store original template when entering edit mode (for cancel)
  const [originalTemplateOnEdit, setOriginalTemplateOnEdit] = React.useState<string>('');
  const [originalCssTemplateOnEdit, setOriginalCssTemplateOnEdit] = React.useState<string>('');
  const [originalJsTemplateOnEdit, setOriginalJsTemplateOnEdit] = React.useState<string>('');

  // Collapsible section states
  const [isTemplateSectionOpen, setIsTemplateSectionOpen] = React.useState(true);
  const [isActionMappingSectionOpen, setIsActionMappingSectionOpen] = React.useState(true);
  const [isVariableMappingSectionOpen, setIsVariableMappingSectionOpen] = React.useState(true);

  // Variable mapping state: maps generic template var names to workflow expressions
  const [editedVariableMapping, setEditedVariableMapping] = React.useState<Record<string, string>>({});

  // Action mapping state: maps CSS selectors to trigger refs
  const [editedActionMapping, setEditedActionMapping] = React.useState<Record<string, string>>(
    data.interfaceData?.actionMapping || {}
  );

  // Discover eligible target nodes (triggers, other interfaces) from the workflow
  // Only show nodes that are connected (directly or indirectly) to this interface in the DAG
  const allNodes = useNodes<BuilderNodeData>();
  const allEdges = useEdges();
  const currentNodeId = node?.id || '';

  // Compute all interface nodes for entry toggle logic (entry is global, not mode-dependent)
  const allInterfaceNodes = React.useMemo(() =>
    allNodes.filter(n => nodeRegistry.isInterfaceNode(n)),
    [allNodes]
  );
  const isOnlyInterface = allInterfaceNodes.length <= 1;
  // Check if another interface already has entry - if not, current cannot be unchecked
  const hasOtherEntry = allInterfaceNodes.some(
    n => n.id !== currentNodeId && (n.data as any)?.interfaceData?.isEntryInterface === true
  );

  // Build connected component: all nodes reachable from this interface (both directions)
  const connectedNodeIds = React.useMemo(() => {
    const adjacency = new Map<string, Set<string>>();
    for (const edge of allEdges) {
      if (!adjacency.has(edge.source)) adjacency.set(edge.source, new Set());
      if (!adjacency.has(edge.target)) adjacency.set(edge.target, new Set());
      adjacency.get(edge.source)!.add(edge.target);
      adjacency.get(edge.target)!.add(edge.source);
    }
    const visited = new Set<string>();
    const queue = [currentNodeId];
    while (queue.length > 0) {
      const id = queue.shift()!;
      if (visited.has(id)) continue;
      visited.add(id);
      const neighbors = adjacency.get(id);
      if (neighbors) {
        for (const neighbor of neighbors) {
          if (!visited.has(neighbor)) queue.push(neighbor);
        }
      }
    }
    return visited;
  }, [allEdges, currentNodeId]);

  const eligibleTargets = React.useMemo(() => {
    return allNodes
      .filter((n) => {
        const id = n.data?.id || n.id || '';
        // Must be in the same connected component as this interface
        if (!connectedNodeIds.has(n.id)) return false;
        // Include standard triggers
        if (nodeRegistry.isTrigger(n)) {
          return (
            id.startsWith('manual-trigger') ||
            id.startsWith('form-trigger') ||
            id.startsWith('chat-trigger')
          );
        }
        // Include other interface nodes (for navigation between interfaces), exclude self
        if (nodeRegistry.isInterfaceNode(n) && n.id !== currentNodeId) return true;
        return false;
      })
      .map((n) => {
        const id = n.data?.id || n.id || '';
        const label = n.data?.label || id;
        const tKey = triggerKey(label);
        let actionType = 'click';
        if (id.startsWith('form-trigger')) actionType = 'submit';
        else if (id.startsWith('chat-trigger')) actionType = 'message';
        else if (nodeRegistry.isInterfaceNode(n)) actionType = 'navigate';
        return {
          nodeId: n.id,
          label,
          triggerRef: tKey ? `${tKey}:${actionType}` : `trigger:${label}:${actionType}`,
          actionType,
        };
      });
  }, [allNodes, allEdges, currentNodeId, connectedNodeIds]);

  // Track if we just saved to prevent re-entering edit mode and hasChanges recalculation
  const [justSaved, setJustSaved] = React.useState(false);

  // Helper to strip HTML tags from a string (in case HTML was incorrectly saved)
  const stripHtmlTags = React.useCallback((html: string): string => {
    if (!html) return '';
    // Only strip <span> tags added by the expression editor (syntax highlighting)
    // Keep all other HTML tags intact (user's template HTML)
    if (!html.includes('<span') && !html.includes('</span>')) return html;

    // Remove only <span> tags with specific classes used by the editor
    // This regex removes spans but keeps their content and all other HTML
    return html
      .replace(/<span[^>]*class="[^"]*token[^"]*"[^>]*>/gi, '')
      .replace(/<\/span>/gi, '')
      .trim();
  }, []);

  // Get current expression based on edit mode
  const currentExpression = isEditMode ? editedHtmlTemplate : getEditorExpression();
  const isEmpty = !currentExpression || currentExpression.trim() === '';

  // Track if we've initialized the template from DB for this interface
  const [initializedInterfaceId, setInitializedInterfaceId] = React.useState<string | null>(null);
  const isInterfaceInitialized = !hasInterfaceId || initializedInterfaceId === interfaceId;
  const [hasLoadedTemplate, setHasLoadedTemplate] = React.useState(false);

  // Reset template load flag when interface changes
  React.useEffect(() => {
    setHasLoadedTemplate(!hasInterfaceId); // if no interfaceId, consider template loaded
  }, [interfaceId, hasInterfaceId]);

  // Load template from DB when interface is loaded (only once per interface, not when editing)
  React.useEffect(() => {
    if (hasInterfaceId && interfaceId && interfaceDetails && !isLoadingInterface && initializedInterfaceId !== interfaceId) {
      const rawTemplateFromDb = interfaceDetails.htmlTemplate || interfaceDetails.editorExpression || '';
      // Clean any HTML tags that might have been incorrectly saved
      const templateFromDb = stripHtmlTags(rawTemplateFromDb);
      const currentTemplate = getEditorExpression();

      // Initialize editedHtmlTemplate with DB template
      if (templateFromDb) {
        setEditedHtmlTemplate(templateFromDb);
        // Also store as original template for cancel
        setOriginalTemplateOnEdit(templateFromDb);
      }

      // Initialize cssTemplate from DB
      const cssFromDb = interfaceDetails.cssTemplate || '';
      setEditedCssTemplate(cssFromDb);
      setOriginalCssTemplateOnEdit(cssFromDb);

      // Initialize jsTemplate from DB
      const jsFromDb = (interfaceDetails as any).jsTemplate || '';
      setEditedJsTemplate(jsFromDb);
      setOriginalJsTemplateOnEdit(jsFromDb);

      // Initialize variable mapping from node data
      const existingMapping = interfaceData?.variableMapping || {};
      setEditedVariableMapping(existingMapping);

      // Initialize action mapping from node data
      const existingActionMapping = interfaceData?.actionMapping || {};
      setEditedActionMapping(existingActionMapping);

      // Sync templateVariables to node interfaceData (for validation rule access)
      const templateVars = interfaceDetails.templateVariables as string[] | undefined;
      if (templateVars && templateVars.length > 0 && node) {
        const currentData = node.data;
        const currentInterfaceData = (currentData as any)?.interfaceData || {};
        if (JSON.stringify(currentInterfaceData.templateVariables) !== JSON.stringify(templateVars)) {
          onUpdate({
            ...currentData,
            interfaceData: {
              ...currentInterfaceData,
              templateVariables: templateVars,
            },
          });
        }
      }

      // Set edit mode to false when we have an interfaceId (read-only by default)
      setIsEditMode(false);

      // Only set if current is empty and we haven't loaded initial template yet
      if (templateFromDb && (!currentTemplate || currentTemplate.trim() === '')) {
        handleEditorExpressionChange(templateFromDb);
      }

      // Mark template as loaded/synced
      setHasLoadedTemplate(true);

      // Mark as initialized for this interface
      setInitializedInterfaceId(interfaceId);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasInterfaceId, interfaceId, interfaceDetails, isLoadingInterface, initializedInterfaceId, getEditorExpression, handleEditorExpressionChange, stripHtmlTags, node, onUpdate]);

  // Sync editedHtmlTemplate with node data when not in edit mode
  React.useEffect(() => {
    if (!isEditMode && hasInterfaceId) {
      const currentTemplate = stripHtmlTags(getEditorExpression());
      const dbTemplate = stripHtmlTags(interfaceDetails?.htmlTemplate || interfaceDetails?.editorExpression || '');
      // Use DB template if available, otherwise use current template
      const templateToUse = dbTemplate || currentTemplate;
      if (templateToUse !== editedHtmlTemplate) {
        setEditedHtmlTemplate(templateToUse);
      }
    }
  }, [isEditMode, hasInterfaceId, interfaceDetails, getEditorExpression, editedHtmlTemplate, stripHtmlTags]);

  // Check if there's a difference between edited template and DB template
  // Don't recalculate hasChanges right after saving to prevent race conditions
  const dbTemplate = interfaceDetails?.htmlTemplate || interfaceDetails?.editorExpression || '';
  const baselineTemplate = dbTemplate || originalTemplateOnEdit || '';
  const currentTemplate = isEditMode ? editedHtmlTemplate : getEditorExpression();
  const dbCssTemplate = interfaceDetails?.cssTemplate || '';
  const baselineCssTemplate = dbCssTemplate || originalCssTemplateOnEdit || '';
  const dbJsTemplate = (interfaceDetails as any)?.jsTemplate || '';
  const baselineJsTemplate = dbJsTemplate || originalJsTemplateOnEdit || '';
  const htmlChanged = baselineTemplate !== '' && currentTemplate !== baselineTemplate;
  const cssChanged = editedCssTemplate !== baselineCssTemplate;
  const jsChanged = editedJsTemplate !== baselineJsTemplate;
  const hasChanges = !justSaved &&
    hasInterfaceId &&
    isInterfaceInitialized &&
    hasLoadedTemplate &&
    (htmlChanged || cssChanged || jsChanged);

  // Update node data to indicate unsaved changes (for validation service)
  React.useEffect(() => {
    if (!node || !hasInterfaceId || !isInterfaceInitialized || isLoadingInterface) return;
    const currentData = node.data;
    const currentHasUnsavedChanges = (currentData as any)?.hasUnsavedInterfaceChanges || false;

    if (hasChanges !== currentHasUnsavedChanges) {
      onUpdate({
        ...currentData,
        hasUnsavedInterfaceChanges: hasChanges,
      });
    }
  }, [hasChanges, hasInterfaceId, isInterfaceInitialized, isLoadingInterface, node, onUpdate]);

  // Handle entering edit mode
  const handleEdit = () => {
    // Capture current template as original (for cancel)
    const currentTemplate = getEditorExpression();
    setOriginalTemplateOnEdit(currentTemplate);
    setOriginalCssTemplateOnEdit(editedCssTemplate);
    setOriginalJsTemplateOnEdit(editedJsTemplate);
    // Initialize editedHtmlTemplate with current template from editor
    setEditedHtmlTemplate(currentTemplate);
    setIsEditMode(true);
  };

  // Handle cancel - restore original template and exit edit mode
  const handleCancel = () => {
    // Reset to original HTML template (captured when entering edit mode)
    const templateToRestore = originalTemplateOnEdit || (interfaceDetails?.htmlTemplate || interfaceDetails?.editorExpression || '');
    const cssToRestore = originalCssTemplateOnEdit || (interfaceDetails?.cssTemplate || '');
    const jsToRestore = originalJsTemplateOnEdit || ((interfaceDetails as any)?.jsTemplate || '');
    setEditedHtmlTemplate(templateToRestore);
    setOriginalTemplateOnEdit(templateToRestore);
    setEditedCssTemplate(cssToRestore);
    setOriginalCssTemplateOnEdit(cssToRestore);
    setEditedJsTemplate(jsToRestore);
    setOriginalJsTemplateOnEdit(jsToRestore);
    setIsEditMode(false);

    // Restore the template and clear the unsaved flag in a single update to avoid race conditions
    if (node) {
      const currentData = node.data;
      const currentInterfaceData = (currentData as any)?.interfaceData || {};
      onUpdate({
        ...currentData,
        interfaceData: {
          ...currentInterfaceData,
          editorExpression: templateToRestore,
          cssTemplate: cssToRestore,
          jsTemplate: jsToRestore,
        },
        hasUnsavedInterfaceChanges: false,
      });
    } else {
      // Fallback for safety (shouldn't normally happen)
      handleEditorExpressionChange(templateToRestore);
    }
  };

  // Save function
  const handleSave = async () => {
    if (!hasInterfaceId || !interfaceId) return;

    try {
      // Mark that we just saved to prevent re-entering edit mode
      setJustSaved(true);

      // Clean the template before saving (strip any HTML tags that shouldn't be there)
      const cleanTemplate = stripHtmlTags(editedHtmlTemplate);

      // Use centralized API with cssTemplate and jsTemplate
      const updated = await orchestratorApi.updateInterface(interfaceId, {
        htmlTemplate: cleanTemplate,
        cssTemplate: editedCssTemplate || undefined,
        jsTemplate: editedJsTemplate || undefined,
      } as any);
      // Update editedHtmlTemplate with saved value (also clean in case API returns HTML)
      const rawSavedTemplate = updated.htmlTemplate || (updated as any).editorExpression || cleanTemplate;
      const savedTemplate = stripHtmlTags(rawSavedTemplate);
      const savedCssTemplate = (updated as any).cssTemplate || editedCssTemplate || '';
      const savedJsTemplate = (updated as any).jsTemplate || editedJsTemplate || '';
      setEditedHtmlTemplate(savedTemplate);
      setOriginalTemplateOnEdit(savedTemplate);
      setEditedCssTemplate(savedCssTemplate);
      setOriginalCssTemplateOnEdit(savedCssTemplate);
      setEditedJsTemplate(savedJsTemplate);
      setOriginalJsTemplateOnEdit(savedJsTemplate);
      setHasLoadedTemplate(true);

      // Exit edit mode
      setIsEditMode(false);

      // Update node data with saved template, mapping, and clear the unsaved changes flag in one go
      if (node) {
        const currentData = node.data;
        const currentInterfaceData = (currentData as any)?.interfaceData || {};
        onUpdate({
          ...currentData,
          interfaceData: {
            ...currentInterfaceData,
            editorExpression: savedTemplate,
            cssTemplate: savedCssTemplate,
            jsTemplate: savedJsTemplate,
            variableMapping: editedVariableMapping,
            actionMapping: editedActionMapping,
          },
          hasUnsavedInterfaceChanges: false,
        });
      } else {
        // Fallback for safety
        handleEditorExpressionChange(savedTemplate);
      }

      // Refetch to get latest data and then clear justSaved
      await refetchInterface();

      // Clear justSaved after refetch completes
      setJustSaved(false);
    } catch (error) {
      console.error('Error saving interface:', error);
      alert('Failed to save interface. Please try again.');
      setJustSaved(false);
    }
  };

  const handleInsertExample = () => {
    if (isRunMode) return;
    handleEditorExpressionChange(HTML_TEMPLATE_EXAMPLE);
  };

  // Determine if editor should be read-only
  const isReadOnly = isRunMode || (hasInterfaceId && !isEditMode);

  // When there are unsaved changes, keep edit mode active even if user temporarily selects another node
  // But don't re-enter edit mode right after saving
  React.useEffect(() => {
    if (!hasInterfaceId || isRunMode || !isInterfaceInitialized || justSaved) return;

    if (hasUnsavedInterfaceChanges && !isEditMode) {
      // Ensure we stay in edit mode and keep the edited template visible
      setIsEditMode(true);
      const currentTemplate = getEditorExpression();
      setEditedHtmlTemplate(currentTemplate);
      if (!originalTemplateOnEdit) {
        const dbTemplate = interfaceDetails?.htmlTemplate || interfaceDetails?.editorExpression || '';
        setOriginalTemplateOnEdit(dbTemplate || currentTemplate);
      }
    }
  }, [
    hasInterfaceId,
    hasUnsavedInterfaceChanges,
    isInterfaceInitialized,
    isLoadingInterface,
    isRunMode,
    isEditMode,
    justSaved,
    getEditorExpression,
    originalTemplateOnEdit,
    interfaceDetails,
  ]);

  return (
    <InspectorColumn
      title={t('columnTitle')}
      showRightBorder={true}
      headerRight={
        <Popover>
          <PopoverTrigger asChild>
            <button
              type="button"
              className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
            >
              <Info className="h-3 w-3 text-slate-500" />
            </button>
          </PopoverTrigger>
          <PopoverContent className="w-[min(420px,calc(100vw-32px))] max-h-[600px] overflow-y-auto p-4 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-[24px] z-[99999]" side="right" align="start">
            <div className="space-y-3">
              <h4 className="font-semibold text-sm">{t('expressionGuideTitle')}</h4>
              <div className="space-y-3 text-xs text-slate-600 dark:text-slate-300">
                {/* Variables */}
                <div>
                  <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-cyan-500"></span>
                    {t('expressionGuideVariables')}
                  </p>
                  <ul className="list-disc list-inside space-y-0.5 ml-2">
                    <li>{t('expressionGuideVariablesGeneric')}</li>
                    <li>{t('expressionGuideVariablesMap')}</li>
                    <li>{t('expressionGuideVariablesExample')} <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`<h1>{{title}}</h1>`}</code></li>
                    <li>{t('expressionGuideVariablesMappingExample')} <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">title → mcp:enricher.output.data.title</code></li>
                  </ul>
                </div>

                {/* Type Casting */}
                <div>
                  <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-indigo-500"></span>
                    Type Casting
                  </p>
                  <ul className="list-disc list-inside space-y-0.5 ml-2">
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">int()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">double()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">string()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">bool()</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{int(mcp:alias.output.value)}}`}</code></li>
                  </ul>
                </div>

                {/* String Functions */}
                <div>
                  <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
                    String Functions
                  </p>
                  <ul className="list-disc list-inside space-y-0.5 ml-2">
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">uppercase()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">lowercase()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">capitalize()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">trim()</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">truncate(val, 50)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">substring(val, 0, 10)</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`replace(val, "old", "new")`}</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`padleft(val, 5, "0")`}</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`padright(val, 10, " ")`}</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">contains()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">startswith()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">endswith()</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{uppercase(mcp:alias.output.name)}}`}</code></li>
                  </ul>
                </div>

                {/* Math Functions */}
                <div>
                  <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-lime-500"></span>
                    Math Functions
                  </p>
                  <ul className="list-disc list-inside space-y-0.5 ml-2">
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">abs()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">round(val, decimals)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">floor()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">ceil()</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">min(a, b)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">max(a, b)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">pow()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">sqrt()</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{round(mcp:alias.output.price, 2)}}`}</code></li>
                  </ul>
                </div>

                {/* Date/Number Formatting */}
                <div>
                  <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-sky-500"></span>
                    Date/Number Formatting
                  </p>
                  <ul className="list-disc list-inside space-y-0.5 ml-2">
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`formatdate(val, "yyyy-MM-dd")`}</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">formatnumber(val, 2)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`formatcurrency(val, "EUR")`}</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">now()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">today()</code></li>
                  </ul>
                </div>

                {/* Utility Functions */}
                <div>
                  <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-fuchsia-500"></span>
                    Utility Functions
                  </p>
                  <ul className="list-disc list-inside space-y-0.5 ml-2">
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">size()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`default(val, "N/A")`}</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">coalesce(a, b, c)</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">isempty()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">isnull()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">typeof()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">len()</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">json(value)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">fromjson(value)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">tojson(value)</code> - JSON parse/serialize (idempotent)</li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{default(mcp:alias.output.value, "Unknown")}}`}</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{json(mcp:fetch.output.body)}}`}</code> - re-parse stringified JSON to typed Map</li>
                  </ul>
                </div>

                {/* Nested Functions */}
                <div>
                  <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-orange-500"></span>
                    Nested Functions
                  </p>
                  <ul className="list-disc list-inside space-y-0.5 ml-2">
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{uppercase(truncate(mcp:alias.output.name, 10))}}`}</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{formatcurrency(round(mcp:alias.output.price), "USD")}}`}</code></li>
                  </ul>
                </div>

                {/* Resolved Data in JS */}
                <div>
                  <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-violet-500"></span>
                    window.__RESOLVED_DATA__ (JS Template)
                  </p>
                  <p className="mb-1 ml-2">In run mode, all resolved variables are available as <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">window.__RESOLVED_DATA__</code> in the JS template.</p>
                  <ul className="list-disc list-inside space-y-0.5 ml-2">
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`const data = window.__RESOLVED_DATA__;`}</code></li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`document.getElementById('name').textContent = data.username;`}</code></li>
                    <li>Complex objects are available directly, no <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">JSON.parse</code> needed</li>
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`const items = data.posts_data;`}</code></li>
                  </ul>
                </div>

                {/* File/Image Display */}
                <div>
                  <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-rose-500"></span>
                    File / Image Display
                  </p>
                  <p className="mb-1 ml-2">After a <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">download_file</code> node, use the canonical <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">file</code> output in image tags:</p>
                  <ul className="list-disc list-inside space-y-0.5 ml-2">
                    <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`<img src="{{avatar}}" />`}</code></li>
                    <li>Map <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">avatar</code> to <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">core:download.output.file</code></li>
                    <li>Auth token (or HMAC signature for marketplace/share preview) is injected automatically</li>
                  </ul>
                </div>
              </div>
            </div>
          </PopoverContent>
        </Popover>
      }
    >
      <div className="flex flex-col space-y-4 pt-2">
        {/* ═══════ ZONE 0: Interface Settings ═══════ */}
        <div className="flex flex-col gap-1.5">
          {/* Entry Interface toggle - hidden in run/preview mode (no edit semantics) */}
          {!isRunMode && (
            <div className="flex items-center justify-between mt-2 px-1 gap-3">
              <div className="flex items-center gap-1">
                <span className="text-sm text-slate-600 dark:text-slate-300">{t('entryInterface')}</span>
                <Popover>
                  <PopoverTrigger asChild>
                    <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                      <Info className="h-2.5 w-2.5 text-slate-400" />
                    </button>
                  </PopoverTrigger>
                  <PopoverContent className="w-[240px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                    <p className="text-xs text-slate-600 dark:text-slate-300">{t('entryInterfaceDescription')}</p>
                  </PopoverContent>
                </Popover>
              </div>
              <Switch
                checked={isOnlyInterface || interfaceData.isEntryInterface === true}
                disabled={isOnlyInterface || (interfaceData.isEntryInterface && !hasOtherEntry)}
                onCheckedChange={(checked) => {
                  // Cannot uncheck if no other entry exists
                  if (!checked && !hasOtherEntry) return;
                  onUpdate({
                    ...data,
                    interfaceData: {
                      ...interfaceData,
                      isEntryInterface: checked,
                    },
                  });
                }}
              />
            </div>
          )}

          {/* Generate screenshot toggle - exposes a `screenshot` FileRef output for downstream nodes. */}
          {!isRunMode && (
            <div className="flex items-center justify-between mt-2 px-1 gap-3">
              <div className="flex items-center gap-1">
                <span className="text-sm text-slate-600 dark:text-slate-300">{t('generateScreenshot')}</span>
                <Popover>
                  <PopoverTrigger asChild>
                    <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                      <Info className="h-2.5 w-2.5 text-slate-400" />
                    </button>
                  </PopoverTrigger>
                  <PopoverContent className="w-[260px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                    <p className="text-xs text-slate-600 dark:text-slate-300">{t('generateScreenshotDescription')}</p>
                  </PopoverContent>
                </Popover>
              </div>
              <Switch
                checked={interfaceData.generateScreenshot === true}
                onCheckedChange={(checked) => {
                  onUpdate({
                    ...data,
                    interfaceData: {
                      ...interfaceData,
                      generateScreenshot: checked,
                    },
                  });
                }}
              />
            </div>
          )}

          {/* Generate PDF toggle - exposes a `pdf` FileRef output for downstream nodes. */}
          {!isRunMode && (
            <div className="mt-2 px-1">
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-1">
                  <span className="text-sm text-slate-600 dark:text-slate-300">{t('generatePdf')}</span>
                  <Popover>
                    <PopoverTrigger asChild>
                      <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                        <Info className="h-2.5 w-2.5 text-slate-400" />
                      </button>
                    </PopoverTrigger>
                    <PopoverContent className="w-[260px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                      <p className="text-xs text-slate-600 dark:text-slate-300">{t('generatePdfDescription')}</p>
                    </PopoverContent>
                  </Popover>
                </div>
                <Switch
                  checked={interfaceData.generatePdf === true}
                  onCheckedChange={(checked) => {
                    onUpdate({
                      ...data,
                      interfaceData: {
                        ...interfaceData,
                        generatePdf: checked,
                      },
                    });
                  }}
                />
              </div>

              {/* PDF page options - only shown when PDF generation is enabled. */}
              {interfaceData.generatePdf === true && (
                <div className="mt-2 flex items-center justify-between gap-3 pl-3">
                  <label className="text-xs text-slate-500 dark:text-slate-400" htmlFor="pdf-format-select">
                    {t('pdfFormat')}
                  </label>
                  <div className="flex items-center gap-3">
                    <select
                      id="pdf-format-select"
                      className="text-xs rounded-md border border-gray-200/60 dark:border-gray-700/60 bg-[var(--bg-primary)] px-2 py-1 text-slate-600 dark:text-slate-300"
                      value={interfaceData.pdfFormat || 'A4'}
                      onChange={(e) => {
                        onUpdate({
                          ...data,
                          interfaceData: {
                            ...interfaceData,
                            pdfFormat: e.target.value,
                          },
                        });
                      }}
                    >
                      <option value="A4">A4</option>
                      <option value="Letter">Letter</option>
                      <option value="Legal">Legal</option>
                    </select>
                    <div className="flex items-center gap-1.5">
                      <span className="text-xs text-slate-500 dark:text-slate-400">{t('pdfLandscape')}</span>
                      <Switch
                        checked={interfaceData.pdfLandscape === true}
                        onCheckedChange={(checked) => {
                          onUpdate({
                            ...data,
                            interfaceData: {
                              ...interfaceData,
                              pdfLandscape: checked,
                            },
                          });
                        }}
                      />
                    </div>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Expose rendered source toggle - exposes `rendered_html` / `rendered_css` / `rendered_js` string outputs. */}
          {!isRunMode && (
            <div className="flex items-center justify-between mt-2 px-1 gap-3">
              <div className="flex items-center gap-1">
                <span className="text-sm text-slate-600 dark:text-slate-300">{t('exposeRenderedSource')}</span>
                <Popover>
                  <PopoverTrigger asChild>
                    <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                      <Info className="h-2.5 w-2.5 text-slate-400" />
                    </button>
                  </PopoverTrigger>
                  <PopoverContent className="w-[260px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                    <p className="text-xs text-slate-600 dark:text-slate-300">{t('exposeRenderedSourceDescription')}</p>
                  </PopoverContent>
                </Popover>
              </div>
              <Switch
                checked={interfaceData.exposeRenderedSource === true}
                onCheckedChange={(checked) => {
                  onUpdate({
                    ...data,
                    interfaceData: {
                      ...interfaceData,
                      exposeRenderedSource: checked,
                    },
                  });
                }}
              />
            </div>
          )}

          {/* ═══════ Action Mapping (sub-section) - visible in preview, inputs gated by isRunMode ═══════ */}
          <>
            {/* Visual separator (only when Entry Interface above is visible) */}
            {!isRunMode && (
              <div className="border-t border-dashed border-slate-300 dark:border-slate-600 mt-2" />
            )}

              <div className="space-y-4 pt-2">
                {/* Section header - collapsible */}
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5">
                    <button
                      type="button"
                      className="flex items-center gap-1.5 group"
                      onClick={() => setIsActionMappingSectionOpen(!isActionMappingSectionOpen)}
                    >
                      <ChevronRight className={`h-3.5 w-3.5 text-slate-400 dark:text-slate-500 transition-transform ${isActionMappingSectionOpen ? 'rotate-90' : ''}`} />
                      <span className="text-sm font-semibold text-slate-500 dark:text-slate-400 group-hover:text-slate-600 dark:group-hover:text-slate-300">
                        {t('actionMappingSection')}
                      </span>
                    </button>
                    {!isActionMappingSectionOpen && Object.keys(editedActionMapping).length > 0 && (
                      <span className="text-xs text-slate-400 dark:text-slate-500">({Object.keys(editedActionMapping).length})</span>
                    )}
                    <Popover>
                      <PopoverTrigger asChild>
                        <button
                          type="button"
                          className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
                        >
                          <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
                        </button>
                      </PopoverTrigger>
                      <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                        <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                          <p className="font-semibold text-slate-900 dark:text-slate-100">{t('actionMappingSection')}</p>
                          <p className="text-xs">{t('actionMappingInfoDesc')}</p>
                          <ul className="list-disc list-inside space-y-1 text-xs">
                            <li>{t('actionMappingInfoAction')}</li>
                            <li>{t('actionMappingInfoTarget')}</li>
                            <li>{t('actionMappingInfoTypes')}</li>
                          </ul>
                        </div>
                      </PopoverContent>
                    </Popover>
                  </div>
                  {!isRunMode && (
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
                      onClick={() => {
                        if (!isActionMappingSectionOpen) setIsActionMappingSectionOpen(true);
                        const newMapping = { ...editedActionMapping, '': '' };
                        setEditedActionMapping(newMapping);
                      }}
                      disabled={Object.keys(editedActionMapping).includes('')}
                      title={t('addAction')}
                    >
                      <Plus className="h-3 w-3" />
                    </Button>
                  )}
                </div>

                {isActionMappingSectionOpen && (<>
                {/* Mapping rows - indexed circles like Fork branches */}
                <div className="space-y-2">
                  {Object.entries(editedActionMapping).map(([selector, triggerRefValue], index) => (
                    <div key={index} className="flex items-start gap-2">
                      {/* Index circle */}
                      <div className="w-6 h-6 rounded-full bg-indigo-100 dark:bg-indigo-900/30 flex items-center justify-center flex-shrink-0 mt-1">
                        <span className="text-xs font-medium text-indigo-600 dark:text-indigo-400">{index + 1}</span>
                      </div>

                      {/* Fields */}
                      <div className="flex-1 min-w-0 space-y-1.5">
                        {/* Action Name */}
                        <label className="flex flex-col gap-1">
                          <div className="flex items-center gap-1">
                            <span className="text-xs font-semibold text-slate-500 dark:text-slate-400">{t('actionName')}</span>
                            <Popover>
                              <PopoverTrigger asChild>
                                <button type="button" className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5">
                                  <Info className="h-2.5 w-2.5 text-slate-400" />
                                </button>
                              </PopoverTrigger>
                              <PopoverContent className="w-[240px] p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                                <p className="text-xs text-slate-600 dark:text-slate-300">{t('actionNameInfo')}</p>
                              </PopoverContent>
                            </Popover>
                          </div>
                          <Input
                            value={selector}
                            onChange={(e) => {
                              const newSelector = e.target.value;
                              const entries = Object.entries(editedActionMapping);
                              const newEntries = entries.map(([s, v], i) =>
                                i === index ? [newSelector, v] : [s, v]
                              );
                              const newMapping = Object.fromEntries(newEntries);
                              setEditedActionMapping(newMapping);
                              if (node) {
                                onUpdate({
                                  ...node.data,
                                  interfaceData: {
                                    ...(node.data as any)?.interfaceData,
                                    actionMapping: newMapping,
                                  },
                                });
                              }
                            }}
                            placeholder={t('actionNamePlaceholder')}
                            className="w-full font-mono"
                            disabled={isRunMode}
                          />
                        </label>

                        {/* Action target select */}
                        <label className="flex flex-col gap-1">
                          <span className="text-xs font-semibold text-slate-500 dark:text-slate-400">{t('selectTarget')}</span>
                          <Select
                            value={triggerRefValue || undefined}
                            onValueChange={(value) => {
                              const newMapping = { ...editedActionMapping, [selector]: value };
                              setEditedActionMapping(newMapping);
                              if (node) {
                                onUpdate({
                                  ...node.data,
                                  interfaceData: {
                                    ...(node.data as any)?.interfaceData,
                                    actionMapping: newMapping,
                                  },
                                });
                              }
                            }}
                            disabled={isRunMode}
                          >
                            <SelectTrigger className="w-full min-h-[36px]">
                              <SelectValue placeholder={t('selectTarget')} />
                            </SelectTrigger>
                            <SelectContent>
                              {eligibleTargets.length > 0 && (
                                <SelectGroup>
                                  <SelectLabel className="text-xs text-slate-400 dark:text-slate-500">{t('nodesGroup')}</SelectLabel>
                                  {eligibleTargets.map((target) => {
                                    const actionTypeLabels: Record<string, string> = {
                                      click: t('actionTypeClick'),
                                      submit: t('actionTypeSubmit'),
                                      message: t('actionTypeMessage'),
                                      navigate: t('actionTypeNavigate'),
                                    };
                                    return (
                                      <SelectItem key={target.nodeId} value={target.triggerRef}>
                                        {target.label} ({actionTypeLabels[target.actionType] || target.actionType})
                                      </SelectItem>
                                    );
                                  })}
                                </SelectGroup>
                              )}
                              {eligibleTargets.length > 0 && <SelectSeparator />}
                              <SelectGroup>
                                <SelectLabel className="text-xs text-slate-400 dark:text-slate-500">{t('paginationGroup')}</SelectLabel>
                                <SelectItem value="__pagination:prev">
                                  {t('actionTypePaginationPrev')}
                                </SelectItem>
                                <SelectItem value="__pagination:next">
                                  {t('actionTypePaginationNext')}
                                </SelectItem>
                              </SelectGroup>
                              <SelectSeparator />
                              <SelectGroup>
                                <SelectLabel className="text-xs text-slate-400 dark:text-slate-500">{t('workflowGroup')}</SelectLabel>
                                <SelectItem value="__continue">
                                  {t('actionTypeContinue')}
                                </SelectItem>
                              </SelectGroup>
                            </SelectContent>
                          </Select>
                        </label>
                      </div>

                      {/* Delete button */}
                      {!isRunMode && (
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          className="flex-shrink-0 h-6 w-6 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 mt-1"
                          onClick={() => {
                            const entries = Object.entries(editedActionMapping).filter((_, i) => i !== index);
                            const newMapping = Object.fromEntries(entries);
                            setEditedActionMapping(newMapping);
                            if (node) {
                              onUpdate({
                                ...node.data,
                                interfaceData: {
                                  ...(node.data as any)?.interfaceData,
                                  actionMapping: newMapping,
                                },
                              });
                            }
                          }}
                          title={t('deleteAction')}
                        >
                          <Trash2 className="h-3 w-3" />
                        </Button>
                      )}
                    </div>
                  ))}
                </div>
                </>)}
              </div>
            </>
          </div>

        {/* ═══════ ZONE 1: Interface Template (saved to DB) ═══════ */}
        <div className="flex flex-col gap-3">
          {/* Section header with collapse toggle + Edit/Save/Cancel */}
          <div className="flex items-center justify-between">
            <button
              type="button"
              className="flex items-center gap-1.5 group"
              onClick={() => setIsTemplateSectionOpen(!isTemplateSectionOpen)}
            >
              <ChevronRight className={`h-3.5 w-3.5 text-slate-400 dark:text-slate-500 transition-transform ${isTemplateSectionOpen ? 'rotate-90' : ''}`} />
              <span className="text-sm font-semibold text-slate-500 dark:text-slate-400 group-hover:text-slate-600 dark:group-hover:text-slate-300">{t('interfaceTemplateSection')}</span>
            </button>
            {hasInterfaceId && (
              <div className="flex items-center gap-1.5">
                {!isEditMode ? (
                  <Button
                    type="button"
                    variant="default"
                    size="sm"
                    className="h-6 px-2 text-xs"
                    onClick={handleEdit}
                    disabled={isRunMode}
                  >
                    <Edit className="h-3 w-3 mr-1" />
                    {t('edit')}
                  </Button>
                ) : (
                  <>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="h-6 px-2 text-xs"
                      onClick={handleCancel}
                      disabled={isRunMode}
                    >
                      <X className="h-3 w-3 mr-1" />
                      {t('cancel')}
                    </Button>
                    <Button
                      type="button"
                      variant="default"
                      size="sm"
                      className="h-6 px-2 text-xs"
                      onClick={handleSave}
                      disabled={isRunMode}
                    >
                      <Save className="h-3 w-3 mr-1" />
                      {t('save')}
                    </Button>
                  </>
                )}
              </div>
            )}
            {/* Save button for non-interfaceId cases (legacy behavior) */}
            {!hasInterfaceId && hasChanges && (
              <Button
                type="button"
                variant="default"
                size="sm"
                className="h-6 px-2 text-xs"
                onClick={handleSave}
                disabled={isRunMode}
              >
                <Save className="h-3 w-3 mr-1" />
                {t('save')}
              </Button>
            )}
          </div>

          {/* Collapsible content */}
          {isTemplateSectionOpen && (<>
          {/* HTML Template Editor */}
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between flex-shrink-0">
              <span className="text-xs font-semibold text-slate-500 dark:text-slate-400">{t('htmlTemplateTitle')}</span>
              <div className="flex items-center gap-2">
                {isEmpty && !hasInterfaceId && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="h-6 px-2 text-xs text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
                    onClick={handleInsertExample}
                    disabled={isRunMode}
                    title="Insert HTML template example"
                  >
                    <FileText className="h-3 w-3 mr-1" />
                    {t('insertExample')}
                  </Button>
                )}
                <span className="text-xs text-slate-500 dark:text-slate-400">{t('required')}</span>
              </div>
            </div>
            <div className="w-full">
              <ExpressionEditor
                key={`editor-${isReadOnly ? 'readonly' : 'edit'}`}
                value={stripHtmlTags(isEditMode ? editedHtmlTemplate : getEditorExpression())}
                onChange={(value) => {
                  if (isReadOnly) return;
                  const cleanValue = stripHtmlTags(value);
                  if (isEditMode) {
                    setEditedHtmlTemplate(cleanValue);
                    handleEditorExpressionChange(cleanValue);
                  } else {
                    handleEditorExpressionChange(cleanValue);
                  }
                }}
                onDrop={(e) => {
                  if (isReadOnly) return;
                  e.preventDefault();
                  const text = e.dataTransfer.getData('text/plain');
                  const fieldType = e.dataTransfer.getData('application/x-field-type') || 'text';
                  if (!text) return;

                  const cleanExpr = text.replace(/^\{\{/, '').replace(/\}\}$/, '').trim();
                  const parts = cleanExpr.split('.');
                  const fieldName = parts[parts.length - 1] || cleanExpr;
                  const defaultValue = getDefaultForType(fieldType, fieldName);
                  const varPlaceholder = `{{${fieldName}|${defaultValue}}}`;

                  const currentValue = isEditMode ? editedHtmlTemplate : getEditorExpression();
                  const newValue = currentValue + varPlaceholder;
                  const cleanValue = stripHtmlTags(newValue);

                  if (isEditMode) {
                    setEditedHtmlTemplate(cleanValue);
                    handleEditorExpressionChange(cleanValue);
                  } else {
                    handleEditorExpressionChange(cleanValue);
                  }
                }}
                placeholder="Enter HTML code with expressions like {{variable|default}}..."
                readOnly={isReadOnly}
                isRequired={true}
              />
            </div>
          </div>

          {/* CSS Template Editor */}
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between flex-shrink-0">
              <span className="text-xs font-semibold text-slate-500 dark:text-slate-400">{t('cssTemplateTitle')}</span>
              <span className="text-xs text-slate-400 dark:text-slate-500">{t('optional')}</span>
            </div>
            <div className="w-full">
              <ExpressionEditor
                key={`css-editor-${isReadOnly ? 'readonly' : 'edit'}`}
                value={editedCssTemplate}
                onChange={(value) => {
                  if (isReadOnly) return;
                  setEditedCssTemplate(value);
                  if (node) {
                    const currentData = node.data;
                    const currentInterfaceData = (currentData as any)?.interfaceData || {};
                    onUpdate({
                      ...currentData,
                      interfaceData: {
                        ...currentInterfaceData,
                        cssTemplate: value,
                      },
                    });
                  }
                }}
                placeholder="Enter CSS styles (optional)..."
                readOnly={isReadOnly}
              />
            </div>
          </div>

          {/* JS Template Editor */}
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between flex-shrink-0">
              <span className="text-xs font-semibold text-slate-500 dark:text-slate-400">{t('jsTemplateTitle')}</span>
              <span className="text-xs text-slate-400 dark:text-slate-500">{t('optional')}</span>
            </div>
            <div className="w-full">
              <ExpressionEditor
                key={`js-editor-${isReadOnly ? 'readonly' : 'edit'}`}
                value={editedJsTemplate}
                onChange={(value) => {
                  if (isReadOnly) return;
                  setEditedJsTemplate(value);
                  if (node) {
                    const currentData = node.data;
                    const currentInterfaceData = (currentData as any)?.interfaceData || {};
                    onUpdate({
                      ...currentData,
                      interfaceData: {
                        ...currentInterfaceData,
                        jsTemplate: value,
                      },
                    });
                  }
                }}
                placeholder="Enter JavaScript code (optional)..."
                readOnly={isReadOnly}
              />
            </div>
          </div>
          </>)}
        </div>

        {/* ═══════ ZONE 2: Workflow Mapping (always editable, saved on node) ═══════ */}
        {hasInterfaceId && interfaceDetails?.templateVariables && interfaceDetails.templateVariables.length > 0 && (
          <>
            {/* Visual separator */}
            <div className="border-t border-dashed border-slate-300 dark:border-slate-600" />

            <div className="flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-1.5">
                  <button
                    type="button"
                    className="flex items-center gap-1.5 group"
                    onClick={() => setIsVariableMappingSectionOpen(!isVariableMappingSectionOpen)}
                  >
                    <ChevronRight className={`h-3.5 w-3.5 text-slate-400 dark:text-slate-500 transition-transform ${isVariableMappingSectionOpen ? 'rotate-90' : ''}`} />
                    <span className="text-sm font-semibold text-slate-500 dark:text-slate-400 group-hover:text-slate-600 dark:group-hover:text-slate-300">{t('variableMapping')}</span>
                  </button>
                  <Popover>
                    <PopoverTrigger asChild>
                      <button
                        type="button"
                        className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
                      >
                        <Info className="h-3 w-3 text-slate-400 dark:text-slate-500" />
                      </button>
                    </PopoverTrigger>
                    <PopoverContent className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl z-[99999]" side="right" align="start">
                      <div className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                        <p className="font-semibold text-slate-900 dark:text-slate-100">{t('variableMapping')}</p>
                        <p className="text-xs">{t('variableMappingInfoDesc')}</p>
                        <ul className="list-disc list-inside space-y-1 text-xs">
                          <li>{t('variableMappingInfoTemplate')}</li>
                          <li>{t('variableMappingInfoDrag')}</li>
                          <li>{t('variableMappingInfoExpression')}</li>
                        </ul>
                      </div>
                    </PopoverContent>
                  </Popover>
                </div>
                <span className="text-xs text-slate-400 dark:text-slate-500">{interfaceDetails.templateVariables.length > 1 ? t('variableCountPlural', { count: interfaceDetails.templateVariables.length }) : t('variableCount', { count: interfaceDetails.templateVariables.length })}</span>
              </div>
              {isVariableMappingSectionOpen && (<>
              <div className="flex flex-col gap-3">
                {interfaceDetails.templateVariables.map((varName: string) => (
                  <div key={varName} className="flex flex-col gap-1">
                    <span className="text-xs font-semibold text-slate-500 dark:text-slate-400">
                      <code className="font-mono">{`{{${varName}}}`}</code>
                    </span>
                    <div className="w-full">
                      <ExpressionEditor
                        key={`mapping-${varName}`}
                        value={editedVariableMapping[varName] || ''}
                        onChange={(value) => {
                          if (isRunMode) return;
                          const newMapping = { ...editedVariableMapping, [varName]: value };
                          setEditedVariableMapping(newMapping);
                          if (node) {
                            const currentData = node.data;
                            const currentInterfaceData = (currentData as any)?.interfaceData || {};
                            onUpdate({
                              ...currentData,
                              interfaceData: {
                                ...currentInterfaceData,
                                variableMapping: newMapping,
                              },
                            });
                          }
                        }}
                        onDrop={(e) => {
                          if (isRunMode) return;
                          e.preventDefault();
                          const text = e.dataTransfer.getData('text/plain');
                          if (!text) return;
                          const expr = text.trim();
                          if (expr) {
                            const newMapping = { ...editedVariableMapping, [varName]: expr };
                            setEditedVariableMapping(newMapping);
                            if (node) {
                              const currentData = node.data;
                              const currentInterfaceData = (currentData as any)?.interfaceData || {};
                              onUpdate({
                                ...currentData,
                                interfaceData: {
                                  ...currentInterfaceData,
                                  variableMapping: newMapping,
                                },
                              });
                            }
                          }
                        }}
                        placeholder={t('mappingPlaceholder')}
                        readOnly={isRunMode}
                      />
                    </div>
                  </div>
                ))}
              </div>
              <p className="text-[10px] text-slate-400 dark:text-slate-500 leading-tight">
                {t('mappingHelp')}
              </p>
              </>)}
            </div>
          </>
        )}

        {/* ═══════ Generic per-node execution policy (nodePolicy) ═══════
            Interface nodes are executable plan entries like any other -
            ParameterColumn renders this section for every other node type,
            but interface nodes use this dedicated column, so it is added
            here too (same gating, same persistence path). */}
        {node && nodeSupportsPolicy(node as unknown as Node<BuilderNodeData>) ? (
          <NodeSettingsSection
            node={node as unknown as Node<BuilderNodeData>}
            data={data}
            onUpdate={onUpdate}
            isRunMode={isRunMode}
          />
        ) : null}
      </div>
    </InspectorColumn>
  );
};


