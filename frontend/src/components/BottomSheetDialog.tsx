import { useEffect, useRef, type ReactNode } from 'react';
import { lockPageScroll } from '../utils/lockPageScroll';

interface BottomSheetDialogProps {
  label: string;
  onClose: () => void;
  children: ReactNode;
}

/** Service-flow modal only. Static two-phone comparison sheets remain inline.
 * showModal supplies the top layer, inert background and native keyboard containment.
 */
export function BottomSheetDialog({ label, onClose, children }: BottomSheetDialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const dialog = ref.current!;
    const trigger = document.activeElement;
    const releaseScroll = lockPageScroll(dialog.ownerDocument);
    try {
      dialog.showModal();
      closeRef.current?.focus({ preventScroll: true });
    } catch (error) {
      releaseScroll();
      throw error;
    }
    return () => {
      dialog.close();
      releaseScroll();
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus({ preventScroll: true });
    };
  }, []);

  return (
    <dialog
      ref={ref}
      aria-label={label}
      aria-modal="true"
      className="bottom-sheet-dialog"
      onCancel={(event) => { event.preventDefault(); onClose(); }}
      onClick={(event) => {
        // A native ::backdrop click targets the dialog. Don't dismiss clicks inside its bounds.
        if (event.target !== event.currentTarget) return;
        const rect = event.currentTarget.getBoundingClientRect();
        if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) onClose();
      }}
    >
      <button ref={closeRef} type="button" onClick={onClose}
        className="w-full min-h-11 shrink-0 bg-[#261608] text-[#fef08a] font-pixel text-[10px] cursor-pointer">
        ▼ 터치하여 닫기
      </button>
      <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain">{children}</div>
    </dialog>
  );
}
