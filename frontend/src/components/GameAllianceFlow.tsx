import { API_MODE } from '../api/mode';
import type { NavTab } from './BottomNav';
import { AllianceApplyScreenPhone } from './AllianceApplyScreenPhone';
import { ApiAllianceScreenPhone } from './ApiAllianceScreenPhone';
import { BattleApplyScreenPhone } from './BattleApplyScreenPhone';
import { BattleStatusScreenPhone } from './BattleStatusScreenPhone';
import { CurrentAllianceScreenPhone } from './CurrentAllianceScreenPhone';

export type AllianceScreen = 'alliance_apply' | 'current_alliance' | 'battle_apply' | 'battle_status';

interface Props {
  screen: AllianceScreen;
  onScreenChange: (screen: AllianceScreen) => void;
  onBack: () => void;
  onGoToLogin: () => void;
  onNavigateTab?: (tab: NavTab) => void;
  embedded?: boolean;
}

/** Same forms and permission handling in the workspace and the original single-column screens. */
export function GameAllianceFlow({ screen, onScreenChange, onBack, onGoToLogin, onNavigateTab, embedded }: Props) {
  const layout = { embedded, onNavigateTab };
  const goCurrent = () => onScreenChange('current_alliance');
  const goApply = () => onScreenChange('alliance_apply');
  const goBattleApply = () => onScreenChange('battle_apply');
  const goBattleStatus = () => onScreenChange('battle_status');

  if (API_MODE === 'api') return <ApiAllianceScreenPhone {...layout} onBack={onBack} onGoToLogin={onGoToLogin} />;
  switch (screen) {
    case 'alliance_apply': return <AllianceApplyScreenPhone {...layout} onBack={onBack} onNavigateToCurrentAlliance={goCurrent} />;
    case 'battle_apply': return <BattleApplyScreenPhone {...layout} onBack={goCurrent} onNavigateToBattleStatus={goBattleStatus} onNavigateToAllianceApply={goApply} />;
    case 'battle_status': return <BattleStatusScreenPhone {...layout} onBack={goCurrent} onNavigateToBattleApply={goBattleApply} />;
    case 'current_alliance': return <CurrentAllianceScreenPhone {...layout} onBack={onBack} onNavigateToApply={goApply}
      onNavigateToBattleApply={goBattleApply} onNavigateToBattleStatus={goBattleStatus} />;
  }
}
