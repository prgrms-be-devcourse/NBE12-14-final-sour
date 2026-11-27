import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { useAuthSession } from '../api/ApiProvider';
import { ALLIANCE_ERROR_CODES, useApplyToAlliance, useInviteUniversity, useUniversityAlliance, type UniversityAlliance } from '../api/alliances';
import { errorMessage, isApiError } from '../api/errors';
import { useMyInfo } from '../api/users';
import type { RankingBoardRow } from '../hooks/useRankingBoard';

export interface RankingSchool { id: number; name: string; isMine: boolean }
export interface RankingSchoolNavigation {
  onVisitSchool?: (id: number) => void;
  onGoToLogin?: () => void;
  visitBlocked?: string;
  allianceActionsEnabled?: boolean;
}

const BUTTON = 'pixel-plaque plaque-wood min-h-11 min-w-11 px-3 py-2 text-xs font-bold disabled:opacity-50';

export function RankingSchoolTrigger({ row, open, className, children }: {
  row: RankingBoardRow; open?: (school: RankingSchool) => void; className: string; children: ReactNode;
}) {
  const { schoolId } = row;
  if (schoolId === undefined || !open) return <span className={className}>{children}</span>;
  return <button type="button" aria-haspopup="dialog" aria-label={`${row.name} 학교 메뉴`}
    className={`${className} min-h-6 rounded-sm cursor-pointer hover:underline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-current`}
    onClick={() => open({ id: schoolId, name: row.name, isMine: row.isMine })}>{children}</button>;
}

export function RankingSchoolMenu({ school, source, onVisitSchool, onGoToLogin, visitBlocked, allianceActionsEnabled, close }:
  RankingSchoolNavigation & { school: RankingSchool; source: 'mock' | 'api'; close: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const title = useId();
  const [lock, setLock] = useState(false);
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    dialog.showModal();
    closeButton.current?.focus();
    return () => { if (dialog.open) dialog.close(); };
  }, []);

  const dismiss = () => { ref.current?.close(); close(); };
  return <dialog ref={ref} aria-labelledby={title} onClose={(event) => { if (!event.currentTarget.open) close(); }}
    onCancel={(event) => { if (lock) event.preventDefault(); }}
    className="item-purchase-dialog rpg-box bg-[#fffbeb] p-4 text-[#261608] font-pixel">
    <h1 id={title} className="text-sm font-black break-words">{school.name}</h1>
    <div className="mt-3 flex flex-col items-start gap-2 text-xs">
      {onVisitSchool && <button type="button" className={BUTTON} disabled={!!visitBlocked || lock}
        onClick={() => { dismiss(); onVisitSchool(school.id); }}>캠퍼스로 이동</button>}
      {visitBlocked && <p>{visitBlocked}</p>}
      {source === 'api' && !school.isMine && <ApiSchoolAlliance school={school} onGoToLogin={onGoToLogin}
        enabled={!!allianceActionsEnabled} onBusy={setLock} />}
      <button type="button" ref={closeButton} className={BUTTON} disabled={lock} onClick={dismiss}>닫기</button>
    </div>
  </dialog>;
}

function ApiSchoolAlliance({ school, enabled, onGoToLogin, onBusy }: {
  school: RankingSchool; enabled: boolean; onGoToLogin?: () => void; onBusy: (busy: boolean) => void;
}) {
  const session = useAuthSession();
  const me = useMyInfo();
  if (session.status !== 'signed-in') return <>
    <p>연합 기능을 이용하려면 로그인해 주세요.</p>
    {onGoToLogin && <button type="button" className={BUTTON} onClick={onGoToLogin}>로그인</button>}
  </>;
  if (!enabled) return null;
  if (me.isError) return <><p role="alert">내 학교를 확인하지 못했어요.</p>
    <button type="button" className={BUTTON} onClick={() => void me.refetch()}>다시 시도</button></>;
  if (!me.data) return <p>내 학교를 확인하는 중이에요.</p>;
  if (me.data.university.id === school.id) return null;
  if (me.data.status !== 'ACTIVE') return <p>활동 중인 회원만 연합 기능을 이용할 수 있어요.</p>;
  return <SchoolAllianceActions key={me.data.id} userId={me.data.id} ownSchoolId={me.data.university.id} school={school} onBusy={onBusy} />;
}

type AllianceAction = { kind: 'invite' | 'apply'; alliance: UniversityAlliance };

function eligibleAction(own: UniversityAlliance | null, target: UniversityAlliance | null): AllianceAction | null {
  if (own && !target) return { kind: 'invite', alliance: own };
  if (!own && target) return { kind: 'apply', alliance: target };
  return null;
}

function blockedReason(alliance: UniversityAlliance) {
  if (alliance.status === 'IN_MATCH') return '대결 중인 연합에는 지금 요청할 수 없어요.';
  if (alliance.status === 'EXPIRED') return '만료된 연합에는 요청할 수 없어요.';
  return alliance.joinable ? null : '연합 정원이 찼어요.';
}

function isUncertain(error: unknown) {
  return !isApiError(error) || error.kind !== 'http' || error.status === null || error.status >= 500;
}

function requestError(error: unknown) {
  if (isUncertain(error) || !isApiError(error)) return '응답을 확인하지 못했어요. 다시 보내기 전에 연합 화면의 보낸 내역을 확인해 주세요.';
  switch (error.code) {
    case ALLIANCE_ERROR_CODES.applicationPending: return '이미 대기 중인 초대나 가입 신청이 있어요. 연합 화면에서 내역을 확인해 주세요.';
    case ALLIANCE_ERROR_CODES.alreadyJoined: return '학교의 연합 소속이 바뀌었어요.';
    case ALLIANCE_ERROR_CODES.full: return '연합 정원이 찼어요.';
    case ALLIANCE_ERROR_CODES.notJoinable: return '지금은 대결 중이거나 만료된 연합이라 요청할 수 없어요.';
    default: return errorMessage(error);
  }
}

function SchoolAllianceActions({ userId, ownSchoolId, school, onBusy }: {
  userId: number; ownSchoolId: number; school: RankingSchool; onBusy: (busy: boolean) => void;
}) {
  const own = useUniversityAlliance(userId, ownSchoolId);
  const target = useUniversityAlliance(userId, school.id);
  const applied = useApplyToAlliance(userId);
  const invited = useInviteUniversity(userId);
  const [confirmation, setConfirmation] = useState<AllianceAction | null>(null);
  const sending = useRef(false);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const actionRef = useRef<HTMLButtonElement>(null);
  const restoreActionFocus = useRef(false);
  const busy = applied.isPending || invited.isPending;
  const error = applied.error ?? invited.error;
  const uncertain = error && isUncertain(error);
  const result = invited.isSuccess ? '연합 초대를 보냈어요.' : applied.isSuccess ? '연합 가입 신청을 보냈어요.' : null;
  const action = own.isSuccess && target.isSuccess ? eligibleAction(own.data, target.data) : null;
  const blocked = action && blockedReason(action.alliance);
  const fetching = own.isFetching || target.isFetching;
  const fresh = own.isFetchedAfterMount && target.isFetchedAfterMount && !fetching;
  const retry = () => { void Promise.all([own.refetch(), target.refetch()]); };
  useEffect(() => {
    if (confirmation) cancelRef.current?.focus();
    else if (restoreActionFocus.current) {
      restoreActionFocus.current = false;
      actionRef.current?.focus();
    }
  }, [confirmation]);

  const submit = async () => {
    if (sending.current || !confirmation || !fresh || blocked || action?.kind !== confirmation.kind
      || action.alliance.id !== confirmation.alliance.id) return;
    sending.current = true;
    onBusy(true);
    setConfirmation(null);
    applied.reset();
    invited.reset();
    try {
      if (confirmation.kind === 'invite') await invited.mutateAsync({ allianceId: confirmation.alliance.id, universityId: school.id });
      else await applied.mutateAsync(confirmation.alliance.id);
    } catch { /* The mutation owns the error shown below. */ }
    finally { sending.current = false; onBusy(false); }
  };

  if (result) return <p role="status">{result}</p>;
  if (busy) return <p role="status">연합 요청을 보내는 중이에요.</p>;
  if (own.isError || target.isError) return <>
    <p role="alert">{error ? requestError(error) : `연합 정보를 확인하지 못했어요. ${errorMessage(own.error ?? target.error)}`}</p>
    {!fetching && <button type="button" className={BUTTON} onClick={retry}>연합 정보 다시 시도</button>}
  </>;
  if (own.fetchStatus === 'paused' || target.fetchStatus === 'paused') return <p>연결이 복구되면 연합 정보를 확인해요.</p>;
  if (own.isPending || target.isPending) return <p>연합 정보를 확인하는 중이에요.</p>;

  const label = action?.kind === 'invite' ? '연합 초대' : '연합 가입 신청';
  const canConfirm = confirmation && fresh && !blocked && action?.kind === confirmation.kind && action.alliance.id === confirmation.alliance.id;
  return <>
    {error && <p role="alert">{requestError(error)}</p>}
    {confirmation ? <div className="flex flex-col gap-2 border-2 border-[#261608] p-2">
      <p>{confirmation.kind === 'invite' ? `${confirmation.alliance.name}에서 ${school.name}에 초대를 보내요.`
        : `우리 학교가 ${school.name} 소속 연합 ‘${confirmation.alliance.name}’에 가입을 신청해요.`}</p>
      {!canConfirm && <p>최신 연합 정보를 확인한 뒤 다시 선택해 주세요.</p>}
      <div className="flex flex-wrap gap-2">
        <button type="button" ref={cancelRef} className={BUTTON} onClick={() => {
          restoreActionFocus.current = true;
          setConfirmation(null);
        }}>취소</button>
        <button type="button" className={`${BUTTON} plaque-yellow`} disabled={!canConfirm || !!uncertain}
          onClick={() => void submit()}>{confirmation.kind === 'invite' ? '초대 보내기' : '가입 신청 보내기'}</button>
      </div>
    </div> : action && <>
      <p>{action.kind === 'invite' ? '우리 연합' : '선택한 학교의 연합'}: {action.alliance.name}</p>
      {blocked && <p>{blocked}</p>}
      <button type="button" ref={actionRef} className={BUTTON} disabled={!fresh || !!blocked || !!uncertain}
        onClick={() => { applied.reset(); invited.reset(); setConfirmation(action); }}>{label}</button>
    </>}
  </>;
}
