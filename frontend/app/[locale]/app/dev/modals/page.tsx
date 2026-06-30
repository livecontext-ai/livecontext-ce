'use client';

import { useState } from 'react';

// Real Modal Components - Chat
import { ConfirmDeleteModal } from '@/components/chat/ConfirmDeleteModal';
import { DeleteConversationModal } from '@/components/chat/DeleteConversationModal';
import { CreateAgentModal } from '@/components/chat/CreateAgentModal';
import { CreateWorkflowModal } from '@/components/chat/CreateWorkflowModal';
import { CreateDataSourceModal } from '@/components/chat/CreateDataSourceModal';
import { CreateInterfaceModal } from '@/components/chat/CreateInterfaceModal';
import { SearchConversationModal } from '@/components/chat/SearchConversationModal';

// Real Modal Components - Modals folder
import { UnsavedChangesModal } from '@/components/modals/UnsavedChangesModal';

// Real Modal Components - Billing
import BillingCycleChangeModal from '@/components/billing/BillingCycleChangeModal';
import DowngradeConfirmModal from '@/components/billing/DowngradeConfirmModal';

// Real Modal Components - Root
import CheckoutModal from '@/components/CheckoutModal';
import UpgradeModal from '@/components/UpgradeModal';
import UpgradeSuccessModal from '@/components/UpgradeSuccessModal';
import UpgradeExplainerModal from '@/components/UpgradeExplainerModal';

// Real Modal Components - Data Table
import { AddColumnModal } from '@/components/data-table/modals/AddColumnModal';
import { AddRowModal } from '@/components/data-table/modals/AddRowModal';
import { DeleteColumnsModal } from '@/components/data-table/modals/DeleteColumnsModal';
import { COLUMN_STYLE_PRESETS } from '@/components/data-table/visualHelpers';
import type { ColumnStylePreset } from '@/components/data-table/visualHelpers';
import type { ColumnDefinition } from '@/components/data-table/types';

// Real Modal Components - Datasource
import { ConfirmDeleteModal as DatasourceConfirmDeleteModal } from '@/components/datasource/ConfirmDeleteModal';

// Icons
import {
  Trash2,
  Plus,
  AlertTriangle,
  CreditCard,
  Database,
  Layout,
  Workflow,
  Bot,
  Table,
  Search,
  ChevronRight,
} from 'lucide-react';

/**
 * Modal Showcase Page - Complete Collection
 *
 * This page lists ALL modal components in the codebase:
 * - Radix Dialog (recommended)
 * - Portal-based modals (createPortal + custom div)
 * - Custom fixed positioned modals
 */

type ModalState = {
  [key: string]: boolean;
};

type ModalItem = {
  id: string;
  name: string;
  file: string;
  type: 'Portal' | 'Radix' | 'Custom';
  icon?: React.ComponentType<{ className?: string }>;
  description?: string;
};

type Category = {
  name: string;
  description: string;
  color: string;
  icon: React.ComponentType<{ className?: string }>;
  requiresApi?: boolean;
  requiresContext?: boolean;
  modals: ModalItem[];
};

export default function ModalShowcasePage() {
  const [openModals, setOpenModals] = useState<ModalState>({});
  const [expandedCategories, setExpandedCategories] = useState<Record<string, boolean>>({});

  // State for AddColumnModal
  const [columnName, setColumnName] = useState('');
  const [selectedStyle, setSelectedStyle] = useState<ColumnStylePreset>(COLUMN_STYLE_PRESETS[0]);

  // State for AddRowModal
  const [newRowPriority, setNewRowPriority] = useState(1);
  const [newRowData, setNewRowData] = useState<Record<string, string>>({ name: '', email: '' });

  const openModal = (id: string) => setOpenModals(prev => ({ ...prev, [id]: true }));
  const closeModal = (id: string) => setOpenModals(prev => ({ ...prev, [id]: false }));
  const toggleCategory = (name: string) => setExpandedCategories(prev => ({ ...prev, [name]: !prev[name] }));

  // ============================================
  // COMPLETE MODAL INVENTORY
  // ============================================

  const categories: Category[] = [
    // ---- CONFIRMATION / DELETE ----
    {
      name: 'Confirmation / Delete',
      description: 'Modales de suppression et confirmation',
      color: 'border-red-500/30 bg-red-500/5',
      icon: Trash2,
      modals: [
        { id: 'confirm-delete', name: 'ConfirmDeleteModal', file: 'components/chat/ConfirmDeleteModal.tsx', type: 'Portal' },
        { id: 'delete-conversation', name: 'DeleteConversationModal', file: 'components/chat/DeleteConversationModal.tsx', type: 'Portal' },
        { id: 'unsaved-changes', name: 'UnsavedChangesModal', file: 'components/modals/UnsavedChangesModal.tsx', type: 'Portal' },
        { id: 'confirm-delete-datasource', name: 'ConfirmDeleteModal (datasource)', file: 'components/datasource/ConfirmDeleteModal.tsx', type: 'Portal', description: 'Generic delete for datasource items' },
        { id: 'delete-columns', name: 'DeleteColumnsModal', file: 'components/data-table/modals/DeleteColumnsModal.tsx', type: 'Radix', description: 'Delete selected table columns' },
        { id: 'delete-tool', name: 'DeleteToolModal', file: 'app/[locale]/app/settings/developers/components/DeleteToolModal.tsx', type: 'Radix', description: 'Delete MCP tool confirmation' },
      ],
    },

    // ---- CREATION - RESOURCES ----
    {
      name: 'Creation - Resources',
      description: 'Modales de creation (font des appels API)',
      color: 'border-green-500/30 bg-green-500/5',
      icon: Plus,
      requiresApi: true,
      modals: [
        { id: 'create-agent', name: 'CreateAgentModal', file: 'components/chat/CreateAgentModal.tsx', type: 'Portal', icon: Bot },
        { id: 'create-workflow', name: 'CreateWorkflowModal', file: 'components/chat/CreateWorkflowModal.tsx', type: 'Portal', icon: Workflow },
        { id: 'create-datasource', name: 'CreateDataSourceModal', file: 'components/chat/CreateDataSourceModal.tsx', type: 'Portal', icon: Database },
        { id: 'create-interface', name: 'CreateInterfaceModal', file: 'components/chat/CreateInterfaceModal.tsx', type: 'Portal', icon: Layout },
        { id: 'create-datasource-table', name: 'CreateDataSourceModal (table)', file: 'components/data-table/modals/CreateDataSourceModal.tsx', type: 'Radix', icon: Table, description: 'Create datasource from table selection' },
      ],
    },

    // ---- DATA TABLE MODALS ----
    {
      name: 'Data Table',
      description: 'Modales pour les operations sur les tableaux',
      color: 'border-cyan-500/30 bg-cyan-500/5',
      icon: Table,
      requiresContext: true,
      modals: [
        { id: 'data-table-modals', name: 'DataTableModals', file: 'components/data-table/DataTableModals.tsx', type: 'Radix', description: 'Container managing all table modals' },
        { id: 'add-column', name: 'AddColumnModal', file: 'components/data-table/modals/AddColumnModal.tsx', type: 'Radix', description: 'Add new column with style preset' },
        { id: 'add-row', name: 'AddRowModal', file: 'components/data-table/modals/AddRowModal.tsx', type: 'Radix', description: 'Add new row with values' },
        { id: 'form-modal', name: 'FormModal', file: 'components/datasource/FormModal.tsx', type: 'Radix', description: 'Reusable form modal with dynamic fields' },
      ],
    },

    // ---- BILLING / PAYMENT ----
    {
      name: 'Billing / Payment',
      description: 'Modales liees aux paiements et abonnements',
      color: 'border-blue-500/30 bg-blue-500/5',
      icon: CreditCard,
      modals: [
        { id: 'billing-cycle', name: 'BillingCycleChangeModal', file: 'components/billing/BillingCycleChangeModal.tsx', type: 'Custom' },
        { id: 'downgrade-confirm', name: 'DowngradeConfirmModal', file: 'components/billing/DowngradeConfirmModal.tsx', type: 'Custom' },
        { id: 'upgrade-confirm', name: 'UpgradeModal (confirm)', file: 'components/UpgradeModal.tsx', type: 'Custom' },
        { id: 'upgrade-processing', name: 'UpgradeModal (processing)', file: 'components/UpgradeModal.tsx', type: 'Custom' },
        { id: 'upgrade-success', name: 'UpgradeModal (success)', file: 'components/UpgradeModal.tsx', type: 'Custom' },
        { id: 'upgrade-error', name: 'UpgradeModal (error)', file: 'components/UpgradeModal.tsx', type: 'Custom' },
        { id: 'enterprise-pricing', name: 'EnterprisePricingModal', file: 'components/EnterprisePricingModal.tsx', type: 'Custom', description: 'Enterprise plan selection' },
        { id: 'upgrade-explainer', name: 'UpgradeExplainerModal', file: 'components/UpgradeExplainerModal.tsx', type: 'Custom', description: 'Prorated amount explanation' },
        { id: 'upgrade-success-modal', name: 'UpgradeSuccessModal', file: 'components/UpgradeSuccessModal.tsx', type: 'Custom', description: 'Upgrade confirmation' },
      ],
    },

    // ---- CHECKOUT STATUS ----
    {
      name: 'Checkout Status',
      description: 'Etats de checkout apres paiement',
      color: 'border-purple-500/30 bg-purple-500/5',
      icon: CreditCard,
      modals: [
        { id: 'checkout-success', name: 'CheckoutModal (success)', file: 'components/CheckoutModal.tsx', type: 'Custom' },
        { id: 'checkout-processing', name: 'CheckoutModal (processing)', file: 'components/CheckoutModal.tsx', type: 'Custom' },
        { id: 'checkout-timeout', name: 'CheckoutModal (timeout)', file: 'components/CheckoutModal.tsx', type: 'Custom' },
        { id: 'checkout-error', name: 'CheckoutModal (error)', file: 'components/CheckoutModal.tsx', type: 'Custom' },
      ],
    },

    // ---- SEARCH ----
    {
      name: 'Search',
      description: 'Modales de recherche',
      color: 'border-teal-500/30 bg-teal-500/5',
      icon: Search,
      requiresContext: true,
      modals: [
        { id: 'search-conversation', name: 'SearchConversationModal', file: 'components/chat/SearchConversationModal.tsx', type: 'Portal', description: 'Search conversations by title/content' },
      ],
    },

  ];

  // Count stats
  const totalModals = categories.reduce((acc, cat) => acc + cat.modals.length, 0);
  const typeCount = (type: string) => categories.reduce((acc, cat) => acc + cat.modals.filter(m => m.type === type).length, 0);

  return (
    <div className="h-screen flex flex-col bg-background">
      <div className="flex-1 overflow-y-auto p-8">
        <div className="max-w-7xl mx-auto">
          {/* Header */}
          <div className="mb-8">
            <h1 className="text-3xl font-bold mb-2">Modal Showcase - Complete Inventory</h1>
            <p className="text-muted-foreground">
              Inventaire complet de tous les composants modaux du projet (Radix, Portal, Custom).
            </p>
          </div>

          {/* Stats */}
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
            <div className="bg-card border rounded-lg p-4">
              <div className="text-2xl font-bold">{totalModals}</div>
              <div className="text-sm text-muted-foreground">Total</div>
            </div>
            <div className="bg-card border rounded-lg p-4">
              <div className="text-2xl font-bold text-blue-500">{typeCount('Radix')}</div>
              <div className="text-sm text-muted-foreground">Radix Dialog</div>
            </div>
            <div className="bg-card border rounded-lg p-4">
              <div className="text-2xl font-bold text-purple-500">{typeCount('Portal')}</div>
              <div className="text-sm text-muted-foreground">Portal</div>
            </div>
            <div className="bg-card border rounded-lg p-4">
              <div className="text-2xl font-bold text-orange-500">{typeCount('Custom')}</div>
              <div className="text-sm text-muted-foreground">Custom Fixed</div>
            </div>
          </div>

          {/* Legend */}
          <div className="bg-card border rounded-lg p-4 mb-6">
            <h3 className="font-semibold mb-3">Types d&apos;implementation</h3>
            <div className="flex flex-wrap gap-4 text-sm">
              <div className="flex items-center gap-2">
                <span className="px-2 py-0.5 rounded bg-blue-500/20 text-blue-700 dark:text-blue-300 text-xs font-medium">Radix</span>
                <span>@radix-ui/dialog (recommande)</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="px-2 py-0.5 rounded bg-purple-500/20 text-purple-700 dark:text-purple-300 text-xs font-medium">Portal</span>
                <span>createPortal + custom div</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="px-2 py-0.5 rounded bg-orange-500/20 text-orange-700 dark:text-orange-300 text-xs font-medium">Custom</span>
                <span>Position fixed manuelle</span>
              </div>
            </div>
          </div>

          {/* Info */}
          <div className="bg-amber-500/10 border border-amber-500/30 rounded-lg p-4 mb-6">
            <div className="flex items-start gap-3">
              <AlertTriangle className="h-5 w-5 text-amber-500 flex-shrink-0 mt-0.5" />
              <div>
                <h4 className="font-medium text-amber-700 dark:text-amber-300">Note</h4>
                <p className="text-sm text-amber-600 dark:text-amber-400">
                  Certains modals font des appels API reels (Create*). {totalModals} modals au total.
                </p>
              </div>
            </div>
          </div>

          {/* Modal Categories */}
          <div className="space-y-4">
            {categories.map((category) => {
              const IconComponent = category.icon;
              const isExpanded = expandedCategories[category.name] ?? true;

              return (
                <div key={category.name} className={`border rounded-lg ${category.color}`}>
                  <button
                    onClick={() => toggleCategory(category.name)}
                    className="w-full p-4 flex items-center justify-between text-left"
                  >
                    <div className="flex items-center gap-2">
                      <IconComponent className="h-5 w-5" />
                      <h2 className="text-lg font-semibold">{category.name}</h2>
                      <span className="text-sm text-muted-foreground">({category.modals.length})</span>
                      {category.requiresApi && (
                        <span className="px-2 py-0.5 rounded bg-amber-500/20 text-amber-700 dark:text-amber-300 text-xs">API</span>
                      )}
                      {category.requiresContext && (
                        <span className="px-2 py-0.5 rounded bg-slate-500/20 text-slate-700 dark:text-slate-300 text-xs">Context</span>
                      )}
                    </div>
                    <ChevronRight className={`h-5 w-5 transition-transform ${isExpanded ? 'rotate-90' : ''}`} />
                  </button>

                  {isExpanded && (
                    <div className="px-4 pb-4">
                      <p className="text-sm text-muted-foreground mb-4">{category.description}</p>
                      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-3">
                        {category.modals.map((modal) => {
                          return (
                            <button
                              key={modal.id}
                              onClick={() => openModal(modal.id)}
                              className="flex flex-col items-start p-3 bg-background border rounded-lg transition-colors text-left hover:border-primary cursor-pointer"
                            >
                              <div className="flex items-center gap-2 w-full">
                                <span className="font-medium text-sm flex-1 truncate">{modal.name}</span>
                                <span className={`px-1.5 py-0.5 rounded text-xs font-medium flex-shrink-0 ${
                                  modal.type === 'Portal' ? 'bg-purple-500/20 text-purple-700 dark:text-purple-300' :
                                  modal.type === 'Radix' ? 'bg-blue-500/20 text-blue-700 dark:text-blue-300' :
                                  'bg-orange-500/20 text-orange-700 dark:text-orange-300'
                                }`}>
                                  {modal.type}
                                </span>
                              </div>
                              <span className="text-xs text-muted-foreground truncate w-full mt-1">{modal.file}</span>
                              {modal.description && (
                                <span className="text-xs text-muted-foreground/70 mt-1 line-clamp-2">{modal.description}</span>
                              )}
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          {/* ============================================ */}
          {/* REAL MODAL COMPONENTS */}
          {/* ============================================ */}

          {/* === CONFIRMATION MODALS === */}
          <ConfirmDeleteModal
            isOpen={openModals['confirm-delete'] || false}
            title="Delete Item"
            message="Are you sure you want to delete this item? This action cannot be undone."
            onConfirm={() => closeModal('confirm-delete')}
            onCancel={() => closeModal('confirm-delete')}
          />

          <DeleteConversationModal
            isOpen={openModals['delete-conversation'] || false}
            onClose={() => closeModal('delete-conversation')}
            onConfirm={() => closeModal('delete-conversation')}
            conversationTitle="Test Conversation"
          />

          <UnsavedChangesModal
            isOpen={openModals['unsaved-changes'] || false}
            onSave={async () => closeModal('unsaved-changes')}
            onDiscard={() => closeModal('unsaved-changes')}
            onCancel={() => closeModal('unsaved-changes')}
          />

          {/* === CREATION MODALS (API) === */}
          {openModals['create-agent'] && (
            <CreateAgentModal
              onClose={() => closeModal('create-agent')}
              onAgentCreated={() => closeModal('create-agent')}
            />
          )}

          {openModals['create-workflow'] && (
            <CreateWorkflowModal
              onClose={() => closeModal('create-workflow')}
              onWorkflowCreated={() => closeModal('create-workflow')}
            />
          )}

          {openModals['create-datasource'] && (
            <CreateDataSourceModal
              onClose={() => closeModal('create-datasource')}
              onDataSourceCreated={() => closeModal('create-datasource')}
            />
          )}

          {openModals['create-interface'] && (
            <CreateInterfaceModal
              onClose={() => closeModal('create-interface')}
              onInterfaceCreated={() => closeModal('create-interface')}
            />
          )}

          {/* === BILLING MODALS === */}
          <BillingCycleChangeModal
            isOpen={openModals['billing-cycle'] || false}
            onClose={() => closeModal('billing-cycle')}
            currentCycle="monthly"
            planName="Pro"
            monthlyPrice={29}
            yearlyPrice={290}
            currentPeriodEnd="2024-03-15"
          />

          <DowngradeConfirmModal
            isOpen={openModals['downgrade-confirm'] || false}
            onClose={() => closeModal('downgrade-confirm')}
            currentPlan={{ code: 'PRO', name: 'Pro', price: 29 }}
            targetPlan={{ code: 'STARTER', name: 'Starter', price: 9 }}
            currentPeriodEnd="2024-03-15"
          />

          {/* === UPGRADE MODALS (different states) === */}
          <UpgradeModal
            open={openModals['upgrade-confirm'] || false}
            state="confirm"
            currentPlan="STARTER"
            targetPlan="PRO"
            onConfirm={() => {}}
            onClose={() => closeModal('upgrade-confirm')}
          />

          <UpgradeModal
            open={openModals['upgrade-processing'] || false}
            state="processing"
            currentPlan="STARTER"
            targetPlan="PRO"
            onConfirm={() => {}}
            onClose={() => closeModal('upgrade-processing')}
          />

          <UpgradeModal
            open={openModals['upgrade-success'] || false}
            state="success"
            currentPlan="STARTER"
            targetPlan="PRO"
            onConfirm={() => {}}
            onClose={() => closeModal('upgrade-success')}
          />

          <UpgradeModal
            open={openModals['upgrade-error'] || false}
            state="error"
            currentPlan="STARTER"
            targetPlan="PRO"
            onConfirm={() => {}}
            onClose={() => closeModal('upgrade-error')}
            errorMessage="Payment failed. Please check your card details."
          />

          {/* === CHECKOUT MODALS === */}
          <CheckoutModal
            open={openModals['checkout-success'] || false}
            type="success"
            onClose={() => closeModal('checkout-success')}
            planName="Pro"
            subscriptionDetails={{ status: 'active', currentPeriodEnd: '2024-04-15' }}
          />

          <CheckoutModal
            open={openModals['checkout-processing'] || false}
            type="processing"
            onClose={() => closeModal('checkout-processing')}
            planName="Pro"
            pollingAttempts={5}
          />

          <CheckoutModal
            open={openModals['checkout-timeout'] || false}
            type="timeout"
            onClose={() => closeModal('checkout-timeout')}
            onRetry={() => {}}
            planName="Pro"
            showRetryButton={true}
          />

          <CheckoutModal
            open={openModals['checkout-error'] || false}
            type="error"
            onClose={() => closeModal('checkout-error')}
            onRetry={() => {}}
            planName="Pro"
            message="Payment failed."
            showRetryButton={true}
          />

          {/* === ADDITIONAL BILLING MODALS === */}
          <UpgradeSuccessModal
            open={openModals['upgrade-success-modal'] || false}
            currentPlan="STARTER"
            newPlan="PRO"
            onClose={() => closeModal('upgrade-success-modal')}
            disableRedirects={true}
          />

          <UpgradeExplainerModal
            open={openModals['upgrade-explainer'] || false}
            currentPlan="STARTER"
            targetPlan="PRO"
            billingCycle="monthly"
            onConfirm={() => closeModal('upgrade-explainer')}
            onCancel={() => closeModal('upgrade-explainer')}
          />

          {/* === DATA TABLE MODALS === */}
          <AddColumnModal
            isOpen={openModals['add-column'] || false}
            isAdding={false}
            columnName={columnName}
            selectedStyle={selectedStyle}
            onClose={() => closeModal('add-column')}
            onAdd={() => closeModal('add-column')}
            onColumnNameChange={setColumnName}
            onStyleChange={setSelectedStyle}
          />

          <AddRowModal
            isOpen={openModals['add-row'] || false}
            isAdding={false}
            newRowPriority={newRowPriority}
            newRowData={newRowData}
            dynamicColumns={[
              { col_id: '1', header_name: 'Name', field: 'data.name', type: 'text', editable: true, sortable: true, filterable: true },
              { col_id: '2', header_name: 'Email', field: 'data.email', type: 'text', editable: true, sortable: true, filterable: true },
            ] as ColumnDefinition[]}
            onClose={() => closeModal('add-row')}
            onAdd={() => closeModal('add-row')}
            onPriorityChange={setNewRowPriority}
            onRowDataChange={(field, value) => setNewRowData(prev => ({ ...prev, [field]: value }))}
          />

          <DeleteColumnsModal
            isOpen={openModals['delete-columns'] || false}
            columnsToDelete={['Column A', 'Column B', 'Column C']}
            onClose={() => closeModal('delete-columns')}
            onConfirm={() => closeModal('delete-columns')}
          />

          {/* === DATASOURCE MODALS === */}
          <DatasourceConfirmDeleteModal
            isOpen={openModals['confirm-delete-datasource'] || false}
            onClose={() => closeModal('confirm-delete-datasource')}
            onConfirm={() => closeModal('confirm-delete-datasource')}
            title="Delete Items"
            itemCount={5}
            itemType="rows"
          />

          {/* === SEARCH MODAL === */}
          <SearchConversationModal
            isOpen={openModals['search-conversation'] || false}
            onClose={() => closeModal('search-conversation')}
            onConversationSelect={() => closeModal('search-conversation')}
          />

          {/* ============================================ */}
          {/* STYLE ANALYSIS */}
          {/* ============================================ */}

          <div className="mt-12 bg-card border rounded-lg p-6">
            <h2 className="text-xl font-semibold mb-4">Analyse des incoherences</h2>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              {/* Overlay */}
              <div className="space-y-2">
                <h3 className="font-medium">Overlay / Backdrop</h3>
                <div className="text-sm space-y-1 text-muted-foreground">
                  <div className="flex justify-between"><span>bg-black/50</span><span className="text-red-500">ConfirmDelete, CreateAgent...</span></div>
                  <div className="flex justify-between"><span>bg-black/60 backdrop-blur-sm</span><span className="text-green-500">Billing, Checkout...</span></div>
                  <div className="flex justify-between"><span>bg-black/20 backdrop-blur-sm</span><span className="text-red-500">CreateDataSource</span></div>
                  <div className="flex justify-between"><span>bg-black/70 ou bg-white/90</span><span className="text-red-500">AuthLoading</span></div>
                </div>
              </div>

              {/* Container */}
              <div className="space-y-2">
                <h3 className="font-medium">Container Shape</h3>
                <div className="text-sm space-y-1 text-muted-foreground">
                  <div className="flex justify-between"><span>rounded-2xl</span><span className="text-red-500">ConfirmDelete, Delete...</span></div>
                  <div className="flex justify-between"><span>rounded-xl</span><span className="text-green-500">Billing, Checkout, Upgrade</span></div>
                  <div className="flex justify-between"><span>rounded-lg</span><span className="text-red-500">CreateAgent, CreateWorkflow</span></div>
                  <div className="flex justify-between"><span>rounded-3xl</span><span className="text-red-500">CreateDataSource</span></div>
                </div>
              </div>

              {/* Icon Size */}
              <div className="space-y-2">
                <h3 className="font-medium">Icon Container</h3>
                <div className="text-sm space-y-1 text-muted-foreground">
                  <div className="flex justify-between"><span>w-12 h-12 rounded-xl</span><span className="text-red-500">ConfirmDelete</span></div>
                  <div className="flex justify-between"><span>w-16 h-16 rounded-full</span><span className="text-green-500">Checkout, Upgrade, Unsaved</span></div>
                  <div className="flex justify-between"><span>p-2 rounded-full</span><span className="text-red-500">BillingCycle, Downgrade</span></div>
                </div>
              </div>

              {/* Z-index */}
              <div className="space-y-2">
                <h3 className="font-medium">Z-index</h3>
                <div className="text-sm space-y-1 text-muted-foreground">
                  <div className="flex justify-between"><span>z-50</span><span className="text-green-500">Radix default, Billing</span></div>
                  <div className="flex justify-between"><span>z-[9999]</span><span className="text-red-500">ConfirmDelete, Create*</span></div>
                  <div className="flex justify-between"><span>z-[99999]</span><span className="text-red-500">HoverPopover</span></div>
                </div>
              </div>
            </div>
          </div>

          {/* Checklist */}
          <div className="mt-8 bg-card border rounded-lg p-6">
            <h2 className="text-xl font-semibold mb-4">Checklist d&apos;harmonisation</h2>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {[
                'Migrer vers Radix Dialog',
                'Overlay: bg-black/60 backdrop-blur-sm',
                'Container: rounded-xl shadow-2xl',
                'Z-index: z-50 (standard)',
                'Icon: w-16 h-16 rounded-full',
                'Header: icon + title + close button',
                'Footer: Cancel (outline) | Primary',
                'Couleurs: red=danger, amber=warning, green=success',
                'Animation: fade-in zoom-in-95',
                'Support i18n complet',
              ].map((item, i) => (
                <label key={i} className="flex items-center gap-3 cursor-pointer text-sm">
                  <input type="checkbox" className="rounded" />
                  <span>{item}</span>
                </label>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
