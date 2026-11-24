import React from 'react';
import { MessageSquare, ChevronUp } from 'lucide-react';

interface ChatPreviewBarProps {
  latestSender?: string;
  latestMessage?: string;
  unreadCount?: number;
  onClick?: () => void;
}

export const ChatPreviewBar: React.FC<ChatPreviewBarProps> = ({
  latestSender = '새벽코딩러',
  latestMessage = '도서관 앞 클릭 모임 ㄱㄱ',
  unreadCount = 12,
  onClick,
}) => {
  return (
    <button
      type="button"
      onClick={(event) => {
        event.currentTarget.focus({ preventScroll: true });
        onClick?.();
      }}
      title="채팅창 열기"
      className="w-full bg-[#faecc7] hover:bg-[#fff7e6] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] px-2.5 py-1 flex items-center justify-between gap-2 cursor-pointer transition-all active:translate-y-0.5 select-none shrink-0"
    >
      {/* Left: Speech bubble icon + Latest message */}
      <div className="flex items-center gap-1.5 min-w-0 flex-1">
        <div className="w-5 h-5 bg-[#8b5327] border border-[#261608] flex items-center justify-center shrink-0 shadow-[1px_1px_0px_#261608]">
          <MessageSquare className="w-3 h-3 text-[#fef08a]" />
        </div>
        <div className="flex items-center gap-1 min-w-0 text-[11px] font-pixel truncate">
          <span className="font-bold text-[#8b5327] shrink-0">{latestSender}:</span>
          <span className="text-[#261608] font-bold truncate">{latestMessage}</span>
        </div>
      </div>

      {/* Right: Unread badge + Up arrow */}
      <div className="flex items-center gap-1.5 shrink-0">
        {unreadCount > 0 && (
          <span className="bg-[#e11d48] text-white font-press-start text-[8px] font-black px-1.5 py-0.5 border border-[#261608] shadow-[1px_1px_0px_#261608] animate-pulse">
            {unreadCount}
          </span>
        )}
        <div className="w-4 h-4 bg-[#fdf5df] border border-[#261608] flex items-center justify-center shadow-[1px_1px_0px_#261608]">
          <ChevronUp className="w-3 h-3 text-[#261608]" />
        </div>
      </div>
    </button>
  );
};
