import { useState } from 'react';
import type { InvitationRow } from '../api/allianceLists';
import { ALLIANCE_ERROR_CODES, useDecideInvitation, type ApplicationDecision } from '../api/alliances';
import { errorMessage, isApiError } from '../api/errors';
import { AllianceConfirmation } from './AllianceConfirmation';
import { AllianceInvitationList } from './AllianceListPanels';

function decisionError(error: unknown): string {
  if (!isApiError(error) || error.kind !== 'http') {
    return '서버 응답을 확인하지 못했어요. 다시 시도하기 전에 초대 목록과 우리 연합을 확인해 주세요.';
  }
  switch (error.code) {
    case ALLIANCE_ERROR_CODES.applicationNotPending: return '이미 처리된 초대예요.';
    case ALLIANCE_ERROR_CODES.full: return '연합 정원이 찼어요.';
    case ALLIANCE_ERROR_CODES.alreadyJoined: return '우리 학교가 이미 다른 연합에 속해 있어요.';
    default: return `연합 초대를 처리하지 못했어요. ${errorMessage(error)}`;
  }
}

export function AllianceInvitationPanel({ userId, allianceId, visible }: {
  userId: number; allianceId?: number; visible: boolean;
}) {
  const decide = useDecideInvitation(userId);
  const [pending, setPending] = useState<{ item: InvitationRow; decision: ApplicationDecision } | null>(null);
  const approving = pending?.decision === 'approve';

  return <>
    <h2 className="text-sm font-black">연합 초대</h2>
    <AllianceInvitationList userId={userId} visible={visible} busy={decide.isPending}
      onDecide={(item, decision) => setPending({ item, decision })} />
    {allianceId !== undefined && <AllianceInvitationList userId={userId} allianceId={allianceId} visible={visible} />}
    {pending && visible && <AllianceConfirmation label={approving ? '초대 승인' : '초대 거절'}
      onCancel={() => setPending(null)} onConfirm={() => {
        setPending(null);
        decide.mutate({ invitationId: pending.item.id, decision: pending.decision });
      }}>
      <p className="break-words">{pending.item.alliance.name}에서 보낸 연합 초대</p>
      <p>{approving ? '우리 학교가 이 연합에 들어와요.' : '우리 학교는 이 연합에 들어가지 않아요.'}</p>
    </AllianceConfirmation>}
    {decide.isPending && <p className="text-xs">연합 초대를 처리하는 중이에요.</p>}
    {decide.isSuccess && <p role="status" className="text-xs">{decide.data.status === 'ACCEPTED' ? '연합에 가입했어요.' : '연합 초대를 거절했어요.'}</p>}
    {decide.isError && <p role="alert" className="text-xs text-[#7f1d1d]">{decisionError(decide.error)}</p>}
  </>;
}
