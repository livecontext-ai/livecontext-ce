import React from 'react';
import { Check } from 'lucide-react';
import { ActionButton } from '@/app/[locale]/app/settings/developers/components/common';

export interface Step {
  id: number;
  title: string;
  description: string;
  icon: React.ComponentType<{ className?: string }>;
  status: 'completed' | 'current' | 'upcoming';
}

interface StepIndicatorProps {
  currentStep: number;
  onStepClick?: (step: number) => void;
  steps: Step[];
  variant?: 'default' | 'simple';
}

export default function StepIndicator({
  currentStep,
  onStepClick,
  steps,
  variant = 'default'
}: StepIndicatorProps) {
  if (variant === 'simple') {
    // Simple variant for local-mcp (backward compatible)
    return (
      <div className="w-full">
        <div className="flex justify-between items-center">
          {steps.map((step, index) => {
            const Icon = step.icon;
            const isActive = currentStep === step.id;
            const isCompleted = currentStep > step.id;
            const isAccessible = currentStep >= step.id;

            return (
              <div key={step.id} className="flex flex-col items-center flex-1">
                {index > 0 && (
                  <div className="flex-1 flex items-center justify-center mb-4">
                    <div 
                      className={`h-0.5 w-full transition-colors duration-300 ${
                        isCompleted ? 'bg-green-500' : 'bg-theme-tertiary'
                      }`}
                    />
                  </div>
                )}
                
                <div 
                  className={`relative flex items-center justify-center w-12 h-12 rounded-full border-2 transition-all duration-300 ${
                    isCompleted 
                      ? 'bg-green-500 border-green-500 text-white' 
                      : isActive 
                        ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)] text-[var(--accent-foreground)] shadow-lg'
                        : isAccessible
                          ? 'bg-theme-tertiary border-theme text-theme-secondary hover:border-[var(--accent-secondary)]'
                          : 'bg-theme-tertiary border-theme text-theme-muted'
                  }`}
                >
                  {isCompleted ? (
                    <Check className="w-6 h-6" />
                  ) : (
                    <Icon className="w-6 h-6" />
                  )}
                  
                  {!isCompleted && (
                    <div className="absolute -top-1 -right-1 w-5 h-5 bg-theme-primary text-theme-secondary text-xs rounded-full flex items-center justify-center border border-theme">
                      {step.id}
                    </div>
                  )}
                </div>
                
                <div className="mt-3 text-center">
                  <div 
                    className={`font-medium transition-colors duration-300 ${
                      isActive ? 'text-[var(--accent-primary)]' : isCompleted ? 'text-emerald-600' : 'text-theme-secondary'
                    }`}
                  >
                    {step.title}
                  </div>
                  <div className="text-sm text-theme-muted mt-1 hidden sm:block">
                    {step.description}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
        
        <div className="mt-8 bg-theme-tertiary rounded-full h-2 overflow-hidden">
          <div 
            className="bg-[var(--accent-primary)] h-full transition-all duration-500 ease-out"
            style={{ width: `${((currentStep - 1) / (steps.length - 1)) * 100}%` }}
          />
        </div>
        
        <div className="mt-6 text-center">
          <h2 className="text-2xl font-bold text-theme-primary">
            Step {currentStep} : {steps[currentStep - 1]?.title}
          </h2>
          <p className="text-theme-secondary mt-2">
            {steps[currentStep - 1]?.description}
          </p>
        </div>
      </div>
    );
  }

  // Default variant for developers (more sophisticated)
  const getStepIcon = (step: Step) => {
    const IconComponent = step.icon;

    switch (step.status) {
      case 'completed':
        return (
          <div className="w-10 h-10 bg-black dark:bg-white rounded-full flex items-center justify-center shadow-lg text-white dark:text-black">
            <Check className="w-6 h-6" />
          </div>
        );
      case 'current':
        return (
          <div className="w-10 h-10 bg-black dark:bg-white rounded-full flex items-center justify-center shadow-lg text-white dark:text-black">
            <IconComponent className="w-5 h-5" />
          </div>
        );
      case 'upcoming':
        return (
          <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center text-theme-secondary">
            <IconComponent className="w-5 h-5" />
          </div>
        );
    }
  };

  const getStepTextColor = (step: Step) => {
    switch (step.status) {
      case 'completed':
        return 'text-black dark:text-white';
      case 'current':
        return 'text-black dark:text-white';
      case 'upcoming':
        return 'text-theme-secondary';
    }
  };

  const getStepBorderColor = (step: Step) => {
    switch (step.status) {
      case 'completed':
        return 'bg-black dark:bg-white';
      case 'current':
        return 'bg-black dark:bg-white';
      case 'upcoming':
        return 'bg-theme-tertiary';
    }
  };

  return (
    <div className="mb-8">
      {/* Desktop layout */}
      <div className="hidden sm:flex items-center justify-between">
        {steps.map((step, index) => (
          <React.Fragment key={step.id}>
            {/* Step */}
            <div className="flex flex-col items-center flex-1">
              {onStepClick ? (
                <ActionButton
                  onClick={() => onStepClick(step.id)}
                  variant="secondary"
                  size="sm"
                  className="p-0 h-auto mb-2"
                  disabled={step.status === 'upcoming'}
                >
                  {getStepIcon(step)}
                </ActionButton>
              ) : (
                <div className="mb-2">
                  {getStepIcon(step)}
                </div>
              )}
              
              <div className="text-center">
                <h3 className={`text-sm font-medium ${getStepTextColor(step)} mb-1 break-words`}>
                  <span className="hidden sm:inline">{step.title}</span>
                  <span className="sm:hidden">
                    {step.title === "Basic Configuration" ? "Basic Config" :
                     step.title === "API Configuration" ? "API Config" :
                     step.title === "MCP Tools" ? "MCP Tools" :
                     step.title}
                  </span>
                </h3>
                <p className="text-xs text-theme-secondary break-words">
                  <span className="hidden sm:inline">{step.description}</span>
                  <span className="sm:hidden">
                    {step.description === "Name, description and categories" ? "Name & categories" :
                     step.description === "URL, auth and visibility" ? "URL & auth" :
                     step.description === "MCP tools and tests" ? "Tools & tests" :
                     step.description === "Prices and subscription plans" ? "Pricing & plans" :
                     step.description}
                  </span>
                </p>
              </div>
            </div>
            
            {/* Connection line */}
            {index < steps.length - 1 && (
              <div className={`flex-1 h-0.5 mx-4 ${getStepBorderColor(step)}`} />
            )}
          </React.Fragment>
        ))}
      </div>

      {/* Mobile layout */}
      <div className="sm:hidden space-y-4">
        {steps.map((step, index) => (
          <React.Fragment key={step.id}>
            <div className="flex items-center space-x-4">
              {onStepClick ? (
                <ActionButton
                  onClick={() => onStepClick(step.id)}
                  variant="secondary"
                  size="sm"
                  className="p-0 h-auto flex-shrink-0"
                  disabled={step.status === 'upcoming'}
                >
                  {getStepIcon(step)}
                </ActionButton>
              ) : (
                <div className="flex-shrink-0">
                  {getStepIcon(step)}
                </div>
              )}

              <div className="flex-1">
                <h3 className={`text-sm font-medium ${getStepTextColor(step)} mb-1 break-words`}>
                  <span className="inline">
                    {step.title === "Basic Configuration" ? "Basic Config" :
                     step.title === "API Configuration" ? "API Config" :
                     step.title === "MCP Tools" ? "MCP Tools" :
                     step.title}
                  </span>
                </h3>
                <p className="text-xs text-theme-secondary break-words">
                  <span className="inline">
                    {step.description === "Name, description and categories" ? "Name & categories" :
                     step.description === "URL, auth and visibility" ? "URL & auth" :
                     step.description === "MCP tools and tests" ? "Tools & tests" :
                     step.description === "Prices and subscription plans" ? "Pricing & plans" :
                     step.description}
                  </span>
                </p>
              </div>

              {index < steps.length - 1 && (
                <div className="flex flex-col items-center space-y-1">
                  <div className={`w-0.5 h-4 ${getStepBorderColor(step)}`} />
                  <div className="text-xs text-theme-secondary">↓</div>
                  <div className={`w-0.5 h-4 ${getStepBorderColor(step)}`} />
                </div>
              )}
            </div>
          </React.Fragment>
        ))}
      </div>

      {/* Progress indicator */}
      <div className="mt-8">
        <div className="flex items-center justify-between text-sm text-theme-secondary mb-3">
          <span className="hidden sm:inline">Step {currentStep} of {steps.length}</span>
          <span className="sm:hidden">Step {currentStep}/{steps.length}</span>
          <span className="hidden sm:inline">{Math.round((currentStep / steps.length) * 100)}% completed</span>
          <span className="sm:hidden">{Math.round((currentStep / steps.length) * 100)}%</span>
        </div>

        <div className="w-full bg-theme-tertiary rounded-full h-3">
          <div
            className="bg-black dark:bg-white h-3 rounded-full transition-all duration-300 ease-in-out shadow-lg"
            style={{ width: `${(currentStep / steps.length) * 100}%` }}
          />
        </div>
      </div>
    </div>
  );
}

