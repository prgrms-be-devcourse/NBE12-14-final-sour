/**
 * The main game's screen frame: fixed header, scrolling main, fixed footer (preview bar + bottom nav), inside a
 * PhoneShell. My campus (App) and a visited campus (VisitingScreen1Phone) both use it, so the two screens keep the
 * same layout (V1, docs/261110-2005-visit_ui_jeju_map_plan.md §4).
 */
import React, { type Ref } from 'react';
import { PhoneShell } from './PhoneShell';

interface GameScreenLayoutProps {
  /** Which screen this is; tests compare the two layouts. */
  kind: 'my-campus' | 'visit' | 'shop';
  header: React.ReactNode;
  /** Bar above the bottom nav: chat preview on my campus, the spectator bar while visiting. */
  previewBar?: React.ReactNode;
  nav: React.ReactNode;
  /**
   * Let main's content take the remaining height (the campus, T2). Main is an `@container` so the campus can cap
   * its height by the frame width (CampusStage fill).
   */
  fillMain?: boolean;
  embedded?: boolean;
  mainRef?: Ref<HTMLElement>;
  /** Main's content. */
  children: React.ReactNode;
  /** Sheets and dialogs, outside the scrolling column. */
  overlays?: React.ReactNode;
}

export function GameScreenLayout({ kind, header, previewBar, nav, fillMain = false, embedded = false, mainRef, children, overlays }: GameScreenLayoutProps) {
  return (
    <PhoneShell embedded={embedded}>
      {/* Game Screen: 고정 헤더 + 스크롤되는 main + 고정 푸터 */}
      <div data-game-layout={kind} className="flex-1 min-h-0 p-2 flex flex-col overflow-hidden bg-[#faecc7]/30">
        {/* Header: 학교 HUD (스크롤되지 않음) */}
        <div className="shrink-0">{header}</div>

        {/* Main: 콘텐츠만 세로 스크롤. my-auto는 넘칠 때 위쪽이 잘리지 않는 안전한 세로 가운데 정렬. */}
        <main data-game-main ref={mainRef} tabIndex={mainRef ? -1 : undefined} className="@container flex-1 min-h-0 overflow-y-auto overflow-x-hidden overscroll-contain flex flex-col">
          <div className={`my-auto py-1 flex flex-col ${fillMain ? 'flex-1' : ''}`}>{children}</div>
        </main>

        {/* Footer: 미리보기 바 + 하단 내비 (스크롤되지 않음) */}
        <footer className="shrink-0">
          <div className="w-full mt-1">{previewBar}</div>
          {nav}
        </footer>
      </div>

      {overlays}
    </PhoneShell>
  );
}
