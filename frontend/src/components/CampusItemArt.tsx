import { Mouse } from 'lucide-react';
import { clickerArt } from '../data/campusItemArt.ts';

type SpriteSize = 24 | 48;

/** Product names and clicker levels are already visible beside these illustrations. */
export function CampusItemSprite({ src, size }: { src: string; size: SpriteSize }) {
  return <img src={src} alt="" width={size} height={size} className="shrink-0" />;
}

export function ClickerArt({ level, size = 24 }: { level: number | null; size?: SpriteSize }) {
  const src = clickerArt(level);
  return src ? <CampusItemSprite src={src} size={size} />
    : <Mouse size={size} aria-hidden="true" className="shrink-0 text-[#8b5327]" />;
}
