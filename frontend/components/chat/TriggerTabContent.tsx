'use client';

import * as React from 'react';
import { CheckCircle2, AlertCircle, X } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Checkbox } from '@/components/ui/checkbox';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { MessageComposer } from '@/components/chat/MessageComposer';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { conversationApi, type Message } from '@/lib/api/conversationApi';
import { fileService, type PendingFileUpload } from '@/lib/api/orchestrator/file.service';
import type { TriggerPanelConfig } from '@/app/workflows/builder/components/TriggerPanel';

interface TriggerTabContentProps {
  config: TriggerPanelConfig;
  disabled: boolean;
  workflowId?: string;
  runId?: string;
  onExecuteTrigger?: (triggerId: string, triggerType: 'chat' | 'form' | 'webhook', payload: Record<string, any>) => Promise<string[] | undefined>;
  onTriggerSuccess?: (triggerId: string, readySteps?: string[]) => void;
  /** Shared conversation ID from useWorkflowChat (parent manages lifecycle) */
  conversationId?: string | null;
  /** Shared messages from useWorkflowChat (survives tab switch & refresh) */
  chatMessages?: Message[];
  /** Force-reload conversation messages (e.g., after trigger returns) */
  reloadConversation?: (force?: boolean) => Promise<void>;
}

export function TriggerTabContent({
  config,
  disabled,
  workflowId,
  runId,
  onExecuteTrigger,
  onTriggerSuccess,
  conversationId: sharedConversationId,
  chatMessages: sharedMessages,
  reloadConversation,
}: TriggerTabContentProps) {
  const t = useTranslations('triggerPanel');
  const [isSubmitting, setIsSubmitting] = React.useState(false);

  // Chat state
  const [chatMessage, setChatMessage] = React.useState('');
  const messagesEndRef = React.useRef<HTMLDivElement>(null);

  // Use shared conversation from parent (useWorkflowChat manages lifecycle & refresh)
  const chatMessages = sharedMessages ?? [];

  // Form state
  const [formData, setFormData] = React.useState<Record<string, any>>({});

  // File upload tracking
  const [pendingUploads, setPendingUploads] = React.useState<Map<string, PendingFileUpload>>(new Map());
  const hasActiveUploads = Array.from(pendingUploads.values()).some(u => u.status === 'uploading');

  // Initialize form data with default values
  React.useEffect(() => {
    if (config.type === 'form' && config.fields) {
      setFormData(prev => {
        // Only initialize if empty (preserve existing data)
        if (Object.keys(prev).length > 0) return prev;
        const initialData: Record<string, any> = {};
        config.fields!.forEach((field) => {
          if (field.type === 'checkbox') {
            initialData[field.name] = false;
          } else if (field.type === 'multiselect' || field.type === 'checkboxGroup') {
            initialData[field.name] = [];
          } else {
            initialData[field.name] = '';
          }
        });
        return initialData;
      });
    }
  }, [config]);

  // Auto-scroll to bottom when new messages arrive
  React.useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatMessages.length]);

  const isDisabled = disabled || isSubmitting || hasActiveUploads;

  const handleFileUpload = React.useCallback(async (fieldName: string, file: File) => {
    if (!workflowId || !runId) {
      console.error('workflowId and runId are required for file upload');
      return;
    }
    setPendingUploads(prev => {
      const next = new Map(prev);
      next.set(fieldName, { fieldName, file, status: 'uploading' });
      return next;
    });
    try {
      const fileRef = await fileService.uploadFile(file, { workflowId, runId, stepAlias: config.triggerId });
      setFormData(prev => ({ ...prev, [fieldName]: fileRef }));
      setPendingUploads(prev => {
        const next = new Map(prev);
        next.set(fieldName, { fieldName, file, status: 'success', fileRef });
        return next;
      });
    } catch (err) {
      setPendingUploads(prev => {
        const next = new Map(prev);
        next.set(fieldName, { fieldName, file, status: 'error', error: String(err) });
        return next;
      });
    }
  }, [workflowId, runId, config.triggerId]);

  const handleRemoveFile = React.useCallback((fieldName: string) => {
    setPendingUploads(prev => {
      const next = new Map(prev);
      next.delete(fieldName);
      return next;
    });
    setFormData(prev => ({ ...prev, [fieldName]: '' }));
  }, []);

  // No-op handlers for MessageComposer required props
  const handleKeyPress = React.useCallback(() => {}, []);
  const handleShowAttachmentMenu = React.useCallback(() => {}, []);

  // Chat attachments (uploaded via fileService to S3)
  const [chatAttachments, setChatAttachments] = React.useState<PendingFileUpload[]>([]);
  const hasChatUploading = chatAttachments.some(a => a.status === 'uploading');
  const chatFileInputRef = React.useRef<HTMLInputElement>(null);

  const handleChatFileSelect = React.useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !workflowId || !runId) return;
    // Reset input so the same file can be re-selected
    e.target.value = '';

    const upload: PendingFileUpload = { fieldName: file.name, file, status: 'uploading' };
    setChatAttachments(prev => [...prev, upload]);

    try {
      const fileRef = await fileService.uploadFile(file, { workflowId, runId, stepAlias: config.triggerId });
      setChatAttachments(prev => prev.map(a =>
        a.file === file ? { ...a, status: 'success', fileRef } : a
      ));
    } catch (err) {
      setChatAttachments(prev => prev.map(a =>
        a.file === file ? { ...a, status: 'error', error: String(err) } : a
      ));
    }
  }, [workflowId, runId, config.triggerId]);

  const handleRemoveChatAttachment = React.useCallback((index: number) => {
    setChatAttachments(prev => prev.filter((_, i) => i !== index));
  }, []);

  const handleChatSubmit = React.useCallback(async (content?: string) => {
    const messageToSend = content || chatMessage.trim();
    if (!messageToSend || isSubmitting || disabled) return;

    setIsSubmitting(true);
    try {
      // Use shared conversation from useWorkflowChat, or create if needed
      let convId = sharedConversationId;
      if (!convId && workflowId) {
        try {
          const conv = await conversationApi.createWorkflowConversation(workflowId);
          convId = conv.id;
        } catch (err) {
          console.error('Failed to create conversation:', err);
        }
      }

      // Save user message to conversation before firing trigger
      if (convId) {
        try {
          await conversationApi.addMessage(convId, {
            role: 'user',
            content: messageToSend,
          });
        } catch (err) {
          console.error('Failed to save user message:', err);
        }
      }

      // Include uploaded FileRefs as attachments in the payload
      const successfulAttachments = chatAttachments
        .filter(a => a.status === 'success' && a.fileRef)
        .map(a => a.fileRef!);

      const payload: Record<string, any> = { message: messageToSend };
      if (successfulAttachments.length > 0) {
        payload.attachments = successfulAttachments;
      }
      // Pass conversationId so backend can save response messages
      if (convId) {
        payload.conversationId = convId;
      }

      let readySteps: string[] | undefined;
      if (onExecuteTrigger) {
        readySteps = await onExecuteTrigger(config.triggerId, 'chat', payload);
      }

      // Reload messages via shared hook (survives tab switch & page refresh)
      if (reloadConversation) {
        await reloadConversation(true);
      }

      setChatMessage('');
      setChatAttachments([]);
      onTriggerSuccess?.(config.triggerId, readySteps);
    } catch (error) {
      console.error('Failed to trigger chat:', error);
    } finally {
      setIsSubmitting(false);
    }
  }, [chatMessage, chatAttachments, isSubmitting, disabled, workflowId, config.triggerId, sharedConversationId, onExecuteTrigger, onTriggerSuccess, reloadConversation]);

  const handleFormSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isSubmitting || disabled) return;

    setIsSubmitting(true);
    try {
      let readySteps: string[] | undefined;
      if (onExecuteTrigger) {
        readySteps = await onExecuteTrigger(config.triggerId, 'form', formData);
      }
      onTriggerSuccess?.(config.triggerId, readySteps);
    } catch (error) {
      console.error('Failed to trigger form:', error);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleFieldChange = (fieldName: string, value: any) => {
    setFormData(prev => ({
      ...prev,
      [fieldName]: value,
    }));
  };

  if (config.type === 'chat') {
    const canAttach = !!workflowId && !!runId;
    // Only show user and assistant messages with actual content
    const visibleMessages = chatMessages.filter(
      msg => (msg.role === 'user' || msg.role === 'assistant') && msg.content
    );
    return (
      <div className="flex-1 flex flex-col min-h-0">
        {/* Chat message history - fills available space and scrolls */}
        <div className="flex-1 overflow-y-auto min-h-0">
          {visibleMessages.length > 0 ? (
            <div className="mx-auto max-w-4xl w-full px-4 py-4 space-y-3">
              {visibleMessages.map((msg) => (
                <div
                  key={msg.id}
                  className={cn(
                    "flex",
                    msg.role === 'user' ? "justify-end" : "justify-start"
                  )}
                >
                  <div
                    className={cn(
                      "max-w-[80%] rounded-lg px-3 py-2 text-sm whitespace-pre-wrap",
                      msg.role === 'user'
                        ? "bg-slate-100 dark:bg-slate-800 text-slate-900 dark:text-slate-100"
                        : "text-slate-900 dark:text-slate-100"
                    )}
                  >
                    {msg.content}
                  </div>
                </div>
              ))}
              <div ref={messagesEndRef} />
            </div>
          ) : (
            <div className="flex-1" />
          )}
        </div>
        <div className="flex-shrink-0 mx-auto max-w-4xl w-full">
          {/* Chat attachment previews */}
          {chatAttachments.length > 0 && (
            <div className="px-4 pb-2 flex flex-wrap gap-2">
              {chatAttachments.map((attachment, index) => (
                <div key={index} className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs bg-slate-100 dark:bg-slate-800">
                  {attachment.status === 'uploading' && <LoadingSpinner size="xs" />}
                  {attachment.status === 'success' && <CheckCircle2 className="h-3 w-3 text-green-500" />}
                  {attachment.status === 'error' && <AlertCircle className="h-3 w-3 text-red-500" />}
                  <span className="max-w-[120px] truncate">{attachment.file.name}</span>
                  <button type="button" onClick={() => handleRemoveChatAttachment(index)} className="text-slate-400 hover:text-slate-600">
                    <X className="h-3 w-3" />
                  </button>
                </div>
              ))}
            </div>
          )}
          {/* Hidden file input for chat attachments */}
          <input
            ref={chatFileInputRef}
            type="file"
            className="hidden"
            onChange={handleChatFileSelect}
          />
          <MessageComposer
            inputValue={chatMessage}
            onInputChange={setChatMessage}
            onSendMessage={handleChatSubmit}
            onKeyPress={handleKeyPress}
            isStreaming={isSubmitting}
            onStopStream={() => {}}
            showAttachmentMenu={canAttach}
            onShowAttachmentMenu={(show) => {
              if (show && chatFileInputRef.current) {
                chatFileInputRef.current.click();
              }
            }}
            fullWidth={true}
            disabled={isDisabled || hasChatUploading}
          />
        </div>
      </div>
    );
  }

  // Form trigger
  return (
    <form onSubmit={handleFormSubmit} className="flex-1 flex flex-col min-h-0">
      <div className="flex-1 overflow-y-auto py-4">
        <div className="mx-auto max-w-4xl w-full px-4 space-y-4">
          {config.formDescription && (
            <p className="text-sm text-slate-500 dark:text-slate-400 mb-4">
              {config.formDescription}
            </p>
          )}

          {config.fields?.map((field) => (
            <div key={field.id} className="space-y-1.5">
              <Label htmlFor={field.id} className="text-sm font-medium">
                {field.label || field.name}
                {field.required && <span className="text-red-500 ml-1">*</span>}
              </Label>

              {field.type === 'textarea' ? (
                <Textarea
                  id={field.id}
                  value={formData[field.name] || ''}
                  onChange={(e) => handleFieldChange(field.name, e.target.value)}
                  placeholder={field.placeholder}
                  required={field.required}
                  disabled={isDisabled}
                  rows={3}
                />
              ) : field.type === 'select' ? (
                <Select
                  value={formData[field.name] || ''}
                  onValueChange={(value) => handleFieldChange(field.name, value)}
                  disabled={isDisabled}
                >
                  <SelectTrigger>
                    <SelectValue placeholder={field.placeholder || t('selectPlaceholder')} />
                  </SelectTrigger>
                  <SelectContent>
                    {field.options?.map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : field.type === 'multiselect' || field.type === 'checkboxGroup' ? (
                <div className="space-y-2">
                  {field.options?.map((option) => {
                    const selectedValues = formData[field.name] || [];
                    const isChecked = selectedValues.includes(option.value);
                    return (
                      <div key={option.value} className="flex items-center gap-2">
                        <Checkbox
                          id={`${field.id}-${option.value}`}
                          checked={isChecked}
                          onCheckedChange={(checked) => {
                            const newValues = checked
                              ? [...selectedValues, option.value]
                              : selectedValues.filter((v: string) => v !== option.value);
                            handleFieldChange(field.name, newValues);
                          }}
                          disabled={isDisabled}
                        />
                        <Label
                          htmlFor={`${field.id}-${option.value}`}
                          className="text-sm font-normal cursor-pointer"
                        >
                          {option.label}
                        </Label>
                      </div>
                    );
                  })}
                </div>
              ) : field.type === 'radio' ? (
                <RadioGroup
                  value={formData[field.name] || ''}
                  onValueChange={(value) => handleFieldChange(field.name, value)}
                  disabled={isDisabled}
                  className="space-y-2"
                >
                  {field.options?.map((option) => (
                    <div key={option.value} className="flex items-center gap-2">
                      <RadioGroupItem value={option.value} id={`${field.id}-${option.value}`} />
                      <Label
                        htmlFor={`${field.id}-${option.value}`}
                        className="text-sm font-normal cursor-pointer"
                      >
                        {option.label}
                      </Label>
                    </div>
                  ))}
                </RadioGroup>
              ) : field.type === 'checkbox' ? (
                <div className="flex items-center gap-2">
                  <Checkbox
                    id={field.id}
                    checked={formData[field.name] || false}
                    onCheckedChange={(checked) => handleFieldChange(field.name, checked)}
                    disabled={isDisabled}
                  />
                  <Label htmlFor={field.id} className="text-sm font-normal cursor-pointer">
                    {field.placeholder}
                  </Label>
                </div>
              ) : field.type === 'file' ? (
                <div className="space-y-2">
                  <Input
                    id={field.id}
                    type="file"
                    accept={field.accept}
                    onChange={(e) => {
                      const file = e.target.files?.[0];
                      if (file) handleFileUpload(field.name, file);
                    }}
                    required={field.required && !pendingUploads.has(field.name)}
                    disabled={isDisabled}
                  />
                  {pendingUploads.has(field.name) && (() => {
                    const upload = pendingUploads.get(field.name)!;
                    return (
                      <div className="flex items-center gap-2 text-xs">
                        {upload.status === 'uploading' && (
                          <>
                            <LoadingSpinner size="xs" />
                            <span className="text-blue-600 dark:text-blue-400">{t('fileUploading')}</span>
                          </>
                        )}
                        {upload.status === 'success' && (
                          <>
                            <CheckCircle2 className="h-3 w-3 text-green-500" />
                            <span className="text-green-600 dark:text-green-400">{upload.file.name} - {t('fileUploaded')}</span>
                            <button type="button" onClick={() => handleRemoveFile(field.name)} className="text-slate-400 hover:text-slate-600">
                              <X className="h-3 w-3" />
                            </button>
                          </>
                        )}
                        {upload.status === 'error' && (
                          <>
                            <AlertCircle className="h-3 w-3 text-red-500" />
                            <span className="text-red-600 dark:text-red-400">{t('fileUploadError')}</span>
                            <button type="button" onClick={() => handleRemoveFile(field.name)} className="text-slate-400 hover:text-slate-600">
                              <X className="h-3 w-3" />
                            </button>
                          </>
                        )}
                      </div>
                    );
                  })()}
                </div>
              ) : (
                <Input
                  id={field.id}
                  type={field.type || 'text'}
                  value={formData[field.name] || ''}
                  onChange={(e) => handleFieldChange(field.name, e.target.value)}
                  placeholder={field.placeholder}
                  required={field.required}
                  disabled={isDisabled}
                />
              )}
            </div>
          ))}
        </div>
      </div>

      <div className="flex-shrink-0 mx-auto max-w-4xl w-full px-4 pb-4">
        <Button
          type="submit"
          disabled={isDisabled}
          className="w-full"
        >
          {isSubmitting ? (
            <>
              <LoadingSpinner size="xs" className="mr-2" />
              {t('submitting')}
            </>
          ) : (
            config.submitButtonText || t('submit')
          )}
        </Button>
      </div>
    </form>
  );
}
