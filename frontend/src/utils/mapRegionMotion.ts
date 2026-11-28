import { regionViewport, type MapRegion } from '../data/schoolMapLayout';

const center = (region: MapRegion) => region === '전체' ? { cx: 50, cy: 50 } : regionViewport(region);

/** Motion of the incoming scene in percent of the viewport. 충청 uses its national-map center, not its own crop. */
export function mapRegionMotion(from: MapRegion, to: MapRegion) {
  if (from === to) return undefined;
  const start = center(from);
  const end = center(to);
  const dx = end.cx - start.cx;
  const dy = end.cy - start.cy;
  const distance = Math.hypot(dx, dy) || 1;
  const zoomIn = from === '전체';
  const zoomOut = to === '전체';
  const travel = zoomIn || zoomOut ? 8 : 16;
  const scale = zoomIn ? .9 : zoomOut ? 1.12 : 1;
  return { x: dx / distance * travel, y: dy / distance * travel, scale };
}
