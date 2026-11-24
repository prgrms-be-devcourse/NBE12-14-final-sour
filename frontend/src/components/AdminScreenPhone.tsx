import { ChevronLeft, RefreshCw } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useAuthSession } from '../api/ApiProvider';
import { useAdminPendingSupportCount } from '../api/admin';
import { errorMessage } from '../api/errors';
import { useIsAdmin, useMyInfo } from '../api/users';
import { soundManager } from '../utils/sound';
import { ADMIN_BOX as BOX, ADMIN_BUTTON as BUTTON, AdminForbiddenNotice } from './adminUi';
import { AdminSupportDetailView, AdminSupportList, type AdminSupportListState } from './AdminSupportsPanel';
import { AdminTestToolsPanel, type SchoolNameTestControl } from './AdminTestToolsPanel';
import { AdminAbuseRanking, AdminUserStatusPanel, type AdminUserTarget } from './AdminUsersPanel';
import { LoadNotice } from './LoadNotice';
import { PhoneShell } from './PhoneShell';

// Admin home in API mode (SCRUM-172, plan .agents/01-PLAN/261119-1023-scrum172_admin_entry.md). Shown only to a
// profile whose role is ADMIN; the server checks the role again on every admin call. The menu items are switched on
// by SCRUM-173 (inquiries, AdminSupportsPanel), SCRUM-174 (member status, abuse ranking, AdminUsersPanel) and SCRUM-175
// (test tools, AdminTestToolsPanel).

type AdminView = { kind: 'home' } | { kind: 'supports' } | { kind: 'support'; id: number }
  | { kind: 'users'; target?: AdminUserTarget } | { kind: 'abuse' } | { kind: 'tools' };

/** Admin menu entries, in the order of their subtasks. */
const ADMIN_MENU: ReadonlyArray<{ label: string; detail: string; opens: MenuView }> = [
  { label: '문의 처리', detail: '문의 목록과 답변', opens: 'supports' },
  { label: '회원 정지·해제', detail: '회원 상태 변경', opens: 'users' },
  { label: '어뷰징 순위', detail: '최근 3일 부정행위 순위', opens: 'abuse' },
  { label: '테스트 도구', detail: '이 브라우저의 요청 지연·실패 주입, 클릭 거부, 학교명 표시', opens: 'tools' },
];

type MenuView = 'supports' | 'users' | 'abuse' | 'tools';

const TITLE: Record<AdminView['kind'], string> = { home: '관리자', supports: '문의 처리', support: '문의 상세', users: '회원 정지·해제', abuse: '어뷰징 순위', tools: '테스트 도구' };

interface AdminScreenPhoneProps {
  onBack: () => void;
  onGoToLogin: () => void;
  /** The campus header's school name override, owned by the app (SCRUM-175). */
  schoolName: SchoolNameTestControl;
}

export function AdminScreenPhone({ onBack, onGoToLogin, schoolName }: AdminScreenPhoneProps) {
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo();
  const isAdmin = useIsAdmin();
  const [stored, setView] = useState<AdminView>({ kind: 'home' });
  const [supportList, setSupportList] = useState<AdminSupportListState>({ filter: 'RECEIVED', page: 0 });
  const [abusePage, setAbusePage] = useState(0);
  const heading = useRef<HTMLHeadingElement>(null);
  // Only an admin gets past the home; signing out or losing the role falls back to its notices.
  const view: AdminView = signedIn && isAdmin && me.data ? stored : { kind: 'home' };
  useEffect(() => { if (view.kind !== 'home' || stored.kind !== 'home') heading.current?.focus(); }, [view.kind, stored.kind]);

  if (signedIn && !me.data) {
    return <LoadNotice status={me.isError ? 'failed' : 'loading'} name="내 정보" failureDetail={errorMessage(me.error)}
      onRetry={() => void me.refetch()} onBack={onBack} backLabel="내 정보로" />;
  }

  // A status change opened from the abuse ranking goes back to the ranking.
  const [back, backLabel] = view.kind === 'support' ? [() => setView({ kind: 'supports' }), '문의 목록으로 돌아가기']
    : view.kind === 'users' && view.target ? [() => setView({ kind: 'abuse' }), '어뷰징 순위로 돌아가기']
    : view.kind === 'home' ? [onBack, '내 정보로 돌아가기'] : [() => setView({ kind: 'home' }), '관리자 홈으로 돌아가기'];

  return (
    <PhoneShell>
      <header className="bg-[#faecc7] border-b-2 border-[#261608] p-2 flex items-center gap-3 shrink-0 font-pixel text-[#261608]">
        <button type="button" onClick={() => { soundManager.playTabSound(); back(); }} className={BUTTON} aria-label={backLabel}>
          <ChevronLeft className="w-4 h-4 inline" aria-hidden="true" /> 뒤로
        </button>
        <h1 ref={heading} tabIndex={-1} className="text-sm font-black">{TITLE[view.kind]}</h1>
      </header>
      <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-3 font-pixel text-[#261608]">
        {view.kind === 'supports' && me.data ? (
          <AdminSupportList adminId={me.data.id} state={supportList} onChange={setSupportList}
            onOpen={(id) => setView({ kind: 'support', id })} onGoToLogin={onGoToLogin} />
        ) : view.kind === 'users' && me.data ? (
          <AdminUserStatusPanel key={view.target?.userId ?? 'blank'} adminId={me.data.id} target={view.target} onGoToLogin={onGoToLogin} />
        ) : view.kind === 'abuse' && me.data ? (
          <AdminAbuseRanking adminId={me.data.id} page={abusePage} onPage={setAbusePage}
            onChangeStatus={(target) => setView({ kind: 'users', target })} onGoToLogin={onGoToLogin} />
        ) : view.kind === 'tools' && me.data ? (
          <AdminTestToolsPanel schoolName={schoolName} />
        ) : view.kind === 'support' && me.data ? (
          <AdminSupportDetailView key={view.id} adminId={me.data.id} supportId={view.id}
            onBackToList={() => setView({ kind: 'supports' })} onGoToLogin={onGoToLogin} />
        ) : !signedIn ? (
          <div role="status" className={`${BOX} items-center text-center`}>
            <p className="text-sm font-bold">관리자 계정으로 로그인해 주세요</p>
            <button type="button" onClick={onGoToLogin} className={BUTTON}>로그인</button>
          </div>
        ) : isAdmin ? (
          <AdminHome onGoToLogin={onGoToLogin} onOpen={(kind) => setView({ kind })} />
        ) : (
          <p role="status" data-testid="admin-denied" className={`${BOX} text-sm font-bold text-center`}>관리자만 이용할 수 있어요.</p>
        )}
      </div>
    </PhoneShell>
  );
}

function AdminHome({ onGoToLogin, onOpen }: { onGoToLogin: () => void; onOpen: (kind: MenuView) => void }) {
  const { query: pending, forbidden } = useAdminPendingSupportCount();

  if (forbidden) return <AdminForbiddenNotice onGoToLogin={onGoToLogin} />;

  return (
    <>
      <section aria-label="답변 대기 문의" className={BOX}>
        {pending.data !== undefined ? (
          <p className="text-sm font-bold">답변 대기 문의 <span data-testid="admin-pending-count">{pending.data.toLocaleString()}</span>건</p>
        ) : pending.isError ? (
          <div role="alert" className="flex flex-col gap-1.5">
            <p className="text-xs font-bold text-[#991b1b]">답변 대기 문의를 불러오지 못했어요</p>
            <p className="text-[10px] text-[#8b5327]">{errorMessage(pending.error)}</p>
            <button type="button" onClick={() => void pending.refetch()} className={`${BUTTON} self-start flex items-center gap-1`}>
              <RefreshCw className="w-3.5 h-3.5" aria-hidden="true" /> 다시 시도
            </button>
          </div>
        ) : (
          <p role="status" className="text-xs">답변 대기 문의 불러오는 중…</p>
        )}
      </section>
      <nav aria-label="관리자 메뉴" className="flex flex-col gap-2">
        {ADMIN_MENU.map(({ label, detail, opens }) => (
          <button key={label} type="button" aria-describedby={`admin-menu-${label}`}
            onClick={() => { soundManager.playTabSound(); onOpen(opens); }}
            className="pixel-plaque plaque-parchment min-h-12 px-3 py-2 text-left text-xs font-bold cursor-pointer">
            {label}
            <span id={`admin-menu-${label}`} className="block text-[10px] font-normal">{detail}</span>
          </button>
        ))}
      </nav>
    </>
  );
}
