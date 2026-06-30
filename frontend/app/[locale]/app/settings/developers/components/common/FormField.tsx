import React from 'react';
import { TYPOGRAPHY } from './index';

interface FormFieldProps {
  label: string | React.ReactNode;
  required?: boolean;
  description?: string;
  error?: string;
  children: React.ReactNode;
  className?: string;
}

const FormField: React.FC<FormFieldProps> = ({
  label,
  required = false,
  description,
  error,
  children,
  className = ''
}) => {
  return (
    <div className={`space-y-2 ${className}`}>
      {/* Label */}
      <label className={`block ${TYPOGRAPHY.label}`}>
        {label}
        {required && <span className="text-red-500 ml-1">*</span>}
      </label>

      {/* Description */}
      {description && (
        <div className={TYPOGRAPHY.description}>{description}</div>
      )}

      {/* Field content */}
      <div>
        {children}
      </div>

      {/* Error message */}
      {error && (
        <div className={TYPOGRAPHY.error}>{error}</div>
      )}
    </div>
  );
};

export default FormField;
