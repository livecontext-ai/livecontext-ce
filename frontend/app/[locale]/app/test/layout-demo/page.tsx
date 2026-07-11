'use client';

import { useSidePanel } from '@/contexts/SidePanelContext';
import { Button } from '@/components/ui/button';
import { Settings, X, FileText, Wrench } from 'lucide-react';

/**
 * Test Page - Demonstrates the unified SidePanel system
 *
 * Features:
 * - SidePanelProvider wraps entire /app layout
 * - useSidePanel() hook to manage tabs + open/close
 * - Resizable, lazy-rendered panel as flex sibling
 */
export default function TestLayoutPage() {
    return <TestContent />;
}

function TestContent() {
    const { openTab, close, isOpen, activeTabId } = useSidePanel();

    const openAgentConfig = () => {
        openTab({
            id: 'agent-config-demo',
            label: 'Agent',
            icon: <Settings className="w-4 h-4" />,
            content: <AgentConfigExample />,
        });
    };

    const openToolsPanel = () => {
        openTab({
            id: 'tools-demo',
            label: 'Tools',
            icon: <Wrench className="w-4 h-4" />,
            content: <ToolsPanelExample />,
        });
    };

    const openDocumentation = () => {
        openTab({
            id: 'docs-demo',
            label: 'Docs',
            icon: <FileText className="w-4 h-4" />,
            content: <DocumentationExample />,
        });
    };

    return (
        <div className="p-8 space-y-8 overflow-y-auto h-full">
            <div>
                <h1 className="text-2xl font-bold text-theme-primary mb-2">
                    Test: Unified SidePanel System
                </h1>
                <p className="text-theme-secondary">
                    This page demonstrates the SidePanel managed via SidePanelContext.
                </p>
                <p className="text-theme-secondary text-sm mt-1">
                    Panel is resizable, tab-based, and lazy-rendered.
                </p>
            </div>

            {/* Status */}
            <div className="p-4 rounded-lg bg-theme-secondary">
                <h2 className="font-semibold mb-2">Status</h2>
                <div className="flex items-center gap-2">
                    <span className={`w-3 h-3 rounded-full ${isOpen ? 'bg-green-500' : 'bg-gray-400'}`} />
                    <span>Side Panel: {isOpen ? 'Open' : 'Closed'}</span>
                    {activeTabId && (
                        <span className="text-theme-secondary">
                            - Active tab: {activeTabId}
                        </span>
                    )}
                </div>
            </div>

            {/* Actions */}
            <div className="space-y-4">
                <h2 className="font-semibold text-lg">Open Different Tabs</h2>

                <div className="flex flex-wrap gap-4">
                    <Button onClick={openAgentConfig} className="flex items-center gap-2">
                        <Settings className="w-4 h-4" />
                        Agent Config
                    </Button>

                    <Button onClick={openToolsPanel} variant="outline" className="flex items-center gap-2">
                        <Wrench className="w-4 h-4" />
                        Tools
                    </Button>

                    <Button onClick={openDocumentation} variant="outline" className="flex items-center gap-2">
                        <FileText className="w-4 h-4" />
                        Documentation
                    </Button>

                    <Button onClick={close} variant="ghost" className="flex items-center gap-2">
                        <X className="w-4 h-4" />
                        Close Panel
                    </Button>
                </div>
            </div>

            {/* Code Example */}
            <div className="space-y-4">
                <h2 className="font-semibold text-lg">Code Example</h2>
                <pre className="p-4 rounded-lg bg-gray-900 text-gray-100 text-sm overflow-x-auto">
                    {`// In any page under /app
import { useSidePanel } from '@/contexts/SidePanelContext';

function MyComponent() {
  const { openTab, close, isOpen, activeTabId } = useSidePanel();

  // Open a tab with content
  openTab({
    id: 'my-tab',
    label: 'My Tab',
    icon: <Settings className="w-4 h-4" />,
    content: <MyCustomContent />,
  });

  // Close the panel
  close();
}`}
                </pre>
            </div>
        </div>
    );
}

// Example panel contents
function AgentConfigExample() {
    return (
        <div className="p-4 space-y-4">
            <p className="text-sm text-theme-secondary">
                This would contain the agent configuration form.
            </p>
            <div className="space-y-3">
                <div>
                    <label className="block text-sm font-medium mb-1">Agent Name</label>
                    <input
                        type="text"
                        placeholder="My Agent"
                        className="w-full h-9 px-2 text-sm border rounded-lg bg-theme-primary text-theme-primary"
                    />
                </div>
                <div>
                    <label className="block text-sm font-medium mb-1">System Prompt</label>
                    <textarea
                        placeholder="You are a helpful assistant..."
                        className="w-full p-2 border rounded-lg bg-theme-primary text-theme-primary h-32"
                    />
                </div>
            </div>
        </div>
    );
}

function ToolsPanelExample() {
    const tools = ['File Manager', 'Code Editor', 'Terminal', 'Web Browser', 'Image Generator'];

    return (
        <div className="p-4 space-y-4">
            <p className="text-sm text-theme-secondary">
                Select tools to enable for this conversation.
            </p>
            <div className="space-y-2">
                {tools.map(tool => (
                    <label key={tool} className="flex items-center gap-2 p-2 rounded-lg hover:bg-theme-secondary cursor-pointer">
                        <input type="checkbox" className="rounded" />
                        <span>{tool}</span>
                    </label>
                ))}
            </div>
        </div>
    );
}

function DocumentationExample() {
    return (
        <div className="p-4 space-y-4">
            <h3 className="font-semibold">SidePanelContext Documentation</h3>
            <div className="prose prose-sm text-theme-secondary">
                <p>
                    The SidePanelContext provides a unified way to manage the right side panel
                    across all pages in the /app section.
                </p>
                <h4 className="font-medium mt-4">Available Methods:</h4>
                <ul className="list-disc pl-4 space-y-1">
                    <li><code>openTab(tab)</code> - Add/update + activate + open</li>
                    <li><code>close()</code> - Close the panel</li>
                    <li><code>toggle()</code> - Toggle open/closed</li>
                    <li><code>addTab(tab)</code> - Add without opening</li>
                    <li><code>removeTab(tabId)</code> - Remove a tab</li>
                </ul>
                <h4 className="font-medium mt-4">Panel Specs:</h4>
                <ul className="list-disc pl-4 space-y-1">
                    <li>Width: Resizable (default 400px)</li>
                    <li>Rendering: Lazy (only mounts when first opened)</li>
                    <li>Position: Flex sibling (pushes content)</li>
                </ul>
            </div>
        </div>
    );
}
