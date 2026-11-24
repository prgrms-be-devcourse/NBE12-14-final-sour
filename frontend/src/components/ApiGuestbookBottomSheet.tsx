import { useEffect, useId, useRef, useState, type SubmitEvent, type ReactNode } from 'react';
import { ChevronDown, Heart } from 'lucide-react';
import { useAuthSession } from '../api/ApiProvider';
import { errorMessage, isApiError } from '../api/errors';
import { mergeGuestbookPages, useCreateGuestbook, useGuestbookPages, useSetGuestbookLike, validateGuestbookContent, type GuestbookItem, type GuestbookLike } from '../api/guestbooks';
import { useMyInfo, type MyInfo } from '../api/users';

interface Props {
  /** Omitted for the signed-in member's own university; never infer that ID from the mock player. */
  universityId?: number;
  schoolName?: string;
  onClose?: () => void;
  onGoToMap?: () => void;
  onGoToLogin?: () => void;
}
const buttonClass = 'pixel-plaque plaque-parchment px-3 min-h-12 font-pixel text-xs disabled:opacity-50';
const dateFormat = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'short', timeStyle: 'short' });
function Frame({ title, onClose, children }: { title: string; onClose?: () => void; children: ReactNode }) {
  return <section className="h-full w-full min-w-0 flex flex-col bg-[#fdf5df] text-[#261608] font-pixel border-t-4 border-[#261608]">
    <header className="flex items-center justify-between gap-2 px-3 py-1 border-b-2 border-[#261608] bg-[#faecc7] shrink-0">
      <div className="min-w-0"><h2 className="font-bold text-sm break-words">{title} 방명록</h2><p className="text-[10px]">다른 학교 학생들이 남긴 응원 · 최신순</p></div>
      {onClose && <button type="button" aria-label="방명록 닫기" className={buttonClass} onClick={onClose}><ChevronDown aria-hidden className="w-4 h-4" /></button>}
    </header>
    {children}
  </section>;
}
export function ApiGuestbookBottomSheet(props: Props) {
  const signedIn = useAuthSession().status === 'signed-in';
  const me = useMyInfo();
  const title = props.universityId === undefined ? me.data?.university.name ?? '우리 학교' : props.schoolName ?? '방문 학교';
  if (signedIn && !me.data) return <Frame title={title} onClose={props.onClose}>
    {me.isError ? <div role="alert" className="p-3 text-xs">{errorMessage(me.error)} <button type="button" className={buttonClass} onClick={() => void me.refetch()}>내 소속 다시 조회</button></div>
      : <p role="status" className="p-3 text-xs">내 소속을 확인하고 있습니다.</p>}
  </Frame>;
  const universityId = props.universityId ?? (signedIn ? me.data?.university.id : undefined);
  if (universityId === undefined) return <Frame title={title} onClose={props.onClose}>
    <p className="p-3 text-xs">우리 학교 방명록을 보려면 로그인해 주세요.</p>
    {props.onGoToLogin && <button type="button" className={buttonClass} onClick={props.onGoToLogin}>로그인</button>}
  </Frame>;
  if (!Number.isSafeInteger(universityId) || universityId <= 0) return <Frame title={title} onClose={props.onClose}><p role="alert" className="p-3 text-xs">학교를 찾을 수 없습니다.</p></Frame>;
  const member = signedIn ? me.data ?? null : null;
  // A new school/member gets a fresh draft, mutation and synchronization boundary, even if the sheet stays mounted.
  return <GuestbookContents key={`${universityId}:${member?.id ?? 'guest'}`} {...props} universityId={universityId} title={title} member={member} />;
}
function GuestbookLikeControl({ item, universityId, viewerId, onChanged }: { item: GuestbookItem; universityId: number; viewerId: number; onChanged: (result: GuestbookLike) => void }) {
  const mutation = useSetGuestbookLike(universityId, viewerId);
  const list = useGuestbookPages(universityId, viewerId);
  const sending = useRef(false);
  const id = useId();
  const uncertain = mutation.isError;
  return <div className="flex flex-col items-end gap-1 max-w-[60%]">
    <button type="button" className={`${buttonClass} flex gap-1 items-center text-[#8b5327]`} aria-label={`방명록 ${item.id} 좋아요`}
      aria-pressed={item.likedByMe} aria-disabled={mutation.isPending || uncertain} aria-describedby={uncertain ? id : undefined}
      onClick={() => {
        if (sending.current || uncertain) return;
        sending.current = true;
        mutation.mutate({ id: item.id, liked: !item.likedByMe }, { onSuccess: onChanged, onSettled: () => { sending.current = false; } });
      }}>
      <Heart aria-hidden className={`w-3 h-3 ${item.likedByMe ? 'fill-rose-600 text-rose-600' : ''}`} />
      좋아요 {item.likeCount}개
    </button>
    {uncertain && <div id={id} role="alert" className="text-xs break-words">
      <p>{errorMessage(mutation.error)} 좋아요 상태를 다시 확인해 주세요. 자동 재전송하지 않습니다.</p>
      <button type="button" className={buttonClass} disabled={list.isFetching} onClick={() => {
        void list.refetch().then((result) => { if (result.isSuccess) mutation.reset(); });
      }}>좋아요 상태 다시 조회</button>
    </div>}
  </div>;
}
function guestbookWriteError(error: unknown) {
  if (isApiError(error)) {
    if (error.code === 'GUEST_TOO_FREQUENT') return '방명록은 학교와 관계없이 1분에 한 번 작성할 수 있어요. 잠시 후 다시 시도해 주세요.';
    if (error.code === 'GUEST_OWN_UNIVERSITY') return '우리 학교에는 방명록을 작성할 수 없어요. 내 소속을 다시 확인해 주세요.';
  }
  return errorMessage(error);
}

function GuestbookContents({ universityId, title, member, onClose, onGoToMap, onGoToLogin }: Props & { universityId: number; title: string; member: MyInfo | null }) {
  const list = useGuestbookPages(universityId, member?.id ?? null);
  const write = useCreateGuestbook(universityId);
  const [draft, setDraft] = useState('');
  const [problem, setProblem] = useState('');
  const [uncertain, setUncertain] = useState(false);
  const [created, setCreated] = useState<GuestbookItem>();
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const posting = useRef(false);
  const id = useId();
  const ownSchool = member?.university.id === universityId;
  const items = mergeGuestbookPages(list.data?.pages ?? [], created);
  useEffect(() => { if (write.isError) inputRef.current?.focus(); }, [write.isError]);

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!member || ownSchool || posting.current || uncertain) return;
    const valid = validateGuestbookContent(draft);
    if ('problem' in valid) { setProblem(valid.problem); inputRef.current?.focus(); return; }
    setProblem(''); posting.current = true;
    write.mutate(valid.content, {
      onSuccess: (result) => { setCreated(result); setDraft(''); if (listRef.current) listRef.current.scrollTop = 0; },
      onError: (error) => { setUncertain(!isApiError(error) || error.kind !== 'http' || error.status === null || error.status >= 500); },
      onSettled: () => { posting.current = false; },
    });
  }
  const writeError = write.isError ? guestbookWriteError(write.error) : '';
  return <Frame title={title} onClose={onClose}>
    <div ref={listRef} className="flex-1 min-h-0 overflow-y-auto overscroll-contain p-3 flex flex-col gap-3 text-xs" aria-busy={list.isFetching}>
      <div className="flex items-center justify-between gap-2"><span>불러온 글 {items.length}개</span>
        <button type="button" className={buttonClass} disabled={list.isFetching} onClick={() => void list.refetch()}>방명록 새로고침</button></div>
      <p className="text-[#8b5327] text-[10px]">좋아요는 로그인 후 이용할 수 있습니다. 좋아요 수와 내 상태는 서버 조회값입니다.</p>
      {list.isPending && <p role="status">방명록을 불러오고 있습니다.</p>}
      {list.isError && <div role="alert">{errorMessage(list.error)}
        {!list.isFetchNextPageError && <button type="button" className={buttonClass} onClick={() => void list.refetch()}>방명록 다시 조회</button>}
      </div>}
      {list.isSuccess && items.length === 0 && <p>아직 남겨진 방명록이 없습니다.</p>}
      <ol className="flex flex-col gap-3" aria-label="방명록 목록">
        {items.map((item) => <li key={item.id} data-testid="api-guestbook-entry" className="bg-[#fffdf5] border-2 border-[#261608] shadow-[2px_2px_0_#261608] p-2 min-w-0">
          <p className="break-words"><strong>{item.author.nickname}</strong> <span className="text-[#8b5327]">({item.author.universityName})</span></p>
          <p data-testid="guestbook-content" className="whitespace-pre-wrap break-words py-2 select-text">{item.content}</p>
          <div className="flex justify-between gap-2 items-center text-[10px]">
            <time dateTime={item.createdAt}>{dateFormat.format(new Date(item.createdAt))} KST</time>
            {member ? <GuestbookLikeControl key={`${item.id}:${member.id}`} item={item} universityId={universityId} viewerId={member.id}
              onChanged={(result) => setCreated((current) => current?.id === result.guestbookId
                ? { ...current, likeCount: result.likeCount, likedByMe: result.liked } : current)} />
              : <span role="img" aria-label={`좋아요 ${item.likeCount}개`} className="flex gap-1 items-center text-[#8b5327]">
                <Heart aria-hidden className="w-3 h-3" />{item.likeCount}
              </span>}
          </div>
        </li>)}
      </ol>
      {list.hasNextPage && <button type="button" className={buttonClass} disabled={list.isFetching} onClick={() => void list.fetchNextPage()}>
        {list.isFetchingNextPage ? '이전 글 불러오는 중…' : '이전 방명록 더 보기'}
      </button>}
      {list.isSuccess && !list.hasNextPage && items.length > 0 && <p>마지막 방명록입니다.</p>}
    </div>
    <div className="shrink-0 max-h-[50%] overflow-y-auto border-t-2 border-[#261608] bg-[#faecc7] p-3 text-xs">
      {!member && <div><p>방명록을 작성하려면 로그인해 주세요.</p>{onGoToLogin && <button type="button" className={buttonClass} onClick={onGoToLogin}>로그인</button>}</div>}
      {ownSchool && <div><p>우리 학교에는 글을 남길 수 없어요. 다른 학교에 응원을 남겨 보세요.</p>{onGoToMap && <button type="button" className={buttonClass} onClick={onGoToMap}>지도로 이동</button>}</div>}
      {member && !ownSchool && <form aria-label="방명록 작성" onSubmit={submit} noValidate className="flex flex-col gap-2">
            <label htmlFor={`${id}-content`}>방명록 내용 (필수 · 500자 이하)</label>
            <textarea ref={inputRef} id={`${id}-content`} name="content" required rows={2} disabled={write.isPending}
              value={draft} aria-invalid={!!problem} aria-describedby={`${id}-help${problem || writeError ? ` ${id}-error` : ''}`}
              className="w-full resize-y min-h-16 max-h-36 border-2 border-[#8b5327] bg-white p-2 text-base" onChange={(event) => {
                setDraft(event.target.value); setProblem(''); if (write.isError && !uncertain) write.reset();
              }} />
            <p id={`${id}-help`} className="text-[10px]">{[...draft.trim()].length}/500자 · 1분에 1개 · 금칙어는 서버에서 ***로 가립니다.</p>
            {problem || writeError ? <p role="alert" id={`${id}-error`}>{problem || writeError}</p> : null}
            {uncertain && <div role="alert"><p>등록 여부를 확인할 수 없습니다. 새로고침으로 목록을 확인한 뒤 다시 보내 주세요. 자동 재전송하지 않습니다.</p>
              <button type="button" className={buttonClass} onClick={() => { setUncertain(false); write.reset(); }}>목록에서 확인했고 다시 보내기 준비</button></div>}
            {write.isSuccess && <p role="status">방명록을 남겼습니다.</p>}
            <button type="submit" className={`${buttonClass} plaque-wood`} disabled={write.isPending || uncertain}>{write.isPending ? '등록 중…' : '방명록 등록'}</button>
          </form>}
    </div>
  </Frame>;
}
