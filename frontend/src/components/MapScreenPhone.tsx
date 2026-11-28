import type { SchoolId } from '../data/schoolIds.ts';
import React, { useRef, useState } from 'react';
import { API_MODE } from '../api/mode';
import { Screen1NationalMap } from './Screen1NationalMap';
import { Screen2RegionMap } from './Screen2RegionMap';
import type { NavTab } from './BottomNav';
import { useMapSchools } from '../hooks/useMapSchools.ts';
import { LoadNotice } from './LoadNotice';
import type { MapRegion } from '../data/schoolMapLayout.ts';
import { filterSchools } from '../data/schoolDirectory';
import { MapFrame, MapSearchHeader, SchoolListSheet, formatWorkspaceSearchStatus } from './MapParts';
import { MapSceneViewport } from './MapSceneViewport';
import { WorkspaceMapSchoolList } from './WorkspaceMapContents';

/** What the map shows. Kept outside the map in the app so a visit can return to it (M2 A2). */
export interface MapContext {
  region: MapRegion;
  query: string;
  selectedSchoolId?: SchoolId;
  regionsExpanded: boolean;
}

export const INITIAL_MAP_CONTEXT: MapContext = { region: '전체', query: '', regionsExpanded: false };

function getSearchPlaceholder(region: MapRegion) {
  if (region === '전체') return '학교 검색 (예: 서울대, KAIST, 부산대)';
  if (region === '충청') return '충청 대학 검색 (예: KAIST, 충남대)';
  return `${region} 대학 검색`;
}

interface MapScreenPhoneProps {
  /** Legacy full map: selecting a marker, row or visit button enters the school immediately. */
  onSelectSchool?: (schoolId: SchoolId) => void;
  /** Workspace selections reveal a row; onVisit runs from its explicit button. */
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
  const [revealMarker, setRevealMarker] = useState(0);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const current = context ?? localContext;
  const { region, query, selectedSchoolId, regionsExpanded } = current;
  const serviceLayout = API_MODE === 'api';
  const embedded = Boolean(panel);
  const update = (patch: Partial<MapContext>) => {
    const next = { ...current, ...patch };
    if (context) onContextChange?.(next);
    else setLocalContext(next);
  };

  const select = (schoolId: SchoolId) => {
    update({ selectedSchoolId: schoolId });
    if (!panel) onSelectSchool?.(schoolId);
  };
  const selectMarker = (schoolId: SchoolId) => {
    select(schoolId);
    if (panel) setRevealMarker(value => value + 1);
  };
  const selectRow = (schoolId: SchoolId) => update({ selectedSchoolId: schoolId === selectedSchoolId ? undefined : schoolId });
  const changeRegion = (next: MapRegion) => update({ region: next });
  const changeQuery = (next: string) => update({ query: next });

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

  const regionSchools = filterSchools(schools, region, '');
  const isSearching = query.trim().length > 0;
  const listed = isSearching ? filterSchools(regionSchools, region, query) : regionSchools;
  const excluded = isSearching && schools.some(school => school.id === selectedSchoolId) && !listed.some(school => school.id === selectedSchoolId);
  const selectionNotice = excluded ? '선택 학교가 검색 결과에 없습니다.' : '';
  const sceneProps = { embedded, regionSchools, selectedSchoolId, onSelectSchool: selectMarker };
  const search = <MapSearchHeader region={region} query={query} placeholder={getSearchPlaceholder(region)}
    onRegionChange={changeRegion} onQueryChange={changeQuery}
    regionPicker={serviceLayout ? { toggleRef, expanded: regionsExpanded, onToggle: () => update({ regionsExpanded: !regionsExpanded }) } : undefined}
    searchStatus={formatWorkspaceSearchStatus(embedded && !serviceLayout, query, listed.length)}
    selectionNotice={panel ? selectionNotice : undefined} />;
  const list = panel
    ? <WorkspaceMapSchoolList schools={listed} selectedSchoolId={selectedSchoolId} query={query} onSelect={selectRow}
        onVisit={panel.onVisit} blocked={panel.visitBlocked} revealMarker={revealMarker}
        serviceLayout={serviceLayout} selectionNotice={selectionNotice} />
    : <SchoolListSheet title={`${region} 대학 ${regionSchools.length}개`} schools={listed} query={query}
        selectedSchoolId={selectedSchoolId} onQueryChange={changeQuery} onSelectSchool={selectMarker} onNavigateTab={onNavigateTab}
        serviceLayout={serviceLayout} />;

  // Keep the old scene-specific shell on comparisons; keep controls mounted in the service.
  const frameKey = serviceLayout ? 'service' : String(region === '충청');
  return <MapFrame key={frameKey} embedded={embedded} search={search} list={list}>
    <MapSceneViewport region={region} animated={serviceLayout}>
      {region === '충청'
        ? <Screen2RegionMap {...sceneProps} />
        : <Screen1NationalMap {...sceneProps} schools={schools} region={region}
            serviceLayout={serviceLayout} onClusterSelect={(next) => { changeRegion(next); toggleRef.current?.focus(); }} />}
    </MapSceneViewport>
  </MapFrame>;
};
