import type { SchoolId } from '../data/schoolIds.ts';
import React, { useState, useRef } from 'react';
import { ChevronDown, Heart, Send, Sparkles, Pin, AlertCircle } from 'lucide-react';
import { soundManager } from '../utils/sound';
import { SharedImage } from './SharedImage.tsx';
import { getVisitGuestbookSamples, type GuestbookEntry } from '../data/mockGuestbook';

export type { GuestbookEntry } from '../data/mockGuestbook';

interface GuestbookBottomSheetProps {
  onClose?: () => void;
  /** Visited school whose guestbook this is. Entries are shared school-neutral samples until M3. */
  schoolId: SchoolId;
  schoolName: string;
  schoolInitial: string;
  schoolBadgeColor: string;
  mySchoolId: SchoolId;
  mySchoolName: string;
  myNickname: string;
  mySchoolInitial: string;
  mySchoolBadgeColor: string;
}

export const GuestbookBottomSheet: React.FC<GuestbookBottomSheetProps> = ({
  onClose,
  schoolId,
  schoolName,
  schoolInitial,
  schoolBadgeColor,
  mySchoolId,
  mySchoolName,
  myNickname,
  mySchoolInitial,
  mySchoolBadgeColor,
}) => {
  const [sortTab, setSortTab] = useState<'popular' | 'latest'>('popular');
  const [inputText, setInputText] = useState<string>('');
  const listEndRef = useRef<HTMLDivElement>(null);

  // Students of the visited school cannot write here, so their samples are left out.
  const [entries, setEntries] = useState<GuestbookEntry[]>(() => getVisitGuestbookSamples(schoolId));

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

  const handlePostEntry = (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const trimmed = inputText.trim();
    if (!trimmed) return;

    soundManager.playTapSound(true);

    const newEntry: GuestbookEntry = {
      id: `gb-user-${Date.now()}`,
      schoolId: mySchoolId,
      schoolInitial: mySchoolInitial,
      schoolBadgeBg: mySchoolBadgeColor,
      schoolName: mySchoolName,
      nickname: myNickname,
      message: trimmed,
      likeCount: 0,
      relativeTime: '방금',
      likedByMe: false,
    };

    setEntries((prev) => [newEntry, ...prev]);
    setInputText('');
  };

  // Sort entries based on active tab
  const sortedEntries = [...entries].sort((a, b) => {
    if (sortTab === 'popular') {
      return b.likeCount - a.likeCount;
    }
    return 0; // maintain list/chronological order
  });

  return (
    <div className="w-full h-full bg-[#fdf5df] border-t-4 border-[#261608] shadow-[0px_-6px_0px_#261608] flex flex-col justify-between select-none relative overflow-hidden">
      {/* 1. Header: "{school} 방명록", sort tabs "인기순" (active) / "최신순", close "▼" */}
      <div className="w-full bg-[#faecc7] border-b-2 border-[#261608] px-3 py-2 flex items-center justify-between shrink-0 relative">
        {/* SOON Ribbon at top-right corner of header */}
        <div className="absolute -top-1 -right-1 z-30 pointer-events-none">
          <div className="bg-[#e11d48] text-white font-press-start text-[7px] font-black px-2 py-0.5 border-2 border-[#261608] shadow-[1px_1px_0px_#261608] transform rotate-3 flex items-center gap-0.5">
            <Sparkles className="w-2 h-2 text-yellow-300" />
            <span>SOON</span>
          </div>
        </div>

        {/* Left: Title + Sort Tabs */}
        <div className="flex flex-wrap items-center gap-2 min-w-0 flex-1">
          <div className="flex items-center gap-1 shrink-0">
            <span style={{ backgroundColor: schoolBadgeColor }} className="w-5 h-5 border-2 border-[#261608] text-white font-pixel font-black text-[10px] flex items-center justify-center shadow-[1px_1px_0px_#261608]">
              {schoolInitial}
            </span>
            <h2 className="font-pixel text-xs sm:text-sm font-black text-[#261608]">
              {schoolName} 방명록
            </h2>
          </div>

          {/* Sort Tabs: "인기순" (active) / "최신순" */}
          <div className="flex items-center gap-1 bg-[#ecd9b2] p-0.5 border border-[#261608]">
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
          </div>
        </div>

        {/* Right: Close "▼" button */}
        {onClose && <button
          type="button"
          onClick={onClose}
          title="방명록 닫기"
          className="pixel-plaque plaque-parchment p-1 border-2 border-[#261608] flex items-center justify-center cursor-pointer hover:bg-stone-100 active:scale-95 ml-auto shrink-0"
        >
          <ChevronDown className="w-4 h-4 text-[#261608]" />
        </button>}
      </div>

      {/* 2. Scrollable List of Paper-Note Style Entries */}
      <div className="flex-1 min-h-0 overflow-y-auto px-2.5 py-2 flex flex-col gap-2 overscroll-contain">
        {sortedEntries.map((item) => (
          <div
            key={item.id}
            className="w-full bg-[#ffffff] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] p-2 flex flex-col gap-1 relative"
          >
            {/* Top decorative pin */}
            <div className="absolute -top-1.5 left-3 w-3 h-3 bg-[#e11d48] border border-[#261608] rounded-full shadow-[1px_1px_0px_#261608] flex items-center justify-center">
              <span className="w-1 h-1 bg-white rounded-full" />
            </div>

            {/* Entry Header: Badge + Nickname + School + Time */}
            <div className="flex items-center justify-between text-[10px] font-pixel pt-0.5">
              <div className="flex items-center gap-1.5 min-w-0">
                {/* School Badge (first syllable) */}
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
        <div ref={listEndRef} />
      </div>

      {/* 3. Bottom Write Box styled like a parchment letter */}
      <form
        onSubmit={handlePostEntry}
        className="w-full bg-[#faecc7] border-t-3 border-[#261608] p-2.5 flex flex-col gap-1.5 shrink-0 shadow-[0_-2px_4px_rgba(0,0,0,0.05)]"
      >
        {/* Write Box Header / Identity */}
        <div className="flex items-center justify-between text-[10px] font-pixel">
          <div className="flex items-center gap-1">
            <Pin className="w-3 h-3 text-[#8b5327]" />
            <span className="font-bold text-[#8b5327]">
              {mySchoolName} · {myNickname} 으로 남겨요
            </span>
          </div>

          {/* Character Counter */}
          <span className="font-mono text-[9px] text-stone-500">
            {inputText.length}/50
          </span>
        </div>

        {/* Text Area */}
        <div className="relative w-full">
          <textarea
            value={inputText}
            onChange={(e) => setInputText(e.target.value.slice(0, 50))}
            placeholder="한마디 남기기 (최대 50자)"
            maxLength={50}
            rows={2}
            className="w-full bg-[#ffffff] border-2 border-[#261608] p-1.5 font-pixel text-xs text-[#261608] placeholder:text-stone-400 shadow-[inset_1px_1px_0px_#edd9b0] focus:outline-none resize-none"
          />
        </div>

        {/* Note & Submit Button */}
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-1 text-[9px] font-pixel text-stone-500">
            <AlertCircle className="w-2.5 h-2.5 text-stone-400 shrink-0" />
            <span>작성한 방명록은 삭제할 수 없어요</span>
          </div>

          <button
            type="submit"
            disabled={!inputText.trim()}
            className="pixel-plaque plaque-green px-3 py-1 text-xs font-pixel font-black text-white flex items-center gap-1 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed shrink-0"
          >
            <Send className="w-3 h-3" />
            <span>남기기</span>
          </button>
        </div>
      </form>
    </div>
  );
};
