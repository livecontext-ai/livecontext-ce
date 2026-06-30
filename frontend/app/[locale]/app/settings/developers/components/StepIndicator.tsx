import React from 'react';
import StepIndicatorShared, { Step } from '@/app/shared/components/StepIndicator';

interface StepIndicatorProps {
  currentStep: number;
  onStepClick: (step: number) => void;
  steps: {
    id: number;
    title: string;
    description: string;
    icon: React.ComponentType<{ className?: string }>;
    status: 'completed' | 'current' | 'upcoming';
  }[];
}

const StepIndicator: React.FC<StepIndicatorProps> = ({
  currentStep,
  onStepClick,
  steps
}) => {
  // Convert steps to shared format
  const sharedSteps: Step[] = steps.map(step => ({
    id: step.id,
    title: step.title,
    description: step.description,
    icon: step.icon,
    status: step.status
  }));

  return (
    <StepIndicatorShared
      currentStep={currentStep}
      onStepClick={onStepClick}
      steps={sharedSteps}
      variant="default"
    />
  );
};

export default StepIndicator;
