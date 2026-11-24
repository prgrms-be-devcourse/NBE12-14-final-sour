import { useId } from 'react';
import type { LibraryClickControl } from '../hooks/useCampusClicks';
import type { LocationStatus } from '../utils/clickLocation';

const MESSAGES: Record<LocationStatus, string> = {
  idle: '위치 없이도 일반 클릭을 계속 적립할 수 있어요.',
  locating: '위치를 확인하는 중이에요. 기다리지 않고 일반 클릭을 계속할 수 있어요.',
  ready: '확인한 위치를 새 클릭 제출에 사용해요. 보너스 적용 여부는 서버가 판단해요.',
  denied: '위치 권한이 거부됐어요. 브라우저 설정에서 허용한 뒤 다시 확인할 수 있어요. 일반 클릭은 계속돼요.',
  unavailable: '위치를 가져오지 못했어요. 잠시 후 다시 확인해 주세요. 일반 클릭은 계속돼요.',
  timeout: '위치 확인 시간이 초과됐어요. 다시 확인하거나 일반 클릭을 계속해 주세요.',
  inaccurate: '위치 오차가 100m보다 커서 좌표를 전송하지 않아요. 위치가 잘 잡히는 곳에서 다시 확인해 주세요.',
  expired: '위치 유효시간이 지났어요. 다시 확인하기 전까지 새 제출에는 좌표를 보내지 않아요.',
  unsupported: '이 브라우저는 위치 확인을 지원하지 않아요. 일반 클릭은 계속 이용할 수 있어요.',
  insecure: '위치 확인은 HTTPS 연결이 필요해요. 일반 클릭은 계속 이용할 수 있어요.',
};
const BUTTON = 'pixel-plaque plaque-parchment min-h-11 px-2 text-xs font-bold cursor-pointer disabled:opacity-50';

export function LibraryBonusControl({ control }: { control: LibraryClickControl }) {
  const id = useId();
  const { status, accuracy } = control.location;
  const active = status === 'locating' || status === 'ready';
  return <details className="shrink-0 w-full border-2 border-[#261608] bg-[#faecc7] font-pixel text-[#261608]">
    <summary className="min-h-11 px-2 py-2 text-xs font-bold cursor-pointer">📚 도서관 보너스 (선택){control.lastResult?.libraryBonus ? ' · 최근 적용' : ''}</summary>
    <div className="px-2 pb-2 space-y-2">
      <p id={`${id}-purpose`} className="text-[11px]">
        내 학교 도서관 100m 이내인지 서버가 확인할 수 있도록 실제 위경도를 클릭 요청에 보내요.
        새 제출용 위치는 메모리에만 최대 30초 보관해요. 지속 추적·브라우저 저장은 하지 않아요.
      </p>
      <div className="flex flex-wrap gap-2">
        <button type="button" className={BUTTON} disabled={status === 'locating'} aria-describedby={`${id}-purpose ${id}-status`} onClick={control.request}>
          {status === 'locating' ? '위치 확인 중…' : '내 위치 확인'}
        </button>
        <button type="button" className={BUTTON} disabled={!active} onClick={control.clear}>
          {status === 'locating' ? '위치 확인 취소' : '다음 제출부터 위치 사용 중지'}
        </button>
      </div>
      <p id={`${id}-status`} role="status" className="text-[11px]" data-testid="click-location-status">
        {MESSAGES[status]}{status === 'ready' && accuracy !== null ? ` 위치 오차 약 ${Math.ceil(accuracy)}m.` : ''}
      </p>
      <p className="text-[10px]">중지·만료는 새 제출부터 적용해요. 이미 전송을 시작한 제출은 재시도할 때도 같은 좌표를 사용해요.</p>
      {control.lastResult && <p role="status" className="text-xs font-bold" data-testid="library-bonus-result">
        {control.lastResult.libraryBonus ? `최근 서버 반영: 도서관 보너스 적용 · 최종 배율 ${control.lastResult.multiplier}배`
          : '최근 서버 반영: 도서관 보너스 미적용'}
      </p>}
    </div>
  </details>;
}
