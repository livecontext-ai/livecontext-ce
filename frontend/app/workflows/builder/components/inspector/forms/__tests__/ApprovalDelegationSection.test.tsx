// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

// Translation stub - surfaces the key as the value so we can assert labels.
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// ExpressionEditor pulls in heavy editor + portal logic. Stub it as a textarea so
// we can assert the template-capable fields route through the expression editor
// (not a plain Input/Textarea) and that isRequired flows to the right field.
vi.mock('@/components/ui/expression-editor', () => ({
  ExpressionEditor: (props: any) => (
    <textarea
      data-testid="expr-editor"
      data-required={props.isRequired ? 'true' : 'false'}
      placeholder={props.placeholder}
      value={props.value ?? ''}
      onChange={(e) => props.onChange(e.target.value)}
      readOnly={props.readOnly}
    />
  ),
}));

// CredentialSection pulls in apiClient. Stub with buttons that fire
// onCredentialSelect so we can assert the Number() coercion in the handler.
vi.mock('../../CredentialSection', () => ({
  CredentialSection: (props: any) => (
    <div
      data-testid="credential-section"
      data-required={props.toolCredentials?.[0]?.isRequired ? 'true' : 'false'}
    >
      <button type="button" data-testid="pick-numeric-string" onClick={() => props.onCredentialSelect('40')}>
        pick-string
      </button>
      <button type="button" data-testid="pick-number" onClick={() => props.onCredentialSelect(7)}>
        pick-number
      </button>
      <button type="button" data-testid="pick-null" onClick={() => props.onCredentialSelect(null)}>
        pick-null
      </button>
    </div>
  ),
}));

// Radix primitives need jsdom hacks. Thin stubs preserving the controlled contract.
vi.mock('@/components/ui/switch', () => ({
  Switch: ({ checked, onCheckedChange, disabled, ...rest }: any) => (
    <input
      type="checkbox"
      data-testid="delegation-toggle"
      checked={checked}
      disabled={disabled}
      onChange={(e) => onCheckedChange?.(e.target.checked)}
      {...rest}
    />
  ),
}));
vi.mock('@/components/ui/popover', () => ({
  Popover: ({ children }: any) => <div>{children}</div>,
  PopoverTrigger: ({ children }: any) => <div>{children}</div>,
  PopoverContent: ({ children }: any) => <div>{children}</div>,
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: any) => <div data-testid="channel-select">{children}</div>,
  SelectContent: ({ children }: any) => <div>{children}</div>,
  SelectItem: ({ children }: any) => <div>{children}</div>,
  SelectTrigger: ({ children }: any) => <div>{children}</div>,
  SelectValue: () => null,
}));

import { ApprovalDelegationSection } from '../ApprovalDelegationSection';

const ENABLED_DELEGATION = { channel: 'telegram' as const, credentialId: 1, chatId: '-100123' };

function renderSection(overrides: Partial<React.ComponentProps<typeof ApprovalDelegationSection>> = {}) {
  const handleDelegationChange = vi.fn();
  render(
    <ApprovalDelegationSection
      approvalDelegation={ENABLED_DELEGATION}
      handleDelegationChange={handleDelegationChange}
      {...overrides}
    />
  );
  return { handleDelegationChange };
}

afterEach(cleanup);

describe('ApprovalDelegationSection - expression editor for template-capable fields', () => {
  it('renders chatId, messageTemplate, image, approveLabel and rejectLabel through the ExpressionEditor (5 editors), not plain inputs', () => {
    renderSection();
    const editors = screen.getAllByTestId('expr-editor');
    expect(editors).toHaveLength(5);
  });

  it('propagates approveLabel and rejectLabel edits from their expression editors to node data', () => {
    const { handleDelegationChange } = renderSection();
    const [, , , approveLabel, rejectLabel] = screen.getAllByTestId('expr-editor');
    fireEvent.change(approveLabel, { target: { value: '👍 Ship it' } });
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ approveLabel: '👍 Ship it' })
    );
    fireEvent.change(rejectLabel, { target: { value: '👎 Hold' } });
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ rejectLabel: '👎 Hold' })
    );
  });

  it('marks the chatId editor required and the messageTemplate/image editors optional', () => {
    renderSection();
    const [chatId, messageTemplate, image] = screen.getAllByTestId('expr-editor');
    expect(chatId.getAttribute('data-required')).toBe('true');
    expect(messageTemplate.getAttribute('data-required')).toBe('false');
    expect(image.getAttribute('data-required')).toBe('false');
  });

  it('propagates chatId edits from the expression editor to node data', () => {
    const { handleDelegationChange } = renderSection();
    const [chatId] = screen.getAllByTestId('expr-editor');
    fireEvent.change(chatId, { target: { value: '{{trigger:form.output.chat_id}}' } });
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ chatId: '{{trigger:form.output.chat_id}}' })
    );
  });

  it('propagates messageTemplate edits from the expression editor to node data', () => {
    const { handleDelegationChange } = renderSection();
    const [, messageTemplate] = screen.getAllByTestId('expr-editor');
    fireEvent.change(messageTemplate, { target: { value: 'Approve {{trigger:form.output.amount}}?' } });
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ messageTemplate: 'Approve {{trigger:form.output.amount}}?' })
    );
  });

  it('propagates image edits from the expression editor to node data', () => {
    const { handleDelegationChange } = renderSection();
    const [, , image] = screen.getAllByTestId('expr-editor');
    fireEvent.change(image, { target: { value: '{{interface:card.output.screenshot}}' } });
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ image: '{{interface:card.output.screenshot}}' })
    );
  });
});

describe('ApprovalDelegationSection - required/optional presentation', () => {
  it('marks channel and chatId labels with the required asterisk', () => {
    renderSection();
    expect(screen.getByText('channelLabel').textContent).toContain('*');
    expect(screen.getByText('chatIdLabel').textContent).toContain('*');
  });

  it('leaves messageTemplate, image and allowedUserIds labels without a required marker', () => {
    renderSection();
    expect(screen.getByText('messageLabel').textContent).not.toContain('*');
    expect(screen.getByText('imageLabel').textContent).not.toContain('*');
    expect(screen.getByText('allowedUserIdsLabel').textContent).not.toContain('*');
  });

  it('marks the credential picker as OPTIONAL (isRequired false descriptor, no asterisk from CredentialSection)', () => {
    renderSection();
    expect(screen.getByTestId('credential-section').getAttribute('data-required')).toBe('false');
  });

  it('shows the default-credential fallback hint under the credential picker', () => {
    renderSection();
    expect(screen.getByText('credentialHint')).toBeTruthy();
  });
});

describe('ApprovalDelegationSection - credential id Number() coercion', () => {
  it('stores a numeric-string credential id ("40") as the NUMBER 40', () => {
    const { handleDelegationChange } = renderSection();
    fireEvent.click(screen.getByTestId('pick-numeric-string'));
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ credentialId: 40 })
    );
    const stored = handleDelegationChange.mock.calls[0][0].credentialId;
    expect(typeof stored).toBe('number');
  });

  it('stores a numeric credential id unchanged', () => {
    const { handleDelegationChange } = renderSection();
    fireEvent.click(screen.getByTestId('pick-number'));
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ credentialId: 7 })
    );
  });

  it('clears the credential id (undefined) when the selection is null', () => {
    const { handleDelegationChange } = renderSection();
    fireEvent.click(screen.getByTestId('pick-null'));
    expect(handleDelegationChange).toHaveBeenCalledWith(
      expect.objectContaining({ credentialId: undefined })
    );
  });
});
