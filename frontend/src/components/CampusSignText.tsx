import { CAMPUS_ASSETS } from '../data/campusAssets';
import type { CampusPlacement } from '../data/campusLayouts';
import { formatContribution } from '../utils/formatContribution';

/** What the school signboard shows: the school's first-ranked department (user request 2026-11-27). */
export interface CampusSignInfo {
  name: string;
  score: number;
}

/**
 * The board face of `signboard.png` (22×33, anchor 11,32) in sprite pixels: its middle, usable width and the slope of
 * its edges. The face rises 1 px for every 2 px to the right, so text lies on it with skewY(-atan(1/2)); a flipped
 * sign mirrors the face, so the skew turns the other way while the text itself stays unmirrored.
 */
const BOARD_CENTER: readonly [number, number] = [11.5, 13.5];
const BOARD_WIDTH = 22;
const BOARD_SKEW_DEG = (Math.atan(1 / 2) * 180) / Math.PI;
const NAME_SIZE = 3.4;
const SCORE_SIZE = 2.6;
const LINE_GAP = 1.6;
/** A one-line name smaller than this is split into two lines instead (user request 2026-11-27). */
export const MIN_NAME_SIZE = 2.6;
/** Largest size of a two-line name, so both lines and the score still fit the board's height. */
const TWO_LINE_NAME_SIZE = 3;
const NAME_LINE_GAP = 0.6;

/** Rough text width in ems: Hangul and other wide glyphs take a full em, ASCII about 0.6. */
export function textEms(text: string): number {
  return [...text].reduce((sum, ch) => sum + (ch.charCodeAt(0) < 0x80 ? 0.6 : 1), 0);
}

/** Largest font size up to `max` at which `text` fits the board width: longer text gets smaller. */
export function fitFontSize(text: string, max: number, width = BOARD_WIDTH): number {
  const ems = textEms(text);
  return ems > 0 ? Math.min(max, width / ems) : max;
}

/**
 * A name too long for one line drops its bracketed part: "음악학과(성악전공, …)" shows as "음악학과" (user request
 * 2026-11-27). Short names keep their brackets, and a name that starts with a bracket stays whole.
 */
export function signDisplayName(name: string): string {
  if (fitFontSize(name, NAME_SIZE) >= MIN_NAME_SIZE) return name;
  const cut = name.search(/[(（[［]/);
  return cut > 0 ? name.slice(0, cut).trim() : name;
}

/**
 * The name as it goes on the board: one line, or two when one line would shrink below MIN_NAME_SIZE. Two lines break
 * at a space when there is one, otherwise where the halves are closest in width.
 */
export function splitSignName(name: string): string[] {
  if (fitFontSize(name, NAME_SIZE) >= MIN_NAME_SIZE) return [name];
  const chars = [...name];
  let best = 1;
  let bestScore = Infinity;
  for (let i = 1; i < chars.length; i++) {
    const left = chars.slice(0, i).join('').trimEnd();
    const right = chars.slice(i).join('').trimStart();
    const score = Math.max(textEms(left), textEms(right)) - (chars[i - 1] === ' ' || chars[i] === ' ' ? 0.5 : 0);
    if (score < bestScore) { bestScore = score; best = i; }
  }
  return [chars.slice(0, best).join('').trim(), chars.slice(best).join('').trim()];
}

export const NO_RANK = '순위 없음';

export function formatSignScore(score: number): string {
  return `${formatContribution(score)}점`;
}

/** Where and how the sign text sits for a signboard placement, in world coordinates. */
export function signTextLayout(item: Pick<CampusPlacement, 'asset' | 'x' | 'y' | 'flip'>, info: CampusSignInfo) {
  const [ax, ay] = CAMPUS_ASSETS[item.asset].anchor;
  const dx = BOARD_CENTER[0] - ax;
  const x = item.flip ? item.x - dx : item.x + dx;
  const y = item.y - ay + BOARD_CENTER[1];
  const skew = item.flip ? BOARD_SKEW_DEG : -BOARD_SKEW_DEG;
  // A school whose first department has no score yet shows "순위 없음" alone (user request 2026-11-27).
  if (info.score <= 0) {
    return { x, y, skew, name: { text: NO_RANK, size: fitFontSize(NO_RANK, NAME_SIZE), lines: [{ text: NO_RANK, dy: 0 }] }, score: null };
  }
  const score = formatSignScore(info.score);
  const shown = signDisplayName(info.name);
  const lines = splitSignName(shown);
  const nameSize = lines.length === 1
    ? fitFontSize(shown, NAME_SIZE)
    : Math.min(...lines.map((line) => fitFontSize(line, TWO_LINE_NAME_SIZE)));
  const scoreSize = fitFontSize(score, SCORE_SIZE);
  // Stack the name lines and the score, centered on the board face.
  const nameBlock = lines.length * nameSize + (lines.length - 1) * NAME_LINE_GAP;
  const top = -(nameBlock + LINE_GAP + scoreSize) / 2;
  return {
    x, y, skew,
    name: {
      text: info.name, size: nameSize,
      lines: lines.map((text, i) => ({ text, dy: top + nameSize / 2 + i * (nameSize + NAME_LINE_GAP) })),
    },
    score: { text: score, size: scoreSize, dy: top + nameBlock + LINE_GAP + scoreSize / 2 },
  };
}

/** The top department's name and score written on a signboard, slanted to its board face. */
export function CampusSignText({ item, info }: { item: CampusPlacement; info: CampusSignInfo }) {
  const t = signTextLayout(item, info);
  return (
    <g data-sign-text transform={`translate(${t.x} ${t.y}) skewY(${t.skew})`} className="font-pixel" pointerEvents="none" opacity={item.opacity}>
      <title>{t.score ? `학과 1위 ${t.name.text} ${t.score.text}` : `학과 ${NO_RANK}`}</title>
      <text data-sign-name fontSize={t.name.size} textAnchor="middle" dominantBaseline="central" fill="#3b2414" fontWeight="bold">
        {t.name.lines.map((line) => <tspan key={line.dy} data-sign-name-line x={0} y={line.dy}>{line.text}</tspan>)}
      </text>
      {t.score && <text data-sign-score y={t.score.dy} fontSize={t.score.size} textAnchor="middle" dominantBaseline="central" fill="#5c3a22">{t.score.text}</text>}
    </g>
  );
}
