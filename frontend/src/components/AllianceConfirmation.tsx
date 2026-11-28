import { useEffect, useId, useRef, type ReactNode } from 'react';

export function AllianceConfirmation({ label, children, onCancel, onConfirm }: {
  label: string; children: ReactNode; onCancel: () => void; onConfirm: () => void;
}) {
  const id = useId();
  const ref = useRef<HTMLDialogElement>(null);
  const decided = useRef(false);
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    dialog.showModal();
    return () => { if (dialog.open) dialog.close(); };
  }, []);

  const dismiss = () => { if (!decided.current) onCancel(); };
  return <dialog ref={ref} aria-labelledby={`${id}-title`} aria-describedby={`${id}-details`}
    onClose={(event) => { if (!event.currentTarget.open) dismiss(); }}
    className="item-purchase-dialog rpg-box bg-[#fffbeb] p-4 font-pixel text-[#261608]">
    <h1 id={`${id}-title`} className="text-sm font-black">{label} 확인</h1>
    <div id={`${id}-details`} className="my-3 text-xs">
      {children}
      <p className="mt-2">처리하면 되돌릴 수 없어요.</p>
    </div>
    <div className="flex flex-wrap gap-2">
      <button type="button" autoFocus className="pixel-plaque plaque-wood min-h-11 min-w-11 px-3 text-xs font-bold" onClick={dismiss}>취소</button>
      <button type="button" className="pixel-plaque plaque-yellow min-h-11 min-w-11 px-3 text-xs font-bold" onClick={() => {
        if (decided.current) return;
        decided.current = true;
        onConfirm();
      }}>{label}</button>
    </div>
  </dialog>;
}
