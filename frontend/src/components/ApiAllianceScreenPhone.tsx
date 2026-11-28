import { useEffect, useId, useRef, useState, type ReactNode, type SubmitEvent } from 'react';
import { ChevronLeft, RefreshCw } from 'lucide-react';
import { useAuthSession } from '../api/ApiProvider';
import {
  ALLIANCE_DURATION_PRESETS, ALLIANCE_ERROR_CODES, ALLIANCE_INVITATIONS_ENABLED, ALLIANCE_LIMITS, ALLIANCE_MAX_SCHOOLS, ALLIANCE_STATUS_LABEL,
  createMatchMemory, createSentApplicationMemory, MATCH_DURATION_PRESETS, MATCH_ERROR_CODES, MATCH_LIMITS, MATCH_STATUS_LABEL,
  parseAllianceId, parseApplicationId, parseMatchId, useAllianceDetail, useAllianceMatch, useApplyToAlliance, useCreateAlliance,
  useDecideApplication, useDecideMatch, useMyAlliance, useRequestMatch, useUniversityName, validateAllianceName, type Alliance,
  type AllianceApplication, type AllianceCreateRequest, type AllianceMatch, type ApplicationDecision, type MatchDecision, type MatchMemory, type SentApplication, type SentApplicationMemory,
} from '../api/alliances';
import { errorMessage, fieldErrorMap, isApiError } from '../api/errors';
import { useMyInfo } from '../api/users';
import { kstDateTimeInput, validateMatchSchedule } from '../api/matchSchedule';
import { soundManager } from '../utils/sound';
import { BottomNavFooter, type NavTab } from './BottomNav';
import { DurationPicker, formatDurationHours } from './DurationPicker';
import { AllianceApplicationList, AllianceDirectory, AllianceHistory, AllianceMatchList, AllianceSchoolList } from './AllianceListPanels';
import { AllianceConfirmation } from './AllianceConfirmation';
import { AllianceInvitationPanel } from './AllianceInvitationPanel';
import { GamePanelTabs } from './GamePanelTabs';
import { LoadNotice } from './LoadNotice';
import { PhoneShell } from './PhoneShell';

// Alliance screen in API mode: create and detail (SCRUM-177), join applications by number (SCRUM-178), matches by number
// (SCRUM-179). Plans in .agents/01-PLAN/261119-1123-*, -1134-* and -1150-*. Replaces the mock alliance and battle
// screens; the mock keeps them with their [시연] buttons and virtual clock.

const BUTTON = 'pixel-plaque plaque-wood min-h-11 min-w-11 px-3 py-2 text-xs font-bold cursor-pointer disabled:opacity-50 disabled:cursor-default';
const BOX = 'rpg-box p-3 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex flex-col gap-2';
const INPUT = 'w-full min-h-12 border-2 border-[#261608] bg-white px-2 py-2 text-base focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#261608] disabled:bg-stone-100';
const dateFormat = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short' });
const defaultApplications = createSentApplicationMemory();
const defaultMatches = createMatchMemory();
const ERROR_TEXT = 'text-[#7f1d1d]';
const ALLIANCE_TABS = [
  { id: 'overview', label: '우리 연합', icon: 'alliance' },
  { id: 'applications', label: '가입 신청', icon: 'detail' },
  { id: 'invitations', label: '연합 초대', icon: 'alliance' },
  { id: 'matches', label: '대결', icon: 'swords', tone: 'battle' },
  { id: 'search', label: '연합 찾기', icon: 'search' },
  { id: 'history', label: '대결 이력', icon: 'history' },
] as const;
type AllianceTab = typeof ALLIANCE_TABS[number]['id'];
const ALLIANCE_TABS_WITHOUT_INVITATIONS = ALLIANCE_TABS.filter((tab) => tab.id !== 'invitations');

export function remainingTime(expiredAt: string, now: number): string {
  const diff = Date.parse(expiredAt) - now;
  if (!Number.isFinite(diff)) return '';
  if (diff <= 0) return '만료 시각이 지났어요';
  const minutes = Math.floor(diff / 60_000);
  return `만료까지 ${Math.floor(minutes / 60)}시간 ${minutes % 60}분`;
}

/** "3시간 5분" until the instant, or null once it has passed or cannot be read. */
export function timeLeft(iso: string, now: number): string | null {
  const diff = Date.parse(iso) - now;
  if (!Number.isFinite(diff) || diff <= 0) return null;
  const minutes = Math.floor(diff / 60_000);
  return `${Math.floor(minutes / 60)}시간 ${minutes % 60}분`;
}

const kst = (iso: string) => `${dateFormat.format(new Date(iso))} (KST)`;

export type AllianceEntry = 'create' | 'search';
export interface AllianceNavigation {
  entry: AllianceEntry;
  onArrive: () => void;
}

interface ApiAllianceScreenPhoneProps {
  embedded?: boolean;
  navigation?: AllianceNavigation;
  onBack: () => void;
  onGoToLogin: () => void;
  onNavigateTab?: (tab: NavTab) => void;
  /** Enable only when the invitations API has been deployed. */
  invitationsEnabled?: boolean;
  /** Tests pass their own storage and clock. */
  applications?: SentApplicationMemory;
  matches?: MatchMemory;
  now?: () => number;
}

export function ApiAllianceScreenPhone({
  embedded = false, onBack, onGoToLogin, onNavigateTab, applications = defaultApplications, matches = defaultMatches, now = Date.now,
  invitationsEnabled = ALLIANCE_INVITATIONS_ENABLED, navigation,
}: ApiAllianceScreenPhoneProps) {
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo();
  if (signedIn && !me.data) return <LoadNotice status={me.isError ? 'failed' : 'loading'} name="내 정보"
    failureDetail={errorMessage(me.error)} onRetry={() => void me.refetch()} onBack={onBack} backLabel="이전 화면으로" variant={embedded ? 'panel' : 'phone'} />;
  return (
    <PhoneShell embedded={embedded}>
      <header className="bg-[#faecc7] border-b-2 border-[#261608] p-2 flex items-center gap-3 shrink-0 font-pixel text-[#261608]">
        <button type="button" onClick={() => { soundManager.playTabSound(); onBack(); }} className={BUTTON} aria-label="이전 화면으로 돌아가기">
          <ChevronLeft className="w-4 h-4 inline" aria-hidden="true" /> 뒤로
        </button>
        <h1 className="text-sm font-black">연합</h1>
      </header>
      {signedIn && me.data ? <AllianceHome key={me.data.id} userId={me.data.id} universityName={me.data.university.name}
        applications={applications} matches={matches} now={now} invitationsEnabled={invitationsEnabled} navigation={navigation} /> : (
        <div className="flex-1 min-h-0 overflow-y-auto p-3 font-pixel text-[#261608]">
          <div role="status" className={`${BOX} items-center text-center`}>
            <p className="text-sm font-bold">연합을 만들거나 보려면 로그인해 주세요</p>
            <button type="button" className={BUTTON} onClick={onGoToLogin}>로그인</button>
          </div>
        </div>
      )}
      {onNavigateTab && <BottomNavFooter activeTab="alliance" onChangeTab={onNavigateTab} />}
    </PhoneShell>
  );
}

function useAllianceEntry(navigation?: AllianceNavigation) {
  const [selectedTab, setTab] = useState<AllianceTab>('overview');
  const tab = navigation ? (navigation.entry === 'search' ? 'search' : 'overview') : selectedTab;
  const overviewHeading = useRef<HTMLHeadingElement>(null);
  const searchHeading = useRef<HTMLHeadingElement>(null);
  const createForm = useRef<HTMLFormElement>(null);
  useEffect(() => {
    if (!navigation) return;
    setTab(tab);
    const target = navigation.entry === 'create' ? createForm.current ?? overviewHeading.current : searchHeading.current;
    target?.focus();
    navigation.onArrive();
  }, [navigation, tab]);
  return { tab, setTab, overviewHeading, searchHeading, createForm };
}

function AllianceHome({ userId, universityName, applications, matches, now, invitationsEnabled, navigation }: {
  userId: number; universityName: string; applications: SentApplicationMemory; matches: MatchMemory; now: () => number;
  invitationsEnabled: boolean; navigation?: AllianceNavigation;
}) {
  const membership = useMyAlliance(userId, { now });
  const mine = membership.isSuccess ? membership.data : undefined;
  const refresh = () => void membership.refetch({ cancelRefetch: false });
  const [sent, setSent] = useState(() => applications.get(userId));
  const [notice, setNotice] = useState<string | null>(null);
  const { tab, setTab, overviewHeading, searchHeading, createForm } = useAllianceEntry(navigation);
  const rememberSent = (application: AllianceApplication) => {
    const next = { applicationId: application.id, allianceId: application.allianceId };
    applications.set(userId, next);
    setSent(next);
  };
  const joinedId = mine?.id ?? null;
  useEffect(() => {
    if (joinedId === null) return;
    applications.forget(userId);
    setSent(null);
  }, [joinedId, applications, userId]);
  const forgetSent = (message: string | null) => {
    applications.forget(userId);
    setSent(null);
    setNotice(message);
  };
  const recheck = <button type="button" className={`${BUTTON} self-start`} disabled={membership.isFetching} onClick={refresh}>우리 연합 다시 확인</button>;

  let mineContent: ReactNode;
  if (membership.isPending) {
    mineContent = <p role="status" className={`${BOX} text-xs`}>우리 연합을 확인하는 중이에요.</p>;
  } else if (membership.isError) {
    mineContent = (
      <div className={`${BOX} text-xs`}>
        <p role="alert" className={ERROR_TEXT}>우리 연합을 확인하지 못했어요. {errorMessage(membership.error)}</p>
        {recheck}
      </div>
    );
  } else if (mine) {
    mineContent = (
      <article aria-label={`연합 #${mine.id}`} data-testid="alliance-detail" aria-busy={membership.isFetching} className={`${BOX} text-xs`}>
        <AllianceFields alliance={mine} now={now} />
        <AllianceSchoolList userId={userId} allianceId={mine.id} />
        {recheck}
      </article>
    );
  } else {
    mineContent = <>
      <p role="status" className="text-xs">우리 학교가 속한 연합이 없어요.</p>
      {recheck}
      {sent && <SentApplicationCard userId={userId} sent={sent} now={now}
        onMissing={() => forgetSent(`가입 신청한 연합 #${sent.allianceId}을(를) 찾을 수 없어 신청 기억을 지웠어요.`)}
        onForget={() => forgetSent(null)} />}
      <CreateAllianceForm formRef={createForm} userId={userId} universityName={universityName} onCreated={() => forgetSent('연합을 만들었어요.')}
        onCheckMembership={refresh} />
    </>;
  }

  const renderTab = (panel: AllianceTab, openAndFocus: (next: AllianceTab) => void) => {
    const visible = panel === tab;
    switch (panel) {
      case 'overview': return <>
        <h2 ref={overviewHeading} tabIndex={-1} className="text-sm font-black">우리 연합</h2>
        {notice && <p role="status" className={`${BOX} text-xs`}>{notice}</p>}
        {mineContent}
      </>;
      case 'applications': return <>
        <h2 className="text-sm font-black">가입 신청</h2>
        {mine && <ApplicationDecisionForm key={mine.id} userId={userId} allianceId={mine.id} visible={visible} />}
        <AllianceApplicationList userId={userId} visible={visible} />
      </>;
      case 'invitations': return <AllianceInvitationPanel userId={userId} allianceId={mine?.id} visible={visible} />;
      case 'matches': return mine
        ? <AllianceMatches key={mine.id} userId={userId} alliance={mine} visible={visible} memory={matches} now={now} />
        : <MatchMembershipNotice unjoined={mine === null} openAndFocus={openAndFocus} />;
      case 'search': return <AllianceSearch headingRef={searchHeading} userId={userId} now={now} mine={mine} onSent={rememberSent} />;
      case 'history': return <>
        <h2 className="text-sm font-black">이전 연합 대결 이력</h2>
        <AllianceHistory userId={userId} visible={visible} detail={(alliance, matchId, close) =>
          <MatchCard key={matchId} userId={userId} alliance={alliance} matchId={matchId} visible={visible}
            now={now} history onMissing={close} onForget={close} />} />
      </>;
    }
  };

  return <div className="flex flex-1 min-h-0 min-w-0 flex-col font-pixel text-[#261608]">
    <GamePanelTabs label="연합 메뉴" compact keepVisitedMounted tabs={invitationsEnabled ? ALLIANCE_TABS : ALLIANCE_TABS_WITHOUT_INVITATIONS} selected={tab} onSelect={setTab}>
      {(panel, openAndFocus) => <div className="flex flex-col gap-3 p-3">{renderTab(panel, openAndFocus)}</div>}
    </GamePanelTabs>
  </div>;
}

function MatchMembershipNotice({ unjoined, openAndFocus }: { unjoined: boolean; openAndFocus: (tab: AllianceTab) => void }) {
  const target = unjoined ? 'search' : 'overview';
  return <>
    <h2 className="text-sm font-black">연합 대결</h2>
    <div className={`${BOX} text-xs`}>
      <p>{unjoined ? '연합에 소속되면 대결을 신청하거나 받은 대결을 확인할 수 있어요.' : '연합 소속이 확인되면 대결 메뉴를 이용할 수 있어요.'}</p>
      <button type="button" className={`${BUTTON} self-start`} onClick={() => openAndFocus(target)}>
        {unjoined ? '연합 찾기로 이동' : '우리 연합으로 이동'}
      </button>
    </div>
  </>;
}

/** The school's name, or its number while the name loads or when it cannot be read. */
function SchoolName({ universityId, viewerId }: { universityId: number; viewerId: number }) {
  const name = useUniversityName(universityId, viewerId);
  return <>{name.data ?? `학교 #${universityId}`}</>;
}

function SentApplicationCard({ userId, sent, now, onMissing, onForget }: {
  userId: number; sent: SentApplication; now: () => number; onMissing: () => void; onForget: () => void;
}) {
  return (
    <section aria-label="보낸 가입 신청" data-testid="sent-application" className={`${BOX} text-xs`}>
      <h3 className="text-sm font-black">보낸 가입 신청 #{sent.applicationId}</h3>
      <p>
        연합 #{sent.allianceId}에 가입을 신청했어요. ‘가입 신청’ 탭에서 현재 상태를 확인할 수 있어요.
        승인되면 ‘우리 연합’에 자동으로 표시돼요.
      </p>
      <AllianceDetail userId={userId} allianceId={sent.allianceId} now={now} onMissing={onMissing}
        actions={<button type="button" className={BUTTON} onClick={onForget}>신청 기억 지우기</button>} />
    </section>
  );
}

function ApplyButton({ userId, allianceId, onSent }: { userId: number; allianceId: number; onSent: (application: AllianceApplication) => void }) {
  const apply = useApplyToAlliance(userId);
  const code = isApiError(apply.error) ? apply.error.code : null;
  const answered = isApiError(apply.error) && apply.error.kind === 'http';
  const reason = code === ALLIANCE_ERROR_CODES.alreadyJoined ? '우리 학교는 이미 연합에 소속돼 있어요. 한 학교는 연합 하나에만 소속될 수 있어요.'
    : code === ALLIANCE_ERROR_CODES.applicationPending ? '우리 학교가 이 연합에 보낸 가입 신청이 이미 처리를 기다리고 있어요. 같은 학교 회원이 보냈을 수 있어요.'
    : code === ALLIANCE_ERROR_CODES.full ? `이 연합은 학교 ${ALLIANCE_MAX_SCHOOLS}곳이 모두 찼어요.`
    : code === ALLIANCE_ERROR_CODES.notJoinable ? '이 연합은 지금 가입 신청을 받을 수 없어요. 대결 중이거나 만료된 연합이에요.'
    : code === ALLIANCE_ERROR_CODES.notFound ? '연합을 찾을 수 없어요.'
    : code === ALLIANCE_ERROR_CODES.notActiveUser ? '활동 중인 회원만 가입 신청할 수 있어요.'
    : `가입 신청을 보내지 못했어요. ${errorMessage(apply.error)}${answered ? '' : ' 응답을 받지 못했다면 신청이 이미 접수됐을 수 있어요. 다시 누르면 "이미 처리를 기다리는 신청"으로 알려 줘요.'}`;
  return <>
    <button type="button" className={`${BUTTON} plaque-yellow`} disabled={apply.isPending}
      onClick={() => apply.mutate(allianceId, { onSuccess: onSent })}>
      {apply.isPending ? '신청하는 중…' : '이 연합에 가입 신청'}
    </button>
    {apply.data && <p role="status" className="basis-full font-bold">가입 신청 #{apply.data.id}을(를) 보냈어요.</p>}
    {apply.error && <p role="alert" data-testid="alliance-apply-error" className={`basis-full ${ERROR_TEXT}`}>{reason}</p>}
  </>;
}

function ApplicationDecisionForm({ userId, allianceId, visible }: { userId: number; allianceId: number; visible: boolean }) {
  const id = useId();
  const [input, setInput] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<{ applicationId: number; decision: ApplicationDecision; universityName?: string } | null>(null);
  const decide = useDecideApplication(userId);
  const done = decide.data;
  const asked = decide.variables;
  const code = isApiError(decide.error) ? decide.error.code : null;
  const answered = isApiError(decide.error) && decide.error.kind === 'http';
  const number = asked ? `#${asked.applicationId}` : '';
  const reason = code === ALLIANCE_ERROR_CODES.applicationNotFound ? `신청 ${number}을(를) 찾을 수 없어요. 번호를 확인해 주세요.`
    : code === ALLIANCE_ERROR_CODES.applicationNotPending ? `신청 ${number}은(는) 이미 처리됐어요. 승인·거절됐거나 정원이 차서 자동으로 거절된 신청이에요.`
    : code === ALLIANCE_ERROR_CODES.notMember ? '우리 학교는 이 신청을 받은 연합의 소속이 아니에요. 그 연합의 회원만 처리할 수 있어요.'
    : code === ALLIANCE_ERROR_CODES.full ? `연합 학교 ${ALLIANCE_MAX_SCHOOLS}곳이 이미 찼어요.`
    : code === ALLIANCE_ERROR_CODES.alreadyJoined ? '신청한 학교가 그사이 다른 연합에 들어갔어요.'
    : code === ALLIANCE_ERROR_CODES.notJoinable ? '지금은 이 연합이 가입 신청을 처리할 수 없어요. 대결 중이거나 만료된 연합이에요.'
    : code === ALLIANCE_ERROR_CODES.notActiveUser ? '활동 중인 회원만 가입 신청을 처리할 수 있어요.'
    : `가입 신청을 처리하지 못했어요. ${errorMessage(decide.error)}${answered ? '' : ' 응답을 받지 못했다면 이미 처리됐을 수 있어요. 다시 누르면 "이미 처리됨"으로 알려 줘요.'}`;

  const ask = (decision: ApplicationDecision) => {
    if (decide.isPending || confirming) return;
    const parsed = parseApplicationId(input);
    if ('problem' in parsed) { setProblem(parsed.problem); return; }
    setProblem(null);
    setConfirming({ applicationId: parsed.applicationId, decision });
  };
  const confirm = () => {
    if (!confirming) return;
    decide.mutate({ applicationId: confirming.applicationId, decision: confirming.decision }, { onSuccess: () => setInput('') });
    setConfirming(null);
  };

  return (
    <form noValidate onSubmit={(event) => { event.preventDefault(); ask('approve'); }} aria-label="가입 신청 처리" className={`${BOX} text-xs`}>
      <h3 className="text-sm font-black">가입 신청 처리</h3>
      <AllianceApplicationList userId={userId} allianceId={allianceId} visible={visible} busy={decide.isPending}
        onDecide={(applicationId, decision, universityName) => { setProblem(null); setConfirming({ applicationId, decision, universityName }); }} />
      <label htmlFor={`${id}-id`} className="font-bold">신청 번호</label>
      <input id={`${id}-id`} inputMode="numeric" autoComplete="off" value={input} disabled={decide.isPending} aria-invalid={problem !== null}
        aria-describedby={problem ? `${id}-problem` : undefined}
        onChange={(event) => { setInput(event.target.value); setProblem(null); setConfirming(null); }} className={INPUT} />
      {problem && <p id={`${id}-problem`} role="alert" className={ERROR_TEXT}>{problem}</p>}
      <div className="flex flex-wrap gap-2">
        <button type="submit" className={`${BUTTON} plaque-yellow`} disabled={decide.isPending}>{decide.isPending && asked?.decision === 'approve' ? '승인하는 중…' : '승인'}</button>
        <button type="button" className={BUTTON} disabled={decide.isPending} onClick={() => ask('reject')}>
          {decide.isPending && asked?.decision === 'reject' ? '거절하는 중…' : '거절'}
        </button>
      </div>
      {confirming && <AllianceConfirmation label={confirming.decision === 'approve' ? '가입 승인' : '가입 거절'}
        onCancel={() => setConfirming(null)} onConfirm={confirm}>
        <p>가입 신청 #{confirming.applicationId}{confirming.universityName && ` · ${confirming.universityName}`}</p>
        <p>{confirming.decision === 'approve' ? '신청한 학교가 연합에 들어와요.' : '신청한 학교는 연합에 들어오지 않아요.'}</p>
      </AllianceConfirmation>}
      {done && !decide.isPending && (
        <div role="status" data-testid="application-decision-result" className="flex flex-col gap-1 font-bold">
          {done.status === 'ACCEPTED' ? <>
            <p><SchoolName universityId={done.universityId} viewerId={userId} />이(가) 연합에 들어왔어요.</p>
            <p>연합 #{done.allianceId} 학교 {ALLIANCE_MAX_SCHOOLS}/{ALLIANCE_MAX_SCHOOLS} · 정원이 찼어요. 남은 대기 신청은 서버가 자동으로 거절해요.</p>
          </> : (
            <p>신청 #{done.id}(<SchoolName universityId={done.universityId} viewerId={userId} />)을(를) 거절했어요.</p>
          )}
          {done.allianceId !== allianceId && <p className="font-normal">이 신청은 우리 연합 #{allianceId}이 아니라 연합 #{done.allianceId}에 온 신청이었어요.</p>}
        </div>
      )}
      {decide.error && !decide.isPending && <p role="alert" data-testid="application-decision-error" className={ERROR_TEXT}>{reason}</p>}
    </form>
  );
}

function AllianceMatches({ userId, alliance, visible, memory, now }: {
  userId: number; alliance: Alliance; visible: boolean; memory: MatchMemory; now: () => number;
}) {
  const [matchId, setMatchId] = useState(() => memory.get(userId));
  const [notice, setNotice] = useState<string | null>(null);
  const show = (id: number, message: string | null) => {
    memory.set(userId, id);
    setMatchId(id);
    setNotice(message);
  };
  const forget = (message: string | null) => {
    memory.forget(userId);
    setMatchId(null);
    setNotice(message);
  };
  return (
    <section aria-labelledby="alliance-matches" className="flex flex-col gap-2">
      <h2 id="alliance-matches" className="text-sm font-black">연합 대결</h2>
      <AllianceMatchList userId={userId} allianceId={alliance.id} visible={visible} selected={matchId} onSelect={(id) => show(id, null)} />
      {notice && <p role="status" className={`${BOX} text-xs`}>{notice}</p>}
      {matchId !== null ? (
        <MatchCard key={matchId} userId={userId} matchId={matchId} alliance={alliance} visible={visible} now={now}
          onMissing={() => forget(`기억해 둔 대결 #${matchId}을(를) 찾을 수 없어 지웠어요.`)} onForget={() => forget(null)} />
      ) : null}
      <MatchRequestForm userId={userId} allianceId={alliance.id} expiredAt={alliance.expiredAt}
        onRequested={(match) => show(match.id, `대결 신청 #${match.id}을(를) 보냈어요.`)} now={now} />
      <MatchLookup onLookup={(id) => show(id, null)} />
    </section>
  );
}

/** What went wrong with a match request or decision, for every code AllianceMatchService can answer. */
function matchErrorText(error: unknown, fallback: string, matchId: number | null): string {
  const code = isApiError(error) ? error.code : null;
  const answered = isApiError(error) && error.kind === 'http';
  const number = matchId === null ? '' : ` #${matchId}`;
  switch (code) {
    case MATCH_ERROR_CODES.notFound: return `대결${number}을(를) 찾을 수 없어요. 번호를 확인해 주세요.`;
    case MATCH_ERROR_CODES.notPending: return `대결${number}은(는) 이미 수락·취소됐거나 마감됐어요. 새로고침해 상태를 확인하세요.`;
    case MATCH_ERROR_CODES.requestExpired: return '수락 마감 시각이 지났어요.';
    case MATCH_ERROR_CODES.alreadyRequested: return '두 연합 사이에 수락을 기다리는 대결 신청이 이미 있어요.';
    case MATCH_ERROR_CODES.membersIncomplete: return `두 연합 모두 학교 ${ALLIANCE_MAX_SCHOOLS}곳이 가입해야 대결할 수 있어요.`;
    case MATCH_ERROR_CODES.scheduleConflict: return '두 연합 중 하나에 예약된 대결이 있어요.';
    case MATCH_ERROR_CODES.invalidSchedule: return '예약 시작 시각이 지났거나 대결 종료가 연합 만료를 넘어요. 시작 시각과 대결 시간을 다시 확인해 주세요.';
    case MATCH_ERROR_CODES.insufficientTime: return '두 연합 중 먼저 만료되는 연합이 끝나기 전에 대결을 마칠 시간이 부족해요. 대결 시간을 줄여 보세요.';
    case ALLIANCE_ERROR_CODES.notJoinable: return '두 연합 중 하나가 지금 대결할 수 없어요. 이미 대결 중이거나 만료된 연합이에요.';
    case ALLIANCE_ERROR_CODES.notFound: return '상대 연합을 찾을 수 없어요.';
    case ALLIANCE_ERROR_CODES.notMember: return '우리 학교는 이 대결을 처리할 수 있는 연합의 소속이 아니에요. 신청은 신청하는 연합, 수락은 신청받은 연합의 회원만 할 수 있어요.';
    case ALLIANCE_ERROR_CODES.notActiveUser: return '활동 중인 회원만 대결을 신청하거나 처리할 수 있어요.';
    default: {
      const fields = fieldErrorMap(error);
      const reason = fields.opponentAllianceId ?? fields.durationHours ?? fields.scheduledStartAt ?? errorMessage(error);
      return `${fallback} ${reason}${answered ? '' : ' 응답을 받지 못했다면 서버에서는 처리됐을 수 있어요. 다시 누르기 전에 새로고침해 확인하세요.'}`;
    }
  }
}

function MatchRequestForm({ userId, allianceId, expiredAt, onRequested, now }: {
  userId: number; allianceId: number; expiredAt: string; onRequested: (match: AllianceMatch) => void; now: () => number;
}) {
  const id = useId();
  const [opponent, setOpponent] = useState('');
  const [hours, setHours] = useState<number>(MATCH_LIMITS.defaultHours);
  const [mode, setMode] = useState<'immediate' | 'scheduled'>('immediate');
  const [startInput, setStartInput] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [scheduleProblem, setScheduleProblem] = useState<string | null>(null);
  const opponentRef = useRef<HTMLInputElement>(null);
  const scheduleRef = useRef<HTMLInputElement>(null);
  const request = useRequestMatch(userId, allianceId);
  const serverScheduleError = !request.isPending && isApiError(request.error) &&
    (request.error.code === MATCH_ERROR_CODES.invalidSchedule || Boolean(fieldErrorMap(request.error).scheduledStartAt));
  // Focus only after React has re-enabled the input following the failed request.
  useEffect(() => { if (serverScheduleError) scheduleRef.current?.focus(); }, [serverScheduleError]);
  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (request.isPending) return;
    const parsed = parseAllianceId(opponent);
    if ('problem' in parsed || parsed.allianceId === allianceId) {
      setProblem('problem' in parsed ? parsed.problem.replace('연합 ID', '상대 연합 ID') : '우리 연합과는 대결할 수 없어요.');
      opponentRef.current?.focus();
      return;
    }
    setProblem(null);
    const schedule: { scheduledStartAt?: string } | { problem: string } = mode === 'scheduled'
      ? validateMatchSchedule(startInput, now(), hours, expiredAt) : {};
    if ('problem' in schedule) { setScheduleProblem(schedule.problem); scheduleRef.current?.focus(); return; }
    setScheduleProblem(null);
    request.mutate({ opponentAllianceId: parsed.allianceId, durationHours: hours, ...schedule }, {
      onSuccess: (match) => { setOpponent(''); setStartInput(''); onRequested(match); },
    });
  };
  return (
    <form noValidate onSubmit={submit} aria-label="대결 신청" className={`${BOX} text-xs`}>
      <h3 className="text-sm font-black">대결 신청</h3>
      <p>
        두 연합 모두 학교 {ALLIANCE_MAX_SCHOOLS}곳이 차 있어야 해요. 두 연합 중 먼저 만료되는 연합이 끝나기 전에 대결이 끝나야 해요.
      </p>
      <AllianceDirectory userId={userId} mode="opponents" ownId={allianceId} disabled={request.isPending}
        onSelect={(item) => { setOpponent(String(item.id)); setProblem(null); request.reset(); }} />
      <label htmlFor={`${id}-opponent`} className="font-bold">상대 연합 ID</label>
      <input ref={opponentRef} id={`${id}-opponent`} name="opponentAllianceId" inputMode="numeric" autoComplete="off" value={opponent} disabled={request.isPending}
        aria-invalid={problem !== null} aria-describedby={problem ? `${id}-problem` : undefined}
        onChange={(event) => { setOpponent(event.target.value); setProblem(null); }} className={INPUT} />
      {problem && <p id={`${id}-problem`} role="alert" className={ERROR_TEXT}>{problem}</p>}
      <fieldset disabled={request.isPending} className="contents">
        <DurationPicker label="대결 시간" value={hours} min={MATCH_LIMITS.minHours} max={MATCH_LIMITS.maxHours}
          presets={MATCH_DURATION_PRESETS} onChange={(value) => { setHours(value); setScheduleProblem(null); request.reset(); }} />
      </fieldset>
      <fieldset disabled={request.isPending} className="flex flex-col gap-2">
        <legend className="font-bold">시작 방식</legend>
        {(['immediate', 'scheduled'] as const).map((value) => (
          <div key={value} className="flex items-center gap-2 min-h-12">
            <input id={`${id}-mode-${value}`} name={`${id}-start-mode`} type="radio" value={value} checked={mode === value}
              onChange={() => { setMode(value); setScheduleProblem(null); request.reset(); }} />
            <label htmlFor={`${id}-mode-${value}`} className="flex items-center min-h-12 cursor-pointer">
              {value === 'immediate' ? '수락하면 즉시 시작' : '시작 시각 예약'}
            </label>
          </div>
        ))}
      </fieldset>
      {mode === 'scheduled' && <>
        <label htmlFor={`${id}-scheduled`} className="font-bold">예약 시작 시각 (한국 시각 KST)</label>
        <input ref={scheduleRef} id={`${id}-scheduled`} name="scheduledStartAt" type="datetime-local" required step="60"
          value={startInput} min={kstDateTimeInput(Math.floor(now() / 60_000 + 1) * 60_000)}
          max={kstDateTimeInput(Date.parse(expiredAt) - hours * 60 * 60_000)}
          disabled={request.isPending} aria-invalid={Boolean(scheduleProblem || serverScheduleError)}
          aria-describedby={`${id}-schedule-help${scheduleProblem ? ` ${id}-schedule-problem` : ''}${serverScheduleError ? ` ${id}-server-error` : ''}`}
          onChange={(event) => { setStartInput(event.target.value); setScheduleProblem(null); request.reset(); }} className={INPUT} />
        <p id={`${id}-schedule-help`}>한국 시각(KST) 기준이에요. 예약 시작 전에 상대가 수락해야 하며, 수락한 예약은 취소할 수 없어요.
          상대 연합의 만료 시각은 신청할 때 서버가 확인해요.</p>
        {scheduleProblem && <p id={`${id}-schedule-problem`} role="alert" className={ERROR_TEXT}>{scheduleProblem}</p>}
      </>}
      {request.error && !request.isPending && (
        <p id={`${id}-server-error`} role="alert" data-testid="match-request-error" className={ERROR_TEXT}>{matchErrorText(request.error, '대결을 신청하지 못했어요.', null)}</p>
      )}
      <button type="submit" className={`${BUTTON} plaque-yellow self-start`} disabled={request.isPending}>
        {request.isPending ? '신청하는 중…' : '대결 신청하기'}
      </button>
    </form>
  );
}

function MatchLookup({ onLookup }: { onLookup: (matchId: number) => void }) {
  const id = useId();
  const [input, setInput] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    const parsed = parseMatchId(input);
    if ('problem' in parsed) { setProblem(parsed.problem); return; }
    setProblem(null);
    setInput('');
    onLookup(parsed.matchId);
  };
  return (
    <form noValidate onSubmit={submit} aria-label="대결 번호로 찾기" className={`${BOX} text-xs`}>
      <label htmlFor={`${id}-id`} className="font-bold">대결 번호로 찾기</label>
      <p>번호를 알고 있다면 직접 조회할 수도 있어요.</p>
      <input id={`${id}-id`} inputMode="numeric" autoComplete="off" value={input} aria-invalid={problem !== null}
        aria-describedby={problem ? `${id}-problem` : undefined}
        onChange={(event) => { setInput(event.target.value); setProblem(null); }} className={INPUT} />
      {problem && <p id={`${id}-problem`} role="alert" className={ERROR_TEXT}>{problem}</p>}
      <button type="submit" className={`${BUTTON} self-start`}>찾기</button>
    </form>
  );
}

function AllianceName({ userId, allianceId, mine }: { userId: number; allianceId: number; mine: Pick<Alliance, 'id' | 'name'> }) {
  const isMine = mine.id === allianceId;
  const detail = useAllianceDetail(userId, isMine ? null : allianceId);
  const name = isMine ? mine.name : detail.data?.name ?? '연합';
  return <>{name} #{allianceId}{isMine && <strong> (우리)</strong>}</>;
}

function MatchCard({ userId, matchId, alliance, visible, now, history = false, onMissing, onForget }: {
  userId: number; matchId: number; alliance: Pick<Alliance, 'id' | 'name'>; visible: boolean;
  now: () => number; history?: boolean; onMissing: () => void; onForget: () => void;
}) {
  const allianceId = alliance.id;
  const query = useAllianceMatch(userId, matchId, visible);
  const missing = isApiError(query.error) && query.error.code === MATCH_ERROR_CODES.notFound;
  // Runs once when the 404 arrives; the parent passes a new callback on every render, so it is not a dependency.
  useEffect(() => { if (missing) onMissing(); }, [missing]);
  const match = query.data;
  const side = match ? (match.allianceAId === allianceId ? 'A' : match.allianceBId === allianceId ? 'B' : null) : null;

  return (
    <article aria-label={`대결 #${matchId}`} data-testid="match-card" aria-busy={query.isFetching} className={`${BOX} text-xs`}>
      {query.isPending ? <p role="status">대결 #{matchId} 정보를 불러오는 중…</p>
        : query.isError ? (
          <div role="alert" className="flex flex-col gap-1.5">
            <p className={`font-bold ${ERROR_TEXT}`}>{missing ? `대결 #${matchId}을(를) 찾을 수 없어요.` : '대결 정보를 불러오지 못했어요.'}</p>
            {!missing && <p>{errorMessage(query.error)}</p>}
          </div>
        ) : match && <>
          <div className="flex items-start justify-between gap-2">
            <h3 className="text-sm font-black">대결 #{match.id}</h3>
            <span data-testid="match-status" className="shrink-0 border-2 border-[#261608] px-1.5 py-0.5 text-[11px] font-bold bg-white">
              {MATCH_STATUS_LABEL[match.status]}
            </span>
          </div>
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
            <dt>신청한 연합</dt><dd><AllianceName userId={userId} allianceId={match.allianceAId} mine={alliance} /></dd>
            <dt>받은 연합</dt><dd><AllianceName userId={userId} allianceId={match.allianceBId} mine={alliance} /></dd>
            <dt>대결 시간</dt><dd>{formatDurationHours(match.durationHours)}</dd>
            {match.status === 'REQUESTED' && <>
              <dt>수락 마감</dt><dd>{kst(match.requestExpiredAt)}</dd>
              <dt>남은 시간</dt><dd>{timeLeft(match.requestExpiredAt, now()) ?? '마감 시각이 지났어요'}</dd>
            </>}
            {match.scheduledStartAt && <><dt>예약 시작</dt><dd>{kst(match.scheduledStartAt)}</dd></>}
            {match.status === 'SCHEDULED' && match.scheduledStartAt && <>
              <dt>시작까지</dt><dd>{timeLeft(match.scheduledStartAt, now()) ?? '시작 시각이 지났어요. 서버 상태를 확인하는 중이에요.'}</dd>
            </>}
            {match.startedAt && <><dt>시작</dt><dd>{kst(match.startedAt)}</dd></>}
            {match.endedAt && <><dt>{match.status === 'FINISHED' ? '종료' : '종료 예정'}</dt><dd>{kst(match.endedAt)}</dd></>}
            {match.status === 'IN_PROGRESS' && match.endedAt && <><dt>남은 시간</dt><dd>{timeLeft(match.endedAt, now()) ?? '곧 정산돼요'}</dd></>}
            {match.status === 'FINISHED' && <>
              <dt>점수</dt><dd>{match.aScore.toLocaleString()} : {match.bScore.toLocaleString()}</dd>
              <dt>결과</dt><dd data-testid="match-result">
                {match.winnerAllianceId === null ? '무승부'
                  : match.winnerAllianceId === allianceId ? '우리 연합 승리'
                  : side ? '우리 연합 패배' : `연합 #${match.winnerAllianceId} 승리`}
              </dd>
            </>}
          </dl>
          {match.status === 'SCHEDULED' && <p>예약을 수락했어요. 시작 여부는 서버가 결정하며 15초마다 다시 확인해요. 수락한 예약은 취소할 수 없어요.</p>}
          {match.status === 'IN_PROGRESS' && <p>진행 중 점수는 대결이 끝나면 정산돼 표시돼요.</p>}
          {match.status === 'CANCELED' && <p>신청 취소, 거절, 수락 마감 경과 또는 예약 시작 조건 불충족으로 끝난 대결이에요.</p>}
          {side === null && <p>우리 연합(#{allianceId})의 대결이 아니에요. 보기만 할 수 있어요.</p>}
          {!history && match.status === 'REQUESTED' && side && <MatchActions key={match.id} userId={userId} match={match} side={side} />}
        </>}
      <div className="flex flex-wrap gap-2">
        {!missing && (
          <button type="button" className={`${BUTTON} flex items-center gap-1`} disabled={query.isFetching} onClick={() => void query.refetch()}>
            <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" /> {query.isFetching && !query.isPending ? '새로고침 중…' : '새로고침'}
          </button>
        )}
        <button type="button" className={BUTTON} onClick={onForget}>{history ? '상세 닫기' : '이 대결 잊기'}</button>
      </div>
    </article>
  );
}

function MatchActions({ userId, match, side }: { userId: number; match: AllianceMatch; side: 'A' | 'B' }) {
  const [confirming, setConfirming] = useState<{ decision: MatchDecision; label: string } | null>(null);
  const decide = useDecideMatch(userId);
  const choices: Array<{ decision: MatchDecision; label: string; question: string }> = side === 'B'
    ? [{ decision: 'accept', label: '수락', question: match.scheduledStartAt
        ? `대결 #${match.id}을(를) 수락할까요? ${kst(match.scheduledStartAt)}에 시작하도록 예약돼요. 수락한 예약은 취소할 수 없어요.`
        : `대결 #${match.id}을(를) 수락할까요? 수락하면 바로 시작되고 되돌릴 수 없어요.` },
      { decision: 'cancel', label: '거절', question: `대결 #${match.id} 신청을 거절할까요? 되돌릴 수 없어요.` }]
    : [{ decision: 'cancel', label: '신청 취소', question: `대결 #${match.id} 신청을 취소할까요? 되돌릴 수 없어요.` }];
  const asking = confirming && choices.find((choice) => choice.label === confirming.label);
  return (
    <div className="flex flex-col gap-1.5">
      {asking ? (
        <div role="group" aria-label="대결 처리 확인" className="flex flex-col gap-1.5 border-2 border-[#261608] bg-white p-2">
          <p className="font-bold">{asking.question}</p>
          <div className="flex flex-wrap gap-2">
            <button type="button" className={`${BUTTON} plaque-yellow`} onClick={() => {
              decide.mutate({ matchId: match.id, decision: asking.decision });
              setConfirming(null);
            }}>{asking.label}하기</button>
            <button type="button" className={BUTTON} onClick={() => setConfirming(null)}>돌아가기</button>
          </div>
        </div>
      ) : (
        <div className="flex flex-wrap gap-2">
          {choices.map((choice) => (
            <button key={choice.label} type="button" className={`${BUTTON} ${choice.decision === 'accept' ? 'plaque-yellow' : ''}`}
              disabled={decide.isPending} onClick={() => setConfirming(choice)}>
              {decide.isPending && decide.variables?.decision === choice.decision ? '처리하는 중…' : choice.label}
            </button>
          ))}
        </div>
      )}
      {decide.error && !decide.isPending && (
        <p role="alert" data-testid="match-decision-error" className={ERROR_TEXT}>{matchErrorText(decide.error, '대결을 처리하지 못했어요.', match.id)}</p>
      )}
    </div>
  );
}

function CreateAllianceForm({ formRef, userId, universityName, onCreated, onCheckMembership }: {
  formRef: React.Ref<HTMLFormElement>; userId: number; universityName: string; onCreated: () => void; onCheckMembership: () => void;
}) {
  const id = useId();
  const [name, setName] = useState('');
  const [hours, setHours] = useState<number>(ALLIANCE_LIMITS.defaultHours);
  const [problem, setProblem] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<AllianceCreateRequest | null>(null);
  const create = useCreateAlliance(userId);
  const alert = useRef<HTMLDivElement>(null);
  useEffect(() => { if (problem || create.error) alert.current?.focus(); }, [problem, create.error]);

  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (create.isPending || confirming) return;
    const checked = validateAllianceName(name);
    if ('problem' in checked) { setProblem(checked.problem); return; }
    setProblem(null);
    setConfirming({ name: checked.name, durationHours: hours });
  };
  const code = isApiError(create.error) ? create.error.code : null;
  const fields = fieldErrorMap(create.error);
  const fieldReason = fields.name ?? fields.durationHours;
  // Only a request that got no server answer may have created the alliance anyway.
  const answered = isApiError(create.error) && create.error.kind === 'http';
  const length = name.trim().length;

  return (
    <form ref={formRef} tabIndex={-1} noValidate onSubmit={submit} aria-label="연합 만들기" className={`${BOX} text-xs`}>
      <p>{universityName}이(가) 창립 학교가 되는 연합을 만들어요. 연합에는 학교 2곳까지 들어갈 수 있고, 한 학교는 연합 하나에만 소속돼요.</p>
      <label htmlFor={`${id}-name`} className="font-bold">연합 이름</label>
      <input id={`${id}-name`} value={name} autoComplete="off" disabled={create.isPending} aria-invalid={problem !== null || fields.name !== undefined}
        aria-describedby={`${id}-count${problem ? ` ${id}-problem` : ''}`}
        onChange={(event) => { setName(event.target.value); setProblem(null); }} className={INPUT} />
      <p id={`${id}-count`} className={`text-[11px] ${length > ALLIANCE_LIMITS.nameMax ? 'text-[#7f1d1d] font-bold' : ''}`}>
        {length}/{ALLIANCE_LIMITS.nameMax}자 · 앞뒤 공백은 빼고 저장돼요
      </p>
      <fieldset disabled={create.isPending} className="contents">
        <DurationPicker label="연합 유지 기간" value={hours} min={ALLIANCE_LIMITS.minHours} max={ALLIANCE_LIMITS.maxHours}
          presets={ALLIANCE_DURATION_PRESETS} onChange={setHours} />
      </fieldset>
      <p className="text-[11px]">만들면 {formatDurationHours(hours)} 뒤에 만료돼요.</p>
      {problem && <div ref={alert} tabIndex={-1} id={`${id}-problem`} role="alert" className="text-[#7f1d1d]">{problem}</div>}
      {create.error && (
        <div ref={alert} tabIndex={-1} role="alert" data-testid="alliance-create-error" className="text-[#7f1d1d] flex flex-col gap-1.5">
          {code === ALLIANCE_ERROR_CODES.alreadyJoined ? <>
            <p className="font-bold">우리 학교는 이미 연합에 소속돼 있어요.</p>
            <p>한 학교는 연합 하나에만 소속될 수 있어요. 같은 학교 회원이 만들었거나 가입한 연합일 수 있어요.</p>
            <button type="button" className={`${BUTTON} self-start`} onClick={onCheckMembership}>우리 연합 다시 확인</button>
          </> : code === ALLIANCE_ERROR_CODES.notActiveUser ? (
            <p>활동 중인 회원만 연합을 만들 수 있어요. {errorMessage(create.error)}</p>
          ) : (
            <p>연합을 만들지 못했어요. {fieldReason ?? errorMessage(create.error)}{answered ? '' : ' 응답을 받지 못했다면 같은 학교 연합이 이미 생겼을 수 있어요. 다시 만들기 전에 ‘우리 연합’을 확인해 보세요.'}</p>
          )}
        </div>
      )}
      <button type="submit" className={`${BUTTON} plaque-yellow`} disabled={create.isPending}>
        {create.isPending ? '만드는 중…' : '연합 만들기'}
      </button>
      {confirming && <AllianceConfirmation label="연합 만들기" onCancel={() => setConfirming(null)} onConfirm={() => {
        setConfirming(null);
        create.mutate(confirming, { onSuccess: onCreated });
      }}>
        <p className="break-words">연합 이름: {confirming.name}</p>
        <p>유지 기간: {formatDurationHours(confirming.durationHours)}</p>
        <p>{universityName}이(가) 창립 학교가 돼요.</p>
      </AllianceConfirmation>}
    </form>
  );
}

function AllianceSearch({ headingRef, userId, now, mine, onSent }: {
  headingRef: React.Ref<HTMLHeadingElement>; userId: number; now: () => number; mine: Alliance | null | undefined; onSent: (application: AllianceApplication) => void;
}) {
  const id = useId();
  const [input, setInput] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [allianceId, setAllianceId] = useState<number | null>(null);
  const result = useRef<HTMLHeadingElement>(null);
  const lookup = (nextId: number) => {
    setProblem(null);
    if (nextId === allianceId) result.current?.focus();
    else setAllianceId(nextId);
  };
  useEffect(() => { if (allianceId !== null) result.current?.focus(); }, [allianceId]);
  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    const parsed = parseAllianceId(input);
    if ('problem' in parsed) { setProblem(parsed.problem); return; }
    lookup(parsed.allianceId);
  };
  return <>
    <h2 ref={headingRef} tabIndex={-1} className="text-sm font-black">연합 찾기</h2>
    <form noValidate onSubmit={submit} aria-labelledby={`${id}-title`} className={`${BOX} text-xs`}>
      <h3 id={`${id}-title`} className="text-sm font-black">연합 ID로 찾기</h3>
      <label htmlFor={`${id}-id`} className="font-bold">연합 ID</label>
      <input id={`${id}-id`} inputMode="numeric" autoComplete="off" value={input} aria-invalid={problem !== null}
        aria-describedby={problem ? `${id}-problem` : undefined}
        onChange={(event) => { setInput(event.target.value); setProblem(null); }} className={INPUT} />
      {problem && <p id={`${id}-problem`} role="alert" className={ERROR_TEXT}>{problem}</p>}
      <button type="submit" className={`${BUTTON} self-start`}>찾기</button>
    </form>
    {allianceId !== null && <>
      <h3 ref={result} tabIndex={-1} className="text-sm font-black">연합 #{allianceId} 상세</h3>
      <AllianceDetail key={allianceId} userId={userId} allianceId={allianceId} now={now} actions={
        allianceId === mine?.id ? <p className="text-[11px] font-bold">우리 연합이에요.</p>
          : mine === null ? <ApplyButton userId={userId} allianceId={allianceId} onSent={onSent} /> : null
      } />
    </>}
    <AllianceDirectory userId={userId} mode="recruiting" onSelect={(alliance) => lookup(alliance.id)} />
  </>;
}

function AllianceDetail({ userId, allianceId, now, onMissing, actions }: {
  userId: number; allianceId: number; now: () => number; onMissing?: () => void;
  /** Shown once the alliance has loaded. */
  actions?: ReactNode;
}) {
  const query = useAllianceDetail(userId, allianceId);
  const missing = isApiError(query.error) && query.error.code === ALLIANCE_ERROR_CODES.notFound;
  // Runs once when the 404 arrives; the parent passes a new callback on every render, so it is not a dependency.
  useEffect(() => { if (missing) onMissing?.(); }, [missing]);
  const alliance = query.data;

  return (
    <article aria-label={`연합 #${allianceId}`} data-testid="alliance-detail" aria-busy={query.isFetching} className={`${BOX} text-xs`}>
      {query.isPending ? <p role="status">연합 #{allianceId} 정보를 불러오는 중…</p>
        : query.isError ? (
          <div role="alert" className="flex flex-col gap-1.5">
            <p className="font-bold text-[#7f1d1d]">{missing ? `연합 #${allianceId}을(를) 찾을 수 없어요.` : '연합 정보를 불러오지 못했어요.'}</p>
            {!missing && <p>{errorMessage(query.error)}</p>}
            {!missing && <button type="button" className={`${BUTTON} self-start`} onClick={() => void query.refetch()}>다시 조회</button>}
          </div>
        ) : alliance && <>
          <AllianceFields alliance={alliance} now={now} />
          <AllianceSchoolList userId={userId} allianceId={allianceId} />
          {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
        </>}
      <div className="flex flex-wrap gap-2">
        {!missing && (
          <button type="button" className={`${BUTTON} flex items-center gap-1`} disabled={query.isFetching} onClick={() => void query.refetch()}>
            <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" /> {query.isFetching && !query.isPending ? '새로고침 중…' : '새로고침'}
          </button>
        )}
      </div>
    </article>
  );
}

function AllianceFields({ alliance, now }: { alliance: Alliance; now: () => number }) {
  return <>
    <div className="flex items-start justify-between gap-2">
      <h3 className="text-sm font-black break-words min-w-0">{alliance.name}</h3>
      <span data-testid="alliance-status" className="shrink-0 border-2 border-[#261608] px-1.5 py-0.5 text-[11px] font-bold bg-white">
        {ALLIANCE_STATUS_LABEL[alliance.status]}
      </span>
    </div>
    <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
      <dt>연합 ID</dt><dd>#{alliance.id}</dd>
      <dt>총점</dt><dd>{alliance.totalScore.toLocaleString()}점</dd>
      <dt>유지 기간</dt><dd>{formatDurationHours(alliance.durationHours)}</dd>
      <dt>만료 시각</dt><dd>{kst(alliance.expiredAt)}</dd>
      <dt>남은 시간</dt><dd>{alliance.status === 'EXPIRED' ? '만료됨' : remainingTime(alliance.expiredAt, now())}</dd>
    </dl>
  </>;
}
