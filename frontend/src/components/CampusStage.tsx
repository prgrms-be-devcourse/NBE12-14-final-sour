/**
 * The campus as the main game shows it, shared by my campus (CampusView) and a visited campus (VisitingScreen1Phone),
 * so both screens keep one size rule and one set of corner controls (V1, docs/261110-2005-visit_ui_jeju_map_plan.md §4).
 * It only draws: the ticker bubble, the frame, the scene, the level and feature labels, and whatever the caller puts
 * in the frame. Input (scoring taps or spectator notices) stays with the caller.
 */
import React from 'react';
import { Sparkles } from 'lucide-react';
import { CampusScene } from './CampusScene';
import { CampusAmbience } from './CampusAmbience';
import { CAMPUS_STAGES } from '../data/gameRules';
import { soundManager } from '../utils/sound';

/** Inner height ≤ inner width × 392/358 (CAMPUS_TALL_ASPECT); the 8px is the 4px border on each side. */
export const CAMPUS_FILL_HEIGHT = 'flex-1 min-h-[220px] max-h-[calc((100cqw_-_8px)*392/358_+_8px)]';
/** The comparison mockups' fixed frame height. */
export const CAMPUS_FIXED_HEIGHT = 'h-[375px]';

interface CampusStageProps {
  level: number;
  /** Ticker above the frame. */
  bubble: React.ReactNode;
  /** Blinking dot in the ticker: green for my campus, blue while spectating. */
  bubbleDot?: string;
  /**
   * Fill the height the parent gives (main game and visit, T2): needs an `@container` ancestor as wide as the frame
   * (GameScreenLayout's main). Without it the frame keeps the fixed 375px of the comparison mockups.
   */
  fill?: boolean;
  /** Prepended to the scene's accessible name, e.g. the visited school's name. */
  sceneLabelPrefix?: string;
  frameRef?: React.Ref<HTMLDivElement>;
  /** Role, label and input handlers of the frame. */
  frameProps?: React.HTMLAttributes<HTMLDivElement>;
  frameClassName?: string;
  /** Overlays inside the frame: corner buttons, effects, notices. */
  children?: React.ReactNode;
}

export function CampusStage({
  level,
  bubble,
  bubbleDot = '#22c55e',
  fill = false,
  sceneLabelPrefix,
  frameRef,
  frameProps,
  frameClassName = '',
  children,
}: CampusStageProps) {
  const stage = CAMPUS_STAGES.find((s) => s.level === level) ?? CAMPUS_STAGES[0];
  return (
    <div className={`flex flex-col items-center w-full relative z-20 ${fill ? 'flex-1 justify-center' : ''}`}>
      {/* Speech-bubble ticker just above the campus: RPG Dialog Style */}
      <div className="w-full flex justify-center mb-1.5 px-2 relative">
        <div className="rpg-speech-bubble px-3 py-1 flex items-center gap-1.5 z-20 shadow-[1px_1px_0px_#261608]">
          <span style={{ backgroundColor: bubbleDot }} className="w-2 h-2 border border-[#261608] inline-block animate-pixel-blink" />
          <p className="font-pixel text-[11px] font-bold text-[#261608] tracking-tight flex items-center gap-1">
            {bubble}
          </p>
        </div>
      </div>

      {/* Center 3/4 View Pixel Campus with Chunky RPG Outline.
          Height: with fill it takes the remaining height (min 220px, max CAMPUS_TALL_ASPECT, T2); otherwise 375px.
          The scene fits its camera to the measured frame (CampusScene useFrameSize). */}
      <div
        ref={frameRef}
        data-campus-stage
        {...frameProps}
        className={`w-full ${fill ? CAMPUS_FILL_HEIGHT : CAMPUS_FIXED_HEIGHT} relative border-4 border-[#261608] bg-[#38a134] cursor-pointer select-none touch-manipulation shadow-[4px_4px_0px_#261608] ${frameClassName}`}
      >
        {/* With fill the scene is taken out of the flow: the svg's viewBox would otherwise give the frame a minimum
            height (its width × aspect) and stop it shrinking. */}
        <CampusScene level={level} labelPrefix={sceneLabelPrefix} className={`${fill ? 'absolute inset-0' : ''} w-full h-full pointer-events-none`} />
        <CampusAmbience level={level} />

        {/* Chunky RPG Building Labels */}
        <div data-testid="campus-level-label" className="game-campus-label game-campus-label-level absolute z-20 top-2.5 left-2.5 bg-[#fdf5df] text-[#261608] border-2 border-[#261608] text-[9px] font-pixel font-bold px-1.5 py-0.5 shadow-[1px_1px_0px_#261608] pointer-events-none flex items-center gap-1">
          <span className="game-campus-label-dot w-1.5 h-1.5 bg-[#facc15] border border-[#261608] inline-block shrink-0" />
          <span className="game-campus-label-text">Lv.{stage.level} · {stage.name}</span>
        </div>
        <div className="game-campus-label game-campus-label-feature absolute z-20 top-2.5 right-2.5 bg-[#fdf5df] text-[#261608] border-2 border-[#261608] text-[9px] font-pixel font-bold px-1.5 py-0.5 shadow-[1px_1px_0px_#261608] pointer-events-none flex items-center gap-1">
          <span className="game-campus-label-dot w-1.5 h-1.5 bg-[#4ade80] border border-[#261608] inline-block shrink-0" />
          <span className="game-campus-label-text">{stage.features[0]}</span>
        </div>

        {children}
      </div>
    </div>
  );
}

/** A frame button must not also count as a tap on the campus, and keeps the page from jumping to it on focus. */
function frameButtonClick(event: React.MouseEvent<HTMLButtonElement>, run?: () => void) {
  event.stopPropagation();
  event.currentTarget.focus({ preventScroll: true });
  soundManager.playTabSound();
  run?.();
}

interface CampusSignButtonProps {
  /** Small pixel icon before the title. */
  icon: React.ReactNode;
  title: string;
  /** Short value under the title (contribution, school score). */
  value: string;
  valueTestId?: string;
  ariaLabel: string;
  tooltip: string;
  testId?: string;
  onOpen?: () => void;
}

/** Wooden sign in the frame's lower-right corner (T5 상세 스탯; 학교 정보 while visiting). Mirrors the guestbook board. */
export function CampusSignButton({ icon, title, value, valueTestId, ariaLabel, tooltip, testId, onOpen }: CampusSignButtonProps) {
  return (
    <button
      type="button"
      onClick={(e) => frameButtonClick(e, onOpen)}
      data-testid={testId}
      className="game-campus-sign-btn absolute bottom-2 right-2.5 z-25 min-w-11 min-h-11 cursor-pointer select-none bg-[#8b5327] text-[#fdf5df] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] px-1.5 py-1 flex flex-col items-center justify-center gap-0.5 hover:brightness-110"
      title={tooltip}
      aria-label={ariaLabel}
    >
      <span className="game-campus-sign-title flex items-center gap-1 font-pixel text-[10px] font-bold leading-none whitespace-nowrap">
        {icon}
        {title}
      </span>
      <span className="game-campus-sign-value-wrap flex items-center gap-1 bg-[#fdf5df] text-[#261608] border border-[#261608] px-1 leading-none">
        <span className="game-campus-sign-dot w-1.5 h-1.5 bg-[#facc15] border border-[#261608] inline-block shrink-0" aria-hidden="true" />
        <span data-testid={valueTestId} className="game-campus-sign-value font-press-start text-[8px] py-0.5 whitespace-nowrap">{value}</span>
      </span>
    </button>
  );
}

interface GuestbookBoardButtonProps {
  ariaLabel: string;
  /** Red count badge next to the label; none while visiting (the mockup has no unread count for other schools). */
  badge?: string;
  onOpen?: () => void;
}

/** Wooden notice board in the frame's lower-left corner that opens a guestbook. */
export function GuestbookBoardButton({ ariaLabel, badge, onOpen }: GuestbookBoardButtonProps) {
  return (
    <button
      type="button"
      onClick={(e) => frameButtonClick(e, onOpen)}
      className="game-campus-guestbook-btn absolute bottom-2 left-2.5 z-25 cursor-pointer group flex flex-col items-center select-none"
      title={ariaLabel}
      aria-label={ariaLabel}
    >
      {/* Above it: Label tag "방명록" and red badge */}
      <div className="game-campus-guestbook-tags flex items-center gap-1 mb-0.5 animate-bounce" style={{ animationDuration: '2.5s' }}>
        <div className="game-campus-guestbook-tag bg-[#fdf5df] text-[#261608] border border-[#261608] text-[8px] font-pixel font-black px-1 py-0.2 shadow-[1px_1px_0px_#261608] flex items-center gap-0.5 whitespace-nowrap">
          <span>방명록</span>
        </div>
        {badge && (
          <div className="game-campus-guestbook-badge bg-[#e11d48] text-white border border-[#261608] text-[7px] font-press-start font-black px-1 py-0.2 shadow-[1px_1px_0px_#261608] whitespace-nowrap">
            {badge}
          </div>
        )}
      </div>

      {/* Wooden Board Object with pinned notes and subtle sparkle */}
      <div className="relative">
        <div className="absolute -top-1.5 -right-1.5 z-10 pointer-events-none animate-pulse">
          <Sparkles className="game-campus-guestbook-sparkle w-3 h-3 text-[#facc15]" />
        </div>

        {/* Wooden frame & cork board */}
        <div className="game-campus-guestbook-board w-10 h-7 bg-[#8b5327] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] p-0.5 flex flex-col justify-between group-hover:brightness-110 transition-all">
          <div className="w-full h-full bg-[#d79b5c] border border-[#5a3012] p-0.5 relative flex items-center justify-around">
            {/* Pinned note 1 */}
            <div className="game-campus-guestbook-note w-3 h-3.5 bg-[#fffdf5] border border-[#261608] shadow-[1px_1px_0px_#261608] -rotate-6 relative">
              <span className="w-1 h-1 bg-[#e11d48] rounded-full absolute -top-0.5 left-1/2 -translate-x-1/2" />
              <div className="w-1.5 h-0.5 bg-[#8b5327] mt-1 mx-auto" />
              <div className="w-1.5 h-0.5 bg-[#8b5327] mt-0.5 mx-auto" />
            </div>
            {/* Pinned note 2 */}
            <div className="game-campus-guestbook-note w-3 h-3.5 bg-[#fef08a] border border-[#261608] shadow-[1px_1px_0px_#261608] rotate-3 relative">
              <span className="w-1 h-1 bg-[#0284c7] rounded-full absolute -top-0.5 left-1/2 -translate-x-1/2" />
              <div className="w-1.5 h-0.5 bg-[#8b5327] mt-1 mx-auto" />
              <div className="w-1.5 h-0.5 bg-[#8b5327] mt-0.5 mx-auto" />
            </div>
          </div>
        </div>

        {/* Wooden stilts / legs standing in the campus ground */}
        <div className="game-campus-guestbook-legs flex justify-between px-1.5 w-full">
          <div className="game-campus-guestbook-leg w-1 h-2 bg-[#613615] border-x border-b border-[#261608]" />
          <div className="game-campus-guestbook-leg w-1 h-2 bg-[#613615] border-x border-b border-[#261608]" />
        </div>
      </div>
    </button>
  );
}
