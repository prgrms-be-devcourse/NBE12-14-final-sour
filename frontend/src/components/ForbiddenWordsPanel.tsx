import { useEffect, useId, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../api/ApiProvider';
import { getForbiddenWords } from '../api/chat';
import { errorMessage } from '../api/errors';

/** Common authenticated list for chat and future guestbook integration. Not a second modal inside the sheet. */
export function ForbiddenWordsPanel({ viewerId, onBack }: { viewerId: number; onBack: () => void }) {
  const client = useApiClient();
  const titleId = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  const words = useQuery({ queryKey: ['forbidden-words', viewerId], queryFn: ({ signal }) => getForbiddenWords(client, signal) });
  useEffect(() => { heading.current?.focus(); }, []);
  return <section aria-labelledby={titleId} className="flex-1 min-h-0 overflow-y-auto p-3 font-pixel text-[#261608]">
    <h2 id={titleId} ref={heading} tabIndex={-1} className="font-bold text-sm">공통 금칙어 목록</h2>
    <p className="text-xs my-2">채팅과 방명록에 공통으로 적용돼요. 포함된 표현은 서버가 **로 가려요.</p>
    <button type="button" onClick={onBack} className="pixel-plaque plaque-parchment min-h-11 px-3 text-xs">채팅으로 돌아가기</button>
    {words.isPending ? <p role="status">금칙어를 불러오는 중이에요.</p> : words.isError ? <div>
      <p role="alert">{errorMessage(words.error)}</p>
      <button type="button" onClick={() => void words.refetch()} disabled={words.isFetching}
        className="pixel-plaque plaque-parchment min-h-11 px-3 text-xs">금칙어 다시 불러오기</button>
    </div> : words.data.length === 0 ? <p role="status">등록된 금칙어가 없어요.</p> :
      <ul className="my-3 space-y-2 text-sm break-words">{words.data.map((word, index) => <li key={`${index}-${word}`}>{word}</li>)}</ul>}
  </section>;
}
