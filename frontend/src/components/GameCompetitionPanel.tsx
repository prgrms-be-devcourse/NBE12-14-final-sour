import { useState } from 'react';
import type { SchoolId } from '../data/schoolIds';
import { GameAllianceFlow, type AllianceScreen } from './GameAllianceFlow';
import { GamePanelTabs } from './GamePanelTabs';
import { MapScreenPhone, type MapContext } from './MapScreenPhone';
import { MockLoadGate } from './MockLoadGate';
import { RankingsView } from './RankingsView';

const tabs = [
  { id: 'map', label: '지도', icon: 'map' },
  { id: 'rankings', label: '순위', icon: 'rankings' },
  { id: 'alliance', label: '연합 대결', icon: 'alliance' },
] as const;
export type CompetitionTab = (typeof tabs)[number]['id'];

interface Props {
  tab: CompetitionTab;
  onTabChange: (tab: CompetitionTab) => void;
  mapContext: MapContext;
  onMapContextChange: (context: MapContext) => void;
  onVisit: (schoolId: SchoolId) => void;
  visitBlocked?: string;
  schoolScore: number;
  onGoToLogin: () => void;
}

export function GameCompetitionPanel({ tab, onTabChange, mapContext, onMapContextChange, onVisit, visitBlocked, schoolScore, onGoToLogin }: Props) {
  const [allianceScreen, setAllianceScreen] = useState<AllianceScreen>('current_alliance');

  return <>
    <h2>전국 경쟁</h2>
    <GamePanelTabs label="전국 경쟁 메뉴" tabs={tabs} selected={tab} onSelect={onTabChange}>
      {(active, openAndFocus) => {
        switch (active) {
          case 'map': return <MockLoadGate name="학교 지도" variant="panel">
            <MapScreenPhone context={mapContext} onContextChange={onMapContextChange} panel={{ onVisit, visitBlocked }} />
          </MockLoadGate>;
          case 'rankings': return <MockLoadGate name="순위" variant="panel">
            <RankingsView currentSchoolScore={schoolScore} desktopPanel />
          </MockLoadGate>;
          case 'alliance': return <GameAllianceFlow embedded screen={allianceScreen} onScreenChange={setAllianceScreen}
            onBack={() => openAndFocus('map')} onGoToLogin={onGoToLogin} />;
        }
      }}
    </GamePanelTabs>
  </>;
}
