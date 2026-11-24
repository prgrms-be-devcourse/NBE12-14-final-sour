import React from 'react';
import { Clock, Minus, Plus } from 'lucide-react';
import { soundManager } from '../utils/sound.ts';

/** 1시간 단위로 범위 안에 맞춘다. 범위가 비어 있으면(min > max) min을 돌려준다. */
export function clampDurationHours(hours: number, min: number, max: number): number {
  if (!Number.isFinite(hours)) return min;
  return Math.min(Math.max(Math.round(hours), min), Math.max(min, max));
}

/** 24시간 이상이면 일·시간 보조 표기를 붙인다. 예: 6 → '6시간', 48 → '48시간 (2일)', 30 → '30시간 (1일 6시간)' */
export function formatDurationHours(hours: number): string {
  if (hours < 24) return `${hours}시간`;
  const days = Math.floor(hours / 24);
  const rest = hours % 24;
  return `${hours}시간 (${days}일${rest ? ` ${rest}시간` : ''})`;
}

interface DurationPickerProps {
  /** 그룹 이름이자 버튼 이름의 접두어 (예: '경기 진행 기간') */
  label: string;
  value: number;
  min: number;
  max: number;
  presets: readonly number[];
  onChange: (hours: number) => void;
}

/**
 * 기간 선택: 프리셋 + 1시간 단위 스테퍼. 직접 숫자 입력은 두지 않아 범위 밖 값이 UI에서 생기지 않는다.
 * 연합(6~72h)과 대결(1~24h)이 함께 쓴다 (.agents/01-PLAN/261112-1206-alliance_battle_rule_update_plan.md §2).
 */
export const DurationPicker: React.FC<DurationPickerProps> = ({ label, value, min, max, presets, onChange }) => {
  const visiblePresets = presets.filter((h) => h >= min && h <= max);
  const change = (hours: number) => {
    const next = clampDurationHours(hours, min, max);
    if (next === value) return;
    soundManager.playTabSound();
    onChange(next);
  };

  return (
    <div role="group" aria-label={label} className="rpg-box p-2.5 bg-[#ffffff] border-2 border-[#261608] flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <span className="font-pixel text-xs font-bold text-[#261608] flex items-center gap-1">
          <Clock className="w-3.5 h-3.5 text-[#8b5327]" />
          <span>{label}</span>
        </span>
        <span className="font-pixel text-[10px] text-[#8b5327]">
          허용: {min}h ~ {max}h · 1시간 단위
        </span>
      </div>

      {visiblePresets.length > 0 && (
        <div className="grid gap-1.5" style={{ gridTemplateColumns: `repeat(${visiblePresets.length}, minmax(0, 1fr))` }}>
          {visiblePresets.map((hours) => {
            const selected = hours === value;
            return (
              <button
                key={hours}
                type="button"
                aria-pressed={selected}
                onClick={() => change(hours)}
                className={`h-11 border-2 font-pixel text-xs font-bold cursor-pointer transition-colors ${
                  selected
                    ? 'bg-[#fef08a] border-[#261608] shadow-[2px_2px_0px_#261608]'
                    : 'bg-[#ffffff] border-[#261608]/40 hover:bg-[#fffbeb]'
                }`}
              >
                {hours}h
              </button>
            );
          })}
        </div>
      )}

      <div className="flex items-center gap-2">
        <button
          type="button"
          aria-label={`${label} 1시간 줄이기`}
          disabled={value <= min}
          onClick={() => change(value - 1)}
          className="w-11 h-11 shrink-0 border-2 border-[#261608] bg-[#fdf5df] flex items-center justify-center cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
        >
          <Minus className="w-4 h-4" />
        </button>
        <output
          aria-live="polite"
          aria-label={`${label} 선택값`}
          className="flex-1 h-11 border-2 border-[#261608] bg-[#fdf5df] flex items-center justify-center font-pixel text-sm font-black text-[#261608]"
        >
          {formatDurationHours(value)}
        </output>
        <button
          type="button"
          aria-label={`${label} 1시간 늘리기`}
          disabled={value >= max}
          onClick={() => change(value + 1)}
          className="w-11 h-11 shrink-0 border-2 border-[#261608] bg-[#fdf5df] flex items-center justify-center cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
        >
          <Plus className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
};
