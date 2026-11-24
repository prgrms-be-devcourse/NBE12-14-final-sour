import { mapLayerFit, MAP_DESIGN_FRAME_PX, type Point, type Size } from '../data/schoolMapLayout';

export type MapPinRect = Point & Size;

export interface MapPinGeometry {
  id: number;
  anchor: Point;
  logo: Size;
  label: Size;
  rank?: Size;
  ownSchool: boolean;
  selected: boolean;
}

type Placement = { anchor: Point; logo: MapPinRect; label?: MapPinRect; rank?: MapPinRect };

export function mapPinAnchor(point: Point, area: Size): Point {
  const { scale, cropX, cropY } = mapLayerFit(area);
  return { x: (point.x - cropX) * scale, y: (point.y - cropY) * scale };
}

const overlaps = (a: MapPinRect, b: MapPinRect, gap: number) => a.x < b.x + b.width + gap
  && b.x < a.x + a.width + gap && a.y < b.y + b.height + gap && b.y < a.y + a.height + gap;

function findSpace(size: Size, centers: Point[], bounds: MapPinRect, taken: MapPinRect[], reach: number, gap: number) {
  const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));
  if (size.width > bounds.width || size.height > bounds.height) return undefined;
  for (let radius = 0; radius <= reach; radius += 6) {
    for (const { x, y } of centers) {
      for (let dy = -radius; dy <= radius; dy += 6) for (let dx = -radius; dx <= radius; dx += 6) {
        if (Math.max(Math.abs(dx), Math.abs(dy)) !== radius) continue;
        const box = {
          ...size,
          x: clamp(x + dx - size.width / 2, bounds.x, bounds.x + bounds.width - size.width),
          y: clamp(y + dy - size.height / 2, bounds.y, bounds.y + bounds.height - size.height),
        };
        if (!taken.some((other) => overlaps(box, other, gap))) return box;
      }
    }
  }
  return undefined;
}

/** Logo hit areas stay stable on selection. Fit labels around the logos, not over them. */
export function placeWorkspaceMapPins(area: Size, pins: MapPinGeometry[], avoid: MapPinRect[], bottomInset: number) {
  const bounds = { x: 4, y: 4, width: area.width - 8, height: area.height - bottomInset - 8 };
  const taken = [...avoid];
  const placements = new Map<number, Placement>();
  const reach = Math.max(area.width, area.height);
  const logoOrder = [...pins].sort((a, b) => Number(Boolean(b.rank)) - Number(Boolean(a.rank)) || Number(b.ownSchool) - Number(a.ownSchool));
  const ranked = logoOrder.filter(pin => pin.rank);
  const ordinary = logoOrder.filter(pin => !pin.rank);
  const placeLogo = (pin: MapPinGeometry) => {
    const logo = findSpace(pin.logo, [pin.anchor], bounds, taken, reach, 3);
    if (logo) {
      taken.push(logo);
      placements.set(pin.id, { anchor: pin.anchor, logo });
    }
  };
  ranked.forEach(placeLogo);
  for (const pin of ranked) {
    const placement = placements.get(pin.id);
    if (!placement || !pin.rank) continue;
    placement.rank = placePlate(pin.rank, placement.logo, bounds, taken, reach, true);
  }
  for (const pin of ranked) {
    const placement = placements.get(pin.id);
    if (placement) placement.label = placePlate(pin.label, placement.logo, bounds, taken, reach);
  }
  ordinary.forEach(placeLogo);
  if (placements.size < pins.length && ordinary.length > 0) {
    // Repack densely when free placement fragments the available space.
    const fixed = [...avoid, ...[...placements.entries()].filter(([id]) => ranked.some(pin => pin.id === id))
      .flatMap(([, { logo, rank, label }]) => [logo, ...(rank ? [rank] : []), ...(label ? [label] : [])])];
    packOrdinary(ordinary, bounds, fixed, placements);
    taken.splice(0, taken.length, ...fixed);
  }

  const priority = (pin: MapPinGeometry) => pin.rank ? 3 : Number(pin.ownSchool) + Number(pin.selected);
  for (const pin of [...pins].sort((a, b) => priority(b) - priority(a))) {
    const placement = placements.get(pin.id);
    if (!placement || placement.label) continue;
    placement.label = placePlate(pin.label, placement.logo, bounds, taken, priority(pin) ? reach : 12);
  }
  return placements;
}

function packOrdinary(pins: MapPinGeometry[], bounds: MapPinRect, taken: MapPinRect[], placements: Map<number, Placement>) {
  const stepX = Math.max(...pins.map(pin => pin.logo.width)) + 2;
  const stepY = Math.max(...pins.map(pin => pin.logo.height)) + 2;
  const slots: Point[] = [];
  for (let y = bounds.y; y + stepY - 2 <= bounds.y + bounds.height; y += stepY) {
    for (let x = bounds.x; x + stepX - 2 <= bounds.x + bounds.width; x += stepX) slots.push({ x, y });
  }
  for (const pin of pins) {
    const distance = ({ x, y }: Point) => Math.hypot(x + pin.logo.width / 2 - pin.anchor.x, y + pin.logo.height / 2 - pin.anchor.y);
    const logo = slots.map(slot => ({ ...slot, ...pin.logo })).filter(slot => !taken.some(box => overlaps(slot, box, 2)))
      .sort((a, b) => distance(a) - distance(b))[0];
    placements.delete(pin.id);
    if (logo) {
      placements.set(pin.id, { anchor: pin.anchor, logo });
      taken.push(logo);
    }
  }
}

function placePlate(size: Size, logo: MapPinRect, bounds: MapPinRect, taken: MapPinRect[], reach: number, preferAbove = false) {
  const x = logo.x + logo.width / 2;
  const y = logo.y + logo.height / 2;
  const above = { x, y: logo.y - size.height / 2 - 5 };
  const below = { x, y: logo.y + logo.height + size.height / 2 + 5 };
  const plate = findSpace(size, [preferAbove ? above : below, preferAbove ? below : above,
    { x: logo.x + logo.width + size.width / 2 + 5, y }, { x: logo.x - size.width / 2 - 5, y }], bounds, taken, reach, 4);
  if (plate) taken.push(plate);
  return plate;
}

export function mapFramePercent(point: Point): Point {
  return { x: point.x * MAP_DESIGN_FRAME_PX.width / 100, y: point.y * MAP_DESIGN_FRAME_PX.height / 100 };
}
