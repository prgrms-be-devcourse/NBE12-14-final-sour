import React, { useState } from 'react';
import { PhoneShell } from './PhoneShell';
import { BottomNavFooter, type NavTab } from './BottomNav';
import {
  ChevronLeft,
  Swords,
  AlertCircle,
  Shield,
  CheckCircle2,
  BarChart3,
  FastForward,
} from 'lucide-react';
import { useAlliance } from '../hooks/useMockAlliance.tsx';
import { useBattle } from '../hooks/useMockBattle.tsx';
import { ALLIANCE_SIZE, getSchoolInfo, isAllianceActive } from '../data/allianceModel.ts';
import {
  getEffectiveBattleStatus,
  isAllianceOccupied,
  formatBattleTimestamp,
  formatKstDateTimeLocal,
  parseKstDateTimeLocal,
  getDefaultRequestedStartTime,
  validateRequestedStartTime,
  getBattleLatestEnd,
  getMaxBattleDurationHours,
  getBattleAllianceWindowError,
} from '../data/battleModel.ts';
import { SharedImage } from './SharedImage.tsx';
import { DurationPicker, formatDurationHours } from './DurationPicker.tsx';
import { soundManager } from '../utils/sound.ts';

interface BattleApplyScreenPhoneProps {
  embedded?: boolean;
  onBack?: () => void;
  /** 하단 내비의 다른 탭으로 이동한다(T4, 계획서 §7). 없으면(단독 렌더링) 내비를 그리지 않는다. */
  onNavigateTab?: (tab: NavTab) => void;
  onNavigateToBattleStatus?: () => void;
  onNavigateToAllianceApply?: () => void;
}

export const BattleApplyScreenPhone: React.FC<BattleApplyScreenPhoneProps> = ({
  embedded = false,
  onBack = () => {},
  onNavigateTab,
  onNavigateToBattleStatus = () => {},
  onNavigateToAllianceApply = () => {},
}) => {
  const { currentAlliance, state: allianceState } = useAlliance();
  const {
    state,
    config,
    effectiveNow,
    myAllianceId,
    myAllianceBattles,
    isMyAllianceOccupied,
    applyRequest,
    cancelRequest,
    acceptRequest,
    rejectRequest,
    demoAcceptRequest,
    demoRejectRequest,
    demoReceiveRequest,
    demoCancelConfirmedRequest,
    clockOffsetMs,
    fastForwardClock,
    resetClock,
    toggleSimulateRequestFailure,
  } = useBattle();

  const [selectedOpponentAllianceId, setSelectedOpponentAllianceId] = useState<string | null>(null);
  const [durationHours, setDurationHours] = useState(
    config.durationHours.default,
  );
  // 기본 시작: 내 연합 만료 전에 기본 기간으로 끝낼 수 있는 가장 빠른 슬롯 (없으면 빈 값)
  const [startInput, setStartInput] = useState(() =>
    formatKstDateTimeLocal(getDefaultRequestedStartTime(effectiveNow, config, currentAlliance?.expiresAt, config.durationHours.default)),
  );
  const requestedStartTime = parseKstDateTimeLocal(startInput);
  const timeValidation = validateRequestedStartTime(requestedStartTime, effectiveNow, config);
  const [feedbackMessage, setFeedbackMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  // Candidate opponent alliances:
  // Must be active, must not be own alliance, must not share any school with own alliance
  const candidateOpponents = allianceState.alliances.filter((a) => {
    if (!currentAlliance) return false;
    if (a.id === currentAlliance.id) return false;
    if (!isAllianceActive(a, effectiveNow)) return false;
    const hasOverlap = a.schoolIds.some((id) => currentAlliance.schoolIds.includes(id));
    return !hasOverlap;
  });

  const selectedOpponent = candidateOpponents.find((a) => a.id === selectedOpponentAllianceId);

  // 대결은 두 연합 중 빠른 만료 전에 끝나야 한다. 상대를 고르기 전에는 내 연합 만료만 상한으로 쓴다.
  const latestEnd = currentAlliance
    ? selectedOpponent
      ? getBattleLatestEnd(currentAlliance, selectedOpponent)
      : currentAlliance.expiresAt
    : undefined;
  const maxDurationForStart =
    latestEnd != null && timeValidation.success
      ? getMaxBattleDurationHours(requestedStartTime, latestEnd, config)
      : config.durationHours.max;
  const durationPickerMax = Math.max(config.durationHours.min, maxDurationForStart);
  const latestStartInputMax = Math.min(
    effectiveNow + config.startTime.maxLeadDays * 24 * 3600_000,
    latestEnd != null ? latestEnd - durationHours * 3600_000 : Infinity,
  );
  const windowError =
    currentAlliance && selectedOpponent && timeValidation.success
      ? getBattleAllianceWindowError(currentAlliance, selectedOpponent, requestedStartTime, durationHours, effectiveNow)
      : undefined;
  const opponentHasNoSlot = (opponentLatestEnd: number) =>
    !Number.isFinite(getDefaultRequestedStartTime(effectiveNow, config, opponentLatestEnd, config.durationHours.min));

  const handleSelectOpponent = (allianceId: string) => {
    soundManager.playTabSound();
    setSelectedOpponentAllianceId(allianceId);
    setFeedbackMessage(null);
  };

  const handleSendBattleApplication = () => {
    if (!selectedOpponentAllianceId) {
      setFeedbackMessage({ type: 'error', text: '상대 연합을 선택해주세요.' });
      return;
    }
    soundManager.playBuySound();
    const result = applyRequest(selectedOpponentAllianceId, durationHours, requestedStartTime);
    if (result.success) {
      setFeedbackMessage({
        type: 'success',
        text: `'${selectedOpponent?.name}'에 대결 신청을 성공적으로 전송했습니다.`,
      });
      setSelectedOpponentAllianceId(null);
    } else {
      setFeedbackMessage({ type: 'error', text: result.error ?? '신청에 실패했습니다.' });
    }
  };

  const handleCancelRequest = (battleId: string) => {
    soundManager.playTabSound();
    const result = cancelRequest(battleId);
    if (result.success) {
      setFeedbackMessage({ type: 'success', text: '대결 신청이 정상적으로 취소되었습니다.' });
    } else {
      setFeedbackMessage({ type: 'error', text: result.error ?? '취소에 실패했습니다.' });
    }
  };

  const handleDemoAccept = (battleId: string) => {
    soundManager.playLevelUpSound();
    const result = demoAcceptRequest(battleId);
    if (result.success) {
      setFeedbackMessage({
        type: 'success',
        text: `[시연] 상대 연합이 수락하여 대결이 확정되었습니다! (단일 동시 상대 점유 시작)`,
      });
    } else {
      setFeedbackMessage({ type: 'error', text: result.error ?? '수락에 실패했습니다.' });
    }
  };

  const handleAcceptReceived = (battleId: string, opponentName: string) => {
    soundManager.playLevelUpSound();
    const result = acceptRequest(battleId);
    setFeedbackMessage(
      result.success
        ? { type: 'success', text: `${opponentName}의 대결 신청을 수락했습니다. 대결이 확정되었습니다!` }
        : { type: 'error', text: result.error ?? '수락에 실패했습니다.' },
    );
  };

  const handleRejectReceived = (battleId: string, opponentName: string) => {
    soundManager.playTabSound();
    const result = rejectRequest(battleId);
    setFeedbackMessage(
      result.success
        ? { type: 'success', text: `${opponentName}의 대결 신청을 거절했습니다.` }
        : { type: 'error', text: result.error ?? '거절에 실패했습니다.' },
    );
  };

  const handleDemoReceive = () => {
    soundManager.playTabSound();
    const result = demoReceiveRequest();
    if (result.success && result.battle) {
      const from = allianceState.alliances.find((a) => a.id === result.battle!.requesterAllianceId);
      setFeedbackMessage({ type: 'success', text: `[시연] ${from?.name ?? '다른 연합'}에서 대결 신청이 도착했습니다.` });
    } else {
      setFeedbackMessage({ type: 'error', text: result.error ?? '시연 신청 생성에 실패했습니다.' });
    }
  };

  const handleDemoReject = (battleId: string) => {
    soundManager.playTabSound();
    const result = demoRejectRequest(battleId);
    if (result.success) {
      setFeedbackMessage({ type: 'success', text: '[시연] 상대 연합 명의로 대결 신청을 거절했습니다.' });
    } else {
      setFeedbackMessage({ type: 'error', text: result.error ?? '거절에 실패했습니다.' });
    }
  };

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
            <span className="font-pixel text-[11px] font-black text-white bg-[#dc2626] border border-[#261608] px-1 shadow-[1px_1px_0px_#261608]">
              N4
            </span>
            <h1 className="font-pixel text-sm font-black text-[#261608]">대결 신청</h1>
          </div>
        </div>

        <button
          onClick={() => {
            soundManager.playTabSound();
            onNavigateToBattleStatus();
          }}
          className="pixel-plaque plaque-yellow px-2 py-1 text-[11px] font-pixel font-bold flex items-center gap-1 cursor-pointer"
        >
          <BarChart3 className="w-3 h-3 text-[#b45309]" />
          <span>대결 현황</span>
        </button>
      </div>

      {/* 3. Main Content Container */}
      <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-3">
        {/* Feedback Banner */}
        {feedbackMessage && (
          <div
            role="alert"
            className={`p-2 border-2 flex items-center gap-2 font-pixel text-xs font-bold shadow-[2px_2px_0px_#261608] ${
              feedbackMessage.type === 'success'
                ? 'bg-[#dcfce7] border-[#15803d] text-[#14532d]'
                : 'bg-[#fee2e2] border-[#b91c1c] text-[#7f1d1d]'
            }`}
          >
            {feedbackMessage.type === 'success' ? (
              <CheckCircle2 className="w-4 h-4 shrink-0 text-[#15803d]" />
            ) : (
              <AlertCircle className="w-4 h-4 shrink-0 text-[#b91c1c]" />
            )}
            <span className="leading-tight">{feedbackMessage.text}</span>
          </div>
        )}

        {/* State: NO ALLIANCE */}
        {!currentAlliance ? (
          <div className="rpg-box p-4 bg-white border-2 border-[#261608] shadow-[3px_3px_0px_#261608] flex flex-col items-center justify-center gap-2 text-center">
            <div className="w-12 h-12 bg-[#faecc7] border-2 border-[#261608] flex items-center justify-center rounded-none shadow-[2px_2px_0px_#261608]">
              <Shield className="w-6 h-6 text-[#8b5327]" />
            </div>
            <h2 className="font-pixel text-sm font-black text-[#261608]">소속 연합 없음</h2>
            <p className="font-pixel text-xs text-[#8b5327] max-w-[240px]">
              대결을 신청하려면 먼저 학교 연합을 결성해야 합니다. 연합을 맺고 대항전에 참가해보세요!
            </p>
            <button
              onClick={() => {
                soundManager.playTabSound();
                onNavigateToAllianceApply();
              }}
              className="pixel-plaque plaque-yellow px-3 py-1.5 text-xs font-pixel font-bold flex items-center gap-1 cursor-pointer mt-1"
            >
              <span>🤝 연합 결성하러 가기</span>
            </button>
          </div>
        ) : (
          /* Form: ALLIANCE EXISTS */
          <div className="flex flex-col gap-3">
            {/* Section 1: My Alliance Info */}
            <div className="rpg-box p-2.5 bg-[#ffffff] border-2 border-[#261608] flex flex-col gap-2">
              <div className="flex items-center justify-between border-b pb-1.5 border-[#261608]/20">
                <span className="font-pixel text-xs font-bold text-[#8b5327]">신청 주체 (내 연합)</span>
                <span className="font-pixel text-[10px] font-bold text-[#261608] bg-[#fef08a] px-1.5 py-0.5 border border-[#261608]">
                  {ALLIANCE_SIZE}:{ALLIANCE_SIZE} 대결
                </span>
              </div>

              <div className="flex items-center gap-2">
                <div className="w-8 h-8 bg-[#faecc7] border-2 border-[#261608] flex items-center justify-center shrink-0">
                  <Shield className="w-4 h-4 text-[#8b5327]" />
                </div>
                <div className="flex flex-col min-w-0">
                  <span className="font-pixel text-xs font-bold text-[#261608] truncate">
                    {currentAlliance.name}
                  </span>
                  <span className="font-pixel text-[9px] text-[#8b5327]">
                    참여 학교: {currentAlliance.schoolIds.map((id) => getSchoolInfo(id).shortName).join(' · ')}
                  </span>
                </div>
              </div>

              {/* Participating Schools Grid */}
              <div className="grid grid-cols-2 gap-1.5 pt-1">
                {currentAlliance.schoolIds.map((sid) => {
                  const s = getSchoolInfo(sid);
                  return (
                    <div
                      key={sid}
                      className="p-1 border border-[#261608]/30 bg-[#fdf5df] flex items-center gap-1.5 min-w-0"
                    >
                      <div className="w-5 h-5 border border-[#261608] shrink-0">
                        <SharedImage
                          target={{ type: 'school', id: sid }}
                          name={s.name}
                          fallback={s.firstSyllable}
                          className="w-full h-full text-[10px]"
                        />
                      </div>
                      <span className="font-pixel text-[10px] font-bold text-[#261608] truncate">{s.name}</span>
                    </div>
                  );
                })}
              </div>

              {/* Active Occupation Warning */}
              {isMyAllianceOccupied && (
                <div className="p-1.5 bg-[#fee2e2] border border-[#dc2626] flex items-center gap-1 text-[#991b1b] font-pixel text-[10px] font-bold">
                  <AlertCircle className="w-3.5 h-3.5 shrink-0" />
                  <span>내 연합은 이미 진행/예정 중인 대결이 있어 추가 신청이 불가합니다.</span>
                </div>
              )}
            </div>

            {/* Section 3: Opponent Alliance Selection */}
            <div className="rpg-box p-2.5 bg-[#ffffff] border-2 border-[#261608] flex flex-col gap-2">
              <label className="font-pixel text-xs font-bold text-[#261608] flex items-center justify-between">
                <span>상대 연합 선택</span>
                <span className="text-[9px] text-[#8b5327] font-normal">
                  ({ALLIANCE_SIZE}개교 연합끼리 {ALLIANCE_SIZE}:{ALLIANCE_SIZE} 대결)
                </span>
              </label>

              <div className="max-h-36 overflow-y-auto border-2 border-[#261608]/40 bg-[#faecc7]/20 p-1 flex flex-col gap-1">
                {candidateOpponents.length === 0 ? (
                  <div className="p-3 text-center font-pixel text-xs text-[#8b5327] font-bold bg-[#fdf5df]">
                    대결 가능한 상대 연합이 없습니다
                  </div>
                ) : (
                  candidateOpponents.map((opponent) => {
                    const isSelected = selectedOpponentAllianceId === opponent.id;
                    const isOccupied = isAllianceOccupied(state.battles, opponent.id, effectiveNow);
                    // 두 연합 중 빠른 만료 전에 최소 기간으로도 끝낼 슬롯이 없으면 대결 불가
                    const isExpiringSoon = !isOccupied && opponentHasNoSlot(getBattleLatestEnd(currentAlliance, opponent));
                    const disabled = isOccupied || isExpiringSoon;

                    return (
                      <button
                        key={opponent.id}
                        type="button"
                        disabled={disabled}
                        onClick={() => handleSelectOpponent(opponent.id)}
                        className={`p-1.5 border flex items-center justify-between text-left transition-colors ${
                          disabled
                            ? 'bg-[#e5e5e5] border-[#261608]/20 opacity-60 cursor-not-allowed'
                            : isSelected
                            ? 'bg-[#fef08a] border-[#261608] shadow-[1px_1px_0px_#261608] cursor-pointer'
                            : 'bg-[#ffffff] border-[#261608]/30 hover:bg-[#fffbeb] cursor-pointer'
                        }`}
                      >
                        <div className="flex flex-col min-w-0 pr-1">
                          <div className="flex items-center gap-1">
                            <span className="font-pixel text-xs font-bold text-[#261608] truncate">
                              {opponent.name}
                            </span>
                            <span className="font-pixel text-[9px] text-[#8b5327]">
                              ({opponent.schoolIds.length}개교)
                            </span>
                          </div>
                          <span className="font-pixel text-[8px] text-[#8b5327] truncate">
                            {opponent.schoolIds.map((id) => getSchoolInfo(id).shortName).join(' · ')}
                          </span>
                        </div>

                        <div className="flex items-center gap-1 shrink-0">
                          {isOccupied ? (
                            <span className="font-pixel text-[8px] px-1 py-0.2 bg-[#fecaca] text-[#991b1b] border border-[#dc2626]">
                              대결 진행 중
                            </span>
                          ) : isExpiringSoon ? (
                            <span className="font-pixel text-[8px] px-1 py-0.2 bg-[#ffedd5] text-[#9a3412] border border-[#ea580c]">
                              만료 임박 (대결 불가)
                            </span>
                          ) : (
                            <span className="font-pixel text-[8px] px-1 py-0.2 bg-[#dcfce7] text-[#15803d] border border-[#16a34a]">
                              선택 가능
                            </span>
                          )}
                        </div>
                      </button>
                    );
                  })
                )}
              </div>
            </div>

            {/* Section 4: Duration Selector (Config Injected) */}
            <DurationPicker
              label="경기 진행 기간"
              value={durationHours}
              min={config.durationHours.min}
              max={durationPickerMax}
              presets={config.durationHours.presets}
              onChange={setDurationHours}
            />
            {latestEnd != null && (
              <p className="font-pixel text-[10px] text-[#8b5327] -mt-1 leading-tight">
                ※ 대결은 {selectedOpponent ? '두 연합 중 빠른 만료' : '내 연합 만료'}({formatBattleTimestamp(latestEnd)} KST) 전에 끝나야 합니다.
                {timeValidation.success && maxDurationForStart >= config.durationHours.min && ` 선택한 시작 시각 기준 최대 ${maxDurationForStart}시간.`}
              </p>
            )}

            <div className="rpg-box p-2.5 bg-white border-2 border-[#261608] flex flex-col gap-2 min-w-0">
              <label htmlFor="battle-start" className="font-pixel text-xs font-bold">시작 일시 (KST)</label>
              <input id="battle-start" type="datetime-local" value={startInput}
                min={formatKstDateTimeLocal(Math.ceil((effectiveNow + config.startTime.minLeadMinutes * 60_000 + 9 * 3600_000) / (config.startTime.stepMinutes * 60_000)) * config.startTime.stepMinutes * 60_000 - 9 * 3600_000)}
                max={formatKstDateTimeLocal(latestStartInputMax)}
                step={config.startTime.stepMinutes * 60} onChange={(e) => setStartInput(e.target.value)}
                className="w-full min-w-0 max-w-full px-2 py-1 text-xs border-2 border-[#261608]" />
              <p className="font-pixel text-[10px] text-[#8b5327]">{String(config.startTime.allowedStartHoursKst.from).padStart(2, '0')}:00~{String(config.startTime.allowedStartHoursKst.to).padStart(2, '0')}:00 시작 · {config.startTime.stepMinutes}분 단위 · 최소 {config.startTime.minLeadMinutes}분 후 ~ 최대 {config.startTime.maxLeadDays}일 이내 (목업 값)</p>
              {!timeValidation.success && <p role="alert" className="text-xs text-red-700">{startInput ? timeValidation.error : '선택 가능한 시작 시각이 없습니다 (연합 만료가 가깝습니다).'}</p>}
              {windowError && <p role="alert" className="text-xs text-red-700">{windowError}</p>}
            </div>

            {/* Section 5: Condition Summary & Dispatch */}
            {selectedOpponent && (
              <div className="rpg-box p-2.5 bg-[#fef08a]/40 border-2 border-[#261608] flex flex-col gap-2">
                <span className="font-pixel text-xs font-bold text-[#261608]">대결 조건 요약</span>
                <div className="font-pixel text-[10px] text-[#8b5327] flex flex-col gap-0.5">
                  <div>• 신청: {currentAlliance.name} ({currentAlliance.schoolIds.length}개교)</div>
                  <div>• 상대: {selectedOpponent.name} ({selectedOpponent.schoolIds.length}개교)</div>
                  <div>• 형식: {ALLIANCE_SIZE}:{ALLIANCE_SIZE} (연합당 {ALLIANCE_SIZE}개교)</div>
                  <div>• 일정: {formatBattleTimestamp(requestedStartTime)} 시작 · {durationHours}시간 진행 · {formatBattleTimestamp(requestedStartTime + durationHours * 3600_000)} 종료</div>
                  {latestEnd != null && timeValidation.success && !windowError && (
                    <div>
                      • 연합 만료: {formatBattleTimestamp(latestEnd)} (종료 후 여유{' '}
                      {formatDurationHours(Math.floor((latestEnd - requestedStartTime - durationHours * 3600_000) / 3600_000))})
                    </div>
                  )}
                  <div>• 규칙: 단일 동시 상대 원칙 (경기 확정 시 종료 전까지 양측 모두 타 대결 불가)</div>
                </div>

                <button
                  onClick={handleSendBattleApplication}
                  disabled={!timeValidation.success || !!windowError}
                  className="pixel-plaque plaque-yellow py-2 text-center font-pixel text-xs font-bold flex items-center justify-center gap-1 cursor-pointer hover:bg-[#fef08a]"
                >
                  <Swords className="w-3.5 h-3.5 text-[#b45309]" />
                  <span>대결 신청 보내기</span>
                </button>
              </div>
            )}

            {/* Section 6: Sent / Received Battle Requests */}
            <div className="rpg-box p-2.5 bg-[#ffffff] border-2 border-[#261608] flex flex-col gap-2">
              <span className="font-pixel text-xs font-bold text-[#261608]">
                내 연합 대결 요청 목록 ({myAllianceBattles.length}건)
              </span>

              {myAllianceBattles.length === 0 ? (
                <div className="p-2 text-center font-pixel text-[10px] text-[#8b5327]">
                  진행 중이거나 대기 중인 대결 요청이 없습니다.
                </div>
              ) : (
                myAllianceBattles.map((b) => {
                  const isSentByMe = b.requesterAllianceId === myAllianceId;
                  const oppId = isSentByMe ? b.recipientAllianceId : b.requesterAllianceId;
                  const oppAlliance = allianceState.alliances.find((a) => a.id === oppId);
                  const effStatus = getEffectiveBattleStatus(b, effectiveNow, config);
                  const oppName = oppAlliance?.name ?? '상대 연합';

                  return (
                    <div
                      key={b.id}
                      className="p-1.5 border border-[#261608]/40 bg-[#fdf5df] flex flex-col gap-1"
                    >
                      <div className="flex items-center justify-between text-[10px] font-pixel">
                        <span className="font-bold text-[#261608]">
                          {oppAlliance?.name ?? '상대 연합'} ({isSentByMe ? '보낸 신청' : '받은 신청'})
                        </span>
                        <span
                          className={`px-1 py-0.2 font-bold ${
                            effStatus === 'pending'
                              ? 'bg-yellow-200 text-yellow-800'
                              : effStatus === 'active'
                              ? 'bg-red-200 text-red-800'
                              : effStatus === 'finished'
                              ? 'bg-green-200 text-green-800'
                              : 'bg-stone-200 text-stone-700'
                          }`}
                        >
                          {effStatus === 'pending'
                            ? '대기 중'
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

                      <div className="text-[9px] font-pixel text-[#8b5327]">{effStatus === 'pending' ? '희망 시작' : '시작'}: {formatBattleTimestamp(b.scheduledStartTime ?? b.requestedStartTime)} KST</div>
                      <div className="text-[9px] font-pixel text-[#8b5327] flex items-center justify-between">
                        <span>형식: {ALLIANCE_SIZE}:{ALLIANCE_SIZE} | 기간: {b.durationHours}h</span>
                        {effStatus === 'pending' && isSentByMe && (
                          <button
                            onClick={() => handleCancelRequest(b.id)}
                            className="px-1.5 py-0.5 bg-[#ffffff] border border-[#261608] text-[9px] font-pixel font-bold text-[#dc2626] hover:bg-[#fee2e2] cursor-pointer"
                          >
                            신청 취소
                          </button>
                        )}
                      </div>
                      {/* 받은 신청: 리더가 없으므로 우리 연합 학생 누구든 수락·거절할 수 있다 */}
                      {effStatus === 'pending' && !isSentByMe && (
                        <div className="grid grid-cols-2 gap-1.5 pt-0.5">
                          <button
                            type="button"
                            onClick={() => handleAcceptReceived(b.id, oppName)}
                            aria-label={`${oppName}의 대결 신청 수락`}
                            className="pixel-plaque plaque-yellow py-1 font-pixel text-xs font-black cursor-pointer"
                          >
                            수락
                          </button>
                          <button
                            type="button"
                            onClick={() => handleRejectReceived(b.id, oppName)}
                            aria-label={`${oppName}의 대결 신청 거절`}
                            className="pixel-plaque plaque-wood py-1 font-pixel text-xs font-bold cursor-pointer"
                          >
                            거절
                          </button>
                        </div>
                      )}
                    </div>
                  );
                })
              )}
            </div>

            {/* Section 7: DEMO CONTROLS (Isolated, Clearly Labeled) */}
            <div data-no-touch44 className="rpg-box p-2.5 bg-[#faecc7]/40 border-2 border-dashed border-[#b45309] flex flex-col gap-2">
              <div className="flex items-center gap-1 text-[#b45309]">
                <Swords className="w-3.5 h-3.5" />
                <span className="font-pixel text-[11px] font-black">
                  🧪 상대 연합 시연 제어 (DEMO CONTROLS)
                </span>
              </div>
              <p className="font-pixel text-[9px] text-[#8b5327]">
                ※ 받은 신청은 우리 연합 학생 누구나 위 목록에서 수락·거절할 수 있습니다. 아래는 상대 연합의 수락·거절과 신청을 흉내 내는 시연용 제어입니다.
              </p>

              <button
                type="button"
                onClick={handleDemoReceive}
                className="py-1 bg-white border border-[#0e7490] text-[#0e7490] font-pixel font-bold text-[9px] hover:bg-[#ecfeff] cursor-pointer"
              >
                [시연] 다른 연합의 대결 신청 받기
              </button>

              {/* Demo Accept / Reject for Pending Battles */}
              {state.battles.filter((b) => getEffectiveBattleStatus(b, effectiveNow, config) === 'pending').length === 0 ? (
                <div className="text-[9px] font-pixel text-[#8b5327]">대기 중인 신청이 없습니다.</div>
              ) : (
                state.battles
                  .filter((b) => getEffectiveBattleStatus(b, effectiveNow, config) === 'pending')
                  .map((b) => {
                    const reqA = allianceState.alliances.find((a) => a.id === b.requesterAllianceId);
                    const recA = allianceState.alliances.find((a) => a.id === b.recipientAllianceId);
                    return (
                      <div
                        key={b.id}
                        className="p-1.5 bg-white border border-[#261608]/40 flex flex-col gap-1 text-[9px] font-pixel"
                      >
                        <div className="font-bold text-[#261608]">
                          {reqA?.name} → {recA?.name} ({b.durationHours}h) · 희망 시작 {formatBattleTimestamp(b.requestedStartTime)} KST
                        </div>
                        <div className="flex items-center gap-1">
                          <button
                            onClick={() => handleDemoAccept(b.id)}
                            className="flex-1 py-1 bg-[#dcfce7] border border-[#16a34a] text-[#14532d] font-bold text-[9px] hover:bg-[#bbf7d0] cursor-pointer"
                          >
                            [시연] 상대 연합 명의로 수락 (대결 확정)
                          </button>
                          <button
                            onClick={() => handleDemoReject(b.id)}
                            className="px-2 py-1 bg-[#fee2e2] border border-[#dc2626] text-[#7f1d1d] font-bold text-[9px] hover:bg-[#fecaca] cursor-pointer"
                          >
                            [시연] 거절
                          </button>
                        </div>
                      </div>
                    );
                  })
              )}

              {/* P3-1: Demo Cancel for Scheduled or Active Battles (Release Occupation) */}
              {state.battles.filter((b) => {
                const s = getEffectiveBattleStatus(b, effectiveNow);
                return s === 'scheduled' || s === 'active';
              }).length > 0 && (
                <div className="border-t border-[#b45309]/30 pt-1.5 flex flex-col gap-1">
                  <span className="font-pixel text-[10px] font-bold text-[#261608]">
                    확정 대결 취소 시연 (점유 해제 검증용)
                  </span>
                  {state.battles
                    .filter((b) => {
                      const s = getEffectiveBattleStatus(b, effectiveNow);
                      return s === 'scheduled' || s === 'active';
                    })
                    .map((b) => {
                      const reqA = allianceState.alliances.find((a) => a.id === b.requesterAllianceId);
                      const recA = allianceState.alliances.find((a) => a.id === b.recipientAllianceId);
                      const eff = getEffectiveBattleStatus(b, effectiveNow);
                      return (
                        <div
                          key={b.id}
                          className="p-1.5 bg-white border border-[#b45309]/40 flex items-center justify-between text-[9px] font-pixel"
                        >
                          <span className="font-bold text-[#261608]">
                            [{eff === 'scheduled' ? '예정' : '진행'}] {reqA?.name} vs {recA?.name}
                          </span>
                          <button
                            onClick={() => {
                              soundManager.playTabSound();
                              const res = demoCancelConfirmedRequest(b.id);
                              if (res.success) {
                                setFeedbackMessage({
                                  type: 'success',
                                  text: '확정 대결이 취소되고 양측 연합의 점유가 해제되었습니다.',
                                });
                              }
                            }}
                            className="px-2 py-0.5 bg-[#fee2e2] border border-[#dc2626] text-[#7f1d1d] font-bold text-[8px] hover:bg-[#fecaca] cursor-pointer"
                          >
                            [시연] 대결 취소(점유 해제)
                          </button>
                        </div>
                      );
                    })}
                </div>
              )}

              {/* Demo Clock Fast-Forward Controls */}
              <div className="border-t border-[#b45309]/30 pt-1.5 flex flex-col gap-1">
                <span className="font-pixel text-[10px] font-bold text-[#261608] flex items-center gap-1">
                  <FastForward className="w-3 h-3 text-[#b45309]" />
                  <span>시연용 가상 시계 (종료·남은 시간 검증)</span>
                </span>
                <div className="flex items-center gap-1 text-[9px] font-pixel">
                  <span>오프셋: +{Math.round(clockOffsetMs / 3600_000)}h</span>
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

              {/* Demo Network Failure Toggle */}
              <div className="border-t border-[#b45309]/30 pt-1.5 flex items-center justify-between text-[10px] font-pixel">
                <span className="text-[#8b5327]">네트워크 신청 오류 시뮬레이션:</span>
                <button
                  onClick={toggleSimulateRequestFailure}
                  className={`px-2 py-0.5 border text-[9px] font-bold cursor-pointer ${
                    state.simulateRequestFailure
                      ? 'bg-[#ef4444] text-white border-[#261608]'
                      : 'bg-white text-[#261608] border-[#261608]'
                  }`}
                >
                  {state.simulateRequestFailure ? '오류 발생 중' : '정상 동작'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>

      {onNavigateTab && <BottomNavFooter activeTab="alliance" onChangeTab={onNavigateTab} />}
    </PhoneShell>
  );
};
