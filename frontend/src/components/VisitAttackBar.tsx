import { CAMPUS_ACTION_LABEL, type CampusActionKind, type CampusActionResult, type CampusAttacks } from '../hooks/useCampusAttacks';
import { formatScore } from './VisitingParts';

const HINT: Record<CampusActionKind, string> = {
  attack: '기여도는 오르지 않아요. 실제 피해량과 점수는 서버가 정합니다.',
  assist: '연합 학교 점수는 클릭의 0.3배, 내 기여도는 클릭만큼 올라요. 실제 점수는 서버가 정합니다.',
};
const WAITING: Record<CampusActionKind, string> = {
  attack: '공격 결과를 받으면 라이벌 여부와 배율을 표시해요.',
  assist: '지원 결과를 받으면 오른 점수를 표시해요.',
};

function describe(result: CampusActionResult): string {
  if (result.kind === 'assist') {
    return `연합 지원 · ${result.acceptedClicks}회 · 학교 +${formatScore(result.assistScore)} · 상대 ${formatScore(result.targetUniversity.totalScore)} · 내 기여도 ${formatScore(result.myContribScore)}${result.assistScore === 0 ? ' (소수점 버림으로 오른 점수가 없을 수 있어요)' : ''}`;
  }
  return `${result.isRival ? '라이벌' : '일반'} 공격 ×${result.multiplier.toFixed(1)} · ${result.acceptedClicks}회 · 피해 ${formatScore(result.damage)} · 상대 ${formatScore(result.targetUniversity.totalScore)}점${result.damage === 0 ? ' (소수점 버림·잔여 점수에 따라 피해가 없을 수 있어요)' : ''}`;
}

/** Confirmed results only; live announcements are batched responses, not every tap. `kind` is this school's mode. */
export function VisitAttackBar({ attacks, schoolId, onBackToMySchool, kind = 'attack' }: {
  attacks: CampusAttacks; schoolId: number; onBackToMySchool: () => void; kind?: CampusActionKind;
}) {
  const result = attacks.result?.targetUniversity.id === schoolId && attacks.result.kind === kind ? attacks.result : null;
  const otherTarget = attacks.pendingTargetId !== null && attacks.pendingTargetId !== schoolId;
  const label = CAMPUS_ACTION_LABEL[kind];
  return (
    <div data-testid="visit-attack-bar" data-mode={kind} className="font-pixel flex flex-col gap-1.5 px-2 py-2 text-[#261608] bg-[#fff7ed] border-y-2 border-[#261608]">
      <div className="flex items-center justify-between gap-2">
        <p className="text-[11px] font-bold">캠퍼스를 누르면 {label} · 1초 단위 전송</p>
        <button type="button" onClick={onBackToMySchool} className="pixel-plaque plaque-parchment min-h-11 px-2 text-[10px] font-bold cursor-pointer shrink-0">내 학교로</button>
      </div>
      <p className="text-[10px]">{otherTarget ? `이전 학교의 ${CAMPUS_ACTION_LABEL[attacks.pendingKind ?? 'attack']} 전송을 기다리는 중이에요.` : attacks.pendingClicks ? `미확정 ${label} ${attacks.pendingClicks}회 · 전송이 끝나기 전에 새로고침하지 마세요.` : HINT[kind]}</p>
      <p role="status" data-testid="attack-result" className="text-[11px] font-bold">
        {result ? describe(result) : WAITING[kind]}
      </p>
    </div>
  );
}

/** App-root recovery stays reachable even after navigating away from the attacked school. */
export function AttackNotice({ attacks }: { attacks: CampusAttacks | null }) {
  if (!attacks?.notice) return null;
  const label = CAMPUS_ACTION_LABEL[attacks.pendingKind ?? 'attack'];
  return (
    <div role="alert" data-testid="attack-notice" className="w-full max-w-3xl border-2 border-[#8b5327] bg-[#fffbeb] p-3 text-sm font-pixel">
      <p>{attacks.notice}</p>
      {attacks.retryLimited && <button type="button" onClick={attacks.retryLimited} className="pixel-plaque plaque-yellow min-h-12 mt-2 px-3 py-2 text-xs font-bold cursor-pointer">거절된 배치를 제외하고 {label} 재개</button>}
      {attacks.botCheck && <button type="button" onClick={attacks.botCheck.onOpen} className="pixel-plaque plaque-yellow min-h-12 mt-2 px-3 py-2 text-xs font-bold cursor-pointer">{label} 자동 입력 방지 확인</button>}
    </div>
  );
}
