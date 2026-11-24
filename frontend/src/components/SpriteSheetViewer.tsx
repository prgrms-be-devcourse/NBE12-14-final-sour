import React, { useState } from 'react';
import { CAMPUS_SHOTS } from '../data/campusShots';
import { Sparkles, Check, ChevronRight, Layers, Eye, Trophy } from 'lucide-react';
import { soundManager } from '../utils/sound';
import { CAMPUS_STAGES } from '../data/gameRules';

interface SpriteSheetViewerProps {
  currentLevel: number;
  onSelectStageForGame?: (level: number) => void;
  onGoToGame?: () => void;
}

export const SpriteSheetViewer: React.FC<SpriteSheetViewerProps> = ({
  currentLevel = 4,
  onSelectStageForGame,
  onGoToGame,
}) => {
  const [selectedStage, setSelectedStage] = useState<number>(currentLevel);

  // One real in-game screenshot per stage (docs/261110-0747-campus_level_shots_plan.md).
  const stageDescriptions = CAMPUS_STAGES.map((stage) => ({
    ...stage,
    title: stage.name,
    category: `Lv.${stage.level} 캠퍼스`,
    desc: stage.description,
    scoreReq: `${stage.requiredScore.toLocaleString('ko-KR')}점`,
  }));

  const currentStageInfo = stageDescriptions[selectedStage - 1] || stageDescriptions[0];

  return (
    <div className="w-full max-w-6xl flex flex-col items-center select-none py-2 animate-fadeIn">
      {/* Title & Description Header */}
      <div className="w-full flex flex-col sm:flex-row items-center justify-between gap-3 mb-3 px-2">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 bg-[#facc15] border-2 border-[#261608] flex items-center justify-center shadow-[2px_2px_0px_#261608]">
            <Layers className="w-5 h-5 text-[#261608]" />
          </div>
          <div>
            <div className="flex items-center gap-1.5">
              <h2 className="font-pixel text-sm sm:text-base font-black text-[#261608]">
                전국 대학 공통 10단계 캠퍼스 성장 단계 (실제 게임 화면)
              </h2>
              <span className="text-[10px] font-mono text-[#ffffff] bg-[#22c55e] border border-[#261608] px-1 py-0.2 shadow-[1px_1px_0px_#261608]">
                Lv.1 ~ Lv.10
              </span>
            </div>
            <p className="font-pixel text-[11px] text-[#8b5327]">
              전국 모든 대학이 레벨에 따라 공유하는 캠퍼스 · 레벨마다 인게임 장면을 낮 시간대로 촬영
            </p>
          </div>
        </div>

        {/* Quick Game Jump Button */}
        {onGoToGame && (
          <button
            onClick={() => {
              soundManager.playLevelUpSound();
              onGoToGame();
            }}
            className="pixel-plaque plaque-yellow px-3 py-1.5 text-xs font-pixel font-bold flex items-center gap-1 cursor-pointer shrink-0"
          >
            <Trophy className="w-3.5 h-3.5 text-[#261608]" />
            <span>현재 게임으로 돌아가기</span>
          </button>
        )}
      </div>

      {/* Main 5x2 grid of in-game screenshots */}
      <div className="w-full rpg-box p-2.5 sm:p-3 bg-[#fdf5df] flex flex-col gap-3">
        <div className="w-full grid grid-cols-2 sm:grid-cols-5 gap-1.5 sm:gap-2">
          {CAMPUS_SHOTS.map((src, i) => {
            const lv = i + 1;
            const isSelected = selectedStage === lv;
            return (
              <button
                key={lv}
                onClick={() => {
                  setSelectedStage(lv);
                  soundManager.playTabSound();
                }}
                aria-pressed={isSelected}
                className={`relative block w-full aspect-[358/367] overflow-hidden border-3 border-[#261608] bg-[#38a134] cursor-pointer transition-transform ${
                  isSelected ? 'ring-3 ring-[#facc15] -translate-y-0.5 shadow-[3px_3px_0px_#261608]' : 'shadow-[2px_2px_0px_#261608] hover:-translate-y-0.5'
                }`}
                title={`Lv.${lv} 단계 확인`}
              >
                <img
                  src={src}
                  alt={`캠퍼스 Lv.${lv} ${CAMPUS_STAGES[i].name}`}
                  width={716}
                  height={734}
                  loading="lazy"
                  decoding="async"
                  className="absolute inset-0 w-full h-full object-cover"
                />
                <span
                  className={`absolute top-1 left-1 font-press-start text-[10px] sm:text-xs font-black px-1 py-0.5 border border-[#261608] shadow-[1px_1px_0px_#261608] leading-none ${
                    isSelected ? 'bg-[#facc15] text-[#261608]' : 'bg-white/90 text-[#261608]'
                  }`}
                >
                  Lv.{lv}
                </span>
                {isSelected && (
                  <span className="absolute bottom-1 right-1 font-pixel text-[9px] bg-[#22c55e] text-white px-1 border border-[#261608] shadow-[1px_1px_0px_#261608] animate-pixel-blink">
                    선택됨
                  </span>
                )}
              </button>
            );
          })}
        </div>

        {/* 10-Stage Quick Selector Bar (Lv.1 to Lv.10) */}
        <div className="w-full flex items-center gap-1 sm:gap-1.5 overflow-x-auto pb-1 pt-0.5">
          {Array.from({ length: 10 }).map((_, i) => {
            const lv = i + 1;
            const isSelected = selectedStage === lv;
            const isCurrent = currentLevel === lv;

            return (
              <button
                key={lv}
                onClick={() => {
                  setSelectedStage(lv);
                  soundManager.playTabSound();
                }}
                className={`pixel-plaque flex-1 min-w-[54px] py-1 px-1 flex flex-col items-center justify-center cursor-pointer transition-transform ${
                  isSelected
                    ? 'plaque-yellow -translate-y-0.5'
                    : isCurrent
                    ? 'plaque-green'
                    : 'plaque-parchment'
                }`}
              >
                <span className="font-press-start text-[10px] font-black leading-none">
                  L.{lv}
                </span>
                <span className="font-pixel text-[9px] mt-0.5 truncate leading-none">
                  {isCurrent ? '현재' : lv === 10 ? '대축제' : lv >= 7 ? '종합대' : '캠퍼스'}
                </span>
              </button>
            );
          })}
        </div>

        {/* Selected Stage Detail Inspector RPG Panel */}
        <div className="rpg-box p-3 bg-[#faecc7]/50 flex flex-col sm:flex-row items-center justify-between gap-3">
          <div className="flex items-center gap-3 min-w-0">
            {/* Level Badge Plaque */}
            <div className="w-12 h-12 bg-[#facc15] border-3 border-[#261608] flex flex-col items-center justify-center shrink-0 shadow-[2px_2px_0px_#261608]">
              <span className="font-press-start text-xs font-black text-[#261608]">
                Lv.{currentStageInfo.level}
              </span>
              <span className="font-pixel text-[9px] text-[#8b5327] font-bold">
                성장
              </span>
            </div>

            {/* Stage Info Prose */}
            <div className="min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <h3 className="font-pixel text-xs sm:text-sm font-black text-[#261608]">
                  {currentStageInfo.title}
                </h3>
                <span className="text-[9px] font-pixel text-[#ffffff] bg-[#8b5327] px-1.5 py-0.2 border border-[#261608]">
                  {currentStageInfo.category}
                </span>
                <span className="text-[10px] font-mono text-[#b45309] font-bold">
                  목표: {currentStageInfo.scoreReq}
                </span>
              </div>
              <p className="font-pixel text-[11px] text-[#5a3012] mt-0.5 leading-tight">
                {currentStageInfo.desc}
              </p>

              {/* Facility Chips */}
              <div className="flex items-center gap-1 flex-wrap mt-1">
                {currentStageInfo.features.map((feat) => (
                  <span
                    key={feat}
                    className="text-[9px] font-pixel text-[#261608] bg-[#fdf5df] border border-[#261608] px-1 py-0.2 shadow-[1px_1px_0px_#261608]"
                  >
                    {feat}
                  </span>
                ))}
              </div>
            </div>
          </div>

          {/* Action Button: Apply to Clicker Game */}
          <div className="flex items-center gap-2 shrink-0 w-full sm:w-auto justify-end">
            {onSelectStageForGame && (
              <button
                onClick={() => {
                  soundManager.playBuySound();
                  onSelectStageForGame(selectedStage);
                }}
                className="pixel-plaque plaque-wood px-3 py-1.5 text-xs font-pixel font-bold flex items-center gap-1 cursor-pointer w-full sm:w-auto justify-center"
              >
                <Eye className="w-3.5 h-3.5 text-[#fef08a]" />
                <span>성장 시연: 학교 점수를 기준값으로 설정</span>
              </button>
            )}

            {onGoToGame && (
              <button
                onClick={() => {
                  soundManager.playLevelUpSound();
                  onGoToGame();
                }}
                className="pixel-plaque plaque-green px-3 py-1.5 text-xs font-pixel font-bold flex items-center gap-1 cursor-pointer w-full sm:w-auto justify-center"
              >
                <span>인게임으로</span>
                <ChevronRight className="w-3.5 h-3.5" />
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
