'use client';

import React, { useCallback } from 'react';
import { X, Minus } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import {
  openFleetSidePanelTab,
  FLEET_SIDE_PANEL_ICONS,
  type FleetSidePanelAction,
} from './fleetSidePanelActions';

interface FleetInspectorActionButtonsProps {
  onClose: () => void;
  onMinimize: () => void;
  sidePanelAction?: FleetSidePanelAction | null;
}

const actionButtonClass =
  'h-8 w-8 p-0 rounded-full bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] shadow-none';

/**
 * Outside action buttons for the FleetInspectorPanel.
 * Positioned absolutely to the right of the panel (top-0 -right-10),
 * matching the InspectorActionButtons pattern.
 */
export function FleetInspectorActionButtons({
  onClose,
  onMinimize,
  sidePanelAction,
}: FleetInspectorActionButtonsProps) {
  const t = useTranslations('fleetInspector');
  const sidePanel = useSidePanelSafe();

  const handleOpenInPanel = useCallback(() => {
    if (!sidePanel || !sidePanelAction) return;
    openFleetSidePanelTab(sidePanel, sidePanelAction);
  }, [sidePanel, sidePanelAction]);

  const IconComponent = sidePanelAction
    ? FLEET_SIDE_PANEL_ICONS[sidePanelAction.type]
    : null;

  return (
    <div className="absolute z-[10000] hidden lg:flex flex-col items-center gap-2 rounded-full p-0 pointer-events-auto top-0 -right-10">
      {/* Open in side panel - conditional */}
      {sidePanelAction && sidePanel && IconComponent && (
        <Button
          variant="ghost"
          size="icon"
          className={actionButtonClass}
          onClick={handleOpenInPanel}
          title={t('openInPanel')}
        >
          <IconComponent className="h-3.5 w-3.5" />
        </Button>
      )}

      {/* Minimize */}
      <Button
        variant="ghost"
        size="icon"
        className={actionButtonClass}
        onClick={onMinimize}
        title={t('collapse')}
      >
        <Minus className="h-3.5 w-3.5" />
      </Button>

      {/* Close */}
      <Button
        variant="ghost"
        size="icon"
        className={actionButtonClass}
        onClick={onClose}
        title={t('close')}
      >
        <X className="h-3.5 w-3.5" />
      </Button>
    </div>
  );
}
