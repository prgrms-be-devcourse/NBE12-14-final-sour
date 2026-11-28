import React from 'react';
import { MapArt } from './MapArt.tsx';
import { WorkspaceMapPins } from './WorkspaceMapPins';
import { mapFramePercent } from '../utils/workspaceMapPins';
import { MapLayer, SchoolMarker, type MapSceneProps } from './MapParts.tsx';
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
export const Screen2RegionMap: React.FC<MapSceneProps> = ({
  regionSchools, selectedSchoolId, onSelectSchool, embedded: workspace,
}) => {
  const highlighted = new Set(regionSchools.filter(school => school.isTop3 && CHUNGCHEONG_PLACEMENTS[school.id]).slice(0, 3).map(school => school.id));
  const pins = workspace ? regionSchools.flatMap(school => {
    const placement = CHUNGCHEONG_PLACEMENTS[school.id];
    return placement ? [{ school, point: mapFramePercent(placement), highlighted: highlighted.has(school.id) }] : [];
  }) : [];

  return (
      <>
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
                highlighted={highlighted.has(school.id)}
                large={highlighted.has(school.id)}
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
      </>
  );
};
