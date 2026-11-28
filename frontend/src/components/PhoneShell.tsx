import React, { createContext, useContext } from 'react';

/**
 * One phone size for every mock screen (T1, docs/261110-1005-mockup_remaining_tasks_plan.md §4):
 * 390×844 logical px, 19.5:9. Screens put their own header, body and footer inside.
 */
export const PHONE_FRAME = { width: 390, height: 844 } as const;

const FRAME_CLASS =
  'w-[390px] max-w-full h-[844px] border-4 border-[#261608] shadow-[8px_8px_0px_#261608] flex flex-col overflow-hidden relative select-none';
/** Full variant (T2, plan §5): fills the visible viewport; height and safe-area padding come from `.phone-shell-full` (src/index.css). */
const FULL_CLASS = 'phone-shell-full w-full flex flex-col overflow-hidden relative select-none';

/**
 * Which shell the screens below render: `framed` (desktop presentation, the default) or `full` (a real phone).
 * App provides `full` for service screens on phones; every shell under it follows without a prop.
 */
export const PhoneShellVariantContext = createContext<'framed' | 'full'>('framed');

export function PhoneStatusBar() {
  return (
    <div data-phone-status-bar className="w-full bg-[#faecc7] border-b-2 border-[#261608] px-3 py-1 flex items-center justify-between text-[10px] font-pixel text-[#261608] shrink-0 z-40">
      <span className="font-bold">09:41</span>
      <div className="w-16 h-2 bg-[#261608] flex items-center justify-center gap-1">
        <span className="w-1 h-1 bg-[#8b5327]" />
        <span className="w-6 h-0.5 bg-[#452409]" />
      </div>
      <div className="flex items-center gap-1 font-mono font-bold">
        <span>LTE</span>
        <div className="w-4 h-2 border border-[#261608] p-0.2 flex">
          <div className="w-full h-full bg-[#22c55e]" />
        </div>
      </div>
    </div>
  );
}

export function PhoneHomeBar() {
  return (
    <div data-phone-home-bar className="w-full bg-[#faecc7] border-t-2 border-[#261608] py-1 flex justify-center shrink-0 z-40">
      <div className="w-20 h-1 bg-[#261608]" />
    </div>
  );
}

interface PhoneShellProps {
  children: React.ReactNode;
  /**
   * Device chrome: the mock status bar (09:41 · LTE) on top and the home bar at the bottom.
   * Loading and not-found frames leave it out.
   */
  chrome?: boolean;
  /** Hide only the simulated top status bar while retaining the framed home bar. */
  statusBar?: boolean;
  /** Let the game workspace size the shell instead of drawing a phone frame. */
  embedded?: boolean;
  /** Screen background behind the content (Tailwind class): parchment by default, wood for the shop, sea for the map. */
  background?: string;
  /** Layout of the frame's column; defaults to the screens' `justify-between`. */
  className?: string;
  /** Names the frame as a landmark region (the onboarding flow). */
  label?: string;
}

export function PhoneShell({ children, chrome = true, statusBar = true, embedded = false, background = 'bg-[#fdf5df]', className = 'justify-between', label }: PhoneShellProps) {
  const phoneVariant = useContext(PhoneShellVariantContext);
  const variant = embedded ? 'embedded' : phoneVariant;
  const sizeClass = embedded ? 'w-full h-full min-h-0 min-w-0 relative flex flex-col' : variant === 'full' ? FULL_CLASS : FRAME_CLASS;
  // A real phone has its own status bar and home indicator, so the full shell leaves out the mock ones.
  const showChrome = chrome && variant === 'framed';
  return (
    <div data-phone-shell={variant} aria-label={label} role={label ? 'region' : undefined}
      className={`${sizeClass} ${background} ${className}`}>
      {showChrome && statusBar && <PhoneStatusBar />}
      {children}
      {showChrome && <PhoneHomeBar />}
    </div>
  );
}
