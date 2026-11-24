/**
 * The national map drawn as layers (T7, docs/261110-1005-mockup_remaining_tasks_plan.md §10-1, decision Q9 option C):
 * a procedural sea, then land, islands, drifting clouds and the static northern cloud bank, all cut from the original
 * JPG by scripts/map/split_layers.py. It replaces `<img className="object-cover">` inside a MapLayer frame, so the
 * image box below reproduces that object-cover box and school and cluster percentages keep pointing at the same spot.
 * With reduced motion the sea glints and clouds stand still (index.css), clouds at their spots in the original.
 */
import { MAP_DESIGN_FRAME_PX } from '../data/schoolMapLayout.ts';
import layers from '../assets/images/map/layers.json';
import landUrl from '../assets/images/map/land.png';
import islandsUrl from '../assets/images/map/islands.png';
import cloudBankUrl from '../assets/images/map/cloud-bank-north.png';
import cloudsRightUrl from '../assets/images/map/clouds-right.png';
import cloudsLeftUrl from '../assets/images/map/clouds-left.png';
import cloudsBottomRightUrl from '../assets/images/map/clouds-bottom-right.png';

const CLOUD_URLS: Record<string, string> = {
  'clouds-right': cloudsRightUrl,
  'clouds-left': cloudsLeftUrl,
  'clouds-bottom-right': cloudsBottomRightUrl,
};

/** One full pass across the map, west to east, then the cloud comes back in from the west (plan: 60-120s). */
export const CLOUD_DRIFT_SECONDS: Record<string, number> = {
  'clouds-right': 72,
  'clouds-left': 96,
  'clouds-bottom-right': 120,
};

export const MAP_SEA_COLOR = `rgb(${layers.sea.join(', ')})`;

/** The source image's object-cover box in the design frame, in percent of the frame. */
export const MAP_IMAGE_BOX = (() => {
  const { width: fw, height: fh } = MAP_DESIGN_FRAME_PX;
  const s = Math.max(fw / layers.width, fh / layers.height);
  return {
    left: ((fw - layers.width * s) / 2 / fw) * 100,
    top: ((fh - layers.height * s) / 2 / fh) * 100,
    width: ((layers.width * s) / fw) * 100,
    height: ((layers.height * s) / fh) * 100,
  };
})();

export interface CloudDrift {
  id: string;
  url: string;
  /** Row box in percent of the image box. */
  top: number;
  height: number;
  /** Sprite width in percent of the image box. */
  width: number;
  /** translateX of the row, in percent of the image box: at rest (the source spot), and the drift's ends. */
  rest: number;
  from: number;
  to: number;
  seconds: number;
  /** Negative delay that starts the drift at the rest spot. */
  delaySeconds: number;
}

export const MAP_CLOUDS: readonly CloudDrift[] = layers.clouds.map((cloud) => {
  const width = (cloud.width / layers.width) * 100;
  const rest = (cloud.x / layers.width) * 100;
  const from = -width;
  const to = 100;
  const seconds = CLOUD_DRIFT_SECONDS[cloud.id];
  return {
    id: cloud.id,
    url: CLOUD_URLS[cloud.id],
    top: (cloud.y / layers.height) * 100,
    height: (cloud.height / layers.height) * 100,
    width,
    rest,
    from,
    to,
    seconds,
    delaySeconds: -((rest - from) / (to - from)) * seconds,
  };
});

/** Glint tile in design pixels: short light dashes like the sparkles drawn in the original sea. */
const GLINT_TILE = 48;
function glintTile(dashes: readonly [number, number, number][]): string {
  const rects = dashes
    .map(([x, y, w]) => `<rect x='${x}' y='${y}' width='${w}' height='1' fill='%23e6f6fe'/><rect x='${x + 1}' y='${y - 1}' width='${Math.max(1, w - 2)}' height='1' fill='%23ffffff'/>`)
    .join('');
  return `url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' width='${GLINT_TILE}' height='${GLINT_TILE}' shape-rendering='crispEdges'>${rects}</svg>")`;
}
/** Two glint frames; with the dark frame between them the sea shimmers in three steps (index.css). */
export const SEA_GLINT_TILES = [
  glintTile([[5, 9, 4], [29, 21, 3], [14, 38, 5], [40, 44, 3]]),
  glintTile([[21, 4, 3], [38, 15, 5], [7, 27, 3], [27, 40, 4]]),
] as const;

export function MapArt({ alt }: { alt: string }) {
  const box = MAP_IMAGE_BOX;
  return (
    <div role="img" aria-label={alt} data-map-art className="absolute inset-0">
      {/* The sea reaches a frame past every edge so a zoomed or moved camera never shows its border (plan §10-1-3). */}
      <div data-map-sea className="absolute -inset-full" style={{ backgroundColor: MAP_SEA_COLOR }}>
        {SEA_GLINT_TILES.map((tile, i) => (
          <div
            key={i}
            data-map-sea-glint={i}
            className={`absolute inset-0 map-sea-glint ${i === 1 ? 'map-sea-glint-late' : ''}`}
            style={{ backgroundImage: tile, backgroundSize: `${GLINT_TILE}px ${GLINT_TILE}px` }}
          />
        ))}
      </div>
      <div
        data-map-image-box
        className="absolute"
        style={{ left: `${box.left}%`, top: `${box.top}%`, width: `${box.width}%`, height: `${box.height}%` }}
      >
        {/* Land first: tests/e2e/map-layout.mjs reads the first <img> of a map area as the source image box. */}
        <img src={landUrl} alt="" aria-hidden data-map-layer-art="land" className="absolute inset-0 w-full h-full pixelated" draggable={false} />
        <img src={islandsUrl} alt="" aria-hidden data-map-layer-art="islands" className="absolute inset-0 w-full h-full pixelated" draggable={false} />
        {MAP_CLOUDS.map((cloud) => (
          <div
            key={cloud.id}
            data-map-cloud={cloud.id}
            className="absolute left-0 w-full map-cloud-drift"
            style={{
              top: `${cloud.top}%`,
              height: `${cloud.height}%`,
              transform: `translateX(${cloud.rest}%)`,
              animationDuration: `${cloud.seconds}s`,
              animationDelay: `${cloud.delaySeconds}s`,
              ['--drift-from' as string]: `${cloud.from}%`,
              ['--drift-to' as string]: `${cloud.to}%`,
            }}
          >
            <img src={cloud.url} alt="" aria-hidden className="absolute left-0 top-0 h-full pixelated" style={{ width: `${cloud.width}%` }} draggable={false} />
          </div>
        ))}
        <img src={cloudBankUrl} alt="" aria-hidden data-map-layer-art="cloud-bank-north" className="absolute inset-0 w-full h-full pixelated" draggable={false} />
      </div>
    </div>
  );
}
