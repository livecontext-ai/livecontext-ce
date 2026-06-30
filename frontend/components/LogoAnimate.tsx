'use client';
import React, { useState, useId, useCallback, useMemo } from 'react';
import { useThemeSafely } from '../hooks/useThemeSafely';

interface LogoAnimateProps {
  size?: 'sm' | 'md' | 'lg' | 'xl' | 'xxl';
  className?: string;
  alwaysPulse?: boolean;
}

const LogoAnimate = React.memo<LogoAnimateProps>(({ size = 'md', className = '', alwaysPulse = false }) => {
  const [isHovered, setIsHovered] = useState(false);
  const id = useId();
  const { theme } = useThemeSafely();

  // Memoize les classes de taille
  const sizeClasses = useMemo(() => ({
    sm: 'w-8 h-8',   // 32px
    md: 'w-12 h-12', // 48px
    lg: 'w-16 h-16', // 64px
    xl: 'w-20 h-20',  // 80px
    xxl: 'w-35 h-35'  // 160px
  }), []);

  // Callback memoize pour les handlers d'evenements
  const handleMouseEnter = useCallback(() => setIsHovered(true), []);
  const handleMouseLeave = useCallback(() => setIsHovered(false), []);

  // Memoize si on doit utiliser l'ombre
  const withShadow = useMemo(() => size === 'lg' || size === 'xl' || size === 'xxl', [size]);

  // Animation CSS personnalisee pour la transition rouge vers transparent
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


  return (
    <>
      <style jsx>{pulseAnimation}</style>
      <div
        className={`${sizeClasses[size]} ${className} cursor-pointer transition-all duration-300 ease-in-out`}
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
        // Astuce : evite le sous-pixel -> contour net
        style={{ imageRendering: 'auto' }}
      >
      <svg
        xmlns="http://www.w3.org/2000/svg"
        version="1.0"
        width="100%"
        height="100%"
        viewBox="0 0 1024 1024"
        preserveAspectRatio="xMidYMid meet"
        // En JSX: camelCase conseille
        shapeRendering="geometricPrecision"
        vectorEffect="non-scaling-stroke"
        role="img"
        aria-label="Logo"
        className="transition-all duration-300 ease-in-out"
        // pas de filter CSS ici (ça pixellise aux petites tailles)
      >
        {withShadow && (
          <defs>
            {/* Ombre en filtre SVG (mieux pour le scaling que le CSS drop-shadow) */}
            <filter
              id={`${id}-shadow`}
              x="-20%" y="-20%" width="140%" height="140%"
              filterUnits="objectBoundingBox" colorInterpolationFilters="sRGB"
            >
              <feDropShadow dx="0" dy="0" stdDeviation="0.35" floodColor="currentColor" floodOpacity="0.4" />
            </filter>
          </defs>
        )}

        <g
          transform="translate(0,1024) scale(0.1,-0.1)"
          fill="currentColor"
          stroke="none"
          filter={withShadow ? `url(#${id}-shadow)` : undefined}
          className="transition-all duration-300 ease-in-out"
        >
          {/* Partie exterieure - statique avec transition, garde sa couleur d'origine */}
          <path
            d="M4905 7724 c-338 -37 -600 -108 -893 -242 -879 -404 -1465 -1294 -1499 -2277 -20 -571 153 -1149 481 -1610 328 -460 784 -796 1308 -963 259 -83 475 -120 743 -129 337 -10 640 35 946 143 123 43 369 161 489 234 298 182 596 471 797 772 36 54 63 104 60 111 -3 9 -593 416 -654 451 -8 4 -30 -20 -62 -67 -293 -434 -691 -700 -1186 -793 -156 -30 -509 -27 -664 4 -225 46 -454 136 -621 244 -422 273 -701 702 -796 1223 -25 138 -25 460 -1 595 51 283 157 532 319 752 93 127 314 342 433 420 325 216 626 308 1005 308 311 0 551 -58 820 -196 69 -36 168 -95 220 -132 126 -89 346 -309 445 -444 43 -60 80 -108 83 -108 5 0 231 141 452 283 85 55 167 107 182 116 16 9 28 22 28 29 0 7 -23 49 -52 95 -233 373 -566 680 -958 886 -269 141 -551 232 -855 277 -97 14 -489 26 -570 18z"
            className={`${className} transition-all duration-300 ease-in-out`}
          />

          {/* Partie centrale - pulsation au hover ou en permanence avec transition */}
          <path
            d="M4970 5795 c-86 -20 -211 -82 -282 -142 -162 -135 -242 -311 -242 -528 0 -175 50 -319 153 -443 240 -289 690 -326 965 -80 207 185 288 479 202 733 -71 208 -239 376 -440 441 -101 33 -256 41 -356 19z"
            className={`transition-all duration-300 ease-in-out ${(isHovered || alwaysPulse) ? 'text-red-600' : 'text-current'}`}
            style={{
              animation: (isHovered || alwaysPulse) ? 'redToTransparentPulse 1.5s ease-in-out infinite' : 'none'
            }}
          />
        </g>
      </svg>
      </div>
    </>
  );
});

LogoAnimate.displayName = "LogoAnimate";

export default LogoAnimate;
