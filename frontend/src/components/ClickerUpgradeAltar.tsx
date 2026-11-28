import { useId, useState } from 'react';
import { ArrowUp, ChevronDown, ChevronUp, List, RefreshCw } from 'lucide-react';
import type { useClicker } from '../hooks/useClicker';
import { ClickerArt } from './CampusItemArt';

type Clicker = ReturnType<typeof useClicker>;

const action = 'pixel-plaque min-h-11 px-2 py-1 inline-flex items-center gap-1.5 font-bold cursor-pointer motion-reduce:transition-none motion-reduce:active:transform-none';
const icon = 'size-4 shrink-0';
const points = (value: number) => `${value.toLocaleString('ko-KR')}점`;

export function ClickerUpgradeAltar({ clicker, contribution, desktop }: {
  clicker: Clicker; contribution: number; desktop: boolean;
}) {
  const [expanded, setExpanded] = useState(false);
  const titleId = useId();
  const contentId = useId();
  const helpId = useId();
  const { api, level, nextClicker, power, upgradeCost } = clicker;
  if (!api) return null;
  const title = '내 클리커 강화 제단';
  const Chevron = expanded ? ChevronUp : ChevronDown;
  const cost = api.loadNotice ? null : upgradeCost;
  const progress = cost ? Math.min(100, Math.floor(contribution / cost * 100)) : 100;

  return (
    <section aria-labelledby={titleId} className="rpg-box min-w-0 p-2 font-pixel text-xs text-[#261608]">
      <h3 className="font-black">
        {desktop ? <span id={titleId}>{title}</span> : (
          <button id={titleId} type="button" aria-expanded={expanded} aria-controls={contentId}
            onClick={() => setExpanded(!expanded)} className={`${action} plaque-parchment w-full text-left`}>
            <Chevron aria-hidden="true" className={icon} />{title}
          </button>
        )}
      </h3>
      <div id={contentId} hidden={!desktop && !expanded}>
        <div className="mt-2 flex flex-col gap-2 border-t-2 border-[#eeddb6] pt-2">
          <div className="flex flex-wrap items-center gap-3">
            <div data-testid="clicker-altar-art" className="relative grid size-18 shrink-0 place-items-center border-3 border-[#261608] bg-[#ebd7a3] shadow-[2px_2px_0px_#261608]" aria-hidden="true">
              <span className="absolute size-14 rounded-full border-2 border-[#ca8a04]/40 bg-[#fef08a]" />
              <span className="absolute inset-x-0 bottom-0 h-2 border-t-2 border-[#261608] bg-[#b45309]" />
              <span className="relative"><ClickerArt level={level} size={48} /></span>
            </div>
            <dl className="min-w-0 flex-1 space-y-1 [overflow-wrap:anywhere]">
              <div><dt className="text-[#8b5327]">현재 효과</dt><dd className="font-bold">{level === null ? '—' : `Lv.${level} · 기본 클릭당 ${points(power)}`}</dd></div>
              <div><dt className="text-[#8b5327]">다음 효과</dt><dd className="font-bold">{api.loadNotice ? '정보 확인 중' : nextClicker ? `Lv.${nextClicker.level} · 기본 클릭당 ${points(nextClicker.power)}` : '최고 단계'}</dd></div>
              <div><dt className="text-[#8b5327]">강화 비용</dt><dd className="font-bold">{api.loadNotice ? '—' : cost === null ? '추가 강화 없음' : points(cost)}</dd></div>
            </dl>
          </div>
          {cost !== null && (
            <div className="flex flex-col gap-1">
              <p className="text-[#8b5327]">보유 기여도 {points(contribution)} / 필요 {points(cost)}</p>
              <progress className="block h-2.5 w-full accent-[#ca8a04]" max={100} value={progress} aria-label="다음 강화 비용 대비 보유 기여도" />
            </div>
          )}
          <p id={helpId} role="status" className="text-[#8b5327]">{api.disabledReason ?? '서버 레벨표 기준 · 개인 기여도만 차감합니다.'}</p>
          <div className="flex flex-wrap gap-2">
            <button type="button" aria-disabled={!api.canUpgrade} aria-describedby={helpId}
              onClick={() => { if (api.canUpgrade) api.upgrade(); }}
              className={`${action} plaque-green aria-disabled:opacity-60 aria-disabled:cursor-not-allowed`}>
              <ArrowUp aria-hidden="true" className={icon} />{api.pending ? '요청 중…' : '클리커 강화하기'}
            </button>
            {api.loadNotice && <button type="button" onClick={api.reload} className={`${action} plaque-parchment`}>
              <RefreshCw aria-hidden="true" className={icon} />클리커 정보 다시 조회
            </button>}
            {api.reconcile && <button type="button" onClick={api.reconcile} className={`${action} plaque-yellow`}>
              <RefreshCw aria-hidden="true" className={icon} />서버 상태 확인
            </button>}
          </div>
          {api.error && <p role="alert" className="font-bold text-[#b91c1c]">{api.error}</p>}
          {api.status && <p role="status">{api.status}</p>}
          {api.levels.length > 0 && (
            <details>
              <summary className="flex min-h-11 cursor-pointer list-none items-center gap-1.5 font-bold [&::-webkit-details-marker]:hidden">
                <List aria-hidden="true" className={icon} />클리커 레벨표 보기
              </summary>
              <div tabIndex={0} role="region" aria-label="클리커 레벨표 스크롤" className="max-h-52 max-w-full overflow-auto focus-visible:outline-2 focus-visible:outline-offset-[-2px]">
                <table className="w-full border-separate border-spacing-0 text-center whitespace-nowrap">
                  <caption className="py-2">서버 클리커 레벨표 · 해당 레벨 도달 비용</caption>
                  <thead className="sticky top-0 z-10 bg-[#fdf5df]"><tr><th scope="col" className="p-2">레벨</th><th scope="col" className="p-2">클릭 세기</th><th scope="col" className="p-2">도달 비용</th></tr></thead>
                  <tbody>{api.levels.map((row) => <tr key={row.level}>
                    <th scope="row" className="sticky left-0 bg-[#fdf5df] p-2">Lv.{row.level}</th>
                    <td className="p-2">{points(row.clickPower)}</td>
                    <td className="p-2">{points(row.requiredScore)}</td>
                  </tr>)}</tbody>
                </table>
              </div>
            </details>
          )}
        </div>
      </div>
    </section>
  );
}
