import { useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { CAMPUS_ASSETS, hdFileFor, isBuilding, type CampusAsset } from '../data/campusAssets';
import { CAMPUS_DEFAULT_ASPECT, CAMPUS_FRAME_WIDTH, CAMPUS_UI_OVERLAYS, campusDeviceRatio, campusView, footprintCores, footprintOf, footprintPolygon, skyHeightOf, type CampusView } from '../data/campusGeometry';
import { CAMPUS_SKY_COLORS, CAMPUS_SUN_POSITION, getCampusLayout, lampGlows, lightOccluders, placementTransform, sortPlacements, terrainGroups, type CampusEffect, type CampusLayout, type CampusPlacement } from '../data/campusLayouts';
import { useCampusTimePhase } from '../hooks/useCampusTimePhase';
import { CAMPUS_STAGES } from '../data/gameRules';
import { CampusSprite } from './CampusSprite';
import { CampusSignText, type CampusSignInfo } from './CampusSignText';
import { WOBBLE_STYLE } from '../utils/campusWobble';
import { MOCK_TOOLS } from '../utils/mockTools';

// Ground shadows suit trees and props. A building's anchor is its bounding-box bottom-center, so an
// ellipse there floats beside the footprint; buildings get no shadow unless a placement asks for one.
const SHADOW_MAX_WIDTH = 60;
/** The far forest's last 10 rows are a flat grass band; the world ground covers them. */
const FOREST_GROUND_ROWS = 10;
/** Terrain outline stroke (world px), centered on the edge: half of it shows outside the fill. */
const TERRAIN_EDGE_WIDTH = 4;

// Fixed star field (frame units) so night skies do not flicker between renders.
const STARS = Array.from({ length: 28 }, (_, i) => ({ x: (i * 97 + 13) % CAMPUS_FRAME_WIDTH, y: (i * 53 + 7) % 44, big: i % 5 === 0 }));

const DEBUG = MOCK_TOOLS && typeof window !== 'undefined' && new URLSearchParams(window.location.search).has('campusDebug');

interface FrameSize {
  /** Height / width. */
  aspect: number;
  /** CSS px width; 0 before layout. */
  width: number;
}

/**
 * Size of the element, measured so the viewBox matches the frame exactly (no hidden crop) and the
 * HD scale follows its device ratio. Falls back to the in-game frame's aspect and width 0
 * before layout and where ResizeObserver is missing.
 */
function useFrameSize<T extends Element>() {
  const ref = useRef<T>(null);
  const [size, setSize] = useState<FrameSize>({ aspect: CAMPUS_DEFAULT_ASPECT, width: 0 });
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const update = () => {
      const { width, height } = el.getBoundingClientRect();
      if (width > 0 && height > 0) {
        setSize((prev) => (Math.abs(prev.aspect - height / width) > 0.001 || Math.abs(prev.width - width) > 0.5 ? { aspect: height / width, width } : prev));
      }
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, size] as const;
}

/**
 * Device ratios (device px per 1x px; 0 before layout) for world sprites (zoomed) and sky sprites
 * (drawn in frame units). Each layer picks its own HD scale from these.
 */
function useDeviceRatios(layout: CampusLayout | undefined, cssWidth: number) {
  const dpr = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1;
  const zoom = layout?.camera.zoom ?? 1;
  return { world: campusDeviceRatio(cssWidth, dpr, zoom), sky: campusDeviceRatio(cssWidth, dpr, 1) };
}

const viewBoxOf = (v: CampusView) => `${v.x} ${v.y} ${v.width} ${v.height}`;

/**
 * One sprite or light mask in its 1x box (docs/261108-1014-campus_hd_default_plan.md 4-2). With an
 * HD copy the browser smooths it down into the box; HD files are exactly 1x × scale, so the box
 * never distorts them. Without one, or when the HD file fails to load, the 1x file draws
 * nearest-neighbor.
 */
function SceneImage({ src, hd, item, width, height, data }: {
  src: string; hd?: string; item: CampusPlacement; width: number; height: number;
  data: { 'data-asset': string } | { 'data-lights': string };
}) {
  const [failed, setFailed] = useState<string>();
  if (hd && failed !== hd) {
    return (
      <image {...data} data-source="hd" href={hd} width={width} height={height} transform={placementTransform(item)} opacity={item.opacity} style={{ imageRendering: 'auto' }}
        onError={() => { console.warn(`[campus] failed to load ${hd}; showing the 1x file`); setFailed(hd); }} />
    );
  }
  return <image {...data} href={src} width={width} height={height} transform={placementTransform(item)} opacity={item.opacity} style={{ imageRendering: 'pixelated' }} />;
}

function Sprite({ item, ratio }: { item: CampusPlacement; ratio: number }) {
  const asset: CampusAsset = CAMPUS_ASSETS[item.asset];
  return <SceneImage data={{ 'data-asset': item.asset }} src={asset.url} hd={hdFileFor(asset, ratio * (item.scale ?? 1))?.url} item={item} width={asset.width} height={asset.height} />;
}

/** Firework rings as [radius share, particle count, particle size]: a big outer shell and a white-hot core. */
const FIREWORK_RINGS = [[1, 24, 4], [0.62, 14, 3], [0.3, 8, 2]] as const;

function Fireworks({ effect }: { effect: Extract<CampusEffect, { kind: 'fireworks' }> }) {
  return (
    <g data-effect="fireworks">
      {effect.bursts.map((b, i) => (
        // fill-box keeps the scale-in animation centered on the burst inside the scaled sky layer.
        <g key={i} data-burst className="campus-firework" style={{ animationDelay: `${i * 0.7}s`, transformBox: 'fill-box', transformOrigin: 'center' }}>
          {FIREWORK_RINGS.flatMap(([share, count, size], ring) => Array.from({ length: count }, (_, k) => {
            const angle = ((k + ring * 0.5) / count) * Math.PI * 2;
            const half = size / 2;
            return <rect key={`${ring}-${k}`} x={Math.round(b.x + Math.cos(angle) * b.r * share - half)} y={Math.round(b.y + Math.sin(angle) * b.r * share - half)} width={size} height={size} fill={ring === 2 ? '#fff6d0' : b.color} />;
          }))}
          <rect x={b.x - 2} y={b.y - 2} width={4} height={4} fill="#ffffff" />
        </g>
      ))}
    </g>
  );
}

/** Sky band in frame units, scaled back from the camera zoom so it never magnifies. */
function Sky({ layout, view, uid, phase, ratio }: { layout: CampusLayout; view: CampusView; uid: string; phase: keyof typeof CAMPUS_SKY_COLORS; ratio: number }) {
  const sky = skyHeightOf(layout.camera);
  const fireworks = layout.effects?.find((e): e is Extract<CampusEffect, { kind: 'fireworks' }> => e.kind === 'fireworks');
  return (
    <g data-sky={phase} transform={`translate(${view.x} ${view.skyTop}) scale(${1 / layout.camera.zoom})`}>
      <rect width={CAMPUS_FRAME_WIDTH} height={sky + FOREST_GROUND_ROWS} fill={`url(#${uid}-sky)`} />
      {phase === 'night' && STARS.map((star, i) => (
        <rect key={i} data-star x={star.x} y={star.y} width={star.big ? 2 : 1} height={star.big ? 2 : 1} fill="#fff8d8" />
      ))}
      <Sprite item={{ asset: phase === 'night' ? 'moon' : 'sun', ...(layout.sun ?? CAMPUS_SUN_POSITION) }} ratio={ratio} />
      {layout.clouds.map((cloud, i) => <Sprite key={i} item={cloud} ratio={ratio} />)}
      <Sprite item={{ asset: 'horizon-forest', x: CAMPUS_FRAME_WIDTH / 2, y: sky + FOREST_GROUND_ROWS }} ratio={ratio} />
      {/* Bursts hang above the hills, so they draw over the far forest instead of behind it. */}
      {fireworks && <Fireworks effect={fireworks} />}
    </g>
  );
}

/** Footprints, cores and in-game UI boxes, shown with ?campusDebug for placement work. */
function DebugOverlay({ layout, view }: { layout: CampusLayout; view: CampusView }) {
  const poly = (pts: readonly (readonly [number, number])[]) => pts.map(([x, y]) => `${x},${y}`).join(' ');
  return (
    <g data-campus-debug fill="none" strokeWidth={1 / layout.camera.zoom}>
      {layout.items.map((item, i) => {
        const f = footprintOf(item);
        return f ? <g key={i}><polygon points={poly(footprintPolygon(f))} stroke="#ff2d55" />{footprintCores(f).map((core, j) => <polygon key={j} points={poly(core)} stroke="#ff2d55" strokeDasharray="2 2" />)}</g>
          : <rect key={i} x={item.x - 1} y={item.y - 1} width={2} height={2} fill="#ff2d55" />;
      })}
      {Object.entries(CAMPUS_UI_OVERLAYS).map(([id, [l, t, r, b]]) => (
        <rect key={id} x={view.x + l * view.width} y={view.y + t * view.height} width={(r - l) * view.width} height={(b - t) * view.height} fill="#2d7bff" opacity={0.25} />
      ))}
    </g>
  );
}

// Composes a campus level from individual assets (plan 5-6) through the level's camera. Lv.1-10
// all have layouts; a level outside that range falls back to the level screenshot.
export function CampusScene({ level, className = '', variant = 'day', labelPrefix, sign }: {
  level: number; className?: string; variant?: 'day' | 'night';
  /** e.g. the visited school's name, prepended to the accessible name. */ labelPrefix?: string;
  /** The school's first-ranked department, written on the signboard. Without it the board stays blank. */
  sign?: CampusSignInfo | null;
}) {
  const layout = getCampusLayout(level);
  if (!layout) return <CampusSprite level={level} className={className} variant={variant} />;
  const index = Math.max(0, Math.min(9, level - 1));
  return (
    <CampusLayoutScene layout={layout} className={className} level={level} sign={sign}
      label={`${labelPrefix ? `${labelPrefix} ` : ''}캠퍼스 Lv.${index + 1} ${CAMPUS_STAGES[index].name}`} />
  );
}

/**
 * Draws any composed scene: a campus level (CampusScene) or a scene outside the level table, such as the
 * onboarding title (docs/261110-0837-onboarding_title_screen_plan.md). `phase` pins the sky instead of
 * following the time of day.
 */
export function CampusLayoutScene({ layout, label, className = '', level, phase: pinned, testId = 'campus-scene', sign }: {
  layout: CampusLayout; label: string; className?: string; level?: number; phase?: keyof typeof CAMPUS_SKY_COLORS; testId?: string;
  sign?: CampusSignInfo | null;
}) {
  const rawId = useId();
  const ambience = useCampusTimePhase();
  const phase = pinned ?? ambience.phase;
  const [ref, { aspect, width }] = useFrameSize<SVGSVGElement>();
  // Terrain textures tile under patterns and stay 1x nearest-neighbor; only sprites have HD copies.
  const ratios = useDeviceRatios(layout, width);

  const uid = `campus-${rawId.replace(/[^a-zA-Z0-9_-]/g, '')}`;
  const textures = [...new Set([layout.ground, ...layout.terrain.map((t) => t.texture)])];
  const items = sortPlacements(layout.items);
  const view = campusView(layout.camera, aspect);
  const glow = layout.effects?.find((e) => e.kind === 'festival-glow');

  return (
    <svg
      ref={ref}
      viewBox={viewBoxOf(view)}
      preserveAspectRatio="xMidYMin slice"
      role="img"
      aria-label={label}
      data-testid={testId}
      data-level={level}
      className={`pixelated ${className}`}
      style={{ imageRendering: 'pixelated' }}
    >
      <defs>
        {textures.map((id) => {
          const tex = CAMPUS_ASSETS[id];
          return (
            <pattern key={id} id={`${uid}-${id}`} patternUnits="userSpaceOnUse" width={tex.width} height={tex.height}>
              <image href={tex.url} width={tex.width} height={tex.height} />
            </pattern>
          );
        })}
        <linearGradient id={`${uid}-sky`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={CAMPUS_SKY_COLORS[phase][0]} />
          <stop offset="1" stopColor={CAMPUS_SKY_COLORS[phase][1]} />
        </linearGradient>
      </defs>
      <Sky layout={layout} view={view} uid={uid} phase={phase} ratio={ratios.sky} />
      <rect data-ground x={view.x} y={layout.camera.horizon} width={view.width} height={view.y + view.height - layout.camera.horizon} fill={`url(#${uid}-${layout.ground})`} />
      {/* Each texture draws all its outlines, then all its fills, so joined pieces show no seam (lead feedback F2). */}
      {terrainGroups(layout.terrain).map((group) => (
        <g key={group.texture} data-terrain-group={group.texture}>
          {group.pieces.map((t, i) => (
            <polygon key={`edge-${i}`} data-terrain-edge={t.texture} points={t.points.map(([x, y]) => `${x},${y}`).join(' ')}
              fill={t.edgeColor} stroke={t.edgeColor} strokeWidth={TERRAIN_EDGE_WIDTH} strokeLinejoin="miter" shapeRendering="crispEdges" />
          ))}
          {group.pieces.map((t, i) => (
            <polygon key={`fill-${i}`} data-terrain={t.texture} points={t.points.map(([x, y]) => `${x},${y}`).join(' ')}
              fill={`url(#${uid}-${t.texture})`} shapeRendering="crispEdges" />
          ))}
        </g>
      ))}
      {glow && <ellipse data-effect="festival-glow" cx={glow.x} cy={glow.y} rx={glow.rx} ry={glow.ry} fill="#ffd34d" opacity={0.28} className="campus-festival-glow" />}
      {items.map((item, i) => {
        const asset = CAMPUS_ASSETS[item.asset];
        const shadowRx = Math.max(3, Math.round(asset.width * 0.38));
        return (
          // Non-building items (with their shadow) wobble opposite to the buildings on an attack (utils/campusWobble).
          <g key={i} {...(isBuilding(asset) ? {} : { 'data-wobble-back': item.asset, style: WOBBLE_STYLE })}>
            {(item.shadow ?? (asset.width <= SHADOW_MAX_WIDTH && !asset.footprint)) && (
              <ellipse cx={item.x} cy={item.y - 1} rx={shadowRx} ry={Math.max(1, Math.round(shadowRx * 0.32))} fill="#10240c" opacity={0.22} />
            )}
            {/* Only buildings wobble on a tap (T6, Q8); the wrapper is a no-op until utils/campusWobble animates it. */}
            {isBuilding(asset)
              ? <g data-wobble={item.asset} data-item-index={i} style={WOBBLE_STYLE}><Sprite item={item} ratio={ratios.world} /></g>
              : <Sprite item={item} ratio={ratios.world} />}
            {sign && item.asset === 'signboard' && <CampusSignText item={item} info={sign} />}
          </g>
        );
      })}
      {DEBUG && <DebugOverlay layout={layout} view={view} />}
    </svg>
  );
}

/**
 * Night window lights for a composed level: each building's light mask at the building's position,
 * plus the layout's glow points, through the same camera as CampusScene. Rendered by CampusAmbience
 * above the night tint so the lights are not darkened. Returns null for levels without a layout.
 */
export function CampusSceneLights({ level, opacity, twinkle }: { level: number; opacity: number; twinkle: boolean }) {
  const [ref, { aspect, width }] = useFrameSize<SVGSVGElement>();
  const layout = getCampusLayout(level);
  const { world: ratio } = useDeviceRatios(layout, width);
  const lit = useMemo(() => {
    if (!layout) return [];
    // The scene's draw index of each lit building, so its lights wobble with it (T6).
    const order = sortPlacements(layout.items);
    return lightOccluders(layout.items).map((entry) => ({ ...entry, index: order.indexOf(entry.item) }));
  }, [layout]);
  const uid = `lights-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;
  if (!layout) return null;
  // Lamps glow at their lantern (LAMP_LIGHTS).
  const lamps = lampGlows(layout.items);
  // Opacity sits on the wrapper because the twinkle animation drives the inner element's opacity.
  return (
    <div data-ambience-layer="window" className="absolute inset-0 pointer-events-none" style={{ opacity }}>
      <svg ref={ref} viewBox={viewBoxOf(campusView(layout.camera, aspect))} preserveAspectRatio="xMidYMin slice" aria-hidden="true"
        className={`w-full h-full ${twinkle ? 'campus-window-twinkle' : ''}`}
        style={{ imageRendering: 'pixelated', filter: 'drop-shadow(0 0 2px #ffd279)' }}>
        <defs>
          {/* Keeps alpha, zeroes color: a sprite's black silhouette, which hides the lights under it in a mask. */}
          <filter id={`${uid}-silhouette`}><feColorMatrix type="matrix" values="0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 0" /></filter>
          {lit.map(({ occluders }, i) => occluders.length > 0 && (
            <mask key={i} id={`${uid}-m${i}`} maskUnits="userSpaceOnUse" x={-10000} y={-10000} width={20000} height={20000}>
              <rect x={-10000} y={-10000} width={20000} height={20000} fill="#fff" />
              {occluders.map((o, k) => {
                const a = CAMPUS_ASSETS[o.asset];
                return <image key={k} data-occluder={o.asset} href={a.url} width={a.width} height={a.height} transform={placementTransform(o)} opacity={o.opacity} filter={`url(#${uid}-silhouette)`} />;
              })}
            </mask>
          ))}
        </defs>
        {lit.map(({ item, occluders, index }, i) => {
          const asset: CampusAsset = CAMPUS_ASSETS[item.asset];
          // The mask stays on the outer group so the occluders in front do not move with the lights.
          return (
            <g key={i} mask={occluders.length > 0 ? `url(#${uid}-m${i})` : undefined}>
              <g data-wobble-lights={item.asset} data-item-index={index} style={WOBBLE_STYLE}>
                <SceneImage data={{ 'data-lights': item.asset }} src={asset.lightsUrl!} hd={hdFileFor(asset, ratio * (item.scale ?? 1))?.lightsUrl} item={item} width={asset.width} height={asset.height} />
              </g>
            </g>
          );
        })}
        {lamps.map((g, i) => <circle key={`l${i}`} data-glow={g.asset} cx={g.x} cy={g.y} r={4} fill="#ffd279" opacity={0.85} />)}
        {layout.glows?.map((g, i) => <circle key={`g${i}`} data-glow cx={g.x} cy={g.y} r={g.r} fill="#ffd279" opacity={0.85} />)}
      </svg>
    </div>
  );
}
