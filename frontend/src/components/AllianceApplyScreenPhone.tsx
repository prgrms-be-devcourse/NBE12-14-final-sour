import type { SchoolId } from '../data/schoolIds.ts';
import React, { useState } from 'react';
import { PhoneShell } from './PhoneShell';
import { BottomNavFooter, type NavTab } from './BottomNav';
import { ChevronLeft, Search, AlertCircle, Shield, CheckCircle2, XCircle, Clock } from 'lucide-react';
import { useMockAlliance } from '../hooks/useMockAlliance.tsx';
import { REAL_UNIVERSITIES } from '../data/universitiesData.ts';
import { getSchoolInfo, getAllianceForSchool, type AllianceRequest } from '../data/allianceModel.ts';
import { MOCK_SHOP_CONFIG } from '../data/mockShopConfig.ts';
import { DurationPicker, formatDurationHours } from './DurationPicker.tsx';
import { ReceivedAllianceProposals } from './ReceivedAllianceProposals.tsx';
import { SharedImage } from './SharedImage.tsx';
import { soundManager } from '../utils/sound.ts';

interface AllianceApplyScreenPhoneProps {
  embedded?: boolean;
  onBack?: () => void;
  /** 하단 내비의 다른 탭으로 이동한다(T4, 계획서 §7). 없으면(단독 렌더링) 내비를 그리지 않는다. */
  onNavigateTab?: (tab: NavTab) => void;
  onNavigateToCurrentAlliance?: () => void;
}

export const AllianceApplyScreenPhone: React.FC<AllianceApplyScreenPhoneProps> = ({
  embedded = false,
  onBack = () => {},
  onNavigateTab,
  onNavigateToCurrentAlliance = () => {},
}) => {
  const {
    state,
    mySchoolId,
    effectiveNow,
    currentAlliance,
    sentRequests,
    receivedRequests,
    applyRequest,
    cancelRequest,
    demoAcceptRequest,
    demoRejectRequest,
    demoReceiveProposal,
    simulateRequestFailure,
    toggleSimulateRequestFailure,
  } = useMockAlliance();

  const mySchool = getSchoolInfo(mySchoolId);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedSchoolId, setSelectedSchoolId] = useState<SchoolId | null>(null);
  const [feedbackMessage, setFeedbackMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const durationConfig = MOCK_SHOP_CONFIG.alliance.durationHours;
  const [durationHours, setDurationHours] = useState(durationConfig.default);
  // 같은 두 학교 사이 대기 제안은 방향과 관계없이 1건이므로, 내가 보낸 제안이나 받은 제안이 대기 중인 학교는 고를 수 없다
  const pendingTargetIds = new Set(sentRequests.filter((r) => r.status === 'pending').map((r) => r.recipientSchoolId));
  const pendingProposerIds = new Set(receivedRequests.filter((r) => r.status === 'pending').map((r) => r.requesterSchoolId));

  // Search filtered candidates (exclude self-school)
  const candidateSchools = REAL_UNIVERSITIES.filter((u) => {
    if (u.id === mySchoolId) return false;
    if (!searchQuery.trim()) return true;
    const query = searchQuery.trim().toLowerCase();
    return u.name.toLowerCase().includes(query) || u.region.toLowerCase().includes(query) || u.shortName.toLowerCase().includes(query);
  });

  const selectedSchool = selectedSchoolId !== null ? getSchoolInfo(selectedSchoolId) : null;

  const handleSelectSchool = (schoolId: SchoolId) => {
    soundManager.playTabSound();
    setSelectedSchoolId(schoolId);
    setFeedbackMessage(null);
  };

  const handleSendApplication = () => {
    if (selectedSchoolId === null) return;
    soundManager.playBuySound();
    const result = applyRequest(selectedSchoolId, durationHours);
    if (result.success) {
      setFeedbackMessage({
        type: 'success',
        text: `${selectedSchool?.name}에 연합 신청을 성공적으로 보냈습니다. (연합 기간 ${formatDurationHours(durationHours)})`,
      });
      setSelectedSchoolId(null);
    } else {
      setFeedbackMessage({ type: 'error', text: result.error });
    }
  };

  const handleCancelRequest = (requestId: string) => {
    soundManager.playTabSound();
    const result = cancelRequest(requestId);
    if (result.success) {
      setFeedbackMessage({ type: 'success', text: '신청이 정상적으로 취소되었습니다.' });
    } else {
      setFeedbackMessage({ type: 'error', text: result.error });
    }
  };

  const handleDemoAccept = (requestId: string) => {
    soundManager.playLevelUpSound();
    const result = demoAcceptRequest(requestId);
    if (result.success) {
      setFeedbackMessage({ type: 'success', text: `[시연] 상대 학교 학생 1명이 수락하여 '${result.alliance?.name}'이 결성되었습니다!` });
    } else {
      setFeedbackMessage({ type: 'error', text: result.error });
    }
  };

  const handleDemoReceive = () => {
    soundManager.playTabSound();
    const result = demoReceiveProposal();
    if (result.success) {
      const proposer = getSchoolInfo(result.request!.requesterSchoolId);
      setFeedbackMessage({ type: 'success', text: `[시연] ${proposer.name}에서 연합 제안이 도착했습니다.` });
    } else {
      setFeedbackMessage({ type: 'error', text: result.error });
    }
  };

  const handleDemoReject = (requestId: string) => {
    soundManager.playTabSound();
    const result = demoRejectRequest(requestId);
    if (result.success) {
      setFeedbackMessage({ type: 'success', text: '[시연] 상대 학교가 연합 신청을 거절했습니다.' });
    } else {
      setFeedbackMessage({ type: 'error', text: result.error });
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
            <span className="font-pixel text-[11px] font-black text-white bg-[#0284c7] border border-[#261608] px-1 shadow-[1px_1px_0px_#261608]">
              대항전
            </span>
            <h1 className="font-pixel text-sm font-black text-[#261608]">연합 신청</h1>
          </div>
        </div>

        <button
          onClick={() => {
            soundManager.playTabSound();
            onNavigateToCurrentAlliance();
          }}
          className="pixel-plaque plaque-yellow px-2 py-1 text-[11px] font-pixel font-bold flex items-center gap-1 cursor-pointer"
        >
          <Shield className="w-3 h-3 text-[#b45309]" />
          <span>현재 연합</span>
        </button>
      </div>

      {/* 3. Main Scrollable Content */}
      <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-3">
        {/* Feedback Alert */}
        {feedbackMessage && (
          <div
            role="status"
            className={`p-2 border-2 border-[#261608] text-xs font-pixel font-bold flex items-start gap-1.5 shadow-[2px_2px_0px_#261608] ${
              feedbackMessage.type === 'success' ? 'bg-[#dcfce7] text-[#166534]' : 'bg-[#fee2e2] text-[#991b1b]'
            }`}
          >
            <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
            <span className="flex-1">{feedbackMessage.text}</span>
          </div>
        )}

        {/* Section: My School Info */}
        <div className="rpg-box p-2.5 bg-[#fef08a] border-2 border-[#261608] flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 border-2 border-[#261608] shadow-[1px_1px_0px_#261608] flex items-center justify-center bg-[#0284c7]">
              <SharedImage
                target={{ type: 'school', id: mySchoolId }}
                name={mySchool.name}
                fallback={mySchool.firstSyllable}
                className="w-full h-full font-pixel text-sm font-black text-white"
              />
            </div>
            <div>
              <span className="text-[10px] font-pixel text-[#8b5327] font-bold">신청 주체 (내 학교)</span>
              <p className="font-pixel text-xs font-black text-[#261608]">{mySchool.name}</p>
            </div>
          </div>
          <span className="text-[9px] font-pixel px-1.5 py-0.5 bg-[#facc15] border border-[#261608] font-bold text-[#261608]">
            {mySchool.region}
          </span>
        </div>

        {/* Section: Target School Search & Selection */}
        <div className="rpg-box p-2.5 bg-[#ffffff] border-2 border-[#261608] flex flex-col gap-2">
          <label className="font-pixel text-xs font-bold text-[#261608] flex items-center gap-1">
            <Search className="w-3.5 h-3.5 text-[#8b5327]" />
            <span>대상 학교 검색 및 선택</span>
          </label>
          <p className="font-pixel text-[9px] text-[#8b5327] -mt-1 leading-tight">
            ※ 연합은 2개교로만 맺으며 한 학교는 활성 연합 하나에만 소속됩니다. 여러 학교에 동시에 제안할 수 있고,
            하나가 성립하면 두 학교에 걸린 나머지 대기 제안은 자동 거절됩니다.
          </p>
          {currentAlliance && (
            <p role="note" className="font-pixel text-[10px] font-bold text-[#991b1b] bg-[#fee2e2] border border-[#991b1b] p-1.5">
              이미 {currentAlliance.name}에 소속되어 있습니다. 연합이 만료된 뒤 새 제안을 보낼 수 있습니다.
            </p>
          )}

          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="학교 이름 또는 지역 검색 (예: 고려, 부산)"
            aria-label="대상 학교 검색"
            className="w-full px-2 py-1 text-xs font-pixel border-2 border-[#261608] bg-[#fdf5df] focus:bg-white outline-none"
          />

          {/* Candidate School List or Zero Search Result */}
          <div className="max-h-36 overflow-y-auto border-2 border-[#261608]/40 bg-[#faecc7]/20 p-1.5 flex flex-col gap-2.5">
            {candidateSchools.length === 0 ? (
              <div
                role="region"
                aria-label="검색 결과 없음"
                className="p-3 text-center font-pixel text-xs text-[#8b5327] font-bold bg-[#fdf5df]"
              >
                검색 결과가 없습니다
              </div>
            ) : (
              candidateSchools.map((school) => {
                const isSelected = selectedSchoolId === school.id;
                const existingAlliance = getAllianceForSchool(state.alliances, school.id, effectiveNow);
                const isPendingTarget = pendingTargetIds.has(school.id);
                const isPendingProposer = pendingProposerIds.has(school.id);
                return (
                  <button
                    key={school.id}
                    type="button"
                    onClick={() => handleSelectSchool(school.id)}
                    disabled={isPendingTarget || isPendingProposer}
                    className={`p-1.5 border flex items-center justify-between text-left cursor-pointer transition-colors disabled:opacity-60 disabled:cursor-not-allowed ${
                      isSelected
                        ? 'bg-[#fef08a] border-[#261608] shadow-[1px_1px_0px_#261608]'
                        : 'bg-[#ffffff] border-[#261608]/30 hover:bg-[#fffbeb]'
                    }`}
                  >
                    <div className="flex items-center gap-1.5 min-w-0">
                      <div className="w-5 h-5 border border-[#261608] shrink-0">
                        <SharedImage
                          target={{ type: 'school', id: school.id }}
                          name={school.name}
                          fallback={school.firstSyllable}
                          className="w-full h-full text-[10px]"
                        />
                      </div>
                      <span className="font-pixel text-xs font-bold text-[#261608] truncate">{school.name}</span>
                    </div>
                    <div className="flex items-center gap-1 shrink-0">
                      {existingAlliance && (
                        <span className="font-pixel text-[8px] px-1 py-0.2 bg-stone-200 border border-[#261608] text-[#8b5327]">
                          소속 연합 있음
                        </span>
                      )}
                      {isPendingTarget && (
                        <span className="font-pixel text-[8px] px-1 py-0.2 bg-[#fef08a] border border-[#b45309] text-[#b45309]">
                          제안 대기 중
                        </span>
                      )}
                      {isPendingProposer && (
                        <span className="font-pixel text-[8px] px-1 py-0.2 bg-[#dcfce7] border border-[#166534] text-[#166534]">
                          받은 제안 있음
                        </span>
                      )}
                      <span className="font-pixel text-[9px] text-[#8b5327]">({school.region})</span>
                    </div>
                  </button>
                );
              })
            )}
          </div>
        </div>

        {/* Section: Selected School Summary & Submit Button */}
        {selectedSchool && (
          <div className="rpg-box p-2.5 bg-[#fefce8] border-2 border-[#261608] flex flex-col gap-2 shadow-[2px_2px_0px_#261608]">
            <span className="font-pixel text-xs font-black text-[#b45309]">신청 요약</span>
            <div className="flex items-center justify-between bg-white border border-[#261608] p-2">
              <div className="flex items-center gap-2">
                <div className="w-7 h-7 border border-[#261608]">
                  <SharedImage
                    target={{ type: 'school', id: selectedSchool.id }}
                    name={selectedSchool.name}
                    fallback={selectedSchool.firstSyllable}
                    className="w-full h-full text-xs"
                  />
                </div>
                <div>
                  <p className="font-pixel text-xs font-black text-[#261608]">{selectedSchool.name}</p>
                  <span className="font-pixel text-[9px] text-[#8b5327]">{selectedSchool.region}</span>
                </div>
              </div>
              <span className="font-pixel text-[10px] font-bold text-[#0284c7] bg-[#e0f2fe] border border-[#0284c7] px-1.5 py-0.5">
                2개교 연합
              </span>
            </div>

            <DurationPicker
              label="연합 기간"
              value={durationHours}
              min={durationConfig.min}
              max={durationConfig.max}
              presets={durationConfig.presets}
              onChange={setDurationHours}
            />
            <p className="font-pixel text-[9px] text-[#8b5327] leading-tight">
              ※ 상대 학교 학생 1명이 수락하는 순간부터 {formatDurationHours(durationHours)} 동안 연합이 유지됩니다.
            </p>

            <button
              onClick={handleSendApplication}
              className="pixel-plaque plaque-yellow py-2 text-center font-pixel text-xs font-black cursor-pointer shadow-[2px_2px_0px_#261608]"
            >
              연합 신청 보내기
            </button>
          </div>
        )}

        <ReceivedAllianceProposals onResult={setFeedbackMessage} />

        {/* Section: Sent Request List */}
        <div className="rpg-box p-2.5 bg-[#ffffff] border-2 border-[#261608] flex flex-col gap-2">
          <span className="font-pixel text-xs font-black text-[#261608]">보낸 제안 내역</span>

          {sentRequests.length === 0 ? (
            <p className="font-pixel text-[11px] text-[#8b5327] py-2 text-center">진행 중인 연합 신청 내역이 없습니다.</p>
          ) : (
            <div className="flex flex-col gap-2">
              {sentRequests.map((req: AllianceRequest) => {
                const targetSchool = getSchoolInfo(req.recipientSchoolId);
                return (
                  <div
                    key={req.id}
                    className="p-2 border border-[#261608] bg-[#fdf5df] flex flex-col gap-1.5 shadow-[1px_1px_0px_#261608]"
                  >
                    <div className="flex items-center justify-between text-xs font-pixel">
                      <div className="flex items-center gap-1.5">
                        <div className="w-5 h-5 border border-[#261608]">
                          <SharedImage
                            target={{ type: 'school', id: targetSchool.id }}
                            name={targetSchool.name}
                            fallback={targetSchool.firstSyllable}
                            className="w-full h-full text-[10px]"
                          />
                        </div>
                        <span className="font-bold text-[#261608]">{targetSchool.name} (보낸 신청)</span>
                      </div>

                      {/* Status Badge */}
                      <span
                        className={`text-[9px] px-1.5 py-0.5 border font-bold flex items-center gap-0.5 ${
                          req.status === 'pending'
                            ? 'bg-[#fef08a] border-[#b45309] text-[#b45309]'
                            : req.status === 'accepted'
                            ? 'bg-[#dcfce7] border-[#166534] text-[#166534]'
                            : req.status === 'rejected'
                            ? 'bg-[#fee2e2] border-[#991b1b] text-[#991b1b]'
                            : 'bg-[#f3f4f6] border-[#4b5563] text-[#4b5563]'
                        }`}
                      >
                        {req.status === 'pending' && <Clock className="w-2.5 h-2.5" />}
                        {req.status === 'accepted' && <CheckCircle2 className="w-2.5 h-2.5" />}
                        {req.status === 'rejected' && <XCircle className="w-2.5 h-2.5" />}
                        {req.status === 'pending'
                          ? '대기 중'
                          : req.status === 'accepted'
                          ? '수락됨'
                          : req.status === 'rejected'
                          ? req.closedReason === 'capacity'
                            ? '자동 거절 (다른 연합 성립)'
                            : '거절됨'
                          : '취소됨'}
                      </span>
                    </div>
                    <span className="font-pixel text-[9px] text-[#8b5327]">연합 기간 {formatDurationHours(req.durationHours)}</span>

                    {/* Normal User Action: Cancel only */}
                    {req.status === 'pending' && (
                      <div className="flex justify-end">
                        <button
                          onClick={() => handleCancelRequest(req.id)}
                          className="px-2 py-0.5 bg-[#f3f4f6] border border-[#261608] text-[10px] font-pixel font-bold text-[#4b5563] hover:bg-stone-200 cursor-pointer"
                        >
                          신청 취소
                        </button>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Section: DEMO CONTROLS (Clearly separated from normal UI) */}
        <div
          role="region"
          aria-label="시연용 상대 학교 제어 영역"
          data-no-touch44
          className="rpg-box p-2.5 bg-[#fef2f2] border-2 border-dashed border-[#b91c1c] flex flex-col gap-2"
        >
          <div className="flex items-center gap-1 text-[#b91c1c]">
            <Shield className="w-3.5 h-3.5" />
            <span className="font-pixel text-[11px] font-black">🧪 상대 학교 시연 제어 (DEMO CONTROLS)</span>
          </div>
          <p className="font-pixel text-[9px] text-[#7f1d1d] leading-tight">
            ※ 실제 서비스에서는 상대 학교 학생 누구든 1명이 수락하면 성립합니다. 아래는 그 상황을 흉내 내는 목업 시연용 버튼입니다.
          </p>

          <button
            type="button"
            onClick={handleDemoReceive}
            className="py-1 bg-white text-[#0e7490] border border-[#0e7490] font-pixel text-[10px] font-black cursor-pointer hover:bg-[#ecfeff]"
          >
            [시연] 다른 학교의 연합 제안 받기
          </button>

          {/* Pending requests actionable by demo control */}
          {sentRequests.filter((r: AllianceRequest) => r.status === 'pending').length === 0 ? (
            <p className="font-pixel text-[10px] text-[#7f1d1d] italic">현재 대기 중인 신청이 없습니다.</p>
          ) : (
            sentRequests
              .filter((r: AllianceRequest) => r.status === 'pending')
              .map((req: AllianceRequest) => {
                const targetSchool = getSchoolInfo(req.recipientSchoolId);
                return (
                  <div key={`demo-${req.id}`} className="bg-white p-2 border border-[#b91c1c] flex flex-col gap-1.5">
                    <span className="font-pixel text-[10px] font-bold text-[#261608]">
                      대상: {targetSchool.name}
                    </span>
                    <div className="flex items-center gap-1.5">
                      <button
                        onClick={() => handleDemoAccept(req.id)}
                        className="flex-1 py-1 bg-[#22c55e] text-white border border-[#261608] font-pixel text-[10px] font-black cursor-pointer hover:bg-green-600"
                      >
                        [시연] 상대 학교 명의로 수락
                      </button>
                      <button
                        onClick={() => handleDemoReject(req.id)}
                        className="flex-1 py-1 bg-[#ef4444] text-white border border-[#261608] font-pixel text-[10px] font-black cursor-pointer hover:bg-red-600"
                      >
                        [시연] 상대 학교 명의로 거절
                      </button>
                    </div>
                  </div>
                );
              })
          )}

          {/* Request failure toggle */}
          <div className="pt-1 border-t border-[#fca5a5] flex items-center justify-between">
            <span className="font-pixel text-[10px] text-[#7f1d1d] font-bold">네트워크 오류 시연:</span>
            <button
              onClick={toggleSimulateRequestFailure}
              className={`px-2 py-0.5 border border-[#261608] text-[9px] font-pixel font-bold cursor-pointer ${
                simulateRequestFailure ? 'bg-[#b91c1c] text-white' : 'bg-white text-[#7f1d1d]'
              }`}
            >
              {simulateRequestFailure ? '오류 강제 활성화 중' : '정상 동작 중'}
            </button>
          </div>
        </div>
      </div>

      {onNavigateTab && <BottomNavFooter activeTab="alliance" onChangeTab={onNavigateTab} />}
    </PhoneShell>
  );
};
