import React from 'react';
import {Check, DollarSign, Gift, Crown, CreditCard, MessageSquare, Code} from 'lucide-react';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';

interface PricingCardProps {
  model: 'freemium' | 'paid';
  title: string;
  description: string;
  features: string[];
  isSelected: boolean;
  onSelect: () => void;
  recommended?: boolean;
  featuresWithIcons?: Array<{
    text: string;
    icon: 'check' | 'x';
    color: string;
  }>;
}

const PricingCard: React.FC<PricingCardProps> = ({
  model,
  title,
  description,
  features,
  isSelected,
  onSelect,
  recommended = false,
  featuresWithIcons
}) => {
  const getModelIcon = () => {
    switch (model) {
      case 'freemium':
        return <MessageSquare className="w-5 h-5 text-purple-500 dark:text-purple-400" />;
      case 'paid':
        return <Code className="w-5 h-5 text-orange-500 dark:text-orange-400" />;
      default:
        return <DollarSign className="w-5 h-5 text-theme-secondary" />;
    }
  };

  const getModelColor = () => {
    switch (model) {
      case 'freemium':
        return 'border-theme hover:border-purple-300';
      case 'paid':
        return 'border-theme hover:border-orange-300';
      default:
        return 'border-theme';
    }
  };

  const getSelectedColor = () => {
    switch (model) {
      case 'freemium':
        return 'ring-2 ring-purple-500 border-purple-500';
      case 'paid':
        return 'ring-2 ring-orange-500 border-orange-500';
      default:
        return 'ring-2 ring-theme-primary border-theme-primary';
    }
  };

  return (
    <Card
      className={`relative p-4 border-2 rounded-xl cursor-pointer transition-all duration-200 hover:shadow-lg ${
        isSelected ? getSelectedColor() : getModelColor()
      } ${isSelected ? 'bg-theme-secondary' : 'bg-theme-tertiary hover:bg-theme-secondary'}`}
      onClick={onSelect}
    >
      {/* Recommended badge */}
      {recommended && (
        <div className="absolute -top-3 left-1/2 transform -translate-x-1/2 z-10">
          <Badge 
            className={`text-xs font-medium px-3 py-1 rounded-full shadow-lg ${
              model === 'freemium'
                ? 'bg-purple-500 text-white border-transparent'
                : 'bg-theme-primary text-theme-secondary border-transparent'
            }`}
          >
            Recommended
          </Badge>
        </div>
      )}

      {/* Header */}
      <div className="text-center mb-3">
        <div className="flex justify-center mb-2">
          {getModelIcon()}
        </div>
        <h3 className="text-base font-semibold text-theme-primary mb-1">{title}</h3>
        <p className="text-xs text-theme-secondary">{description}</p>
      </div>

      {/* Features list */}
      <div className="space-y-2 mb-4 mx-auto max-w-fit">
        {featuresWithIcons ? (
          featuresWithIcons.map((feature, index) => (
            <div key={index} className="flex items-start space-x-2">
              {feature.icon === 'check' ? (
                <Check className={`w-3.5 h-3.5 ${feature.color} mt-0.5 flex-shrink-0`} />
              ) : (
                <div className={`w-3.5 h-3.5 ${feature.color} mt-0.5 flex-shrink-0 flex items-center justify-center`}>
                  <span className="text-xs font-bold">×</span>
                </div>
              )}
              <span className="text-xs text-theme-primary">{feature.text}</span>
            </div>
          ))
        ) : (
          features.map((feature, index) => (
            <div key={index} className="flex items-start space-x-2">
              <Check className="w-3.5 h-3.5 text-green-500 dark:text-green-400 mt-0.5 flex-shrink-0" />
              <span className="text-xs text-theme-primary">{feature}</span>
            </div>
          ))
        )}
      </div>

      {/* Selection indicator */}
      <div className="flex justify-center" onClick={(e) => e.stopPropagation()}>
        <Checkbox
          checked={isSelected}
          onCheckedChange={(checked) => {
            if (checked !== isSelected) {
              onSelect();
            }
          }}
          className={`rounded-full w-5 h-5 ${
            model === 'freemium'
              ? 'border-purple-500 data-[state=checked]:bg-purple-500'
              : 'border-orange-500 data-[state=checked]:bg-orange-500'
          }`}
        />
      </div>

      {/* Usage description */}
      <div className="mt-3 text-center">
        <div className="mb-1">
          <span className="text-xs text-theme-muted uppercase tracking-wide">
            {model} Model
          </span>
        </div>
        <div className="text-xs text-theme-secondary">
          {model === 'freemium' && (
            <div className="flex items-center justify-center space-x-1">
              <MessageSquare className="w-3 h-3" />
              <span>AI Integration</span>
            </div>
          )}
          {model === 'paid' && (
            <div className="flex items-center justify-center space-x-1">
              <Code className="w-3 h-3" />
              <span>Traditional API Access</span>
            </div>
          )}
        </div>
      </div>
    </Card>
  );
};

export default PricingCard;
