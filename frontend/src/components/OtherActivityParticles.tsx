import type { OtherClickParticle } from '../hooks/useMockOtherClicks';
import type { SchoolActivity } from '../utils/otherClicksFeed';

/** Click particles take the time-of-day palette (sky by day, warm otherwise); attacks and assists keep their CSS colors. */
const clickColor = (palette: string) => (palette === 'sky' ? '#7dd3fc' : '#ffe49a');

/**
 * Other people's clicks, attacks and assists at this school in the previous second (2026-11-28): a click is a glowing
 * orb, an attack a red-orange burst that shakes, an assist a green cross that rises. `+N` marks a particle that
 * stands for several actions.
 */
export function OtherActivityParticles({ particles, lifetimeMs, palette, reducedMotion }: {
  particles: OtherClickParticle[];
  lifetimeMs: number;
  palette: string;
  reducedMotion: boolean;
}) {
  return (
    <>
      {particles.map((particle) => (
        <div key={particle.id} data-testid="other-click-particle" data-kind={particle.kind} aria-hidden="true"
          className={`absolute z-10 pointer-events-none other-click-particle other-${particle.kind}-particle ${reducedMotion ? '' : `other-${particle.kind}-animate`}`}
          style={{ left: `${particle.xPct}%`, top: `${particle.yPct}%`, color: particle.kind === 'click' ? clickColor(palette) : undefined, animationDuration: `${lifetimeMs}ms` }}>
          <span className={`other-${particle.kind}-mark`} />{particle.weight > 1 && <span className="other-click-weight">+{particle.weight}</span>}
        </div>
      ))}
    </>
  );
}

/** "클릭 12 · 공격받음 3 · 지원받음 1": kinds with nothing that second are left out. */
export function activitySummary(activity: SchoolActivity, clickLabel: (count: number) => string): string {
  return [
    clickLabel(activity.clicks),
    activity.attacks > 0 ? `공격받음 ${activity.attacks}회` : null,
    activity.assists > 0 ? `지원받음 ${activity.assists}회` : null,
  ].filter(Boolean).join(' · ');
}
