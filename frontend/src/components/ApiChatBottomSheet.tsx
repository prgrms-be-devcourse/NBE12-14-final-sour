import { useEffect, useId, useMemo, useRef, useState, useSyncExternalStore, type SubmitEvent } from 'react';
import { ChevronDown, Send, ShieldAlert } from 'lucide-react';
import { useApiClient, useAuthSession } from '../api/ApiProvider';
import { useMyAlliance } from '../api/alliances';
import { allianceRoom, universityRoom, type ChatRoom } from '../api/chat';
import type { ChatConnectionFactory } from '../api/chatConnection';
import { createRoomChat } from '../api/roomChat';
import { errorMessage } from '../api/errors';
import { useMyInfo, type MyInfo } from '../api/users';
import { useChatScroll } from '../hooks/useChatScroll';
import { ForbiddenWordsPanel } from './ForbiddenWordsPanel';

const BUTTON = 'pixel-plaque plaque-parchment min-h-11 min-w-11 px-2 text-xs font-bold cursor-pointer disabled:opacity-50';
const time = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'short', timeStyle: 'short' });
const CONNECTION_TEXT = { connecting: '채팅에 연결하는 중이에요.', connected: '실시간 연결됨', reconnecting: '연결이 끊겨 다시 연결하는 중이에요.', blocked: '자동 연결을 멈췄어요. 오류를 확인하고 다시 연결해 주세요.' };

interface ApiChatProps { onClose?: () => void; onGoToLogin?: () => void; connectionFactory?: ChatConnectionFactory; schoolOnly?: boolean }
/** Only the live API entry mounts this component. Mock comparison screens keep their original sheet. */
export function ApiChatBottomSheet({ onClose, onGoToLogin, connectionFactory, schoolOnly = false }: ApiChatProps) {
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo();
  const [view, setView] = useState<'school' | 'union' | 'words'>('school');
  // The alliance socket opens on the first visit to its tab and then stays, like the school room, until the sheet closes.
  const [unionOpened, setUnionOpened] = useState(false);
  const wordsButton = useRef<HTMLButtonElement>(null);
  return <div className="w-full h-full bg-[#fdf5df] border-t-4 border-[#261608] flex flex-col overflow-hidden font-pixel text-[#261608]">
    <header className="bg-[#faecc7] border-b-2 border-[#261608] p-2 flex flex-wrap items-center gap-1 shrink-0">
      <h2 className="text-sm font-black mr-1">{schoolOnly ? '우리 학교 채팅' : '채팅'}</h2>
      {!schoolOnly && <>
        <button type="button" aria-pressed={view === 'school'} onClick={() => setView('school')} className={BUTTON}>교내 채팅</button>
        <button type="button" aria-pressed={view === 'union'} onClick={() => { setView('union'); setUnionOpened(true); }} className={BUTTON}>연합 채팅</button>
      </>}
      <button ref={wordsButton} type="button" aria-pressed={view === 'words'} onClick={() => setView('words')} className={BUTTON}>금칙어</button>
      {!schoolOnly && <button type="button" aria-label="채팅창 닫기" onClick={onClose} className={`${BUTTON} ml-auto`}><ChevronDown className="w-4 h-4" aria-hidden="true" /></button>}
    </header>
    {!signedIn ? <div role="status" className="p-3 text-sm">
      <p>채팅을 이용하려면 로그인해 주세요.</p>
      {onGoToLogin && <button type="button" className={BUTTON} onClick={onGoToLogin}>로그인</button>}
    </div> : !me.data ? <div className="p-3 text-sm">
      {me.isError ? <><p role="alert">{errorMessage(me.error)}</p><button type="button" className={BUTTON} onClick={() => void me.refetch()}>내 정보 다시 불러오기</button></> : <p role="status">내 학교 정보를 확인하는 중이에요.</p>}
    </div> : <>
      {view === 'words' && <ForbiddenWordsPanel viewerId={me.data.id} onBack={() => { setView('school'); wordsButton.current?.focus(); }} />}
      <div hidden={view !== 'school'} className={view === 'school' ? 'flex flex-col flex-1 min-h-0' : 'hidden'}>
        <ChatRoomPanel key={`${me.data.id}:${me.data.university.id}`} me={me.data} room={universityRoom(me.data.university.id)}
          title={me.data.university.name} label="교내 메시지" visible={view === 'school'} connectionFactory={connectionFactory} />
      </div>
      {unionOpened && <div hidden={view !== 'union'} className={view === 'union' ? 'flex flex-col flex-1 min-h-0' : 'hidden'}>
        <AllianceChat key={me.data.id} me={me.data} visible={view === 'union'} connectionFactory={connectionFactory} />
      </div>}
    </>}
  </div>;
}

interface RoomProps { me: MyInfo; visible: boolean; connectionFactory?: ChatConnectionFactory }

/** The room follows GET /alliances/me: no alliance, no socket. A changed alliance remounts a fresh room. */
function AllianceChat({ me, visible, connectionFactory }: RoomProps) {
  const alliance = useMyAlliance(me.id, { visible });
  const { refetch } = alliance;
  const wasVisible = useRef(visible);
  useEffect(() => {
    if (visible && !wasVisible.current) void refetch({ cancelRefetch: false });
    wasVisible.current = visible;
  }, [visible, refetch]);
  const recheck = <button type="button" className={BUTTON} disabled={alliance.isFetching} onClick={() => void alliance.refetch()}>연합 다시 확인</button>;
  if (alliance.isPending) return <p role="status" className="p-3 text-sm">우리 학교의 연합을 확인하는 중이에요.</p>;
  if (alliance.isError) return <div className="p-3 text-sm"><p role="alert">{errorMessage(alliance.error)}</p>{recheck}</div>;
  if (!alliance.data) return <div className="p-3 text-sm">
    <p role="status">우리 학교가 속한 연합이 없어요. 연합을 만들거나 가입이 승인되면 연합 채팅을 이용할 수 있어요.</p>{recheck}
  </div>;
  const { id, name, expiredAt } = alliance.data;
  return <ChatRoomPanel key={id} me={me} room={allianceRoom(id)} title={`${name} 연합 · ${time.format(new Date(expiredAt))} KST까지`}
    label="연합 메시지" visible={visible} connectionFactory={connectionFactory} />;
}

function ChatRoomPanel({ me, room, title, label, visible, connectionFactory }: RoomProps & { room: ChatRoom; title: string; label: string }) {
  const client = useApiClient();
  const { kind, id: roomId } = room;
  const chat = useMemo(() => createRoomChat(client, { kind, id: roomId }, connectionFactory), [client, kind, roomId, connectionFactory]);
  const state = useSyncExternalStore(chat.subscribe, chat.getSnapshot, chat.getSnapshot);
  useEffect(() => { chat.start(); return chat.stop; }, [chat]);
  const [draft, setDraft] = useState('');
  const input = useRef<HTMLTextAreaElement>(null);
  const id = useId();
  const scroll = useChatScroll(state.messages, visible,
    state.hasOlder && !state.syncing && !state.loadingOlder && !state.historyError, chat.loadOlder);
  const canSend = state.connection === 'connected' && state.initialized && !state.syncing;
  const length = Array.from(draft.trim()).length;
  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (chat.send(draft)) setDraft('');
    else input.current?.focus();
  };
  const latest = state.messages.at(-1);
  return <>
    <div className="m-2 p-2 border-2 border-[#261608] bg-[#f5e6c4] text-[11px] shrink-0">
      <p className="flex gap-1 items-center"><ShieldAlert className="w-4 h-4 shrink-0" aria-hidden="true" />{title} · 욕설·비방은 서버가 가려요.</p>
      <p role="status" data-testid="chat-connection">{state.syncing ? '메시지 이력을 확인하는 중이에요.' : CONNECTION_TEXT[state.connection]}</p>
      <p>끊긴 연결은 최대 30초 간격으로 다시 시도해요. 메시지는 자동 재전송하지 않아요.</p>
      {(state.connection === 'blocked' || state.connection === 'reconnecting') && <button type="button" className={BUTTON} onClick={chat.reconnect}>다시 연결</button>}
    </div>
    {state.error && <p id={`${id}-error`} role="alert" className="px-3 pb-1 text-xs text-[#7f1d1d] break-words">{state.error}</p>}
    <div ref={scroll.list} onScroll={scroll.onScroll} role="log" aria-label={label} aria-live="off"
      data-testid="chat-history" className="flex-1 min-h-0 overflow-y-auto overscroll-contain px-2.5 py-2 [overflow-anchor:none]">
      {state.historyError && <div className="text-xs mb-2">
        <p role="alert">{state.historyError}</p>
        <button type="button" className={BUTTON} disabled={state.syncing} onClick={() => void chat.refresh()}>이력 다시 확인</button>
      </div>}
      {state.hasOlder && <button ref={scroll.sentinel} type="button" className={`${BUTTON} w-full mb-2`}
        disabled={state.loadingOlder || state.syncing || state.connection !== 'connected'} onClick={() => void chat.loadOlder()}>
        {state.loadingOlder ? '이전 메시지를 불러오는 중…' : '이전 메시지 20개 불러오기'}
      </button>}
      {state.initialized && state.messages.length === 0 && <p role="status" className="text-center text-xs my-4">아직 메시지가 없어요. 첫 대화를 시작해 보세요.</p>}
      <ol className="flex flex-col gap-2.5">
        {state.messages.map((message) => <li key={message.messageId} data-message-id={message.messageId} data-testid="chat-message"
          className={`flex flex-col gap-0.5 ${message.userId === me.id ? 'items-end' : 'items-start'}`}>
          <div className="flex flex-wrap gap-1 text-[10px] text-[#8b5327]">
            <span className="font-bold">{message.nickname}</span><span>({message.universityName})</span>
            <time dateTime={message.createdAt}>{time.format(new Date(message.createdAt))} KST</time>
          </div>
          <p className={`max-w-[85%] min-w-0 border-2 border-[#261608] shadow-[2px_2px_0px_#261608] px-2.5 py-1.5 text-xs whitespace-pre-wrap [overflow-wrap:anywhere] ${message.userId === me.id ? 'bg-[#fef08a]' : 'bg-white'}`}>
            {message.content}
          </p>
        </li>)}
      </ol>
    </div>
    {!scroll.atBottom && <button type="button" className={`${BUTTON} self-center`} onClick={scroll.jumpToLatest}>최신 메시지로</button>}
    <p className="sr-only" aria-live="polite" aria-atomic="true">{latest ? `${latest.nickname}: ${latest.content}` : ''}</p>
    <form onSubmit={submit} aria-label="채팅 전송" className="bg-[#faecc7] border-t-2 border-[#261608] p-2 shrink-0">
      <div className="flex items-center gap-1.5">
        <label htmlFor={`${id}-draft`} className="sr-only">채팅 메시지</label>
        <textarea ref={input} id={`${id}-draft`} name="content" rows={1} value={draft} onChange={(event) => setDraft(event.target.value)}
          disabled={!canSend} aria-invalid={length > 200} aria-describedby={`${id}-help${state.error ? ` ${id}-error` : ''}`}
          placeholder="메시지 입력 (최대 200자)" className="flex-1 min-w-0 min-h-11 max-h-24 resize-none border-2 border-[#261608] p-2 text-base bg-white disabled:bg-stone-100"
          onKeyDown={(event) => {
            if (event.key !== 'Enter' || event.shiftKey) return;
            event.preventDefault();
            if (event.nativeEvent.isComposing || event.keyCode === 229) return;
            event.currentTarget.form?.requestSubmit();
          }} />
        <button type="submit" disabled={!canSend || length < 1 || length > 200} className={`${BUTTON} plaque-yellow flex items-center gap-1`}>
          <Send className="w-3 h-3" aria-hidden="true" />전송
        </button>
      </div>
      <p id={`${id}-help`} className="text-[10px] mt-1">{length}/200자 · 1초에 1개 · Enter 전송, Shift+Enter 줄바꿈</p>
    </form>
  </>;
}
