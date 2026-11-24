import React from 'react';
import { RefreshCw } from 'lucide-react';
import { soundManager } from '../utils/sound';
import { PhoneShell } from './PhoneShell';

export interface LoadNoticeProps {
  status: 'loading' | 'failed';
  /** What is being loaded, shown in the notice (e.g. "순위"). */
  name: string;
  /** The line under the failure title: the mock's demo note or an API error message. */
  failureDetail: string;
  onRetry: () => void;
  /** 'phone' fills a phone frame (full screens); 'panel' sits inside the game screen (tabs). */
  variant?: 'phone' | 'panel';
  onBack?: () => void;
  backLabel?: string;
}

/**
 * Loading and failure notice shared by the mock load gate and API queries (M5-B, SCRUM-147). Retrying is the only
 * way forward from a failure besides going back.
 */
export const LoadNotice: React.FC<LoadNoticeProps> = ({
  status, name, failureDetail, onRetry, variant = 'phone', onBack, backLabel = '내 학교로',
}) => {
  const body = (
    <>
      {status === 'loading' ? (
        <p role="status" className="font-pixel text-sm font-bold text-[#261608] flex items-center gap-1.5">
          <RefreshCw className="w-4 h-4 animate-spin" />
          {name} 불러오는 중…
        </p>
      ) : (
        <>
          <div role="alert" className="rpg-box p-3 bg-[#fffbeb] border-3 border-[#261608] flex flex-col items-center gap-1 text-center">
            <p className="font-pixel text-sm font-black text-[#991b1b]">{name} 조회에 실패했습니다</p>
            <p className="font-pixel text-[10px] text-[#8b5327]">{failureDetail}</p>
          </div>
          <button
            type="button"
            onClick={() => {
              soundManager.playTabSound();
              onRetry();
            }}
            className="pixel-plaque plaque-yellow py-1.5 px-3 font-pixel text-xs font-black text-[#261608] flex items-center gap-1.5 cursor-pointer"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            다시 시도
          </button>
        </>
      )}
      {onBack && (
        <button type="button" onClick={onBack} className="font-pixel text-[10px] text-[#8b5327] underline cursor-pointer">
          {backLabel}
        </button>
      )}
    </>
  );
  return variant === 'phone'
    ? <PhoneShell chrome={false} className="items-center justify-center gap-3 p-6">{body}</PhoneShell>
    : <div className="w-full min-h-[240px] rpg-box p-4 flex flex-col items-center justify-center gap-3 select-none">{body}</div>;
};
