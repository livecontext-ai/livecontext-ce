import type { ElementType, ImgHTMLAttributes } from 'react';
import { darkIconSrc } from '@/lib/credentials/darkIcons';

export type ServiceLogoProps = Omit<ImgHTMLAttributes<HTMLImageElement>, 'src'> & {
  /** The icon file, typically `/icons/services/<key>.svg`. */
  src: string | undefined;
  /**
   * Renders each file. Defaults to a plain `<img>`; pass `next/image`'s `Image` where the
   * call site already went through the optimizer. Every other prop reaches it unchanged.
   */
  as?: ElementType;
};

/**
 * A brand icon that also reads on the dark theme.
 *
 * <p>An icon that ships `<key>.dark.svg` (the brand's own dark form, or its reversed mark
 * when the brand publishes none) is rendered twice, and the `.svc-logo-light` /
 * `.svc-logo-dark` rules in `globals.css` hide the one that does not match the theme. The
 * theme is read from the `.dark` ancestor exactly like Tailwind's `dark:` variant, so this
 * works in server components and on the public site's own `.landing-root` theme, with no
 * JavaScript. Nothing is recoloured by a CSS filter: what is drawn is always a real file.
 *
 * <p>An icon without a dark file renders a single element, identical to what the caller
 * had before. Both copies carry the caller's `alt`: the hidden one is `display: none`, which
 * takes it out of the accessibility tree, so the name is announced once.
 */
export function ServiceLogo({ src, as: Tag = 'img', className = '', ...rest }: ServiceLogoProps) {
  const dark = darkIconSrc(src);
  if (!dark) {
    return <Tag src={src} className={className || undefined} {...rest} />;
  }
  return (
    <>
      <Tag src={src} className={`${className} svc-logo-light`.trim()} {...rest} />
      <Tag src={dark} className={`${className} svc-logo-dark`.trim()} {...rest} />
    </>
  );
}

export default ServiceLogo;
