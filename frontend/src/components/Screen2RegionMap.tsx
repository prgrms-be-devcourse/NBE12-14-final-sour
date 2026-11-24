import React from 'react';
import { ZoomIn } from 'lucide-react';
import { MapArt } from './MapArt.tsx';
import { WorkspaceMapPins } from './WorkspaceMapPins';
import { mapFramePercent } from '../utils/workspaceMapPins';
import { MAP_AREA_HEIGHT, MapLayer, MapFrame, MapSearchHeader, SchoolListSheet, SchoolMarker, formatWorkspaceSearchStatus, type MapControlsProps } from './MapParts.tsx';
import { filterSchools } from '../data/schoolDirectory.ts';
import { CHUNGCHEONG_PLACEMENTS } from '../data/schoolMapLayout.ts';

// Hand-placed city captions on the dedicated 충청 frame.
const SUB_REGION_LABELS = [
  { name: '아산', x: 22, y: 20 },
  { name: '천안', x: 38, y: 14 },
  { name: '청주', x: 64, y: 29 },
  { name: '공주', x: 19, y: 47 },
  { name: '대전', x: 44, y: 57 },
  { name: '충주', x: 70, y: 17 },
  { name: '제천', x: 81, y: 9 },
];

/** Dedicated 충청 screen, kept from the original mockup (decision D2). */
export const Screen2RegionMap: React.FC<MapControlsProps> = ({
  schools,
  query,
  selectedSchoolId,
  onRegionChange,
  onQueryChange,
  onSelectSchool,
  onNavigateTab,
  preview,
  renderPanelList,
}) => {
  const regionSchools = filterSchools(schools, '충청', '');
  const listed = filterSchools(schools, '충청', query);
  const workspace = preview !== undefined;
  const searchStatus = formatWorkspaceSearchStatus(workspace, query, listed.length);
  const pins = workspace ? regionSchools.flatMap(school => {
    const placement = CHUNGCHEONG_PLACEMENTS[school.id];
    return placement ? [{ school, point: mapFramePercent(placement) }] : [];
  }) : [];

  return (
    <MapFrame preview={preview} searching={query.trim().length > 0} list={renderPanelList?.(listed) ?? <SchoolListSheet
      title={`충청 대학 ${regionSchools.length}개`}
      schools={listed} query={query} selectedSchoolId={selectedSchoolId}
      onQueryChange={onQueryChange} onSelectSchool={onSelectSchool} onNavigateTab={onNavigateTab}
    />} search={
      <MapSearchHeader
        region="충청"
        query={query}
        placeholder="충청 대학 검색 (예: KAIST, 충남대)"
        onRegionChange={onRegionChange}
        onQueryChange={onQueryChange}
        searchStatus={searchStatus}
        badge={
          <div className="bg-[#facc15] border-2 border-[#261608] px-2 py-1 flex items-center gap-1 shadow-[1px_1px_0px_#261608] shrink-0">
            <ZoomIn className="w-3 h-3 text-[#261608]" />
            <span className="font-pixel text-[10px] font-black text-[#261608]">충청 확대</span>
          </div>
        }
      />
    }>
      <div className={`${MAP_AREA_HEIGHT} relative overflow-hidden isolate bg-[#38bdf8] select-none`} data-testid="map-area" data-region="충청">
        <MapLayer>
        <div
          className="absolute inset-0 w-full h-full pointer-events-none"
          style={{ transform: 'scale(2.3) translate(-2%, -6%)', transformOrigin: '46% 45%' }}
        >
          <MapArt alt="충청 권역 16비트 도트 확대 지도" />
        </div>

        <div className="absolute inset-0 pointer-events-none z-10">
          {SUB_REGION_LABELS.map((label) => (
            <span
              key={label.name}
              className="absolute font-pixel text-[9px] text-[#261608]/40 font-bold"
              style={{ left: `${label.x}%`, top: `${label.y}%` }}
            >
              [{label.name}]
            </span>
          ))}
        </div>

        <div className="absolute inset-0 z-20 pointer-events-auto">
          {!workspace && regionSchools.map((school) => {
            const placement = CHUNGCHEONG_PLACEMENTS[school.id];
            if (!placement) return null;
            return (
              <SchoolMarker
                key={school.id}
                school={school}
                large={school.isTop3}
                selected={school.id === selectedSchoolId}
                labelOffset={placement.leaderLine}
                onSelect={onSelectSchool}
                style={{ left: `${placement.x}%`, top: `${placement.y}%`, transform: 'translate(-50%, -50%)' }}
              />
            );
          })}
        </div>
        </MapLayer>
        {workspace && <WorkspaceMapPins pins={pins} hint="학교를 눌러 선택하세요" selectedId={selectedSchoolId} onSelect={onSelectSchool} />}
      </div>

    </MapFrame>
  );
};
