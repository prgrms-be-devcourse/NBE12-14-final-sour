import type { SchoolId } from '../data/schoolIds.ts';
import React, { useState } from 'react';
import { ChevronDown, Heart, Sparkles, Info } from 'lucide-react';
import { soundManager } from '../utils/sound';
import { SharedImage } from './SharedImage.tsx';
import { getOurSchoolGuestbookSamples, type GuestbookEntry } from '../data/mockGuestbook.ts';

interface OurSchoolGuestbookBottomSheetProps {
  onClose?: () => void;
  onGoToMap?: () => void;
  showSoonRibbon?: boolean;
  /** The player's school (M4); its own students' notes are left out. */
  mySchoolId: SchoolId;
  schoolName: string;
}

export const OurSchoolGuestbookBottomSheet: React.FC<OurSchoolGuestbookBottomSheetProps> = ({
  onClose,
  onGoToMap = () => {},
  showSoonRibbon = true,
  mySchoolId,
  schoolName,
}) => {
  const [sortTab, setSortTab] = useState<'latest' | 'popular'>('latest');
  const [entries, setEntries] = useState<GuestbookEntry[]>(() => getOurSchoolGuestbookSamples(mySchoolId));

  const toggleLike = (id: string) => {
    soundManager.playTapSound(true);
    setEntries((prev) =>
      prev.map((item) => {
        if (item.id === id) {
          const isLiked = !item.likedByMe;
          return {
            ...item,
            likedByMe: isLiked,
            likeCount: item.likeCount + (isLiked ? 1 : -1),
          };
        }
        return item;
      })
    );
  };

  const sortedEntries = [...entries].sort((a, b) => {
    if (sortTab === 'popular') {
      return b.likeCount - a.likeCount;
    }
    return 0; // maintain latest chronological order
  });

  return (
    <div className="w-full h-full bg-[#fdf5df] border-t-4 border-[#261608] shadow-[0px_-6px_0px_#261608] flex flex-col justify-between select-none relative overflow-hidden">
      {/* 1. Header: "{school} 방명록", subtitle, sort tabs "최신순" / "인기순", total count, close "▼" */}
      <div className="w-full bg-[#faecc7] border-b-2 border-[#261608] px-3 py-2 flex flex-col gap-1.5 shrink-0 relative">
        {showSoonRibbon ? <div className="absolute -top-1 -right-1 z-30 pointer-events-none">
          <div className="bg-[#e11d48] text-white font-press-start text-[7px] font-black px-2 py-0.5 border-2 border-[#261608] shadow-[1px_1px_0px_#261608] transform rotate-3 flex items-center gap-0.5">
            <Sparkles className="w-2 h-2 text-yellow-300" />
            <span>SOON</span>
          </div>
        </div> : null}

        {/* Row 1: Badge + Title + Close Button */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="w-5 h-5 bg-[#0284c7] border-2 border-[#261608] text-white font-pixel font-black text-[10px] flex items-center justify-center shadow-[1px_1px_0px_#261608]">
              한
            </span>
            <div>
              <h2 className="font-pixel text-xs sm:text-sm font-black text-[#261608]">
                {schoolName} 방명록
              </h2>
              <p className="font-pixel text-[9px] text-[#8b5327] leading-none mt-0.5">
                다른 학교 학생들이 남긴 응원이에요
              </p>
            </div>
          </div>

          {onClose && <button
            type="button"
            onClick={onClose}
            title="방명록 닫기"
            className="pixel-plaque plaque-parchment p-1 border-2 border-[#261608] flex items-center justify-center cursor-pointer hover:bg-stone-100 active:scale-95 shrink-0"
          >
            <ChevronDown className="w-4 h-4 text-[#261608]" />
          </button>}
        </div>

        {/* Row 2: Sort Tabs ("최신순" active / "인기순") + Total Count */}
        <div className="flex items-center justify-between pt-1 border-t border-[#eeddb6]">
          {/* Sort Tabs */}
          <div className="flex items-center gap-1 bg-[#ecd9b2] p-0.5 border border-[#261608]">
            <button
              type="button"
              onClick={() => {
                setSortTab('latest');
                soundManager.playTabSound();
              }}
              className={`pixel-plaque px-2 py-0.5 text-[10px] font-pixel font-bold cursor-pointer transition-transform ${
                sortTab === 'latest' ? 'plaque-wood' : 'bg-transparent border-transparent shadow-none text-[#8b5327]'
              }`}
            >
              최신순
            </button>
            <button
              type="button"
              onClick={() => {
                setSortTab('popular');
                soundManager.playTabSound();
              }}
              className={`pixel-plaque px-2 py-0.5 text-[10px] font-pixel font-bold cursor-pointer transition-transform ${
                sortTab === 'popular' ? 'plaque-wood' : 'bg-transparent border-transparent shadow-none text-[#8b5327]'
              }`}
            >
              인기순
            </button>
          </div>

          {/* Total Count */}
          <span className="font-pixel text-[10px] font-bold text-[#8b5327] bg-[#fdf5df] px-2 py-0.5 border border-[#261608] shadow-[1px_1px_0px_#261608]">
            방명록 <span data-testid="our-guestbook-count" className="font-press-start text-[8px] text-[#261608]">{entries.length}</span>개
          </span>
        </div>
      </div>

      {/* 2. Scrollable List of Paper-Note Style Entries Pinned to Wooden Board */}
      <div className="flex-1 min-h-0 overflow-y-auto px-2.5 py-2 flex flex-col gap-2 bg-[#8b5327]/10 overscroll-contain">
        {sortedEntries.map((item) => (
          <div
            key={item.id}
            data-testid="our-guestbook-entry"
            className="w-full bg-[#fffdf5] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] p-2 flex flex-col gap-1 relative"
          >
            {/* Top decorative pushpin */}
            <div className="absolute -top-1.5 left-3 w-3 h-3 bg-[#e11d48] border border-[#261608] rounded-full shadow-[1px_1px_0px_#261608] flex items-center justify-center">
              <span className="w-1 h-1 bg-white rounded-full" />
            </div>

            {/* Entry Header: Badge + Nickname + School + Time + NEW tag */}
            <div className="flex items-center justify-between text-[10px] font-pixel pt-0.5">
              <div className="flex items-center gap-1.5 min-w-0">
                {/* School Badge (first syllable on solid color square) */}
                <span
                  style={{ backgroundColor: item.schoolBadgeBg }}
                  className="w-4 h-4 border border-[#261608] text-white font-pixel font-bold text-[9px] flex items-center justify-center shrink-0 shadow-[1px_1px_0px_#261608]"
                >
                  <SharedImage target={{ type: 'profile', id: item.id }} name={item.nickname} fallback={item.schoolInitial} className="w-full h-full" />
                </span>

                <span className="font-bold text-[#261608] truncate">
                  {item.nickname}
                </span>
                <span className="text-[9px] text-[#8b5327] truncate">
                  ({item.schoolName})
                </span>

                {/* Yellow NEW tag */}
                {item.isNew && (
                  <span className="bg-[#facc15] text-[#261608] font-press-start text-[7px] font-black px-1 border border-[#261608] shadow-[1px_1px_0px_#261608] shrink-0">
                    NEW
                  </span>
                )}
              </div>

              <span className="text-[9px] text-stone-400 font-pixel shrink-0">
                {item.relativeTime}
              </span>
            </div>

            {/* Message Body */}
            <p className="font-pixel text-xs text-[#261608] font-bold leading-snug pl-1 pr-1 break-words">
              {item.message}
            </p>

            {/* Footer with Like Button */}
            <div className="flex items-center justify-end gap-1 pt-0.5 border-t border-stone-200">
              <button
                type="button"
                onClick={() => toggleLike(item.id)}
                className={`flex items-center gap-1 px-1.5 py-0.5 border border-[#261608] text-[9px] font-pixel font-bold cursor-pointer transition-transform active:scale-95 ${
                  item.likedByMe
                    ? 'bg-[#fee2e2] text-[#e11d48]'
                    : 'bg-[#fdf5df] text-[#8b5327] hover:bg-amber-100'
                }`}
              >
                <Heart
                  className={`w-3 h-3 ${
                    item.likedByMe ? 'fill-[#e11d48] text-[#e11d48]' : 'text-[#8b5327]'
                  }`}
                />
                <span className="font-mono font-bold">{item.likeCount}</span>
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* 3. Bottom Muted Dialog Box (No write box) */}
      <div className="w-full bg-[#faecc7] border-t-3 border-[#261608] p-2.5 flex items-center justify-between gap-2 shrink-0 shadow-[0_-2px_4px_rgba(0,0,0,0.05)]">
        <div className="flex items-center gap-1.5 min-w-0">
          <div className="w-4 h-4 bg-[#8b5327] border border-[#261608] flex items-center justify-center text-[#fef08a] shrink-0">
            <Info className="w-2.5 h-2.5" />
          </div>
          <p className="font-pixel text-[10px] text-[#8b5327] font-bold leading-tight truncate">
            방명록은 다른 학교를 방문했을 때 남길 수 있어요
          </p>
        </div>

        {/* Link-style button: "지도에서 학교 찾기 >" */}
        <button
          type="button"
          onClick={() => {
            soundManager.playTabSound();
            onGoToMap();
          }}
          className="pixel-plaque plaque-yellow px-2 py-1 text-[10px] font-pixel font-black text-[#261608] whitespace-nowrap cursor-pointer shrink-0 shadow-[1px_1px_0px_#261608]"
        >
          <span>지도에서 학교 찾기 &gt;</span>
        </button>
      </div>
    </div>
  );
};
