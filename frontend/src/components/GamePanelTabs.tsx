import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { soundManager } from '../utils/sound';
import type { GameMenuIconName } from './GameMenuIcon';
import { WorkspaceMenuLabel } from './GameWorkspace';

interface Props<T extends string> {
  label: string;
  tabs: readonly { id: T; label: string; icon: GameMenuIconName }[];
  selected: T;
  onSelect: (tab: T) => void;
  children: (tab: T, openAndFocus: (tab: T) => void) => ReactNode;
}

/** Manual activation: moving focus must not open a query, start polling, or connect a chat socket. */
export function GamePanelTabs<T extends string>({ label, tabs, selected, onSelect, children }: Props<T>) {
  const id = useId();
  const buttons = useRef<Array<HTMLButtonElement | null>>([]);
  const [focused, setFocused] = useState<T | null>(null);
  const openAndFocus = (tab: T) => {
    onSelect(tab);
    buttons.current[tabs.findIndex((item) => item.id === tab)]?.focus();
  };
  const navigate = (event: KeyboardEvent, index: number) => {
    let next: number;
    switch (event.key) {
      case 'ArrowLeft': next = (index + tabs.length - 1) % tabs.length; break;
      case 'ArrowRight': next = (index + 1) % tabs.length; break;
      case 'Home': next = 0; break;
      case 'End': next = tabs.length - 1; break;
      default: return;
    }
    event.preventDefault();
    buttons.current[next]?.focus();
  };
  return <>
    <div className="game-panel-tabs" role="tablist" aria-label={label}
      onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) setFocused(null); }}>
      {tabs.map(({ id: tab, label: text, icon }, index) => <button key={tab} ref={(node) => { buttons.current[index] = node; }}
        type="button" role="tab" id={`${id}-${tab}`} aria-controls={`${id}-${tab}-panel`} className="game-workspace-action game-nav-control" aria-selected={selected === tab}
        tabIndex={(focused ?? selected) === tab ? 0 : -1} onFocus={() => setFocused(tab)} onKeyDown={(event) => navigate(event, index)}
        onClick={() => { soundManager.playTabSound(); onSelect(tab); }}><WorkspaceMenuLabel icon={icon}>{text}</WorkspaceMenuLabel></button>)}
    </div>
    {tabs.map(({ id: tab }) => <div key={tab} id={`${id}-${tab}-panel`} role="tabpanel" aria-labelledby={`${id}-${tab}`} hidden={selected !== tab}
      tabIndex={selected === tab ? 0 : undefined} className="game-panel-content">
      {selected === tab && children(tab, openAndFocus)}
    </div>)}
  </>;
}
