import React, { useEffect, useState } from 'react';
import { useCampusTimePhase } from '../hooks/useCampusTimePhase';
import { CampusSceneLights } from './CampusScene';

export function useReducedMotion() {
  const [reduced, setReduced] = useState(() => typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    const update = () => setReduced(query.matches);
    update();
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  return reduced;
}

export function CampusAmbience({ level }: { level: number }) {
  const ambience = useCampusTimePhase();
  const reduced = useReducedMotion();
  return (
    <div data-testid="campus-ambience" data-phase={ambience.phase} aria-hidden="true" className="absolute inset-0 pointer-events-none overflow-hidden">
      {ambience.layers.map((layer, index) => layer.tintOpacity > 0 || layer.glowIntensity > 0 ? (
        <React.Fragment key={`${layer.phase}-${index}`}>
          <div data-ambience-layer="tint" className="absolute inset-0 pointer-events-none" style={{ opacity: layer.tintOpacity, mixBlendMode: 'multiply', background: layer.tint }} />
          <div data-ambience-layer={layer.lightType} className="absolute inset-0 pointer-events-none" style={{ opacity: layer.glowIntensity, mixBlendMode: 'screen' }}>
            <div className={`absolute inset-0 pointer-events-none ${!reduced && layer.lightType === 'sun' ? 'campus-sun-sway' : ''}`} style={{ background: layer.lightType === 'sun' ? `linear-gradient(125deg, ${layer.light}, transparent 55%)` : layer.lightType === 'warm' ? `radial-gradient(ellipse at left center, ${layer.light}, transparent 70%)` : `radial-gradient(ellipse at center bottom, ${layer.light}, transparent 65%)` }} />
          </div>
          {/* Window masks and lamp glows of the composed scene at this level (CampusScene). */}
          {layer.windowLights && <CampusSceneLights level={level} opacity={layer.windowOpacity} twinkle={!reduced} />}
        </React.Fragment>
      ) : null)}
    </div>
  );
}
