/**
 * Pudding wobble for the campus buildings on a tap (T6, docs/261110-1005-mockup_remaining_tasks_plan.md §9).
 * Only buildings move (user decision Q8); the click area itself stays still. Each wrapper is marked by
 * CampusScene (`data-wobble`) and CampusSceneLights (`data-wobble-lights`) with the building's draw index
 * (`data-item-index`), so a building and its night window lights get the same keyframes and delay.
 */

/** Squash, stretch and settle back to the integer scale, anchored at the building's base (fill-box, 50% 100%). */
export const WOBBLE_KEYFRAMES: Keyframe[] = [
  { transform: 'scale(1, 1)' },
  { transform: 'scale(1.04, 0.95)' },
  { transform: 'scale(0.98, 1.03)' },
  { transform: 'scale(1.01, 0.99)' },
  { transform: 'scale(1, 1)' },
];
export const WOBBLE_DURATION_MS = 320;
/** Delay between buildings in draw order, so two or three read as one jelly ripple (at most 40ms at Lv.7·9). */
export const WOBBLE_STAGGER_MS = 20;
export const WOBBLE_SELECTOR = '[data-wobble], [data-wobble-lights]';

/**
 * Attack only: a left-right shake instead of the tap's squash. The buildings (and their window lights) swing one way
 * while the surroundings (trees, props, people, fountain; CampusScene `data-wobble-back`) swing the other, so the hit
 * reads as a shock wave. Sky and ground stay still: moving them would show the frame's edge. Offsets are scene units, at most 2.
 */
export const WOBBLE_BACK_SELECTOR = '[data-wobble-back]';
export const ATTACK_SHAKE_KEYFRAMES: Keyframe[] = [
  { transform: 'translateX(0px)' },
  { transform: 'translateX(-2px)' },
  { transform: 'translateX(2px)' },
  { transform: 'translateX(-1px)' },
  { transform: 'translateX(1px)' },
  { transform: 'translateX(0px)' },
];
export const REVERSE_ATTACK_SHAKE_KEYFRAMES: Keyframe[] = [
  { transform: 'translateX(0px)' },
  { transform: 'translateX(2px)' },
  { transform: 'translateX(-2px)' },
  { transform: 'translateX(1px)' },
  { transform: 'translateX(-1px)' },
  { transform: 'translateX(0px)' },
];

/** Static style of a wobble wrapper: scales around the bottom center of its own box. */
export const WOBBLE_STYLE = { transformBox: 'fill-box', transformOrigin: '50% 100%' } as const;

// One animation per element: a new tap restarts it instead of stacking.
const running = new WeakMap<Element, Animation>();

/**
 * Starts the wobble on every building wrapper under `root` and returns how many animations started.
 * Does nothing with reduced motion or where the Web Animations API is missing.
 */
export function wobbleBuildings(root: ParentNode | null, reducedMotion: boolean): number {
  if (!root || reducedMotion) return 0;
  const targets = Array.from(root.querySelectorAll<SVGElement>(WOBBLE_SELECTOR));
  const order = [...new Set(targets.map((el) => Number(el.dataset.itemIndex)))].sort((a, b) => a - b);
  let started = 0;
  for (const el of targets) {
    if (typeof el.animate !== 'function') continue;
    running.get(el)?.cancel();
    const delay = order.indexOf(Number(el.dataset.itemIndex)) * WOBBLE_STAGGER_MS;
    running.set(el, el.animate(WOBBLE_KEYFRAMES, { duration: WOBBLE_DURATION_MS, delay, easing: 'ease-out' }));
    started++;
  }
  return started;
}

/** Buildings shake left and right and the surroundings shake the opposite way, together (no stagger). */
export function wobbleCampusAttack(root: ParentNode | null, reducedMotion: boolean): number {
  if (!root || reducedMotion) return 0;
  let started = 0;
  const shake = (selector: string, frames: Keyframe[]) => {
    for (const el of Array.from(root.querySelectorAll<SVGElement>(selector))) {
      if (typeof el.animate !== 'function') continue;
      running.get(el)?.cancel();
      running.set(el, el.animate(frames, { duration: WOBBLE_DURATION_MS, easing: 'ease-out' }));
      started++;
    }
  };
  shake(WOBBLE_SELECTOR, ATTACK_SHAKE_KEYFRAMES);
  shake(WOBBLE_BACK_SELECTOR, REVERSE_ATTACK_SHAKE_KEYFRAMES);
  return started;
}
