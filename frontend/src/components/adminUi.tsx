// Shared look of the admin screens (SCRUM-171).

export const ADMIN_BUTTON = 'pixel-plaque plaque-wood min-h-11 min-w-11 px-3 py-2 text-xs font-bold cursor-pointer disabled:opacity-50 disabled:cursor-default';
export const ADMIN_BOX = 'rpg-box p-3 bg-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex flex-col gap-2';
export const ADMIN_INPUT = 'w-full min-h-12 border-2 border-[#261608] bg-white px-2 py-2 text-base focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#261608] disabled:bg-stone-100';

/** A 403 COMMON_FORBIDDEN while the profile still says ADMIN: the access token carries the old role. */
export function AdminForbiddenNotice({ onGoToLogin }: { onGoToLogin: () => void }) {
  return (
    <div role="alert" data-testid="admin-forbidden" className={`${ADMIN_BOX} items-center text-center`}>
      <p className="text-sm font-black text-[#991b1b]">이 로그인에는 아직 관리자 권한이 없어요</p>
      <p className="text-[11px]">권한이 막 바뀌었다면 로그아웃한 뒤 다시 로그인해 주세요.</p>
      <button type="button" onClick={onGoToLogin} className={ADMIN_BUTTON}>로그인 화면으로</button>
    </div>
  );
}
