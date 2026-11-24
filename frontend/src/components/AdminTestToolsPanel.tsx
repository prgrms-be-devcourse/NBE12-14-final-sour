import { useMutation } from '@tanstack/react-query';
import { useEffect, useId } from 'react';
import { useApiClickRefusal, useApiClient, useApiFaultMode } from '../api/ApiProvider';
import { errorMessage } from '../api/errors';
import type { ApiFaultMode } from '../api/faults';
import { getMyInfo, useIsAdmin } from '../api/users';
import { DEMO_SCHOOL_NAMES, type DemoSchoolNameId } from '../data/demoSchoolNames';
import { MOCK_REQUEST_MODES } from '../utils/mockRequest';
import { ADMIN_BOX, ADMIN_BUTTON } from './adminUi';

// Admin test tools in API mode (SCRUM-175, plan .agents/01-PLAN/261119-1108-scrum175_admin_test_tools.md). Every
// tool stays inside this browser: request faults run in the API client before a request leaves, the click refusal
// answers POST /clicks with the server's 429 itself, and the school name only changes the campus header.

export interface SchoolNameTestControl {
  value: DemoSchoolNameId;
  onChange: (id: DemoSchoolNameId) => void;
}

const modeLabel = (mode: ApiFaultMode) => MOCK_REQUEST_MODES.find((option) => option.mode === mode)!.label;

/** Tools that are not at their default, as short labels for the notice. */
export function activeTestTools(mode: ApiFaultMode, clickRefusal: boolean, schoolName: DemoSchoolNameId): string[] {
  return [
    ...(mode === 'normal' ? [] : [`요청 ${modeLabel(mode)}`]),
    ...(clickRefusal ? ['클릭 거부'] : []),
    ...(schoolName === 'default' ? [] : [`학교명 ${DEMO_SCHOOL_NAMES.find(({ id }) => id === schoolName)!.label}`]),
  ];
}

function useResetTestTools(schoolName: SchoolNameTestControl) {
  const { faults } = useApiClient();
  const { onChange } = schoolName;
  return () => { faults.reset(); onChange('default'); };
}

export function AdminTestToolsPanel({ schoolName }: { schoolName: SchoolNameTestControl }) {
  const id = useId();
  const client = useApiClient();
  const [mode, setMode] = useApiFaultMode();
  const [clickRefusal, setClickRefusal] = useApiClickRefusal();
  const resetAll = useResetTestTools(schoolName);
  // Goes through the same client, so it shows what the current injection does to a real request.
  const probe = useMutation({
    mutationFn: async (_sentIn: ApiFaultMode) => {
      const started = performance.now();
      await getMyInfo(client);
      return Math.round(performance.now() - started);
    },
    retry: false,
  });
  const active = activeTestTools(mode, clickRefusal, schoolName.value);

  return <>
    <p className="text-xs">이 브라우저에서만 바뀌어요. 서버와 다른 사용자에게는 영향이 없고, 로그아웃하면 모두 기본으로 돌아가요.</p>

    <section aria-labelledby={`${id}-faults`} className={`${ADMIN_BOX} text-xs`}>
      <h2 id={`${id}-faults`} className="font-black text-sm">요청 지연·실패 주입</h2>
      <fieldset className="flex flex-col gap-1">
        <legend className="mb-1">다음 API 요청</legend>
        {MOCK_REQUEST_MODES.map((option) => (
          <label key={option.mode} className="flex items-center gap-2 min-h-11 cursor-pointer">
            <input type="radio" name={`${id}-mode`} value={option.mode} checked={mode === option.mode} className="w-5 h-5"
              onChange={() => setMode(option.mode)} />
            {option.label}
          </label>
        ))}
      </fieldset>
      <p className="text-[11px]">1회 실패는 다음 요청 하나만 실패시키고 정상으로 돌아가요. 실패한 요청은 서버에 가지 않아요.</p>
      <button type="button" className={`${ADMIN_BUTTON} self-start`} disabled={probe.isPending} onClick={() => probe.mutate(mode)}>
        {probe.isPending ? '확인 요청 보내는 중…' : '확인 요청 보내기'}
      </button>
      {probe.isSuccess && (
        <p role="status" data-testid="admin-probe-result" className="text-[#14532d]">
          성공 · 내 정보 조회(GET /users/me) {probe.data.toLocaleString()}ms · 보낼 때 모드: {modeLabel(probe.variables)}
        </p>
      )}
      {probe.isError && (
        <p role="alert" data-testid="admin-probe-result" className="text-[#7f1d1d]">
          실패 · {errorMessage(probe.error)} · 보낼 때 모드: {modeLabel(probe.variables)}
        </p>
      )}
    </section>

    <section aria-labelledby={`${id}-clicks`} className={`${ADMIN_BOX} text-xs`}>
      <h2 id={`${id}-clicks`} className="font-black text-sm">클릭 거부 화면 확인</h2>
      <label className="flex items-center gap-2 min-h-11 cursor-pointer">
        <input type="checkbox" checked={clickRefusal} className="w-5 h-5" onChange={(event) => setClickRefusal(event.target.checked)} />
        내 캠퍼스 클릭을 서버 대신 429로 거부
      </label>
      <p className="text-[11px]">켜 두면 클릭 전송이 서버로 가지 않고 "제출 요청이 너무 많습니다"(CLICK_TOO_FREQUENT)로 끝나요. 거부된 클릭은 점수에 들어가지 않아요. 내 캠퍼스에서 클릭해 확인하세요.</p>
    </section>

    <section aria-labelledby={`${id}-school`} className={`${ADMIN_BOX} text-xs`}>
      <h2 id={`${id}-school`} className="font-black text-sm">학교명 길이 표시</h2>
      <fieldset className="flex flex-col gap-1">
        <legend className="mb-1">내 캠퍼스 상단에 보일 학교명</legend>
        {DEMO_SCHOOL_NAMES.map((option) => (
          <label key={option.id} className="flex items-center gap-2 min-h-11 cursor-pointer">
            <input type="radio" name={`${id}-school`} value={option.id} checked={schoolName.value === option.id} className="w-5 h-5"
              onChange={() => schoolName.onChange(option.id)} />
            {option.label}{option.name ? ` (${option.name})` : ' (내 학교)'}
          </label>
        ))}
      </fieldset>
      <p className="text-[11px]">화면 표시만 바뀌어요. 학교 정보와 점수는 그대로예요.</p>
    </section>

    <button type="button" className={ADMIN_BUTTON} disabled={active.length === 0} onClick={resetAll}>
      {active.length === 0 ? '모두 기본값이에요' : '모두 해제'}
    </button>
  </>;
}

/**
 * Mounted at the app root in API mode. Shows which test tools are on, and puts them all back to default whenever the
 * signed-in profile is not an admin: after a logout, another member on this browser gets ordinary requests.
 */
export function ApiTestToolsNotice({ schoolName }: { schoolName: SchoolNameTestControl }) {
  const isAdmin = useIsAdmin();
  const { faults } = useApiClient();
  const [mode] = useApiFaultMode();
  const [clickRefusal] = useApiClickRefusal();
  const resetAll = useResetTestTools(schoolName);
  const { onChange } = schoolName;
  useEffect(() => {
    if (isAdmin) return;
    faults.reset();
    onChange('default');
  }, [isAdmin, faults, onChange]);

  const active = activeTestTools(mode, clickRefusal, schoolName.value);
  if (!isAdmin || active.length === 0) return null;
  return (
    <div role="status" data-testid="test-tools-active" className="w-full max-w-3xl border-2 border-dashed border-[#7f1d1d] bg-[#fef2f2] px-3 py-1.5 flex flex-wrap items-center gap-2 font-pixel text-xs text-[#7f1d1d]">
      <span className="font-black">테스트 도구 켜짐: {active.join(' · ')}</span>
      <button type="button" onClick={resetAll} className="pixel-plaque plaque-parchment min-h-11 px-3 py-1 text-xs font-bold cursor-pointer">모두 해제</button>
    </div>
  );
}
