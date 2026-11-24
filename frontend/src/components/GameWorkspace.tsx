import type { ReactNode } from 'react';
import { GameMenuIcon, type GameMenuIconName } from './GameMenuIcon';

interface GameWorkspaceProps {
  wide: boolean;
  left: ReactNode;
  right: ReactNode;
  children: ReactNode;
}

/** The center stays in the same React slot as the viewport changes. Only the side panels come and go. */
export function GameWorkspace({ wide, left, right, children }: GameWorkspaceProps) {
  return (
    <div className={wide ? 'game-workspace game-width' : 'contents'} data-game-workspace={wide ? 'wide' : 'single'}>
      {wide && <aside className="game-workspace-side" aria-label="학교 패널">{left}</aside>}
      <div className={wide ? 'game-workspace-center' : 'contents'}>{children}</div>
      {wide && <aside className="game-workspace-side" aria-label="전국 경쟁 패널">{right}</aside>}
    </div>
  );
}

export function WorkspaceMenuLabel({ icon, children }: { icon: GameMenuIconName; children: ReactNode }) {
  return <><GameMenuIcon name={icon} className="game-nav-icon" /><span className="game-nav-label">{children}</span></>;
}

export function WorkspaceShortcut({ icon, current, children, onClick }: {
  icon: GameMenuIconName; current?: boolean; children: ReactNode; onClick: () => void;
}) {
  return <button type="button" className="game-workspace-action game-nav-control" aria-current={current ? 'page' : undefined} onClick={onClick}>
    <WorkspaceMenuLabel icon={icon}>{children}</WorkspaceMenuLabel>
  </button>;
}
