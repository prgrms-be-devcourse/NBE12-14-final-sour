import { useState } from 'react';
import { useSharedImage } from '../hooks/useSharedImages.tsx';
import { validImageUrl, type ImageTarget } from '../utils/sharedImages.ts';
import { SCHOOL_LOGO_ASSETS } from '../data/schoolLogos.ts';

export function ImageFallback({
  url,
  defaultUrl,
  name,
  className = '',
  fallback,
}: {
  url?: string;
  defaultUrl?: string;
  name: string;
  className?: string;
  fallback?: string;
}) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const [failedDefaultUrl, setFailedDefaultUrl] = useState<string | null>(null);

  if (url && validImageUrl(url) && failedUrl !== url) {
    return (
      <img
        src={url}
        alt={`${name} 이미지`}
        className={`${className} object-cover`}
        onError={() => setFailedUrl(url)}
      />
    );
  }

  if (defaultUrl && validImageUrl(defaultUrl) && failedDefaultUrl !== defaultUrl) {
    return (
      <img
        src={defaultUrl}
        alt={`${name} 기본 로고`}
        className={`${className} object-cover`}
        onError={() => setFailedDefaultUrl(defaultUrl)}
      />
    );
  }

  return (
    <span
      role="img"
      aria-label={`${name} 기본 배지`}
      className={`${className} flex items-center justify-center`}
    >
      {fallback || name.slice(0, 1) || '?'}
    </span>
  );
}

export function SharedImage({
  target,
  name,
  className,
  fallback,
  defaultUrl,
}: {
  target: ImageTarget;
  name: string;
  className?: string;
  fallback?: string;
  defaultUrl?: string;
}) {
  const image = useSharedImage(target);
  const resolvedDefaultUrl =
    // One source for bundled school logos, shared with the map's logo status (M1-D5).
    defaultUrl ?? (target.type === 'school' ? SCHOOL_LOGO_ASSETS[target.id] : undefined);
  return (
    <ImageFallback
      url={image?.url}
      defaultUrl={resolvedDefaultUrl}
      name={name}
      className={className}
      fallback={fallback}
    />
  );
}
