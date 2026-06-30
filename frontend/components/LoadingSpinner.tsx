'use client';
import React from 'react';
import { useThemeSafely } from '../hooks/useThemeSafely';

interface LoadingSpinnerProps {
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  className?: string;
  text?: string;
}

// Animation CSS personnalisee pour la pulsation rouge
const pulseAnimation = `
  @keyframes redToTransparentPulse {
    0%, 100% {
      opacity: 1;
    }
    50% {
      opacity: 0.1;
    }
  }
`;

const LoadingSpinner = React.memo<LoadingSpinnerProps>(({
  size = 'md',
  className = '',
  text
}) => {
  // Utiliser useTheme de maniere securisee
  const { theme } = useThemeSafely();

  const sizeClasses = {
    xs: 'w-4 h-4',
    sm: 'w-6 h-6',
    md: 'w-8 h-8',
    lg: 'w-12 h-12',
    xl: 'w-16 h-16'
  };

  const spinnerColor = theme === 'dark'
    ? 'text-white'
    : 'text-black';

  return (
    <>
      <style jsx>{pulseAnimation}</style>
      <div className={`flex items-center ${className}`}>
        <div className={`${sizeClasses[size]} ${spinnerColor} animate-spin`}>
          <svg
            xmlns="http://www.w3.org/2000/svg"
            version="1.0"
            width="100%"
            height="100%"
            viewBox="0 0 1024 1024"
            preserveAspectRatio="xMidYMid meet"
            shapeRendering="geometricPrecision"
            vectorEffect="non-scaling-stroke"
            role="img"
            aria-label="Loading Logo"
            className="transition-all duration-300 ease-in-out"
          >
            <g
              transform="translate(0,1024) scale(0.1,-0.1)"
              fill="currentColor"
              stroke="none"
              className="transition-all duration-300 ease-in-out"
            >
              {/* Partie exterieure - logo principal qui tourne */}
              <path
                d="M4905 7724 c-338 -37 -600 -108 -893 -242 -879 -404 -1465 -1294 -1499 -2277 -20 -571 153 -1149 481 -1610 328 -460 784 -796 1308 -963 259 -83 475 -120 743 -129 337 -10 640 35 946 143 123 43 369 161 489 234 298 182 596 471 797 772 36 54 63 104 60 111 -3 9 -593 416 -654 451 -8 4 -30 -20 -62 -67 -293 -434 -691 -700 -1186 -793 -156 -30 -509 -27 -664 4 -225 46 -454 136 -621 244 -422 273 -701 702 -796 1223 -25 138 -25 460 -1 595 51 283 157 532 319 752 93 127 314 342 433 420 325 216 626 308 1005 308 311 0 551 -58 820 -196 69 -36 168 -95 220 -132 126 -89 346 -309 445 -444 43 -60 80 -108 83 -108 5 0 231 141 452 283 85 55 167 107 182 116 16 9 28 22 28 29 0 7 -23 49 -52 95 -233 373 -566 680 -958 886 -269 141 -551 232 -855 277 -97 14 -489 26 -570 18z"
                className="transition-all duration-300 ease-in-out"
              />

              {/* Partie centrale - cœur du logo avec pulsation rouge */}
              <path
                d="M4970 5795 c-86 -20 -211 -82 -282 -142 -162 -135 -242 -311 -242 -528 0 -175 50 -319 153 -443 240 -289 690 -326 965 -80 207 185 288 479 202 733 -71 208 -239 376 -440 441 -101 33 -256 41 -356 19z"
                className="transition-all duration-300 ease-in-out text-red-600"
                style={{
                  animation: 'redToTransparentPulse 1.5s ease-in-out infinite'
                }}
              />
            </g>
          </svg>
        </div>
        {text && <span className="ml-3 text-sm text-gray-600 dark:text-gray-300">{text}</span>}
      </div>
    </>
  );
});

LoadingSpinner.displayName = "LoadingSpinner";

export default LoadingSpinner;
