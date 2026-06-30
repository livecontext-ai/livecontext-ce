// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent, waitFor, within } from '@testing-library/react';
import { FleetTriggerButtons } from '../FleetTriggerButtons';
import { BTN_CLS } from '../NodeBottomBar';

vi.mock('next-intl', () => ({
  useTranslations: (namespace?: string) => (key: string) => {
    const messages: Record<string, Record<string, string>> = {
      triggerPanel: {
        webhookTriggerTitle: 'Webhook',
      },
      'workflowBuilder.inspector.scheduleTrigger': {
        scheduleLabel: 'Schedule',
      },
    };
    return messages[namespace ?? '']?.[key] ?? key;
  },
}));

const WEBHOOK_URL = 'https://example.com/api/webhook/abc123';
const CRON = '0 9 * * *';

afterEach(() => cleanup());

describe('FleetTriggerButtons', () => {
  it('renders BOTH the webhook and schedule buttons with their tooltips when both triggers are present', () => {
    const { getByTitle, getByTestId, getAllByTestId } = render(
      <FleetTriggerButtons
        triggers={{ hasWebhook: true, hasSchedule: true, webhookUrl: WEBHOOK_URL, cronExpression: CRON, timezone: 'UTC' }}
        borderColor="#10b981"
      />,
    );

    // Container keeps the legacy test id so the fleet e2e selector still resolves.
    expect(getByTestId('fleet-trigger-badge')).toBeTruthy();

    // One button per trigger.
    expect(getByTitle('Webhook')).toBeTruthy();
    expect(getByTitle('Schedule')).toBeTruthy();

    // Each trigger gets its OWN tooltip (webhook URL / cron), not one shared tooltip.
    const tooltips = getAllByTestId('fleet-trigger-tooltip');
    expect(tooltips).toHaveLength(2);
    expect(within(tooltips[0]).getByText(WEBHOOK_URL)).toBeTruthy();
    expect(within(tooltips[1]).getByText(CRON)).toBeTruthy();
    expect(within(tooltips[1]).getByText('UTC')).toBeTruthy();
  });

  it('renders ONLY the webhook button when only a webhook trigger exists', () => {
    const { getByTitle, queryByTitle, getAllByTestId } = render(
      <FleetTriggerButtons
        triggers={{ hasWebhook: true, hasSchedule: false, webhookUrl: WEBHOOK_URL }}
        borderColor="#10b981"
      />,
    );
    expect(getByTitle('Webhook')).toBeTruthy();
    expect(queryByTitle('Schedule')).toBeNull();
    expect(getAllByTestId('fleet-trigger-tooltip')).toHaveLength(1);
  });

  it('renders ONLY the schedule button (with its cron tooltip) when only a schedule trigger exists', () => {
    const { getByTitle, queryByTitle, getByTestId } = render(
      <FleetTriggerButtons
        triggers={{ hasWebhook: false, hasSchedule: true, cronExpression: CRON, timezone: 'Europe/Paris' }}
        borderColor="#10b981"
      />,
    );
    expect(getByTitle('Schedule')).toBeTruthy();
    expect(queryByTitle('Webhook')).toBeNull();
    const tooltip = getByTestId('fleet-trigger-tooltip');
    expect(within(tooltip).getByText(CRON)).toBeTruthy();
    expect(within(tooltip).getByText('Europe/Paris')).toBeTruthy();
  });

  it('renders nothing actionable when there is neither a webhook nor a schedule', () => {
    const { queryByTitle, getByTestId } = render(
      <FleetTriggerButtons triggers={{ hasWebhook: false, hasSchedule: false }} borderColor="#10b981" />,
    );
    expect(getByTestId('fleet-trigger-badge')).toBeTruthy();
    expect(queryByTitle('Webhook')).toBeNull();
    expect(queryByTitle('Schedule')).toBeNull();
  });

  it('styles the buttons EXACTLY like the workflow node bottom buttons (shared BTN_CLS) with the status border color', () => {
    const { getByTitle } = render(
      <FleetTriggerButtons
        triggers={{ hasWebhook: true, hasSchedule: true, webhookUrl: WEBHOOK_URL, cronExpression: CRON }}
        borderColor="rgb(239, 68, 68)"
      />,
    );
    const webhookBtn = getByTitle('Webhook');
    // Same round bottom-bar button class as NodeBottomBar (single source of truth).
    BTN_CLS.split(/\s+/).forEach((cls) => expect(webhookBtn.className).toContain(cls));
    // Status-synced border (2px solid borderColor), same pattern as NodeBottomBar buttons.
    expect(webhookBtn.style.borderColor).toBe('rgb(239, 68, 68)');
    expect(webhookBtn.style.borderWidth).toBe('2px');
  });

  it('carries the amber trigger shimmer overlay on each button', () => {
    const { container } = render(
      <FleetTriggerButtons
        triggers={{ hasWebhook: true, hasSchedule: true, webhookUrl: WEBHOOK_URL, cronExpression: CRON }}
        borderColor="#10b981"
      />,
    );
    const shimmers = Array.from(container.querySelectorAll('span')).filter((s) =>
      s.getAttribute('style')?.includes('rgba(245, 158, 11, 0.3)'),
    );
    expect(shimmers).toHaveLength(2);
  });

  it('copies the webhook URL to the clipboard and flips the copy icon to a check when the webhook button is clicked', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });

    const { getByTitle, container } = render(
      <FleetTriggerButtons
        triggers={{ hasWebhook: true, hasSchedule: false, webhookUrl: WEBHOOK_URL }}
        borderColor="#10b981"
      />,
    );

    // Before the click: copy affordance, no check.
    expect(container.querySelector('.lucide-copy')).toBeTruthy();
    expect(container.querySelector('.lucide-check')).toBeNull();

    fireEvent.click(getByTitle('Webhook'));

    expect(writeText).toHaveBeenCalledWith(WEBHOOK_URL);
    await waitFor(() => expect(container.querySelector('.lucide-check')).toBeTruthy());
    expect(container.querySelector('.lucide-copy')).toBeNull();
  });

  it('does not let a button click bubble to the node (no accidental node selection on a draggable canvas)', () => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
    const onParentClick = vi.fn();
    const { getByTitle } = render(
      <div onClick={onParentClick}>
        <FleetTriggerButtons
          triggers={{ hasWebhook: true, hasSchedule: true, webhookUrl: WEBHOOK_URL, cronExpression: CRON }}
          borderColor="#10b981"
        />
      </div>,
    );
    fireEvent.click(getByTitle('Webhook'));
    fireEvent.click(getByTitle('Schedule'));
    expect(onParentClick).not.toHaveBeenCalled();
  });

  // Only the JS-state conditional emits the literal `hidden` token (no static class contains it),
  // so its presence/absence reliably reflects the tooltip's revealed state in jsdom (which cannot
  // evaluate the CSS `group-hover` :hover fallback).
  it('reveals the webhook tooltip on hover (JS-state reveal) and hides it again on mouse-leave', () => {
    const { getAllByTestId } = render(
      <FleetTriggerButtons
        triggers={{ hasWebhook: true, hasSchedule: true, webhookUrl: WEBHOOK_URL, cronExpression: CRON, timezone: 'UTC' }}
        borderColor="#10b981"
      />,
    );
    const [webhookTooltip] = getAllByTestId('fleet-trigger-tooltip');
    const webhookGroup = webhookTooltip.parentElement as HTMLElement;

    expect(webhookTooltip.className).toContain('hidden');
    fireEvent.mouseEnter(webhookGroup);
    expect(webhookTooltip.className).not.toContain('hidden');
    fireEvent.mouseLeave(webhookGroup);
    expect(webhookTooltip.className).toContain('hidden');
  });

  it('reveals the tooltip on keyboard focus of its trigger button and hides it on blur (accessibility)', () => {
    const { getByTitle, getAllByTestId } = render(
      <FleetTriggerButtons
        triggers={{ hasWebhook: true, hasSchedule: true, webhookUrl: WEBHOOK_URL, cronExpression: CRON, timezone: 'UTC' }}
        borderColor="#10b981"
      />,
    );
    const scheduleTooltip = getAllByTestId('fleet-trigger-tooltip')[1];

    expect(scheduleTooltip.className).toContain('hidden');
    fireEvent.focus(getByTitle('Schedule'));
    expect(scheduleTooltip.className).not.toContain('hidden');
    fireEvent.blur(getByTitle('Schedule'));
    expect(scheduleTooltip.className).toContain('hidden');
  });
});
