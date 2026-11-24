import { useCallback, useEffect, useRef, useState, type SubmitEvent } from 'react';
import { ChevronLeft } from 'lucide-react';
import { PhoneShell } from './PhoneShell';
import { useGuestSupportSender, type GuestSupportSender } from '../hooks/useGuestSupportSender';
import { useTurnstileWidget } from '../hooks/useTurnstileWidget';
import { TURNSTILE_ACTIONS } from '../utils/turnstile';
import { soundManager } from '../utils/sound';
import { MOCK_TOOLS } from '../utils/mockTools';
import {
  normalizeSupportForm,
  SUPPORT_FORM_LIMITS,
  SUPPORT_MESSAGES,
  validateSupportForm,
  type SupportForm,
} from '../utils/mockSupport';

// Guest inquiry form (SCRUM-122, plan .agents/01-PLAN/261117-0848-turnstile_bot_verification_plan.md §3-4).
// POST /api/v1/supports. From the third inquiry in an hour the server answers 403 ABUSE_VERIFICATION_REQUIRED; the
// form then shows a widget (action support_guest) and sends the same request again with `turnstileToken`.
// Sending is by mode (SCRUM-164, hooks/useGuestSupportSender.ts): the mock server (request layer + utils/mockSupport.ts)
// or the API. A refused token (403 ABUSE_VERIFICATION_FAILED) keeps the check open; "다시 시도" gets a new token.
// Attachments are left out: the server takes them from members only.

const EMPTY_FORM: SupportForm = { recipientEmail: '', title: '', content: '' };
const INPUT_CLASS = 'w-full bg-[#ffffff] border-2 border-[#261608] px-2.5 py-1.5 text-xs text-[#261608] placeholder:text-stone-400 focus:outline-none focus:bg-[#fef9c3] shadow-[inset_1px_1px_0px_#261608] disabled:bg-stone-100 disabled:text-stone-500';

/** `retry`: the request can be sent again (no answer, or a refused token: the check asks for a new one). */
type Notice = { kind: 'success' | 'error' | 'retry'; text: string };

export function SupportGuestScreenPhone({ onBack = () => {} }: { onBack?: () => void }) {
  return <SupportGuestView sender={useGuestSupportSender()} onBack={onBack} />;
}

/** The form with a given sender, so tests can drive the API sender in mock-mode builds. */
export function SupportGuestView({ sender, onBack = () => {} }: { sender: GuestSupportSender; onBack?: () => void }) {
  const { send: sendRequest, demo } = sender;
  const [form, setForm] = useState<SupportForm>(EMPTY_FORM);
  const [sending, setSending] = useState(false);
  const [checkOpen, setCheckOpen] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  /** The request the server asked to confirm; the widget's token is attached to this one. */
  const pendingRef = useRef<SupportForm | null>(null);
  const cancelRef = useRef<(() => void) | null>(null);
  useEffect(() => () => cancelRef.current?.(), []);

  /** `token` undefined sends no `turnstileToken` field. */
  const send = useCallback((request: SupportForm, token?: string) => {
    setSending(true);
    setNotice(null);
    cancelRef.current = sendRequest(request, token, (result) => {
      cancelRef.current = null;
      setSending(false);
      if (result.kind === 'failed' || result.kind === 'verification-failed') {
        // The pending request stays; with the check open, "다시 시도" resets the widget for a new token.
        setNotice({ kind: 'retry', text: result.text });
        return;
      }
      if (result.kind === 'verification-required') {
        pendingRef.current = request;
        setCheckOpen(true);
        return;
      }
      pendingRef.current = null;
      setCheckOpen(false);
      if (result.kind === 'too-many') {
        setNotice({ kind: 'error', text: result.text });
        return;
      }
      setForm((current) => ({ ...current, title: '', content: '' }));
      setNotice({ kind: 'success', text: SUPPORT_MESSAGES.accepted });
    });
  }, [sendRequest]);

  const sendWithToken = useCallback((token: string) => {
    if (pendingRef.current) send(pendingRef.current, token);
  }, [send]);

  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (sending || checkOpen) return;
    const problem = validateSupportForm(form);
    if (problem) {
      setNotice({ kind: 'error', text: problem });
      return;
    }
    soundManager.playTabSound();
    send(normalizeSupportForm(form));
  };

  const cancelCheck = () => {
    pendingRef.current = null;
    setCheckOpen(false);
    setNotice(null);
  };

  const resetDemo = () => {
    cancelRef.current?.();
    cancelRef.current = null;
    demo?.reset();
    setSending(false);
    cancelCheck();
  };

  const locked = sending || checkOpen;
  const update = (key: keyof SupportForm) => (event: { target: { value: string } }) => setForm((current) => ({ ...current, [key]: event.target.value }));

  return (
    <PhoneShell>
      <div className="w-full bg-[#faecc7] border-b-2 border-[#261608] px-3 py-2 flex items-center gap-2 shrink-0 z-30">
        <button
          type="button"
          onClick={() => { soundManager.playTabSound(); onBack(); }}
          aria-label="뒤로가기"
          className="pixel-plaque plaque-wood px-1.5 py-1 text-xs font-pixel font-bold flex items-center gap-0.5 cursor-pointer"
        >
          <ChevronLeft className="w-3.5 h-3.5" />
          <span>뒤로</span>
        </button>
        <h1 className="font-pixel text-sm font-black text-[#261608]">비회원 문의</h1>
      </div>

      <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-3 font-pixel text-[#261608]">
        <form onSubmit={submit} noValidate className="rpg-box p-3 bg-[#fdf5df] border-2 border-[#261608] flex flex-col gap-2.5 shadow-[2px_2px_0px_#261608]">
          <p className="text-[10px] text-[#8b5327] leading-tight">답변은 받을 이메일로 보내 드려요. 첨부 파일은 회원만 보낼 수 있어요.</p>
          <label className="text-[10px] font-bold flex flex-col gap-1">
            받을 이메일
            <input type="email" value={form.recipientEmail} onChange={update('recipientEmail')} disabled={locked}
              maxLength={SUPPORT_FORM_LIMITS.recipientEmail} placeholder="guest@example.com" autoComplete="email" className={`${INPUT_CLASS} font-mono`} />
          </label>
          <label className="text-[10px] font-bold flex flex-col gap-1">
            제목
            <input type="text" value={form.title} onChange={update('title')} disabled={locked}
              maxLength={SUPPORT_FORM_LIMITS.title} className={INPUT_CLASS} />
          </label>
          <label className="text-[10px] font-bold flex flex-col gap-1">
            본문
            <textarea value={form.content} onChange={update('content')} disabled={locked} rows={6}
              maxLength={SUPPORT_FORM_LIMITS.content} className={`${INPUT_CLASS} resize-none`} />
          </label>
          <p className="text-[9px] text-right text-[#8b5327]">{form.content.length.toLocaleString()} / 5,000</p>
          <button type="submit" disabled={locked}
            className="pixel-plaque plaque-yellow px-3 py-1.5 text-xs font-bold cursor-pointer disabled:opacity-60 disabled:cursor-default">
            보내기
          </button>
          {sending && !checkOpen && <p role="status" className="text-[10px] text-center text-[#8b5327]">보내는 중…</p>}
        </form>

        {checkOpen && (
          <SupportBotCheck
            onToken={sendWithToken}
            failed={notice?.kind === 'retry'}
            sending={sending}
            onCancel={cancelCheck}
          />
        )}

        {notice?.kind === 'success' && (
          <p data-testid="support-result" role="status" className="rpg-box p-2.5 bg-[#dcfce7] border-2 border-[#15803d] text-[11px] font-bold text-[#14532d]">{notice.text}</p>
        )}
        {notice && notice.kind !== 'success' && (
          <p data-testid="support-error" role="alert" className="rpg-box p-2.5 bg-[#fee2e2] border-2 border-[#991b1b] text-[11px] font-bold text-[#7f1d1d]">{notice.text}</p>
        )}

        {MOCK_TOOLS && demo && <div className="border-2 border-dashed border-[#8b5327] bg-[#fffbeb] p-2 flex flex-wrap items-center gap-2 text-[10px] text-[#8b5327]">
          <span data-testid="support-sent-count" className="font-bold">[시연] 보낸 문의 수: {demo.sentCount}</span>
          <button type="button" onClick={resetDemo} className="pixel-plaque plaque-parchment px-2 py-0.5 text-[10px] font-bold cursor-pointer">[시연] 보낸 문의 수 초기화</button>
          <p className="w-full leading-tight">[시연] 서버는 IP마다 1시간 동안 셉니다. 3건째부터 확인, 6건째부터 거절합니다. 목업은 이 화면에서 보낸 수로 대신합니다.</p>
        </div>}
      </div>
    </PhoneShell>
  );
}

/** The widget below the form. Mounted while the server waits for a token, so a new mount means a new widget. */
function SupportBotCheck({ onToken, failed, sending, onCancel }: {
  onToken: (token: string) => void;
  failed: boolean;
  sending: boolean;
  onCancel: () => void;
}) {
  const widget = useTurnstileWidget(TURNSTILE_ACTIONS.supportGuest, onToken);
  const status = sending ? (widget.phase === 'unavailable' ? '확인 위젯을 불러오지 못해 위젯 없이 보내는 중…' : '보내는 중…')
    : failed ? null
    : widget.phase === 'loading' ? '확인 위젯을 불러오는 중…'
    : widget.widgetError ? '확인 위젯에 문제가 생겼어요. 잠시 후 자동으로 다시 시도합니다.'
    : null;

  return (
    <section data-testid="support-bot-check" aria-label="자동 입력 방지 확인"
      className="rpg-box p-3 bg-[#fffbeb] border-2 border-[#b45309] flex flex-col items-center gap-2 text-center">
      <h2 className="text-xs font-black">자동 입력 방지 확인</h2>
      <p className="text-[11px] leading-relaxed">{SUPPORT_MESSAGES.verificationRequired}</p>
      <div ref={widget.containerRef} className="min-h-[65px] flex items-center justify-center" />
      {status && <p role="status" className="text-[11px] text-[#8b5327]">{status}</p>}
      <div className="flex gap-2">
        {failed && (
          <button type="button" onClick={widget.reset} className="pixel-plaque plaque-yellow px-3 py-1 text-xs font-bold cursor-pointer">다시 시도</button>
        )}
        <button type="button" onClick={onCancel} disabled={sending}
          className="pixel-plaque plaque-parchment px-3 py-1 text-xs font-bold cursor-pointer disabled:opacity-60">확인 취소</button>
      </div>
      {MOCK_TOOLS && <p className="text-[10px] text-[#8b5327]">[시연] 위젯 키를 설정하지 않으면 Cloudflare 테스트 키(항상 통과)를 씁니다.</p>}
    </section>
  );
}
