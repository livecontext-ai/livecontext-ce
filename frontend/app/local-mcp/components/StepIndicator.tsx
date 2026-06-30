import React from 'react';
import { Settings, Code, TestTube, Database, Zap } from 'lucide-react';
import StepIndicatorShared, { Step } from '@/app/shared/components/StepIndicator';
import { useTranslations } from 'next-intl';

interface StepIndicatorProps {
  currentStep: number;
}

export default function StepIndicator({ currentStep }: StepIndicatorProps) {
  const t = useTranslations('localMcp');

  const steps: Step[] = [
    {
      id: 1,
      title: t('steps.configuration.title'),
      description: t('steps.configuration.description'),
      icon: Settings,
      status: 'current'
    },
    {
      id: 2,
      title: t('steps.mcpTools.title'),
      description: t('steps.mcpTools.description'),
      icon: Code,
      status: 'upcoming'
    },
    {
      id: 3,
      title: t('steps.setup.title'),
      description: t('steps.setup.description'),
      icon: Database,
      status: 'upcoming'
    },
    {
      id: 4,
      title: t('steps.tests.title'),
      description: t('steps.tests.description'),
      icon: TestTube,
      status: 'upcoming'
    },
    {
      id: 5,
      title: t('steps.submission.title'),
      description: t('steps.submission.description'),
      icon: Zap,
      status: 'upcoming'
    }
  ];

  // Map currentStep to step statuses
  const stepsWithStatus: Step[] = steps.map(step => {
    if (currentStep > step.id) {
      return { ...step, status: 'completed' as const };
    } else if (currentStep === step.id) {
      return { ...step, status: 'current' as const };
    } else {
      return { ...step, status: 'upcoming' as const };
    }
  });

  return (
    <StepIndicatorShared
      currentStep={currentStep}
      steps={stepsWithStatus}
      variant="simple"
    />
  );
}
