import { useLayoutEffect, useRef, useState } from 'react';
import { useMockGame } from '../hooks/useMockGame.tsx';
import { useMySchool } from '../hooks/useSchoolViews.ts';
import { useImageStore } from '../hooks/useSharedImages.tsx';
import { copyImageTarget, validImageUrl, type ImageTarget } from '../utils/sharedImages.ts';
import { ImageFallback, SharedImage } from './SharedImage.tsx';
import sample from '../assets/images/mock_identity_sample.svg?no-inline';

type ImageChoice = ImageTarget & {
  name: string;
  targetName: string;
};

let nextMockSessionCounter = 0;

export function ImageChangeShop({ onConfirmationChange }: { onConfirmationChange?: (confirming: boolean) => void } = {}) {
  const store = useImageStore();
  const { shopConfig, player } = useMockGame();
  const mySchool = useMySchool();

  const choices: readonly ImageChoice[] = [
    { type: 'school', id: player.schoolId, name: '학교 이미지 변경', targetName: mySchool.name },
    { type: 'department', id: player.departmentId, name: '학과 이미지 변경', targetName: player.department },
    { type: 'profile', id: player.userId, name: '내 프로필 이미지 변경', targetName: player.nickname },
  ];

  const [selected, setSelected] = useState<ImageChoice | null>(null);
  const [url, setUrl] = useState('');
  const [message, setMessage] = useState('');
  const busy = useRef(false);
  const [pending, setPending] = useState(false);
  const sessionRequestIdRef = useRef<string | null>(null);
  const confirming = selected !== null;
  useLayoutEffect(() => {
    if (!confirming) return;
    onConfirmationChange?.(true);
    return () => onConfirmationChange?.(false);
  }, [confirming, onConfirmationChange]);

  const handleSelectChoice = (choice: ImageChoice) => {
    setSelected(choice);
    setMessage('');
    setUrl('');
    sessionRequestIdRef.current = `mock-session-req-${choice.type}-${++nextMockSessionCounter}`;
  };

  const handleCancel = () => {
    setSelected(null);
    setUrl('');
    sessionRequestIdRef.current = null;
    setMessage('취소했습니다. 기존 이미지가 유지됩니다.');
  };

  const finish = async (success: boolean) => {
    if (busy.current || !selected) return;
    busy.current = true;
    setPending(true);
    await Promise.resolve();

    const target = copyImageTarget(selected);
    const requestId = sessionRequestIdRef.current || `mock-session-req-${selected.type}-${++nextMockSessionCounter}`;
    const applied = success && store.apply(player.userId, target, url, requestId);

    if (applied) {
      setMessage('모의 결제 성공 · 이미지가 즉시 적용되었습니다.');
      sessionRequestIdRef.current = null;
      setSelected(null);
      setUrl('');
    } else {
      setMessage('모의 결제 실패 또는 이미지/권한 오류 · 기존 이미지를 유지합니다.');
    }
    busy.current = false;
    setPending(false);
  };

  return <section className="rpg-box p-2 bg-[#fdf5df] font-pixel" aria-label="이미지 변경 상품">
    <p>이미지 변경 · 실제 청구 없는 모의 결제</p>
    <div className="grid grid-cols-1 gap-1">
      {choices.map((choice) => (
        <button
          type="button"
          disabled={pending}
          className="pixel-plaque plaque-parchment p-2 text-left"
          key={choice.type}
          onClick={() => handleSelectChoice(choice)}
        >
          {choice.name} · {shopConfig.imagePricesKrw[choice.type]}원
        </button>
      ))}
    </div>
    {selected && <div role="dialog" aria-label={`${selected.name} 확인`} data-session-request-id={sessionRequestIdRef.current ?? ''} className="p-2 flex flex-col gap-2">
      <p>{selected.type === 'department' ? player.department : selected.type === 'profile' ? player.nickname : selected.targetName} · {shopConfig.imagePricesKrw[selected.type]}원 · 모의 결제 (실제 청구 없음)</p>
      <p>성공 시 변경 대상의 모든 열람 화면에 즉시 적용됩니다.</p>
      <p>현재 이미지</p>
      <SharedImage target={copyImageTarget(selected)} name={selected.targetName} className="w-20 h-20 bg-blue-700 text-white" />
      <label>이미지 URL <input className="w-full border border-black" disabled={pending} value={url} onChange={(event) => setUrl(event.target.value)} /></label>
      <button
        type="button"
        disabled={pending}
        className="pixel-plaque plaque-parchment px-2 py-1 text-xs font-pixel font-bold cursor-pointer hover:bg-[#faecc7] disabled:opacity-50 disabled:cursor-not-allowed self-start"
        onClick={() => setUrl(new URL(sample, window.location.href).href)}
      >
        로컬 샘플 사용
      </button>
      <ImageFallback url={url} name={`${selected.targetName} 미리보기`} className="w-20 h-20 bg-blue-700 text-white" />
      <button
        type="button"
        disabled={pending || !validImageUrl(url.trim())}
        className={`pixel-plaque plaque-yellow px-3 py-2 text-xs font-pixel font-bold text-center ${
          pending || !validImageUrl(url.trim())
            ? 'opacity-50 cursor-not-allowed shadow-none'
            : 'cursor-pointer hover:brightness-105'
        } disabled:opacity-50 disabled:cursor-not-allowed`}
        onClick={() => finish(true)}
      >
        모의 결제 성공 및 즉시 적용
      </button>
      <button
        type="button"
        disabled={pending}
        aria-label="결제 실패 시연"
        className="pixel-plaque plaque-coral px-3 py-1.5 text-xs font-pixel font-bold text-center cursor-pointer hover:brightness-105 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-1"
        onClick={() => finish(false)}
      >
        <span className="text-[10px] text-[#ffe4e6] bg-[#e11d48] px-1 py-0.2 border border-[#261608] font-bold">
          시연
        </span>
        <span>결제 실패 시연</span>
      </button>
      <button
        type="button"
        disabled={pending}
        className="pixel-plaque plaque-parchment px-3 py-1.5 text-xs font-pixel font-bold text-center cursor-pointer hover:bg-[#faecc7] disabled:opacity-50 disabled:cursor-not-allowed"
        onClick={handleCancel}
      >
        취소
      </button>
    </div>}
    {message && <p role="status">{message}</p>}
  </section>;
}
