import React, { createContext, useContext, useEffect, useState } from 'react';
import { getCampusTimePhase, MOCK_AMBIENCE_CONFIG, resolveAmbience, type AmbienceOverride } from '../data/campusAmbience';

interface AmbienceContextValue { now: number; override: AmbienceOverride; setOverride: (value: AmbienceOverride) => void }
const AmbienceContext = createContext<AmbienceContextValue | null>(null);

export function CampusAmbienceProvider({ children, initialOverride = 'auto' }: { children: React.ReactNode; initialOverride?: AmbienceOverride }) {
  const [now, setNow] = useState(() => Date.now());
  const [override, setOverride] = useState<AmbienceOverride>(initialOverride);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), MOCK_AMBIENCE_CONFIG.updateIntervalMs);
    return () => clearInterval(timer);
  }, []);
  return <AmbienceContext.Provider value={{ now, override, setOverride }}>{children}</AmbienceContext.Provider>;
}

export function useCampusTimePhase() {
  const context = useContext(AmbienceContext);
  const [fallbackNow, setFallbackNow] = useState(() => Date.now());
  useEffect(() => {
    if (context) return;
    const timer = setInterval(() => setFallbackNow(Date.now()), MOCK_AMBIENCE_CONFIG.updateIntervalMs);
    return () => clearInterval(timer);
  }, [!!context]);
  const info = getCampusTimePhase(context?.now ?? fallbackNow, MOCK_AMBIENCE_CONFIG);
  return resolveAmbience(info, context?.override ?? 'auto', MOCK_AMBIENCE_CONFIG);
}

export function useAmbienceControl() {
  return useContext(AmbienceContext);
}
