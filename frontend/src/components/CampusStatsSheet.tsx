import { DialogPanel } from './DialogPanel';
import { useMockGame } from '../hooks/useMockGame';
import { useClicker } from '../hooks/useClicker';

/**
 * Body of the campus stats sheet (T5, docs/261110-1005-mockup_remaining_tasks_plan.md §8): the campus stage with
 * both features, then the contribution and clicker boxes that used to sit under the campus. Shows the shared
 * mock game values as they are; no new calculation.
 */
export function CampusStatsSheet({ onGoToShop }: { onGoToShop: () => void }) {
  const { campus, state } = useMockGame();
  const clicker = useClicker();
  return (
    <div className="p-3 flex flex-col gap-2 bg-[#fdf5df] text-[#261608]">
      <h2 className="font-pixel text-sm font-black">상세 스탯</h2>
      <section aria-label="캠퍼스" className="rpg-box p-2 flex flex-col gap-1 shadow-[2px_2px_0px_#261608]">
        <p className="font-pixel text-xs font-bold">🏛️ 캠퍼스 Lv.{campus.level} · {campus.name}</p>
        <ul className="flex flex-wrap gap-1 font-pixel text-[10px] font-bold">
          {campus.features.map((feature, i) => (
            <li key={feature} className="bg-[#fdf5df] border-2 border-[#261608] px-1.5 py-0.5 flex items-center gap-1">
              <span className={`w-1.5 h-1.5 border border-[#261608] inline-block ${i === 0 ? 'bg-[#4ade80]' : 'bg-[#38bdf8]'}`} aria-hidden="true" />
              {feature}
            </li>
          ))}
        </ul>
      </section>
      <DialogPanel myContribution={state.contribution} clickerLevel={clicker.level} />
      <button type="button" onClick={onGoToShop}
        className="pixel-plaque plaque-yellow min-h-11 font-pixel text-xs font-bold cursor-pointer">
        상점에서 강화하기
      </button>
    </div>
  );
}
