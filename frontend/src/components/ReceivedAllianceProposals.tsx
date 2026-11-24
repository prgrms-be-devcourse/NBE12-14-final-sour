// 우리 학교가 받은 연합 제안 (연합 신청 화면·현재 연합 화면 공용).
// 연합장 같은 리더가 없으므로 우리 학교 학생 누구든 수락·거절할 수 있고, 1명이 수락하면 즉시 성립한다.
import React from 'react';
import { Inbox } from 'lucide-react';
import { useMockAlliance } from '../hooks/useMockAlliance.tsx';
import { getSchoolInfo, type AllianceActionResult } from '../data/allianceModel.ts';
import { formatDurationHours } from './DurationPicker.tsx';
import { SharedImage } from './SharedImage.tsx';
import { soundManager } from '../utils/sound.ts';

export const RECEIVED_PROPOSAL_RULE_TEXT =
  '우리 학교 학생 누구나 수락할 수 있으며, 1명이 수락하면 즉시 연합이 성립합니다.';

export const ReceivedAllianceProposals: React.FC<{
  onResult: (feedback: { type: 'success' | 'error'; text: string }) => void;
}> = ({ onResult }) => {
  const { receivedRequests, acceptRequest, rejectRequest, currentAlliance } = useMockAlliance();
  const pending = receivedRequests.filter((r) => r.status === 'pending');
  if (pending.length === 0) return null;

  const report = (result: AllianceActionResult, successText: (r: AllianceActionResult & { success: true }) => string) => {
    onResult(result.success ? { type: 'success', text: successText(result) } : { type: 'error', text: result.error });
  };

  return (
    <section
      aria-label="받은 연합 제안"
      className="rpg-box p-2.5 bg-[#ecfeff] border-2 border-[#261608] flex flex-col gap-2 shadow-[2px_2px_0px_#261608]"
    >
      <span className="font-pixel text-xs font-black text-[#0e7490] flex items-center gap-1">
        <Inbox className="w-3.5 h-3.5" />
        <span>받은 연합 제안 ({pending.length}건)</span>
      </span>
      <p className="font-pixel text-[9px] text-[#155e75] leading-tight">※ {RECEIVED_PROPOSAL_RULE_TEXT}</p>
      {currentAlliance && (
        <p className="font-pixel text-[9px] text-[#991b1b] leading-tight">
          ※ 이미 {currentAlliance.name}에 소속되어 있어 만료 전에는 수락할 수 없습니다.
        </p>
      )}

      {pending.map((req) => {
        const proposer = getSchoolInfo(req.requesterSchoolId);
        return (
          <div key={req.id} className="p-2 bg-white border border-[#261608] flex flex-col gap-1.5">
            <div className="flex items-center justify-between gap-2 text-xs font-pixel">
              <div className="flex items-center gap-1.5 min-w-0">
                <div className="w-5 h-5 border border-[#261608] shrink-0">
                  <SharedImage
                    target={{ type: 'school', id: proposer.id }}
                    name={proposer.name}
                    fallback={proposer.firstSyllable}
                    className="w-full h-full text-[10px]"
                  />
                </div>
                <span className="font-bold text-[#261608] truncate">{proposer.name}</span>
              </div>
              <span className="text-[10px] font-bold text-[#0e7490] shrink-0">
                연합 기간 {formatDurationHours(req.durationHours)}
              </span>
            </div>
            <div className="grid grid-cols-2 gap-1.5">
              <button
                type="button"
                onClick={() => {
                  soundManager.playLevelUpSound();
                  report(acceptRequest(req.id), (r) => `'${r.alliance?.name}'이 결성되었습니다! (${formatDurationHours(req.durationHours)})`);
                }}
                aria-label={`${proposer.name}의 연합 제안 수락`}
                className="pixel-plaque plaque-yellow py-1 font-pixel text-xs font-black cursor-pointer"
              >
                수락
              </button>
              <button
                type="button"
                onClick={() => {
                  soundManager.playTabSound();
                  report(rejectRequest(req.id), () => `${proposer.name}의 연합 제안을 거절했습니다.`);
                }}
                aria-label={`${proposer.name}의 연합 제안 거절`}
                className="pixel-plaque plaque-wood py-1 font-pixel text-xs font-bold cursor-pointer"
              >
                거절
              </button>
            </div>
          </div>
        );
      })}
    </section>
  );
};
