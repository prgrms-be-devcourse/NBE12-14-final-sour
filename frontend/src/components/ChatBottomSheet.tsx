import React, { useState, useRef, useEffect } from 'react';
import { ChevronDown, Send, ShieldAlert, Sparkles, MessageSquare } from 'lucide-react';
import { soundManager } from '../utils/sound';
import { SharedImage } from './SharedImage.tsx';
import { getSchoolChatSamples, getUnionChatSamples, MY_MESSAGE_BADGE, type ChatMessage } from '../data/mockChat.ts';
import { useSchoolViews } from '../hooks/useSchoolViews.ts';
import { useMockGame } from '../hooks/useMockGame.tsx';
import { useAlliance } from '../hooks/useMockAlliance.tsx';

export type { ChatMessage } from '../data/mockChat.ts';

interface ChatBottomSheetProps {
  onClose?: () => void;
  schoolOnly?: boolean;
  showSoonRibbon?: boolean;
  mySchoolInitial: string;
  myNickname: string;
  myDepartment: string;
}

export const ChatBottomSheet: React.FC<ChatBottomSheetProps> = ({
  onClose,
  schoolOnly = false,
  showSoonRibbon = true,
  mySchoolInitial,
  myNickname,
  myDepartment,
}) => {
  const [activeTab, setActiveTab] = useState<'school' | 'union'>('school');
  const [inputText, setInputText] = useState<string>('');
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Samples follow the current ranking and the player's alliance (T44-D1, T44-D2). Chat history is a snapshot
  // taken when the sheet opens, like a loaded message log; new messages are appended locally.
  const schools = useSchoolViews();
  const { player } = useMockGame();
  const { currentAlliance } = useAlliance();
  const sampleInput = { schools, mySchoolId: player.schoolId, myNickname, myDepartment };
  const [schoolMessages, setSchoolMessages] = useState<ChatMessage[]>(() => getSchoolChatSamples(sampleInput));
  const [unionMessages, setUnionMessages] = useState<ChatMessage[] | null>(() => schoolOnly ? null : getUnionChatSamples(sampleInput, currentAlliance?.schoolIds ?? null));
  const noAlliance = activeTab === 'union' && unionMessages === null;

  const currentMessages = activeTab === 'school' ? schoolMessages : unionMessages ?? [];

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [currentMessages.length, activeTab]);

  const handleSendMessage = (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const trimmed = inputText.trim();
    if (!trimmed || noAlliance) return;

    soundManager.playTapSound(true);

    const newMsg: ChatMessage = {
      id: `my-${Date.now()}`,
      schoolInitial: mySchoolInitial,
      schoolBadgeBg: MY_MESSAGE_BADGE,
      nickname: myNickname,
      department: myDepartment,
      text: trimmed,
      time: '방금',
      isMe: true,
    };

    if (activeTab === 'school') {
      setSchoolMessages((prev) => [...prev, newMsg]);
    } else {
      setUnionMessages((prev) => (prev ? [...prev, newMsg] : prev));
    }

    setInputText('');
  };

  return (
    <div className="w-full h-full bg-[#fdf5df] border-t-4 border-[#261608] shadow-[0px_-6px_0px_#261608] flex flex-col justify-between select-none relative overflow-hidden">
      {/* Top Header */}
      <div className="w-full bg-[#faecc7] border-b-2 border-[#261608] px-3 py-2 flex items-center justify-between shrink-0 relative">
        {showSoonRibbon ? <div className="absolute -top-1 -right-1 z-30 pointer-events-none">
          <div className="bg-[#e11d48] text-white font-press-start text-[7px] font-black px-2 py-0.5 border-2 border-[#261608] shadow-[1px_1px_0px_#261608] transform rotate-3 flex items-center gap-0.5">
            <Sparkles className="w-2 h-2 text-yellow-300" />
            <span>SOON</span>
          </div>
        </div> : null}

        {/* Left: Title + Tabs */}
        <div className="flex flex-wrap items-center gap-2 min-w-0 flex-1">
          <div className="flex items-center gap-1 shrink-0">
            <MessageSquare className="w-4 h-4 text-[#8b5327]" />
            <span className="font-pixel text-sm font-black text-[#261608]">{schoolOnly ? '우리 학교 채팅' : '채팅'}</span>
          </div>

          {/* Tab Switcher: "교내 채팅" (active) and "연합 채팅" */}
          {!schoolOnly && <div className="flex items-center gap-1 bg-[#ecd9b2] p-0.5 border border-[#261608]">
            <button
              onClick={() => {
                setActiveTab('school');
                soundManager.playTabSound();
              }}
              className={`pixel-plaque px-2 py-0.5 text-[10px] font-pixel font-bold cursor-pointer transition-transform ${
                activeTab === 'school' ? 'plaque-wood' : 'bg-transparent border-transparent shadow-none text-[#8b5327]'
              }`}
            >
              교내 채팅
            </button>
            <button
              onClick={() => {
                setActiveTab('union');
                soundManager.playTabSound();
              }}
              className={`pixel-plaque px-2 py-0.5 text-[10px] font-pixel font-bold cursor-pointer transition-transform ${
                activeTab === 'union' ? 'plaque-wood' : 'bg-transparent border-transparent shadow-none text-[#8b5327]'
              }`}
            >
              연합 채팅
            </button>
          </div>}
        </div>

        {/* Right: Close "▼" button */}
        {!schoolOnly && <button
          onClick={onClose}
          title="채팅창 닫기"
          className="pixel-plaque plaque-parchment p-1 border-2 border-[#261608] flex items-center justify-center cursor-pointer hover:bg-stone-100 active:scale-95 ml-auto shrink-0"
        >
          <ChevronDown className="w-4 h-4 text-[#261608]" />
        </button>}
      </div>

      {/* Pinned Notice in Retro Dialog Box */}
      <div className="mx-2.5 mt-2 px-2.5 py-1.5 bg-[#f5e6c4] border-2 border-[#261608] shadow-[1px_1px_0px_#261608] flex items-center gap-2 shrink-0">
        <div className="w-4 h-4 bg-[#e11d48] border border-[#261608] flex items-center justify-center text-white shrink-0 shadow-[1px_1px_0px_#261608]">
          <ShieldAlert className="w-2.5 h-2.5" />
        </div>
        <p className="font-pixel text-[10px] text-[#261608] font-bold tracking-tight">
          욕설·비방은 자동으로 가려져요
        </p>
        <span className="text-[9px] font-pixel text-[#8b5327] ml-auto">클린캠퍼스</span>
      </div>

      {/* Message List in Retro RPG Dialog-Box Style */}
      <div className="flex-1 min-h-0 overflow-y-auto px-2.5 py-2 flex flex-col gap-2.5 overscroll-contain">
        {noAlliance && (
          <div data-testid="union-chat-empty" className="m-auto max-w-[85%] text-center bg-[#f5e6c4] border-2 border-dashed border-[#8b5327] px-3 py-2.5 font-pixel text-[11px] font-bold text-[#5a3012] leading-snug">
            아직 소속 연합이 없어요.
            <br />
            연합에 가입하면 연합 학교 학생들과 대화할 수 있어요.
          </div>
        )}
        {currentMessages.map((msg) => {
          if (msg.isFiltered) {
            // Filtered message in gray
            return (
              <div key={msg.id} className="w-full flex justify-center my-0.5">
                <div className="bg-[#e7e5e4] border border-[#a8a29e] px-2.5 py-1 text-[10px] font-pixel text-[#78716c] font-bold flex items-center gap-1 shadow-[1px_1px_0px_#a8a29e]">
                  <span>⚠</span>
                  <span>필터링된 메시지입니다</span>
                  <span className="text-[8px] text-stone-400 font-mono ml-1">{msg.time}</span>
                </div>
              </div>
            );
          }

          if (msg.isMe) {
            // My message: right-aligned, yellow box
            return (
              <div key={msg.id} className="w-full flex flex-col items-end gap-0.5">
                {/* Meta info */}
                <div className="flex items-center gap-1 text-[9px] font-pixel text-[#8b5327]">
                  <span className="font-mono text-stone-400">{msg.time}</span>
                  <span className="text-[#8b5327] font-normal">({msg.department})</span>
                  <span className="font-bold text-[#261608]">{msg.nickname}</span>
                  <div
                    style={{ backgroundColor: msg.schoolBadgeBg }}
                    className="w-4 h-4 border border-[#261608] flex items-center justify-center text-[9px] font-pixel font-black text-[#261608] shadow-[1px_1px_0px_#261608]"
                  >
                    <SharedImage target={{ type: 'profile', id: 'me' }} name={msg.nickname} fallback={msg.schoolInitial} className="w-full h-full" />
                  </div>
                </div>

                {/* Bubble in Yellow Box */}
                <div className="max-w-[82%] bg-[#fef08a] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] px-2.5 py-1.5 text-xs font-pixel font-bold text-[#261608] break-words">
                  {msg.text}
                </div>
              </div>
            );
          }

          // Other users' message: left-aligned, cream box
          return (
            <div key={msg.id} className="w-full flex items-start gap-1.5">
              {/* School Badge Initial */}
              <div
                style={{ backgroundColor: msg.schoolBadgeBg }}
                className="w-5 h-5 border-2 border-[#261608] flex items-center justify-center text-[10px] font-pixel font-black text-white shrink-0 shadow-[1px_1px_0px_#261608] mt-0.5"
              >
                <SharedImage target={{ type: 'profile', id: msg.id }} name={msg.nickname} fallback={msg.schoolInitial} className="w-full h-full" />
              </div>

              {/* Message Content */}
              <div className="flex flex-col items-start gap-0.5 max-w-[82%]">
                <div className="flex items-baseline gap-1.5 flex-wrap">
                  <span className="font-pixel text-[11px] font-bold text-[#261608]">
                    {msg.nickname}
                  </span>
                  <span className="font-pixel text-[9px] text-[#8b5327]">
                    ({msg.department})
                  </span>
                  <span className="font-mono text-[8px] text-stone-400">
                    {msg.time}
                  </span>
                </div>

                {/* Bubble in Cream Box */}
                <div className="w-full bg-[#ffffff] border-2 border-[#261608] shadow-[2px_2px_0px_#261608] px-2.5 py-1.5 text-xs font-pixel font-bold text-[#261608] break-words">
                  {msg.text}
                </div>
              </div>
            </div>
          );
        })}
        <div ref={messagesEndRef} />
      </div>

      {/* Bottom Input Bar */}
      <form
        onSubmit={handleSendMessage}
        className="w-full bg-[#faecc7] border-t-2 border-[#261608] p-2 flex items-center gap-1.5 shrink-0"
      >
        <input
          type="text"
          value={inputText}
          onChange={(e) => setInputText(e.target.value)}
          placeholder={noAlliance ? '연합에 가입하면 쓸 수 있어요' : '메시지 입력 (최대 100자)'}
          disabled={noAlliance}
          aria-label="채팅 메시지"
          maxLength={100}
          className="flex-1 min-w-0 bg-[#ffffff] border-2 border-[#261608] px-2.5 py-1.5 font-pixel text-xs text-[#261608] placeholder:text-stone-400 shadow-[inset_1px_1px_0px_#edd9b0] focus:outline-none"
        />

        <button
          type="submit"
          disabled={noAlliance || !inputText.trim()}
          className="pixel-plaque plaque-yellow px-3 py-1.5 text-xs font-pixel font-black text-[#261608] flex items-center gap-1 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed shrink-0"
        >
          <Send className="w-3 h-3 text-[#261608]" />
          <span>전송</span>
        </button>
      </form>
    </div>
  );
};
