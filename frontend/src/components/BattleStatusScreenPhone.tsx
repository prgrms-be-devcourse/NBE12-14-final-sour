import React, { useState } from 'react';
import { PhoneShell } from './PhoneShell';
import { BottomNavFooter, type NavTab } from './BottomNav';
import {
  ChevronLeft,
  Swords,
  AlertCircle,
  RefreshCw,
  Clock,
  Trophy,
  FastForward,
} from 'lucide-react';
import { useAlliance } from '../hooks/useMockAlliance.tsx';
import { useBattle } from '../hooks/useMockBattle.tsx';
import { ALLIANCE_SIZE, getSchoolInfo } from '../data/allianceModel.ts';
import {
  getEffectiveBattleStatus,
  getBattleResultLabel,
  formatBattleTimestamp,
  formatBattleRemainingTime,
} from '../data/battleModel.ts';
import { SharedImage } from './SharedImage.tsx';
import { soundManager } from '../utils/sound.ts';

interface BattleStatusScreenPhoneProps {
  embedded?: boolean;
  onBack?: () => void;
  /** 하단 내비의 다른 탭으로 이동한다(T4, 계획서 §7). 없으면(단독 렌더링) 내비를 그리지 않는다. */
  onNavigateTab?: (tab: NavTab) => void;
  onNavigateToBattleApply?: () => void;
}

export const BattleStatusScreenPhone: React.FC<BattleStatusScreenPhoneProps> = ({
  embedded = false,
  onBack = () => {},
  onNavigateTab,
  onNavigateToBattleApply = () => {},
}) => {
  const { state: allianceState } = useAlliance();
  const {
    state,
    config,
    effectiveNow,
    demoCancelConfirmedRequest,
    clockOffsetMs,
    fastForwardClock,
    resetClock,
    toggleSimulateLoadFailure,
    retryLoad,
  } = useBattle();

  const [activeTab, setActiveTab] = useState<'all' | 'active' | 'pending' | 'finished'>('all');

  const filteredBattles = state.battles.filter((b) => {
    const effStatus = getEffectiveBattleStatus(b, effectiveNow, config);
    if (activeTab === 'all') return true;
    if (activeTab === 'active') return effStatus === 'active' || effStatus === 'scheduled';
    if (activeTab === 'pending') return effStatus === 'pending';
    if (activeTab === 'finished') return effStatus === 'finished';
    return true;
  });

  return (
    <PhoneShell embedded={embedded}>
      {/* 2. Top Header Navigation */}
      <div className="w-full bg-[#faecc7] border-b-2 border-[#261608] px-3 py-2 flex items-center justify-between shrink-0 z-30">
        <div className="flex items-center gap-2">
          <button
            onClick={() => {
              soundManager.playTabSound();
              onBack();
            }}
            aria-label="뒤로가기"
            className="pixel-plaque plaque-wood px-1.5 py-1 text-xs font-pixel font-bold flex items-center gap-0.5 cursor-pointer"
          >
            <ChevronLeft className="w-3.5 h-3.5" />
            <span>뒤로</span>
          </button>
          <div className="flex items-center gap-1.5">
            <span className="font-pixel text-[11px] font-black text-white bg-[#b45309] border border-[#261608] px-1 shadow-[1px_1px_0px_#261608]">
              N4
            </span>
            <h1 className="font-pixel text-sm font-black text-[#261608]">대결 현황</h1>
          </div>
        </div>

        <button
          onClick={() => {
            soundManager.playTabSound();
            onNavigateToBattleApply();
          }}
          className="pixel-plaque plaque-yellow px-2 py-1 text-[11px] font-pixel font-bold flex items-center gap-1 cursor-pointer"
        >
          <Swords className="w-3 h-3 text-[#b45309]" />
          <span>대결 신청</span>
        </button>
      </div>

      {/* 3. Filter Tabs */}
      <div className="w-full bg-[#faecc7]/80 border-b-2 border-[#261608] px-2 py-2.5 flex items-center gap-1 shrink-0 z-20">
        {(
          [
            { id: 'all', label: '전체' },
            { id: 'active', label: '진행/예정' },
            { id: 'pending', label: '신청 대기' },
            { id: 'finished', label: '종료' },
          ] as const
        ).map((tab) => {
          const isSelected = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => {
                soundManager.playTabSound();
                setActiveTab(tab.id);
              }}
              className={`flex-1 py-1 font-pixel text-[11px] font-bold border transition-colors cursor-pointer ${
                isSelected
                  ? 'bg-[#261608] text-[#fdf5df] border-[#261608]'
                  : 'bg-white text-[#261608] border-[#261608]/40 hover:bg-[#fffbeb]'
              }`}
            >
              {tab.label}
            </button>
          );
        })}
      </div>

      {/* 4. Main Content Container */}
      <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-3">
        {/* Load Failure State */}
        {state.simulateLoadFailure ? (
          <div
            role="alert"
            className="rpg-box p-4 bg-[#fee2e2] border-2 border-[#991b1b] flex flex-col items-center justify-center gap-2 text-center shadow-[2px_2px_0px_#261608]"
          >
            <AlertCircle className="w-8 h-8 text-[#991b1b]" />
            <h2 className="font-pixel text-sm font-black text-[#991b1b]">대결 현황 조회 실패</h2>
            <p className="font-pixel text-xs text-[#7f1d1d]">
              네트워크 통신 오류가 발생했습니다. 잠시 후 다시 시도해주세요.
            </p>
            <button
              onClick={() => {
                soundManager.playTabSound();
                retryLoad();
              }}
              className="pixel-plaque plaque-yellow px-3 py-1.5 text-xs font-pixel font-bold flex items-center gap-1 cursor-pointer mt-1"
            >
              <RefreshCw className="w-3.5 h-3.5" />
              <span>다시 시도</span>
            </button>
          </div>
        ) : filteredBattles.length === 0 ? (
          <div className="rpg-box p-4 bg-white border-2 border-[#261608] text-center font-pixel text-xs text-[#8b5327]">
            조회된 대결 내역이 없습니다.
          </div>
        ) : (
          filteredBattles.map((b) => {
            const reqA = allianceState.alliances.find((a) => a.id === b.requesterAllianceId);
            const recA = allianceState.alliances.find((a) => a.id === b.recipientAllianceId);
            const effStatus = getEffectiveBattleStatus(b, effectiveNow, config);
            const remainingText = formatBattleRemainingTime(b, effectiveNow, config);

            const reqScore = b.scores?.requesterScore ?? 0;
            const recScore = b.scores?.recipientScore ?? 0;
            const scoreDiff = Math.abs(reqScore - recScore);

            return (
              <div
                key={b.id}
                className="rpg-box p-3 bg-white border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex flex-col gap-2"
              >
                {/* Header: Format & Status */}
                <div className="flex items-center justify-between border-b pb-1.5 border-[#261608]/20">
                  <div className="flex items-center gap-1.5">
                    <span className="font-pixel text-[10px] font-bold px-1.5 py-0.5 bg-[#fef08a] border border-[#261608] text-[#261608]">
                      {ALLIANCE_SIZE}:{ALLIANCE_SIZE} (연합별 {ALLIANCE_SIZE}개교)
                    </span>
                    <span className="font-pixel text-[9px] text-[#8b5327] font-mono">{b.durationHours}h 경기</span>
                  </div>

                  <span
                    className={`font-pixel text-[10px] font-bold px-1.5 py-0.5 border ${
                      effStatus === 'pending'
                        ? 'bg-yellow-100 text-yellow-800 border-yellow-500'
                        : effStatus === 'active'
                        ? 'bg-red-100 text-red-800 border-red-500'
                        : effStatus === 'finished'
                        ? 'bg-green-100 text-green-800 border-green-500'
                        : 'bg-stone-100 text-stone-600 border-stone-400'
                    }`}
                  >
                    {effStatus === 'pending'
                      ? '대결 대기'
                      : effStatus === 'active'
                      ? '진행 중'
                      : effStatus === 'scheduled'
                      ? '경기 예정'
                      : effStatus === 'finished'
                      ? '종료됨'
                      : effStatus === 'cancelled'
                      ? '취소됨'
                      : effStatus === 'expired'
                      ? '만료됨'
                      : '거절됨'}
                  </span>
                </div>

                {/* Alliances Comparison Row */}
                <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-1 bg-[#fdf5df] p-2 border border-[#261608]/20">
                  {/* Requester Alliance */}
                  <div className="flex flex-col items-center text-center gap-1 min-w-0">
                    <span className="font-pixel text-[11px] font-bold text-[#261608] truncate w-full">
                      {reqA?.name ?? b.requesterAllianceId}
                    </span>
                    <div className="flex items-center justify-center gap-1 flex-wrap">
                      {b.requesterSchoolIds.map((sid) => {
                        const s = getSchoolInfo(sid);
                        return (
                          <div
                            key={sid}
                            title={s.name}
                            className="w-5 h-5 border border-[#261608] bg-white shrink-0"
                          >
                            <SharedImage
                              target={{ type: 'school', id: sid }}
                              name={s.name}
                              fallback={s.firstSyllable}
                              className="w-full h-full text-[9px]"
                            />
                          </div>
                        );
                      })}
                    </div>
                  </div>

                  {/* VS Badge */}
                  <div className="font-pixel text-xs font-black text-[#8b5327] px-1">VS</div>

                  {/* Recipient Alliance */}
                  <div className="flex flex-col items-center text-center gap-1 min-w-0">
                    <span className="font-pixel text-[11px] font-bold text-[#261608] truncate w-full">
                      {recA?.name ?? b.recipientAllianceId}
                    </span>
                    <div className="flex items-center justify-center gap-1 flex-wrap">
                      {b.recipientSchoolIds.map((sid) => {
                        const s = getSchoolInfo(sid);
                        return (
                          <div
                            key={sid}
                            title={s.name}
                            className="w-5 h-5 border border-[#261608] bg-white shrink-0"
                          >
                            <SharedImage
                              target={{ type: 'school', id: sid }}
                              name={s.name}
                              fallback={s.firstSyllable}
                              className="w-full h-full text-[9px]"
                            />
                          </div>
                        );
                      })}
                    </div>
                  </div>
                </div>

                {effStatus === 'pending' && <div className="text-[10px] font-pixel text-[#8b5327]">희망 시작: {formatBattleTimestamp(b.requestedStartTime)} KST</div>}
                {/* Start & End Times (P4-2: 시작/종료 시각) */}
                {(b.scheduledStartTime != null || b.scheduledEndTime != null) && (
                  <div className="flex items-center justify-between text-[10px] font-pixel text-[#8b5327] bg-[#fdf5df] px-2 py-1 border border-[#261608]/15">
                    <span>시작: {formatBattleTimestamp(b.scheduledStartTime)}</span>
                    <span>종료: {formatBattleTimestamp(b.scheduledEndTime)}</span>
                  </div>
                )}

                {/* Score & Difference (For active or finished battles) */}
                {(effStatus === 'active' || effStatus === 'finished') && (
                  <div className="flex flex-col gap-1 bg-[#fffbeb] p-2 border border-[#f59e0b]/40">
                    <div className="flex items-center justify-between font-pixel text-xs font-bold text-[#261608]">
                      <span>{reqScore.toLocaleString()}점</span>
                      <span className="text-[10px] text-[#b45309]">격차: {scoreDiff.toLocaleString()}점</span>
                      <span>{recScore.toLocaleString()}점</span>
                    </div>

                    <p className="font-pixel text-[8px] text-[#8b5327] text-center">
                      ※ 경기 점수는 모의 시연 샘플 값이며, 실제 학교 누적 점수·기여도·버프와 무관합니다.
                    </p>
                  </div>
                )}

                {/* Timing & Result Notice */}
                {effStatus !== 'active' && effStatus !== 'finished' && (
                  <div className="flex items-center justify-between text-[10px] font-pixel text-[#8b5327] border-t pt-1.5 border-[#261608]/10">
                    <div className="flex items-center gap-1">
                      <Clock className="w-3 h-3 text-[#8b5327]" />
                      <span>{remainingText ?? '대기 중'}</span>
                    </div>
                  </div>
                )}

                {effStatus === 'active' && (
                  <div className="flex items-center justify-between text-[10px] font-pixel text-[#8b5327] border-t pt-1.5 border-[#261608]/10">
                    <div className="flex items-center gap-1">
                      <Clock className="w-3 h-3 text-[#8b5327]" />
                      <span>{remainingText ?? '진행 중'}</span>
                    </div>
                    <div className="text-[10px] font-bold text-[#b45309]">
                      {reqScore > recScore
                        ? `${reqA?.name ?? b.requesterAllianceId} 리드 중`
                        : recScore > reqScore
                        ? `${recA?.name ?? b.recipientAllianceId} 리드 중`
                        : '동점 진행 중'}
                    </div>
                  </div>
                )}

                {effStatus === 'finished' && (
                  <div className="flex flex-col gap-1 border-t pt-1.5 border-[#261608]/10">
                    <div className="flex items-center justify-between text-[10px] font-pixel text-[#8b5327]">
                      <div className="flex items-center gap-1 font-bold text-[#261608]">
                        <Trophy className="w-3.5 h-3.5 text-[#eab308]" />
                        <span>
                          {getBattleResultLabel(
                            b,
                            reqA?.name ?? b.requesterAllianceId,
                            recA?.name ?? b.recipientAllianceId,
                          )}
                        </span>
                      </div>
                      <span className="text-[9px] text-[#b45309]">결과: 샘플 점수 기준</span>
                    </div>
                    <div className="text-[9px] font-pixel text-[#8b5327] bg-[#faecc7]/40 px-2 py-1 border border-[#261608]/20">
                      보상: 정책 미확정·정산 미연동 (점수 차감 또는 이전 없음)
                    </div>
                  </div>
                )}
              </div>
            );
          })
        )}

        {/* 5. Demo Controls Section */}
        <div data-no-touch44 className="rpg-box p-2.5 bg-[#faecc7]/40 border-2 border-dashed border-[#b45309] flex flex-col gap-2 mt-2">
          <div className="flex items-center gap-1 text-[#b45309]">
            <FastForward className="w-3.5 h-3.5" />
            <span className="font-pixel text-[11px] font-black">
              🧪 대결 현황 시연 제어 (DEMO CONTROLS)
            </span>
          </div>

          {/* Virtual Clock Controls */}
          <div className="flex items-center justify-between text-[10px] font-pixel">
            <span className="text-[#261608]">
              가상 시계 (+{Math.round(clockOffsetMs / 3600_000)}h 경과):
            </span>
            <div className="flex items-center gap-1">
              <button
                onClick={() => fastForwardClock(1)}
                className="px-1.5 py-0.5 bg-white border border-[#261608] text-[8px] font-pixel cursor-pointer hover:bg-stone-100"
              >
                +1h
              </button>
              <button
                onClick={() => fastForwardClock(12)}
                className="px-1.5 py-0.5 bg-white border border-[#261608] text-[8px] font-pixel cursor-pointer hover:bg-stone-100"
              >
                +12h
              </button>
              <button
                onClick={() => fastForwardClock(24)}
                className="px-1.5 py-0.5 bg-white border border-[#261608] text-[8px] font-pixel cursor-pointer hover:bg-stone-100"
              >
                +24h
              </button>
              <button
                onClick={resetClock}
                className="px-1.5 py-0.5 bg-white border border-[#261608] text-[8px] font-pixel text-[#dc2626] cursor-pointer hover:bg-stone-100"
              >
                리셋
              </button>
            </div>
          </div>

          {/* Load Failure Simulation Toggle */}
          <div className="flex items-center justify-between text-[10px] font-pixel border-t border-[#b45309]/30 pt-1.5">
            <span className="text-[#8b5327]">현황 조회 오류 시뮬레이션:</span>
            <button
              onClick={toggleSimulateLoadFailure}
              className={`px-2 py-0.5 border text-[9px] font-bold cursor-pointer ${
                state.simulateLoadFailure
                  ? 'bg-[#ef4444] text-white border-[#261608]'
                  : 'bg-white text-[#261608] border-[#261608]'
              }`}
            >
              {state.simulateLoadFailure ? '오류 발생 중' : '정상 동작'}
            </button>
          </div>

          {/* P3-1: Demo Cancel for Scheduled or Active Battles */}
          {state.battles.filter((b) => {
            const s = getEffectiveBattleStatus(b, effectiveNow, config);
            return s === 'scheduled' || s === 'active';
          }).length > 0 && (
            <div className="border-t border-[#b45309]/30 pt-1.5 flex flex-col gap-1">
              <span className="font-pixel text-[10px] font-bold text-[#261608]">
                확정 대결 취소 시연 (점유 해제 검증용)
              </span>
              {state.battles
                .filter((b) => {
                  const s = getEffectiveBattleStatus(b, effectiveNow, config);
                  return s === 'scheduled' || s === 'active';
                })
                .map((b) => {
                  const reqA = allianceState.alliances.find((a) => a.id === b.requesterAllianceId);
                  const recA = allianceState.alliances.find((a) => a.id === b.recipientAllianceId);
                  const eff = getEffectiveBattleStatus(b, effectiveNow, config);
                  return (
                    <div
                      key={b.id}
                      className="p-1.5 bg-white border border-[#b45309]/40 flex items-center justify-between text-[9px] font-pixel"
                    >
                      <span className="font-bold text-[#261608]">
                        [{eff === 'scheduled' ? '예정' : '진행'}] {reqA?.name} vs {recA?.name}
                      </span>
                      <button
                        onClick={() => demoCancelConfirmedRequest(b.id)}
                        className="px-2 py-0.5 bg-[#fee2e2] border border-[#dc2626] text-[#7f1d1d] font-bold text-[8px] hover:bg-[#fecaca] cursor-pointer"
                      >
                        [시연] 대결 취소(점유 해제)
                      </button>
                    </div>
                  );
                })}
            </div>
          )}
        </div>
      </div>

      {onNavigateTab && <BottomNavFooter activeTab="alliance" onChangeTab={onNavigateTab} />}
    </PhoneShell>
  );
};
