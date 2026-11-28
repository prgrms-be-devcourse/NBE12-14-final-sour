import type { ShopItem } from '../api/items.ts';

const clickers = [
  new URL('../assets/campus-items/clicker-01.svg', import.meta.url).href,
  new URL('../assets/campus-items/clicker-02.svg', import.meta.url).href,
  new URL('../assets/campus-items/clicker-03.svg', import.meta.url).href,
  new URL('../assets/campus-items/clicker-04.svg', import.meta.url).href,
  new URL('../assets/campus-items/clicker-05.svg', import.meta.url).href,
  new URL('../assets/campus-items/clicker-06.svg', import.meta.url).href,
  new URL('../assets/campus-items/clicker-07.svg', import.meta.url).href,
  new URL('../assets/campus-items/clicker-08.svg', import.meta.url).href,
  new URL('../assets/campus-items/clicker-09.svg', import.meta.url).href,
  new URL('../assets/campus-items/clicker-10.svg', import.meta.url).href,
] as const;

export function clickerArt(level: number | null): string | undefined {
  return level !== null && Number.isInteger(level) ? clickers[level - 1] : undefined;
}

// V16 seeds these names, not the generated item IDs. This is decoration, not purchase eligibility.
const drinks = new Map([
  ['아메리카노', new URL('../assets/campus-items/iced-americano.svg', import.meta.url).href],
  ['에너지 드링크', new URL('../assets/campus-items/energy-can.svg', import.meta.url).href],
  ['밤샘 각성제', new URL('../assets/campus-items/moon-vial.svg', import.meta.url).href],
]);

export function drinkArt(item: Pick<ShopItem, 'name' | 'type' | 'payType'>): string | undefined {
  return item.type === 'MULTIPLIER' && item.payType === 'CONTRIBUTION' ? drinks.get(item.name) : undefined;
}
