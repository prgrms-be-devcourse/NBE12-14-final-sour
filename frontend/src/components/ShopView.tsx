import type { Ref } from 'react';
import { BottomNav, type NavTab } from './BottomNav';
import { GameScreenLayout } from './GameScreenLayout';
import { WorkspaceMenuLabel } from './GameWorkspace';
import { ShopScreenPhone } from './ShopScreenPhone';

interface Props {
  embedded: boolean;
  mainRef: Ref<HTMLElement>;
  onBackToCampus: () => void;
  onNavigateTab: (tab: NavTab) => void;
  onConfirmationChange: (confirming: boolean) => void;
}

/** Keep the same shelf and its confirmation mounted across wide / compact changes. */
export function ShopView({ embedded, mainRef, onBackToCampus, onNavigateTab, onConfirmationChange }: Props) {
  return (
    <GameScreenLayout kind="shop" embedded={embedded} mainRef={mainRef}
      header={<button type="button" onClick={onBackToCampus} className={`game-workspace-action w-full font-pixel${embedded ? ' game-nav-control' : ''}`}>
        {embedded ? <WorkspaceMenuLabel icon="back">캠퍼스로 돌아가기</WorkspaceMenuLabel> : '캠퍼스로 돌아가기'}
      </button>}
      nav={!embedded && <BottomNav activeTab="shop" onChangeTab={onNavigateTab} hasShopUpgrade={false} hasRankChange={false} />}>
      <ShopScreenPhone embedded onNavigateTab={onNavigateTab} onConfirmationChange={onConfirmationChange} />
    </GameScreenLayout>
  );
}
