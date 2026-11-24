import { useCallback, useEffect, useRef, useState } from 'react';
import { BottomSheetDialog } from './BottomSheetDialog';
import { useApiClient } from '../api/ApiProvider';
import { verifyClickAbuse } from '../api/abuse';
import { errorMessage } from '../api/errors';
import { useTurnstileWidget } from '../hooks/useTurnstileWidget';
import { TURNSTILE_ACTIONS } from '../utils/turnstile';

/** API-only bot check. The backend, never a widget callback alone, authorizes resuming clicks. */
export function ApiBotCheckDialog({ onVerified, onClose }: { onVerified: () => void; onClose: () => void }) {
  const client = useApiClient();
  const [request, setRequest] = useState<'idle' | 'sending' | 'failed'>('idle');
  const requestRef = useRef(request);
  const [error, setError] = useState<string | null>(null);
  const active = useRef(false);
  const abortRef = useRef<AbortController | null>(null);
  const spentTokens = useRef(new Set<string>());
  const onVerifiedRef = useRef(onVerified);
  useEffect(() => { onVerifiedRef.current = onVerified; }, [onVerified]);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
      abortRef.current?.abort();
    };
  }, []);

  const submit = useCallback((token: string) => {
    if (!active.current || requestRef.current !== 'idle' || (token !== '' && spentTokens.current.has(token))) return;
    if (token !== '') spentTokens.current.add(token);
    requestRef.current = 'sending';
    setRequest('sending');
    setError(null);
    const controller = new AbortController();
    abortRef.current = controller;
    // Empty tokens only mean the script was unavailable. The server still decides whether to allow the request.
    void verifyClickAbuse(client, token, controller.signal).then(() => {
      if (active.current && !controller.signal.aborted) onVerifiedRef.current();
    }, (failure: unknown) => {
      if (!active.current || controller.signal.aborted) return;
      requestRef.current = 'failed';
      setRequest('failed');
      setError(errorMessage(failure));
    });
  }, [client]);
  const widget = useTurnstileWidget(TURNSTILE_ACTIONS.click, submit);

  const retry = () => {
    requestRef.current = 'idle';
    setRequest('idle');
    setError(null);
    // Never replay a redeemed token. Reset only on the user's retry, avoiding automatic verification loops.
    widget.reset();
  };
  const status = request === 'sending'
    ? widget.phase === 'unavailable' ? '확인 위젯을 불러오지 못해 서버에 확인 중…' : '서버에 확인 중…'
    : request === 'failed' ? null
    : widget.phase === 'loading' ? '확인 위젯을 불러오는 중…'
    : widget.widgetError ? '확인 위젯에 문제가 생겼어요. 잠시 후 자동으로 다시 시도합니다.'
    : '아래 확인을 마치면 서버 확인 후 클릭을 재개합니다.';

  return (
    <BottomSheetDialog label="자동 입력 방지 확인" onClose={onClose}>
      <div data-testid="api-bot-check-dialog" aria-busy={request === 'sending'} className="p-4 flex flex-col items-center gap-3 font-pixel text-[#261608]">
        <h2 className="text-sm font-black">자동 입력 방지 확인</h2>
        <p className="text-xs text-center leading-relaxed">클릭이 평소보다 많아 사람인지 확인이 필요해요.</p>
        <div ref={widget.containerRef} className="min-h-[65px] flex items-center justify-center" />
        {status && <p role="status" className="text-xs text-center text-[#8b5327]">{status}</p>}
        {error && (
          <>
            <p role="alert" className="text-xs text-center font-bold text-[#991b1b]">{error}</p>
            <button type="button" onClick={retry} className="pixel-plaque plaque-yellow min-h-12 px-3 py-2 text-xs font-bold cursor-pointer">다시 시도</button>
          </>
        )}
        <p className="text-[11px] text-center text-[#8b5327]">확인하지 않고 닫으면 클릭은 계속 보류됩니다.</p>
      </div>
    </BottomSheetDialog>
  );
}
