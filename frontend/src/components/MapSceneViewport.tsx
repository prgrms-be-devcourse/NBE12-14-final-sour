import { useLayoutEffect, useRef, type ReactNode } from 'react';
import type { MapRegion } from '../data/schoolMapLayout';
import { mapRegionMotion } from '../utils/mapRegionMotion';

/** Keep the scene between the widest and tallest tested map aspects. Its parent is a query container. */
const MAP_AREA_HEIGHT = 'flex-1 min-h-[calc(100cqw*466/382)] max-h-[calc(100cqw*466/336)]';

export function MapSceneViewport({ region, animated, children }: { region: MapRegion; animated: boolean; children: ReactNode }) {
  const sceneRef = useRef<HTMLDivElement>(null);
  const lastRegion = useRef(region);

  useLayoutEffect(() => {
    const previous = lastRegion.current;
    lastRegion.current = region;
    const scene = sceneRef.current;
    if (!animated || !scene?.animate || !window.matchMedia) return;
    const motion = mapRegionMotion(previous, region);
    if (!motion) return;
    const preference = window.matchMedia('(prefers-reduced-motion: no-preference)');
    if (!preference.matches) return;

    // Animate only the live scene; controls and the list take the new region immediately.
    const animation = scene.animate([
      { translate: `${motion.x}% ${motion.y}%`, scale: motion.scale, opacity: .82 },
      { translate: '0% 0%', scale: 1, opacity: 1 },
    ], { id: 'map-region-motion', duration: 300, easing: 'cubic-bezier(.2,.65,.3,1)' });
    const stopOnPreferenceChange = () => { if (!preference.matches) animation.cancel(); };
    preference.addEventListener('change', stopOnPreferenceChange);
    return () => {
      animation.cancel();
      preference.removeEventListener('change', stopOnPreferenceChange);
    };
  }, [animated, region]);

  return <div className={`${MAP_AREA_HEIGHT} relative overflow-hidden isolate bg-[#38bdf8] select-none`} data-testid="map-area" data-region={region}>
    {animated ? <div ref={sceneRef} className="absolute inset-0" data-map-scene>{children}</div> : children}
  </div>;
}
