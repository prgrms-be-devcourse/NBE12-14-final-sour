/**
 * Contribution on the campus stats button (T5, docs/261110-1005-mockup_remaining_tasks_plan.md §8; user instruction
 * 2026-11-10): up to 999,999 with thousands commas; from a million on, M (million), B (billion) or T (trillion)
 * with one decimal, truncated so a value never shows larger than it is, and a trailing ".0" dropped.
 * T is the largest unit, so 1,234,500,000,000,000 shows as "1234.5T".
 */
const UNITS = [[1e12, 'T'], [1e9, 'B'], [1e6, 'M']] as const;

export function formatContribution(value: number): string {
  if (value < 0) return `-${formatContribution(-value)}`;
  const whole = Math.floor(value);
  for (const [size, unit] of UNITS) {
    if (whole < size) continue;
    const tenth = size / 10;
    // Integer arithmetic: subtracting the remainder first keeps the division exact.
    const tenths = (whole - (whole % tenth)) / tenth;
    const decimal = tenths % 10;
    return `${(tenths - decimal) / 10}${decimal ? `.${decimal}` : ''}${unit}`;
  }
  return whole.toLocaleString('ko-KR');
}
