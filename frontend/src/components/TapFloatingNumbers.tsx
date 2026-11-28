import React, { useCallback, useEffect, useRef, useState } from 'react';
import type { FloatingNumber } from '../types';

const LIFETIME_MS = 900;

/** Short-lived damage numbers over a tapped area, styled like the campus tap numbers. */
export function useTapFloatingNumbers() {
  const [items, setItems] = useState<FloatingNumber[]>([]);
  const sequence = useRef(0);
  const add = useCallback((x: number, y: number, text: string, isCrit: boolean) => {
    const id = `${Date.now()}-${++sequence.current}`;
    setItems((prev) => [...prev.slice(-15), { id, x, y: y - 10, text, isCrit, drift: (Math.random() - 0.5) * 36 }]);
  }, []);
  useEffect(() => {
    if (items.length === 0) return;
    const timer = setTimeout(() => {
      setItems((prev) => prev.filter((item) => Date.now() - parseInt(item.id.split('-')[0]) < LIFETIME_MS));
    }, LIFETIME_MS);
    return () => clearTimeout(timer);
  }, [items]);
  return { items, add };
}

export function TapFloatingNumbers({ items }: { items: FloatingNumber[] }) {
  return (
    <>
      {items.map((num) => (
        <div
          key={num.id}
          data-testid="tap-floating-number"
          className="absolute pointer-events-none z-30 animate-float-number"
          style={{ left: `${num.x}px`, top: `${num.y}px`, '--drift': `${num.drift}px` } as React.CSSProperties}
        >
          <span
            className={`font-press-start font-black text-sm select-none ${
              num.isCrit ? 'text-[#facc15] drop-shadow-[2px_2px_0px_#261608]' : 'text-[#ffffff] drop-shadow-[2px_2px_0px_#261608]'
            }`}
          >
            {num.text}
          </span>
        </div>
      ))}
    </>
  );
}
