import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { soundManager } from '../utils/sound';
import type { GameMenuIconName } from './GameMenuIcon';
import { WorkspaceMenuLabel } from './GameWorkspace';

type TabTone = 'battle';

interface Props<T extends string> {
  label: string;
  tabs: readonly { id: T; label: string; icon?: GameMenuIconName; tone?: TabTone }[];
  selected: T;
  onSelect: (tab: T) => void;
  compact?: boolean;
  /** Keep visited panels mounted when forms and in-flight mutations must survive a switch. */
  keepVisitedMounted?: boolean;
  children: (tab: T, openAndFocus: (tab: T) => void) => ReactNode;
}

function compactTabClass(selected: boolean, tone?: TabTone) {
  const palette = tone === 'battle' ? 'plaque-battle' : selected ? 'plaque-yellow' : 'plaque-wood';
  return `game-compact-tab pixel-plaque ${palette}`;
}

const workspace = { list: 'game-panel-tabs', tab: () => 'game-workspace-action game-nav-control', panel: 'game-panel-content' };
const compactLayout = { list: 'game-compact-tabs', tab: compactTabClass, panel: 'game-compact-tab-panel', 'data-no-touch44': '' };

function nextTabIndex(key: string, index: number, length: number) {
  switch (key) {
    case 'ArrowLeft': return (index + length - 1) % length;
    case 'ArrowRight': return (index + 1) % length;
    case 'Home': return 0;
    case 'End': return length - 1;
    default: return null;
  }
}

/** Manual activation: moving focus must not open a query, start polling, or connect a chat socket. */
export function GamePanelTabs<T extends string>({ label, tabs, selected, onSelect, compact = false, keepVisitedMounted = false, children }: Props<T>) {
  const { list, tab: tabClass, panel, ...listAttributes } = compact ? compactLayout : workspace;
  const id = useId();
  const buttons = useRef<Array<HTMLButtonElement | null>>([]);
  const [focused, setFocused] = useState<T | null>(null);
  const [visited, setVisited] = useState<ReadonlySet<T>>(() => new Set([selected]));
  const select = (tab: T) => {
    if (keepVisitedMounted) setVisited((previous) => previous.has(tab) ? previous : new Set([...previous, tab]));
    onSelect(tab);
  };
  const openAndFocus = (tab: T) => {
    select(tab);
    buttons.current[tabs.findIndex((item) => item.id === tab)]?.focus();
  };
  const navigate = (event: KeyboardEvent, index: number) => {
    const next = nextTabIndex(event.key, index, tabs.length);
    if (next === null) return;
    event.preventDefault();
    buttons.current[next]?.focus();
  };
  return <>
    <div className={list} role="tablist" aria-label={label} {...listAttributes}
      onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) setFocused(null); }}>
      {tabs.map(({ id: tab, label: text, icon, tone }, index) => <button key={tab} ref={(node) => { buttons.current[index] = node; }}
        type="button" role="tab" id={`${id}-${tab}`} aria-controls={`${id}-${tab}-panel`}
        className={tabClass(selected === tab, tone)} aria-selected={selected === tab}
        tabIndex={(focused ?? selected) === tab ? 0 : -1} onFocus={() => setFocused(tab)} onKeyDown={(event) => navigate(event, index)}
        onClick={() => { soundManager.playTabSound(); select(tab); }}>{icon ? <WorkspaceMenuLabel icon={icon}>{text}</WorkspaceMenuLabel> : text}</button>)}
    </div>
    {tabs.map(({ id: tab }) => <div key={tab} id={`${id}-${tab}-panel`} role="tabpanel" aria-labelledby={`${id}-${tab}`} hidden={selected !== tab}
      tabIndex={selected === tab ? 0 : undefined} className={panel}>
      {(selected === tab || (keepVisitedMounted && visited.has(tab))) && children(tab, openAndFocus)}
    </div>)}
  </>;
}
