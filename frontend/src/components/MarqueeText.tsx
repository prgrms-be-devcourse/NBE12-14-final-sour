import React, { useLayoutEffect, useRef, useState } from 'react';

/** 양 끝 페이드 폭(px). 왕복 시작·끝에서 글자가 페이드에 가려지지 않도록 같은 폭의 여백을 둔다. */
export const MARQUEE_FADE_PX = 10;
/** 이동 속도(px/s). 읽을 수 있도록 느리게 둔다. */
export const MARQUEE_SPEED_PX_PER_S = 18;
/** 한쪽 끝에서 멈춰 있는 비율(키프레임 기준). index.css의 marquee-pingpong과 맞춘다. */
const HOLD_RATIO = 0.3;
/** 한 방향 최소 시간(s). 조금만 넘쳐도 양 끝에서 약 1.5초(5 × 0.3)씩 멈추게 한다. */
export const MARQUEE_MIN_DURATION_S = 5;

/** 넘치는 거리(px)로 한 방향 이동 애니메이션 시간을 계산한다. 넘치지 않으면 0. */
export function getMarqueeDurationSeconds(distancePx: number): number {
  if (distancePx <= 0) return 0;
  return Math.max(MARQUEE_MIN_DURATION_S, distancePx / MARQUEE_SPEED_PX_PER_S / (1 - HOLD_RATIO));
}

interface MarqueeTextProps {
  text: string;
  className?: string;
}

/**
 * 한 줄 텍스트. 칸보다 길 때만 양 끝을 투명하게 흐리고 좌우로 느리게 왕복해 전체를 보여 준다.
 * 모션 줄이기 설정에서는 왕복 대신 말줄임으로 표시하고 title로 전체 이름을 제공한다.
 */
export const MarqueeText: React.FC<MarqueeTextProps> = ({ text, className = '' }) => {
  const boxRef = useRef<HTMLSpanElement>(null);
  const labelRef = useRef<HTMLSpanElement>(null);
  const [distance, setDistance] = useState(0);

  useLayoutEffect(() => {
    const box = boxRef.current;
    const label = labelRef.current;
    if (!box || !label) return;
    const measure = () => {
      const labelWidth = label.offsetWidth;
      const boxWidth = box.clientWidth;
      setDistance(labelWidth > boxWidth ? Math.ceil(labelWidth + MARQUEE_FADE_PX * 2 - boxWidth) : 0);
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    // 웹폰트 로딩·화면 폭 변화로 크기가 바뀌면 다시 잰다.
    const observer = new ResizeObserver(measure);
    observer.observe(box);
    observer.observe(label);
    return () => observer.disconnect();
  }, [text]);

  const overflowing = distance > 0;
  const style = overflowing
    ? ({
        '--marquee-distance': `-${distance}px`,
        '--marquee-duration': `${getMarqueeDurationSeconds(distance).toFixed(2)}s`,
        '--marquee-fade': `${MARQUEE_FADE_PX}px`,
      } as React.CSSProperties)
    : undefined;

  return (
    <span
      ref={boxRef}
      className={`marquee-box ${className}`}
      data-overflowing={overflowing ? 'true' : 'false'}
      title={overflowing ? text : undefined}
      style={style}
    >
      <span className="marquee-track">
        <span ref={labelRef} className="marquee-label">
          {text}
        </span>
      </span>
    </span>
  );
};
