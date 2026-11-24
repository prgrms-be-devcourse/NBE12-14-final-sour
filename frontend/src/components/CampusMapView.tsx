import React from 'react';
import { MapPin, CheckCircle, Lock, Star } from 'lucide-react';
import { CAMPUS_STAGES } from '../data/gameRules';

interface CampusMapViewProps {
  currentLevel: number;
  currentScore: number;
  onBackToCampus: () => void;
  onOpenKoreaMap?: () => void;
}

export const CampusMapView: React.FC<CampusMapViewProps> = ({
  currentLevel,
  currentScore,
  onBackToCampus,
  onOpenKoreaMap,
}) => {
  const milestones = CAMPUS_STAGES;

  const currentMilestone = milestones[currentLevel - 1] || milestones[0];
  const nextMilestone = milestones[currentLevel] || null;
  const nextReq = nextMilestone ? nextMilestone.requiredScore : 20000000;
  const prevReq = currentMilestone.requiredScore;
  const progressPercent = nextMilestone
    ? Math.min(100, Math.max(0, Math.floor(((currentScore - prevReq) / (nextReq - prevReq)) * 100)))
    : 100;

  return (
    <div className="w-full flex flex-col gap-2 animate-fadeIn select-none">
      {/* Header Banner */}
      <div className="rpg-box p-2.5 flex flex-col gap-2">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 bg-[#4ade80] border-2 border-[#261608] flex items-center justify-center shadow-[1px_1px_0px_#261608]">
            <MapPin className="w-5 h-5 text-[#261608]" />
          </div>
          <div>
            <h2 className="font-pixel text-xs sm:text-sm font-bold text-[#261608]">
              캠퍼스 성장 로드맵 (10단계)
            </h2>
            <p className="text-[10px] text-[#8b5327] font-mono">
              현재 성장 단계: Lv.{currentLevel} / Lv.10
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2 [&>button]:whitespace-nowrap [&>button]:shrink-0 [&>button]:min-h-6">
          {onOpenKoreaMap && (
            <button
              onClick={onOpenKoreaMap}
              className="pixel-plaque plaque-yellow text-[10px] font-pixel font-bold px-1.5 py-1 text-[#261608] cursor-pointer"
            >
              전국 지도
            </button>
          )}
          <button
            onClick={onBackToCampus}
            className="pixel-plaque plaque-parchment text-xs font-pixel font-bold px-2 py-1 text-[#261608] cursor-pointer"
          >
            돌아가기
          </button>
        </div>
      </div>

      {/* Current Level Status Card */}
      <div className="rpg-box p-2.5 flex flex-col gap-1.5 bg-[#fef9c3]">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <Star className="w-4 h-4 text-[#facc15] fill-[#facc15]" />
            <span className="font-pixel text-xs font-bold text-[#261608]">
              현재 캠퍼스: Lv.{currentLevel} {currentMilestone.name}
            </span>
          </div>
          <span className="text-[10px] font-mono font-bold text-white bg-[#22c55e] border border-[#261608] px-1 shadow-[1px_1px_0px_#261608]">
            운영 중
          </span>
        </div>

        <p className="text-[10px] text-[#8b5327] font-mono">
          {currentMilestone.description}
        </p>

        {/* Progress to next level */}
        {nextMilestone && (
          <div className="flex flex-col gap-1 mt-0.5">
            <div className="flex items-center justify-between text-[10px] font-mono font-bold text-[#8b5327]">
              <span>다음 Lv.{nextMilestone.level}: {nextMilestone.requiredScore.toLocaleString('ko-KR')}점</span>
              <span>{progressPercent}%</span>
            </div>
            <div className="w-full h-2.5 bg-[#faecc7] border-2 border-[#261608] shadow-[1px_1px_0px_#261608] p-0.5">
              <div
                className="h-full bg-[#4ade80] border border-[#261608]"
                style={{ width: `${progressPercent}%` }}
              />
            </div>
          </div>
        )}
      </div>

      {/* 10-Level Timeline List */}
      <div className="rpg-box p-1.5 flex flex-col gap-1.5 max-h-[290px] overflow-y-auto">
        {milestones.map((ms) => {
          const isDone = ms.level < currentLevel;
          const isCurrent = ms.level === currentLevel;

          return (
            <div
              key={ms.level}
              role="group"
              aria-label={`캠퍼스 Lv.${ms.level} · ${isDone ? '달성' : isCurrent ? '현재' : '미해금'}`}
              className={`p-2 border-2 transition-all ${
                isCurrent
                  ? 'bg-[#fef08a] border-[#261608] shadow-[2px_2px_0px_#261608]'
                  : isDone
                  ? 'bg-[#ffffff] border-[#261608]/40'
                  : 'bg-stone-100 border-stone-300 opacity-60'
              }`}
            >
              <div className="flex items-center justify-between mb-1">
                <div className="flex items-center gap-1.5">
                  {isDone ? (
                    <CheckCircle className="w-3.5 h-3.5 text-[#22c55e] shrink-0" />
                  ) : isCurrent ? (
                    <Star className="w-3.5 h-3.5 text-[#facc15] fill-[#facc15] shrink-0" />
                  ) : (
                    <Lock className="w-3.5 h-3.5 text-stone-400 shrink-0" />
                  )}
                  <span className="font-pixel text-xs font-bold text-[#261608]">
                    Lv.{ms.level} {ms.name}
                  </span>
                </div>
                <span className="font-mono text-[9px] text-[#8b5327] font-bold">
                  {ms.requiredScore.toLocaleString('ko-KR')}점 · {isDone ? '달성' : isCurrent ? '현재' : '미해금'}
                </span>
              </div>

              <div className="flex items-center gap-1 flex-wrap pl-5">
                {ms.features.map((feat) => (
                  <span
                    key={feat}
                    className="text-[9px] font-pixel text-[#261608] bg-[#faecc7] border border-[#261608]/40 px-1 py-0.2"
                  >
                    {feat}
                  </span>
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

