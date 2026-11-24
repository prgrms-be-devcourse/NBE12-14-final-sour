import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { ChatMessage } from '../api/chat';

/** Preserve a visible message, not just scrollHeight: live appends can arrive while older HTTP pages are pending. */
export function useChatScroll(messages: readonly ChatMessage[], visible: boolean, canLoadOlder: boolean, loadOlder: () => Promise<void>) {
  const list = useRef<HTMLDivElement>(null);
  const sentinel = useRef<HTMLButtonElement>(null);
  const bottom = useRef(true);
  const anchor = useRef<{ id: string; offset: number } | null>(null);
  const [atBottom, setAtBottom] = useState(true);
  const remember = () => {
    const node = list.current;
    if (!node) return;
    const top = node.getBoundingClientRect().top;
    const first = [...node.querySelectorAll<HTMLElement>('[data-message-id]')].find((item) => item.getBoundingClientRect().bottom > top);
    anchor.current = first ? { id: first.dataset.messageId!, offset: first.getBoundingClientRect().top - top } : null;
  };
  const onScroll = () => {
    const node = list.current;
    if (!node) return;
    bottom.current = node.scrollHeight - node.scrollTop - node.clientHeight < 32;
    setAtBottom(bottom.current);
    remember();
  };
  const jumpToLatest = () => {
    if (list.current) list.current.scrollTop = list.current.scrollHeight;
    bottom.current = true;
    setAtBottom(true);
    remember();
  };
  useLayoutEffect(() => {
    const node = list.current;
    if (!node || !visible) return;
    if (bottom.current) node.scrollTop = node.scrollHeight;
    else if (anchor.current) {
      const saved = anchor.current;
      const item = node.querySelector<HTMLElement>(`[data-message-id="${saved.id}"]`);
      if (item) node.scrollTop += item.getBoundingClientRect().top - node.getBoundingClientRect().top - saved.offset;
    }
    remember();
  }, [messages, visible]);
  useEffect(() => {
    if (!visible || !canLoadOlder || !list.current || !sentinel.current || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadOlder();
    }, { root: list.current });
    observer.observe(sentinel.current);
    return () => observer.disconnect();
  }, [visible, canLoadOlder, loadOlder]);
  return { list, sentinel, onScroll, atBottom, jumpToLatest };
}
