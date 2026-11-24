import type { SchoolId } from '../data/schoolIds.ts';
import React, { useState } from 'react';
import { Screen1NationalMap } from './Screen1NationalMap';
import { Screen2RegionMap } from './Screen2RegionMap';
import type { NavTab } from './BottomNav';
import { useMapSchools } from '../hooks/useMapSchools.ts';
import { LoadNotice } from './LoadNotice';
import type { MapRegion } from '../data/schoolMapLayout.ts';
import { filterSchools, type SchoolView } from '../data/schoolDirectory';
import { WorkspaceMapPreview, WorkspaceMapSchoolList } from './WorkspaceMapContents';

/** What the map shows. Kept outside the map in the app so a visit can return to it (M2 A2). */
export interface MapContext {
  region: MapRegion;
  query: string;
  selectedSchoolId?: SchoolId;
}

export const INITIAL_MAP_CONTEXT: MapContext = { region: '전체', query: '' };

interface MapScreenPhoneProps {
  /** Legacy full map: selecting a marker, row or visit button enters the school immediately. */
  onSelectSchool?: (schoolId: SchoolId) => void;
  /** Workspace map: selections only update the preview; onVisit runs from its explicit button. */
  panel?: { onVisit: (schoolId: SchoolId) => void; visitBlocked?: string };
  onNavigateTab?: (tab: NavTab) => void;
  /** Controlled context. Without it the phone keeps its own state (comparison demos). */
  context?: MapContext;
  onContextChange?: (context: MapContext) => void;
  initialRegion?: MapRegion;
}

/**
 * Map phone shared by the single map and the comparison demo.
 * Plans: docs/261108-1930-m1_school_map_data_plan.md 4-2, docs/261108-2000-m2_map_visit_return_plan.md 4-1.
 */
export const MapScreenPhone: React.FC<MapScreenPhoneProps> = ({
  onSelectSchool,
  panel,
  onNavigateTab = () => {},
  context,
  onContextChange,
  initialRegion = '전체',
}) => {
  // Mock rows or the server's ranks on the school directory, by mode (SCRUM-162).
  const map = useMapSchools();
  const schools = map.schools;
  const [localContext, setLocalContext] = useState<MapContext>({ ...INITIAL_MAP_CONTEXT, region: initialRegion });
  const current = context ?? localContext;
  const update = (patch: Partial<MapContext>) => {
    const next = { ...current, ...patch };
    if (context) onContextChange?.(next);
    else setLocalContext(next);
  };

  const select = (schoolId: SchoolId) => {
    update({ selectedSchoolId: schoolId });
    if (!panel) onSelectSchool?.(schoolId);
  };
  const selectedSchool = schools.find((school) => school.id === current.selectedSchoolId);
  const isSearching = current.query.trim().length > 0;
  const listed = isSearching ? filterSchools(schools, current.region, current.query) : schools;
  const selectedExcludedFromSearch = Boolean(
    selectedSchool && isSearching && !listed.some((school) => school.id === selectedSchool.id),
  );
  const controls = {
    schools,
    query: current.query,
    selectedSchoolId: current.selectedSchoolId,
    onRegionChange: (region: MapRegion) => update({ region }),
    onQueryChange: (query: string) => update({ query }),
    onSelectSchool: select,
    onNavigateTab,
    preview: panel && <WorkspaceMapPreview school={selectedSchool}
      onVisit={panel.onVisit} blocked={panel.visitBlocked} excludedFromSearch={selectedExcludedFromSearch} />,
    renderPanelList: panel && ((panelSchools: readonly SchoolView[]) => <WorkspaceMapSchoolList schools={panelSchools}
      selectedSchoolId={current.selectedSchoolId} query={current.query} onSelect={select} />),
  };

  if (map.status !== 'ready') {
    return (
      <LoadNotice
        status={map.status}
        name="학교 지도"
        failureDetail={map.failureDetail}
        onRetry={map.retry}
        variant={panel ? 'panel' : 'phone'}
        onBack={panel ? undefined : () => onNavigateTab('campus')}
      />
    );
  }

  return current.region === '충청' ? <Screen2RegionMap {...controls} /> : <Screen1NationalMap {...controls} region={current.region} />;
};
