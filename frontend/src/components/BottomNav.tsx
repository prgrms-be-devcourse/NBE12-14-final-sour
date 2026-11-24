import React, { createContext, useContext } from 'react';
import { soundManager } from '../utils/sound';
import { GameMenuIcon } from './GameMenuIcon';

export type NavTab = 'campus' | 'rankings' | 'shop' | 'map';

/**
 * 하단 내비의 연합·대결 버튼 설정. 탭 전환이 아니라 별도 화면 이동이라 NavTab에 넣지 않고,
 * 모든 폰 화면의 BottomNav가 같은 동작을 쓰도록 App에서 한 번 제공한다.
 * Provider가 없으면(단독 렌더링·테스트) 기존 4칸으로 표시한다.
 */
export interface AllianceNavValue {
  open: () => void;
  /** 받은 연합 신청·받은 대결 신청 대기, 또는 우리 연합의 대결 예정·진행 중 */
  hasAlert: boolean;
}
const AllianceNavContext = createContext<AllianceNavValue | null>(null);
export const AllianceNavProvider = AllianceNavContext.Provider;

/**
 * 다섯 버튼이 같은 모양을 쓴다(가운데 강조 없음, 결정 Q7). 사용자 지시 2-1-1에 따라 아이콘 16→24px,
 * 라벨 11→12px로 버튼을 거의 채우고, 세로 패딩을 줄여 버튼 높이(47px)는 그대로 둔다.
 * 라벨 12px·tracking-tighter는 360px 화면의 가장 좁은 버튼(57px)에서 "연합·대결"(44.6px)이
 * 판 안쪽 흰 선 안(47px)에 들어가는 최대값이다. 13px는 50px로 선을 넘었다(T3 실측).
 * `!`: 레이어 밖의 `.font-pixel`(src/index.css)이 letter-spacing을 -0.02em으로 고정해 tracking 유틸리티를 이긴다.
 * 센서: tests/e2e/narrow-layout.mjs.
 */
const NAV_BUTTON_CLASS = 'pixel-plaque flex flex-col items-center justify-center py-0.5 px-0 rounded-none cursor-pointer relative';
const NAV_ICON_CLASS = 'w-6 h-6';
const NAV_LABEL_CLASS = 'font-pixel text-[12px] tracking-tighter! leading-none font-bold whitespace-nowrap';

interface BottomNavProps {
  /** 'alliance': 연합·대결 화면(T4, 계획서 §7)에서 연합·대결 버튼을 활성으로 표시한다. */
  activeTab: NavTab | 'alliance';
  onChangeTab: (tab: NavTab) => void;
  hasShopUpgrade: boolean;
  hasRankChange: boolean;
}

export const BottomNav: React.FC<BottomNavProps> = ({
  activeTab,
  onChangeTab,
  hasShopUpgrade,
  hasRankChange,
}) => {
  const allianceNav = useContext(AllianceNavContext);
  const tabs: { id: NavTab; label: string; badge?: boolean }[] = [
    {
      id: 'shop',
      label: '상점',
      badge: hasShopUpgrade,
    },
    {
      id: 'rankings',
      label: '순위',
      badge: hasRankChange,
    },
    {
      id: 'campus',
      label: '캠퍼스',
    },
    {
      id: 'map',
      label: '지도',
    },
  ];

  const isAllianceActive = activeTab === 'alliance';
  const showAllianceAlert = !!allianceNav?.hasAlert && !isAllianceActive;
  const allianceButton = allianceNav ? (
    <button
      key="alliance"
      type="button"
      onClick={() => {
        soundManager.playTabSound();
        allianceNav.open();
      }}
      title={showAllianceAlert ? '연합·대결 화면으로 이동 (확인할 소식 있음)' : '연합·대결 화면으로 이동'}
      data-alert={showAllianceAlert ? 'true' : undefined}
      aria-current={isAllianceActive ? 'page' : undefined}
      className={`${NAV_BUTTON_CLASS} ${isAllianceActive ? 'plaque-yellow' : 'plaque-wood'}`}
    >
      {showAllianceAlert && (
        <span className="absolute top-1 right-2 w-2 h-2 bg-[#e11d48] border border-[#ffffff] animate-pixel-blink" />
      )}
      <div className="mb-px">
        <GameMenuIcon name="alliance" className={NAV_ICON_CLASS} />
      </div>
      <span className={NAV_LABEL_CLASS}>
        연합·대결
      </span>
    </button>
  ) : null;

  return (
    <nav aria-label="주요 메뉴" className="w-full rpg-box p-1.5 mt-2 relative z-30 select-none">
      <div className={`grid ${allianceButton ? 'grid-cols-5' : 'grid-cols-4'} gap-1 sm:gap-1.5`}>
        {tabs.flatMap((tab) => {
          const isActive = activeTab === tab.id;
          const tabButton = (
            <button
              key={tab.id}
              onClick={() => {
                onChangeTab(tab.id);
                soundManager.playTabSound();
              }}
              aria-current={isActive ? 'page' : undefined}
              className={`${NAV_BUTTON_CLASS} ${
                isActive ? 'plaque-yellow' : 'plaque-wood'
              }`}
            >
              {tab.badge && !isActive && (
                <span className="absolute top-1 right-2 w-2 h-2 bg-[#e11d48] border border-[#ffffff] animate-pixel-blink" />
              )}

              <div className="mb-px"><GameMenuIcon name={tab.id} className={NAV_ICON_CLASS} /></div>
              <span className={NAV_LABEL_CLASS}>
                {tab.label}
              </span>
            </button>
          );
          // 순서는 상점·순위·캠퍼스·연합·대결·지도(사용자 지시 2-1-2): 연합·대결은 캠퍼스 뒤에 둔다.
          return tab.id === 'campus' && allianceButton ? [tabButton, allianceButton] : [tabButton];
        })}
      </div>
    </nav>
  );
};

/**
 * 하단 내비를 담는 고정 푸터. 순위·상점 화면과 같은 모양이다.
 * 연합·대결 4개 화면(T4, 계획서 §7)이 같은 푸터를 쓰도록 모았다.
 */
export function BottomNavFooter({ activeTab, onChangeTab }: Pick<BottomNavProps, 'activeTab' | 'onChangeTab'>) {
  return (
    <div className="w-full px-2 pb-2 bg-[#fdf5df] border-t-2 border-[#261608] shrink-0">
      <BottomNav activeTab={activeTab} onChangeTab={onChangeTab} hasShopUpgrade={false} hasRankChange={false} />
    </div>
  );
}
