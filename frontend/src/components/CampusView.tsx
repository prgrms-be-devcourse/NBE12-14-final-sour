import React, { useState, useEffect, useRef, useCallback } from 'react';
import { CampusStage, CampusSignButton, GuestbookBoardButton } from './CampusStage';
import { useReducedMotion } from './CampusAmbience';
import { useCampusTimePhase } from '../hooks/useCampusTimePhase';
import { useOtherClicks, type OtherClickScheduler } from '../hooks/useMockOtherClicks';
import { useOtherClicksFeed } from '../hooks/otherClicksFeedContext';
import { MOCK_REALTIME_CONFIG, type MockRealtimeConfig, type OtherClicksSource } from '../data/mockRealtime';
import { useMockGame } from '../hooks/useMockGame';
import { useMySchool } from '../hooks/useSchoolViews';
import { FloatingNumber, Sparkle } from '../types';
import { soundManager } from '../utils/sound';
import { INITIAL_KEY_GATE, keyPressDown, keyPressUp, CLICK_KEYS } from '../utils/clickGuard';
import { formatContribution } from '../utils/formatContribution';
import { wobbleBuildings } from '../utils/campusWobble';

interface CampusViewProps {
  onTap: (x: number, y: number, isCrit: boolean, gainedPoints: number) => number | null | void;
  clickPower: number;
  basePower: number;
  activeStudents: number;
  onOpenGuestbook?: () => void;
  showGuestbookBoard?: boolean;
  /** null hides the mock unread count when the service has no unread API. */
  guestbookBadge?: string | null;
  /** Opens the campus stats sheet (T5). Without it the sign still shows, as on the comparison mockups. */
  onOpenStats?: () => void;
  showStatsButton?: boolean;
  /**
   * Fill the height the parent gives (main game, T2): at least 220px, at most the tallest frame the campus
   * scenes are checked at (CAMPUS_TALL_ASPECT, inner 358×392 scaled to the width). Needs an `@container`
   * ancestor as wide as the frame. Without it the frame keeps the fixed 375px of the comparison mockups.
   */
  fill?: boolean;
  /** Label beneath the initial hand prompt ('터치!' on mobile/demo, '클릭!' on wide PC). */
  tapPrompt?: string;
  otherClicksSource?: OtherClicksSource;
  realtimeConfig?: MockRealtimeConfig;
  realtimeScheduler?: OtherClickScheduler;
}

export const CampusView: React.FC<CampusViewProps> = ({
  onTap,
  clickPower,
  basePower,
  activeStudents,
  onOpenGuestbook,
  showGuestbookBoard = true,
  guestbookBadge = 'N 5',
  onOpenStats,
  showStatsButton = true,
  fill = false,
  tapPrompt = '터치!',
  otherClicksSource,
  realtimeConfig,
  realtimeScheduler,
}) => {
  const { campus, clickNotice, state: { contribution: myContribution } } = useMockGame();
  // Keyboard clicks count on a full press and release only; held-key repeats never count (2026-11-08 지시).
  const keyGate = useRef(INITIAL_KEY_GATE);
  // Cheer uses the player's school (M4), e.g. 한양대 → "한양 파이팅!".
  const mySchool = useMySchool();
  const cheer = `${mySchool.shortName.replace(/대$/, '')} 파이팅! 🏛️`;
  const containerRef = useRef<HTMLDivElement>(null);
  const effectSequence = useRef(0);
  const ownClicks = useRef(0);
  const getMyClicksSinceLastTick = useCallback(() => { const count = ownClicks.current; ownClicks.current = 0; return count; }, []);
  // API mode pushes estimates from click responses; without a feed (mock mode) the mock source is polled.
  const otherClicksFeed = useOtherClicksFeed();
  const { particles: otherParticles, otherClicks } = useOtherClicks({ getMyClicksSinceLastTick, config: realtimeConfig, source: otherClicksSource, scheduler: realtimeScheduler, feed: otherClicksFeed });
  const ambience = useCampusTimePhase();
  const reducedMotion = useReducedMotion();
  const [floatingNumbers, setFloatingNumbers] = useState<FloatingNumber[]>([]);
  const [sparkles, setSparkles] = useState<Sparkle[]>([]);
  const mockBubble = otherClicks === null ? '동시 접속 표시는 목업용 샘플입니다.' : `[시연] ${activeStudents}명 접속 · ${ambience.phase === 'night' ? '밤샘 ' : ''}다른 사용자 ${otherClicks}회 클릭 · 점수 미반영`;
  // The API count is a lower bound: own clicks that may be in the server's previous-second bucket are subtracted.
  const apiBubble = otherClicks === null ? '클릭하면 같은 학교 학생들의 직전 1초 클릭이 함께 보입니다.' : `직전 1초 다른 학생 약 ${otherClicks}회 클릭`;
  const bubbleMessage = otherClicksFeed ? apiBubble : mockBubble;
  const [showDemoHand, setShowDemoHand] = useState<boolean>(true);
  const [studentWhisper, setStudentWhisper] = useState<{ text: string; x: number; y: number } | null>({
    text: cheer,
    x: 48,
    y: 35,
  });

  // Whisper speech pool from active fictional schoolmates (no copyrighted mascots)
  const studentWhispers = [
    cheer,
    '도서관 3층 열람실 만석! 📚',
    '시계탑 종소리 좋다 🔔',
    '오늘 학식 꿀맛! 🍱',
    '과제 끝! 광클 시작! 💻',
    '전국 순위 올리자! 🏆',
    '잔디광장 벚꽃 만개 🌸',
    '다같이 클릭 릴레이 가자! ✨',
  ];

  useEffect(() => {
    const whisperInterval = setInterval(() => {
      const randomMsg = studentWhispers[Math.floor(Math.random() * studentWhispers.length)];
      const randomX = Math.floor(Math.random() * 60) + 20;
      const randomY = Math.floor(Math.random() * 50) + 25;
      setStudentWhisper({ text: randomMsg, x: randomX, y: randomY });

      setTimeout(() => {
        setStudentWhisper((prev) => (prev?.text === randomMsg ? null : prev));
      }, 2800);
    }, 6000);

    return () => {
      clearInterval(whisperInterval);
    };
  }, [activeStudents]);

  const handleCampusTap = useCallback(
    (clientX: number, clientY: number) => {
      if (!containerRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      const x = clientX - rect.left;
      const y = clientY - rect.top;

      const isCrit = Math.random() < 0.25;
      const points = isCrit ? clickPower : basePower;
      const result = onTap(x, y, isCrit, Math.round(points));
      if (result === null) {
        // Refused by the mock click check (M5-A): no score, sound or sparkle, only a marker where it landed.
        const numId = `${Date.now()}-${++effectSequence.current}`;
        setFloatingNumbers((prev) => [...prev.slice(-15), { id: numId, x, y: y - 10, text: '거절', isCrit: false, drift: 0 }]);
        return;
      }
      const finalPoints = result ?? Math.round(points);
      ownClicks.current += 1;

      soundManager.playTapSound(isCrit);
      if (typeof navigator !== 'undefined' && navigator.vibrate) {
        navigator.vibrate(isCrit ? 25 : 12);
      }

      // T6 (Q8): the buildings wobble; the click area no longer moves.
      wobbleBuildings(containerRef.current, reducedMotion);

      if (showDemoHand) {
        setShowDemoHand(false);
      }

      const numId = `${Date.now()}-${++effectSequence.current}`;
      const newNumber: FloatingNumber = {
        id: numId,
        x,
        y: y - 10,
        text: `+${finalPoints}${isCrit ? '!' : ''}`,
        isCrit,
        drift: (Math.random() - 0.5) * 36,
      };

      setFloatingNumbers((prev) => [...prev.slice(-15), newNumber]);

      const newSparkles: Sparkle[] = Array.from({ length: isCrit ? 4 : 2 }).map((_, i) => ({
        id: `${numId}-sp-${i}`,
        x: x + (Math.random() - 0.5) * 40,
        y: y + (Math.random() - 0.5) * 40,
        size: Math.random() * 8 + 8,
        color: isCrit ? '#fb7185' : '#38bdf8',
      }));

      setSparkles((prev) => [...prev.slice(-16), ...newSparkles]);
    },
    [basePower, clickPower, onTap, showDemoHand, reducedMotion]
  );

  useEffect(() => {
    if (floatingNumbers.length === 0) return;
    const timer = setTimeout(() => {
      setFloatingNumbers((prev) => prev.filter((item) => Date.now() - parseInt(item.id.split('-')[0]) < 900));
    }, 900);
    return () => clearTimeout(timer);
  }, [floatingNumbers]);

  useEffect(() => {
    if (sparkles.length === 0) return;
    const timer = setTimeout(() => {
      setSparkles((prev) => prev.slice(4));
    }, 700);
    return () => clearTimeout(timer);
  }, [sparkles]);

  // Ambient demo hand if player hasn't tapped yet
  useEffect(() => {
    if (!showDemoHand) return;
    const interval = setInterval(() => {
      if (!containerRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      const demoX = rect.width * 0.52 + (Math.random() - 0.5) * 24;
      const demoY = rect.height * 0.48 + (Math.random() - 0.5) * 24;

      const isCrit = Math.random() < 0.3;
      const points = isCrit ? clickPower : basePower;
      const numId = `${Date.now()}-${++effectSequence.current}`;

      setFloatingNumbers((prev) => [
        ...prev.slice(-8),
        {
          id: numId,
          x: demoX,
          y: demoY,
          text: `시연 +${points}`,
          isCrit,
          drift: (Math.random() - 0.5) * 24,
        },
      ]);
    }, 1200);

    return () => clearInterval(interval);
  }, [showDemoHand, clickPower, basePower]);

  // Frame, ticker, scene and corner labels come from CampusStage, shared with the visit screen (V1).
  return (
    <CampusStage
      level={campus.level}
      bubble={bubbleMessage}
      fill={fill}
      frameRef={containerRef}
      frameProps={{
        role: 'button',
        tabIndex: 0,
        'aria-label': '캠퍼스 클릭 영역',
        ...({ 'data-my-campus': '' } as Record<string, string>),
        onClick: (e) => handleCampusTap(e.clientX, e.clientY),
        onKeyDown: (e) => {
          if (e.target !== e.currentTarget || !CLICK_KEYS.includes(e.key)) return;
          e.preventDefault();
          keyGate.current = keyPressDown(keyGate.current, e.key, e.repeat);
        },
        onKeyUp: (e) => {
          if (e.target !== e.currentTarget || !CLICK_KEYS.includes(e.key)) return;
          e.preventDefault();
          const released = keyPressUp(keyGate.current, e.key);
          keyGate.current = released.gate;
          if (!released.click) return;
          const rect = e.currentTarget.getBoundingClientRect();
          handleCampusTap(rect.left + rect.width / 2, rect.top + rect.height / 2);
        },
        onBlur: () => {
          // Focus left mid-press: the release will not reach us, so the press is dropped.
          keyGate.current = INITIAL_KEY_GATE;
        },
      }}
    >
        {otherParticles.map((particle) => (
          <div key={particle.id} data-testid="other-click-particle" aria-hidden="true"
            className={`absolute z-10 pointer-events-none other-click-particle ${reducedMotion ? '' : 'other-click-animate'}`}
            style={{ left: `${particle.xPct}%`, top: `${particle.yPct}%`, color: ambience.particlePalette === 'sky' ? '#7dd3fc' : '#ffe49a', animationDuration: `${(realtimeConfig ?? MOCK_REALTIME_CONFIG).particleLifetimeMs}ms` }}>
            <span className="other-click-orb" />{particle.weight > 1 && <span className="other-click-weight">+{particle.weight}</span>}
          </div>
        ))}

        {clickNotice && (
          <p
            role="status"
            data-testid="click-rejected-notice"
            className="absolute z-30 inset-x-3 top-10 bg-[#fee2e2] text-[#991b1b] border-2 border-[#991b1b] px-2 py-1 font-pixel text-[10px] font-bold leading-tight text-center shadow-[2px_2px_0px_#261608] pointer-events-none"
          >
            {clickNotice}
          </p>
        )}

        {/* Stats sign (T5, plan §8): mirrors the guestbook board. features[1] moved into the stats sheet (Q2). */}
        {showStatsButton && (
          <CampusSignButton
            testId="campus-stats-button"
            valueTestId="campus-stats-contribution"
            icon={
              <svg viewBox="0 0 12 12" className="w-3 h-3" fill="currentColor" aria-hidden="true">
                <path d="M1 7H3V11H1ZM5 4H7V11H5ZM9 1H11V11H9Z" />
              </svg>
            }
            title="상세 스탯"
            value={formatContribution(myContribution)}
            tooltip={`상세 스탯 보기 · 보유 기여도 ${myContribution.toLocaleString('ko-KR')}점`}
            ariaLabel={`상세 스탯 보기, 보유 기여도 ${myContribution.toLocaleString('ko-KR')}`}
            onOpen={onOpenStats}
          />
        )}

        {/* Wooden Pixel Notice Board Object in Lower-Left Area near Main Path */}
        {showGuestbookBoard && <GuestbookBoardButton ariaLabel="우리 학교 방명록 보기" badge={guestbookBadge ?? undefined} onOpen={onOpenGuestbook} />}

        {/* Student Live Whisper Bubble */}
        {studentWhisper && (
          <div
            className="absolute z-20 bg-[#ffffff] text-[#261608] border-2 border-[#261608] text-[9px] font-pixel font-bold px-1.5 py-0.5 shadow-[2px_2px_0px_#261608] pointer-events-none transform -translate-x-1/2 -translate-y-full"
            style={{
              left: `${studentWhisper.x}%`,
              top: `${studentWhisper.y}%`,
            }}
          >
            {studentWhisper.text}
          </div>
        )}

        {/* Pixel Hand Cursor with Hard Shadows */}
        {showDemoHand && (
          <div
            className="absolute pointer-events-none z-30 animate-hand-tap"
            style={{
              left: '52%',
              top: '46%',
            }}
          >
            <svg
              aria-hidden="true"
              className="w-10 h-10 drop-shadow-[2px_2px_0px_#261608]"
              viewBox="0 0 24 24"
              fill="none"
              xmlns="http://www.w3.org/2000/svg"
            >
              <path
                d="M8 2H11V11H13V7H15V11H17V9H19V17H17V19H13V21H7V19H5V12H7V11H8V2Z"
                fill="#ffffff"
                stroke="#261608"
                strokeWidth="1.8"
              />
              <circle cx="9.5" cy="5.5" r="1" fill="#facc15" />
            </svg>
            <div className="game-campus-hand-label absolute -bottom-4 left-1/2 -translate-x-1/2 bg-[#261608] text-[#fef08a] text-[9px] font-pixel px-1.5 py-0.2 border border-[#fef08a] whitespace-nowrap">
              {tapPrompt}
            </div>
          </div>
        )}

        {/* Floating Numbers (Stepped 16-bit movement) */}
        {floatingNumbers.map((num) => (
          <div
            key={num.id}
            className="absolute pointer-events-none z-30 animate-float-number"
            style={
              {
                left: `${num.x}px`,
                top: `${num.y}px`,
                '--drift': `${num.drift}px`,
              } as React.CSSProperties
            }
          >
            <span
              className={`font-press-start font-black text-sm select-none ${
                num.isCrit
                  ? 'text-[#facc15] drop-shadow-[2px_2px_0px_#261608]'
                  : 'text-[#ffffff] drop-shadow-[2px_2px_0px_#261608]'
              }`}
            >
              {num.text}
            </span>
          </div>
        ))}

        {/* Pixel Sparkle Particles */}
        {sparkles.map((sp) => (
          <div
            key={sp.id}
            className="absolute pointer-events-none z-20 animate-sparkle"
            style={{
              left: `${sp.x}px`,
              top: `${sp.y}px`,
              width: `${sp.size}px`,
              height: `${sp.size}px`,
            }}
          >
            <svg aria-hidden="true" viewBox="0 0 16 16" fill="none" className="w-full h-full">
              <path
                d="M8 0L9.5 6.5L16 8L9.5 9.5L8 16L6.5 9.5L0 8L6.5 6.5L8 0Z"
                fill={sp.color}
                stroke="#261608"
                strokeWidth="1.2"
              />
            </svg>
          </div>
        ))}
    </CampusStage>
  );
};
