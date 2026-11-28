import React from 'react';
import { Hand, ZoomIn } from 'lucide-react';
import { soundManager } from '../utils/sound';
import { MapArt } from './MapArt.tsx';
import { WorkspaceMapPins } from './WorkspaceMapPins';
import { mapFramePercent } from '../utils/workspaceMapPins';
import { MAP_AREA_HEIGHT, MapLayer, MapFrame, MapSearchHeader, SchoolListSheet, SchoolMarker, SchoolBadge, RankRibbon, formatWorkspaceSearchStatus, type MapControlsProps } from './MapParts.tsx';
import { countByRegion, filterSchools, getTopSchools } from '../data/schoolDirectory.ts';
import {
  MAP_CLUSTERS,
  NATIONAL_PLACEMENTS,
  REGION_TO_FILTER,
  SLOT_OFFSETS_PX,
  nationalRibbonPoint,
  projectToViewport,
  regionViewport,
  regionMarkerPx,
  type MapRegion,
  type SchoolFilterRegion,
} from '../data/schoolMapLayout.ts';

interface Screen1NationalMapProps extends MapControlsProps {
  /** '전체' shows region bubbles and the TOP 3; any other region except 충청 zooms the map (decision D2). */
  region: Exclude<MapRegion, '충청'>;
}

export const Screen1NationalMap: React.FC<Screen1NationalMapProps> = ({
  schools,
  region,
  query,
  selectedSchoolId,
  onRegionChange,
  onQueryChange,
  onSelectSchool,
  onNavigateTab,
  embedded = false,
  selectionNotice,
  renderPanelList,
}) => {
  const isNational = region === '전체';
  const hint = isNational ? '지역을 눌러 대학을 확인하세요' : '학교를 눌러 선택하세요';
  const regionSchools = filterSchools(schools, region, '');
  const listed = filterSchools(schools, region, query);
  const counts = countByRegion(schools);
  const mySchool = schools.find((school) => school.isMySchool);
  const viewport = isNational ? undefined : regionViewport(region as SchoolFilterRegion);
  // Choose only drawable candidates, in the same rank/tie order as the list. Search never changes highlights.
  const topSchools = isNational ? getTopSchools(schools.filter((school) => school.isTop3 && nationalRibbonPoint(school.id)), 3) : [];
  const highlighted = new Set((isNational ? topSchools : regionSchools.filter((school) => school.isTop3 && NATIONAL_PLACEMENTS[school.id]).slice(0, 3)).map((school) => school.id));
  const workspace = embedded;
  const searchStatus = formatWorkspaceSearchStatus(workspace, query, listed.length);
  const pins = workspace ? (viewport ? regionSchools : topSchools).flatMap(school => {
    const ribbonPoint = viewport ? undefined : nationalRibbonPoint(school.id);
    const point = viewport ? regionMarkerPx(school.id, viewport) : ribbonPoint && mapFramePercent(ribbonPoint);
    return point ? [{ school, point, highlighted: highlighted.has(school.id) }] : [];
  }) : [];

  return (
    <MapFrame embedded={embedded} searching={query.trim().length > 0} list={renderPanelList?.(listed) ?? <SchoolListSheet
      title={`${isNational ? '전체' : region} 대학 ${regionSchools.length}개`}
      schools={listed} query={query} selectedSchoolId={selectedSchoolId}
      onQueryChange={onQueryChange} onSelectSchool={onSelectSchool} onNavigateTab={onNavigateTab}
    />} search={
      <MapSearchHeader
        region={region}
        query={query}
        placeholder={isNational ? '학교 검색 (예: 서울대, KAIST, 부산대)' : `${region} 대학 검색`}
        onRegionChange={onRegionChange}
        onQueryChange={onQueryChange}
        searchStatus={searchStatus}
        selectionNotice={selectionNotice}
        badge={
          <div className="bg-[#facc15] border-2 border-[#261608] px-2 py-1 flex items-center gap-1 shadow-[1px_1px_0px_#261608] shrink-0">
            {isNational ? <span className="w-2 h-2 bg-[#e11d48] border border-[#261608]" /> : <ZoomIn className="w-3 h-3 text-[#261608]" />}
            <span className="font-pixel text-[10px] font-black text-[#261608]">{isNational ? '전국 지도' : `${region} 확대`}</span>
          </div>
        }
      />
    }>
      <div className={`${MAP_AREA_HEIGHT} relative overflow-hidden isolate bg-[#38bdf8] select-none`} data-testid="map-area" data-region={region}>
        <MapLayer>
        <div
          className="absolute inset-0 w-full h-full pointer-events-none transition-transform duration-300"
          style={
            viewport
              ? { transform: `scale(${viewport.zoom}) translate(${50 - viewport.cx}%, ${50 - viewport.cy}%)`, transformOrigin: '50% 50%' }
              : undefined
          }
        >
          <MapArt alt={isNational ? '대한민국 16비트 도트 지도 배경' : `${region} 권역 확대 지도`} />
        </div>

        <div className="absolute inset-0 z-20 pointer-events-auto">
          {isNational &&
            MAP_CLUSTERS.map((cluster) => {
              const count = counts.get(cluster.region) ?? 0;
              const hasMySchool = mySchool?.region === cluster.region;
              return (
                <button
                  type="button"
                  key={cluster.id}
                  aria-label={`${cluster.region} 대학 ${count}개, ${REGION_TO_FILTER[cluster.region]} 확대`}
                  data-cluster-region={cluster.region}
                  data-no-touch44
                  onClick={() => {
                    soundManager.playTabSound();
                    onRegionChange(REGION_TO_FILTER[cluster.region]);
                  }}
                  className="absolute transform -translate-x-1/2 -translate-y-1/2 cursor-pointer z-30 group hover:scale-105 active:scale-95 transition-transform"
                  style={{ left: `${cluster.x}%`, top: `${cluster.y}%` }}
                >
                  {hasMySchool && (
                    <div className="absolute inset-0 -m-1.5 rounded-full border-2 border-[#facc15] animate-ping opacity-85 pointer-events-none" />
                  )}
                  {hasMySchool && mySchool && (
                    // Above the bubble: to the right it ran under the 강원 bubble and hid the rank (M1 browser check, 2026-11-08).
                    <div className="game-map-own-tag absolute bottom-full mb-1.5 left-1/2 -translate-x-1/2 bg-[#e11d48] text-white border-2 border-[#261608] px-1 py-0.2 shadow-[2px_2px_0px_#261608] whitespace-nowrap flex items-center gap-0.5 z-40 animate-pulse">
                      <span className="w-1.5 h-1.5 bg-[#facc15] border border-[#261608]" />
                      <span className="font-pixel text-[8px] font-black tracking-tight" data-testid="my-school-tag">
                        내 학교 {mySchool.shortName} · {mySchool.rank}위
                      </span>
                    </div>
                  )}
                  <div
                    className={`${cluster.size} rounded-full border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex items-center justify-center font-pixel font-black ${cluster.fontSize} text-center leading-tight transition-all`}
                    style={{ backgroundColor: cluster.bg, color: cluster.textColor }}
                  >
                    <span className="px-0.5">
                      {cluster.region} {count}
                    </span>
                  </div>
                </button>
              );
            })}

          {!workspace && topSchools.map((school) => {
              const point = nationalRibbonPoint(school.id);
              if (!point) return null;
              return (
                <button
                  type="button"
                  key={school.id}
                  aria-label={`${school.name} 전국 ${school.rank}위`}
                  aria-pressed={embedded ? selectedSchoolId === school.id : undefined}
                  data-school-id={school.id}
                  data-top-rank={school.rank}
                  data-no-touch44
                  onClick={() => {
                    soundManager.playTabSound();
                    onSelectSchool(school.id);
                  }}
                  className="absolute transform -translate-x-1/2 -translate-y-1/2 cursor-pointer z-35 group"
                  style={{ left: `${point.x}%`, top: `${point.y}%` }}
                >
                  <RankRibbon school={school} />
                  <SchoolBadge
                    school={school}
                    className="w-7 h-7 border-3 border-[#261608] shadow-[2px_2px_0px_#261608] flex items-center justify-center transition-transform group-hover:scale-105 font-pixel font-black text-white text-xs"
                  />
                  <div className="flex justify-center mt-0.5">
                    <div className="px-1 py-0.2 bg-[#fdf5df] border border-[#261608] text-[8px] font-pixel font-bold text-[#261608] text-center whitespace-nowrap shadow-[1px_1px_0px_#261608]">
                      {school.shortName}
                    </div>
                  </div>
                </button>
              );
            })}

          {!workspace && viewport &&
            regionSchools.map((school) => {
              const placement = NATIONAL_PLACEMENTS[school.id];
              if (!placement) return null;
              const p = projectToViewport(placement.point, viewport);
              const offset = SLOT_OFFSETS_PX[placement.slot] ?? SLOT_OFFSETS_PX[0];
              return (
                <SchoolMarker
                  key={school.id}
                  school={school}
                  highlighted={highlighted.has(school.id)}
                  selected={school.id === selectedSchoolId}
                  onSelect={onSelectSchool}
                  style={{
                    left: `${p.x}%`,
                    top: `${p.y}%`,
                    transform: `translate(calc(-50% + ${offset.x}px), calc(-50% + ${offset.y}px))`,
                  }}
                />
              );
            })}
        </div>
        </MapLayer>
        {workspace ? (
          <WorkspaceMapPins key={region} pins={pins} hint={hint} selectedId={selectedSchoolId} onSelect={onSelectSchool} />
        ) : (
          <div className="absolute bottom-2 left-1/2 -translate-x-1/2 z-25 pointer-events-none">
            <div className="rpg-box px-2.5 py-1 bg-[#fdf5df]/95 border-2 border-[#261608] shadow-[2px_2px_0px_#261608] flex items-center gap-1.5 whitespace-nowrap animate-pulse">
              <Hand className="w-3 h-3 text-[#b45309]" />
              <span className="font-pixel text-[10px] font-black text-[#261608]">{hint}</span>
            </div>
          </div>
        )}
      </div>

    </MapFrame>
  );
};
