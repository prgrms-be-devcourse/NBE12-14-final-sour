// Shared chrome for the national and 충청 map phones: status bar, search + region chips, school list sheet
// and school markers. Plan: docs/261108-1930-m1_school_map_data_plan.md 4-2, 4-3.
import type { SchoolId } from '../data/schoolIds.ts';
import React, { useLayoutEffect, useRef, useState } from 'react';
import { Search, Crown, X, ChevronUp, ChevronDown } from 'lucide-react';
import { soundManager } from '../utils/sound';
import { BottomNav, type NavTab } from './BottomNav';
import { PhoneShell } from './PhoneShell';
import { ImageFallback, SharedImage } from './SharedImage.tsx';
import { SCHOOL_LOGO_ASSETS } from '../data/schoolLogos.ts';
import { MAP_DESIGN_FRAME_PX, MAP_REGIONS, mapLayerFit, type MapRegion } from '../data/schoolMapLayout.ts';
import type { SchoolView } from '../data/schoolDirectory.ts';

/**
 * Map area height (T2, user decision 2026-11-10): fills the space the header and list sheet leave, kept between the
 * tallest and widest aspects in MAP_AREAS_PX (466/382 to 466/336), where the marker checks prove nothing is cropped.
 * `100cqw` is the width of MapScrollBody, the container.
 */
export const MAP_AREA_HEIGHT = 'flex-1 min-h-[calc(100cqw*466/382)] max-h-[calc(100cqw*466/336)]';

/**
 * Search header and map. On a phone too short for both (full-screen mode), this column scrolls instead of the map
 * cropping regions; the list sheet with the bottom nav stays below it.
 */
export function MapScrollBody({ children }: { children: React.ReactNode }) {
  return (
    <div data-testid="map-scroll-body" className="@container flex-1 min-h-0 flex flex-col overflow-y-auto overscroll-contain scrollbar-none">
      {children}
    </div>
  );
}

/**
 * Holds the map image and everything placed on it at the design frame size, scaled to cover the map area (its
 * parent). Image, markers and their spacing then scale together at any area size (M1-D2, D3).
 */
export function MapLayer({ children }: { children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);
  useLayoutEffect(() => {
    const area = ref.current?.parentElement;
    if (!area || typeof ResizeObserver === 'undefined') return;
    const update = () => {
      if (area.clientWidth > 0 && area.clientHeight > 0) setScale(mapLayerFit({ width: area.clientWidth, height: area.clientHeight }).scale);
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(area);
    return () => observer.disconnect();
  }, []);
  return (
    <div
      ref={ref}
      data-map-layer
      className="absolute left-1/2 top-1/2"
      style={{ width: MAP_DESIGN_FRAME_PX.width, height: MAP_DESIGN_FRAME_PX.height, transform: `translate(-50%, -50%) scale(${scale})` }}
    >
      {children}
    </div>
  );
}

export interface MapControlsProps {
  schools: readonly SchoolView[];
  query: string;
  selectedSchoolId?: SchoolId;
  onRegionChange: (region: MapRegion) => void;
  onQueryChange: (query: string) => void;
  onSelectSchool: (schoolId: SchoolId) => void;
  onNavigateTab?: (tab: NavTab) => void;
  /** Workspace uses a map and list in the shared scroll area; standalone retains the phone sheet. */
  embedded?: boolean;
  selectionNotice?: string;
  renderPanelList?: (schools: readonly SchoolView[]) => React.ReactNode;
}

/** Compact status shown beside the workspace search input while searching. */
export function formatWorkspaceSearchStatus(workspace: boolean, query: string, count: number): string | undefined {
  if (!workspace || query.trim().length === 0) return undefined;
  return count > 0 ? `${count}개교` : '검색 결과 없음';
}

export function MapFrame({ embedded = false, search, list, children }: {
  embedded?: boolean; search: React.ReactNode; list: React.ReactNode; searching?: boolean; children: React.ReactNode;
}) {
  return <PhoneShell embedded={embedded} background="bg-[#38bdf8]" className="game-map-frame justify-between">
    <MapScrollBody>
      {search}
      {children}
      {embedded && list}
    </MapScrollBody>
    {!embedded && list}
  </PhoneShell>;
}

export function MapSearchHeader({
  region,
  query,
  placeholder,
  badge,
  searchStatus,
  selectionNotice,
  onRegionChange,
  onQueryChange,
}: {
  region: MapRegion;
  query: string;
  placeholder: string;
  badge: React.ReactNode;
  /** Compact inline status shown beside the search input while searching on PC workspace. */
  searchStatus?: string;
  selectionNotice?: string;
  onRegionChange: (region: MapRegion) => void;
  onQueryChange: (query: string) => void;
}) {
  return (
    <div className="w-full bg-[#fdf5df] border-b-3 border-[#261608] p-2 flex flex-col gap-2.5 shrink-0 z-30 shadow-[0_2px_0px_#261608]">
      <div className="flex items-center gap-1.5">
        {/* Border drawn as inset shadows so the box stays as tall as the 44px input (T44-D3). */}
        <div data-testid="map-search-box" className="flex-1 min-w-0 bg-[#ffffff] shadow-[inset_0_0_0_2px_#261608,inset_3px_3px_0px_#261608] px-2.5 flex items-center gap-1.5">
          <Search className="w-3.5 h-3.5 text-[#8b5327] shrink-0" />
          <input
            type="text"
            value={query}
            onChange={(e) => onQueryChange(e.target.value)}
            placeholder={placeholder}
            aria-label="학교 검색"
            className="w-full min-w-0 bg-transparent font-pixel text-xs text-[#261608] placeholder:text-stone-400 focus:outline-none"
          />
          {query && (
            <button
              type="button"
              aria-label="검색어 지우기"
              onClick={() => onQueryChange('')}
              className="min-h-6 min-w-6 shrink-0 flex items-center justify-center text-[#8b5327] hover:text-[#261608] cursor-pointer"
            >
              <X className="w-3 h-3" />
            </button>
          )}
        </div>
        {searchStatus ? (
          <span
            role="status"
            data-testid="map-search-status"
            className="game-map-search-status bg-[#faecc7] border-2 border-[#261608] px-2 py-1 font-pixel text-[10px] font-bold text-[#261608] shadow-[1px_1px_0px_#261608] shrink-0 whitespace-nowrap"
          >
            {searchStatus}
          </span>
        ) : badge}
      </div>

      {selectionNotice !== undefined && <p role="status" className="game-map-search-notice">{selectionNotice}</p>}
      <fieldset className="game-map-regions flex min-w-0 items-center gap-2 overflow-x-auto px-1 py-2.5 -mt-2.5 scrollbar-none" aria-label="권역 선택">
        {MAP_REGIONS.map((item) => {
          const isActive = item === region;
          return (
            <button
              type="button"
              key={item}
              aria-pressed={isActive}
              onClick={() => {
                soundManager.playTabSound();
                onRegionChange(item);
              }}
              className={`pixel-plaque px-2 py-0.5 text-[9px] font-pixel font-bold whitespace-nowrap cursor-pointer transition-transform ${
                isActive ? 'plaque-yellow' : 'plaque-parchment text-[#8b5327]'
              }`}
            >
              {item}
            </button>
          );
        })}
      </fieldset>
    </div>
  );
}

export function SchoolBadge({ school, className }: { school: SchoolView; className: string }) {
  return (
    <div className={className} style={{ backgroundColor: school.badgeColor }} data-logo-status={school.logoStatus}>
      {school.logoUrl ? (
        <ImageFallback url={school.logoUrl} defaultUrl={SCHOOL_LOGO_ASSETS[school.id]} name={school.name} fallback={school.firstSyllable} className="w-full h-full" />
      ) : (
        <SharedImage target={{ type: 'school', id: school.id }} name={school.name} fallback={school.firstSyllable} className="w-full h-full" />
      )}
    </div>
  );
}

export function RankRibbon({ school }: { school: SchoolView }) {
  return (
    <div className="absolute -top-6 left-1/2 -translate-x-1/2 bg-[#261608] text-[#fef08a] border border-[#facc15] px-1 py-0.2 shadow-[2px_2px_0px_#261608] whitespace-nowrap flex items-center gap-0.5 z-40">
      {school.rank === 1 ? (
        <Crown className="w-2.5 h-2.5 text-[#facc15] fill-[#facc15]" />
      ) : (
        <span className="text-[7px]">{school.rank === 2 ? '🥈' : '🥉'}</span>
      )}
      <span className="font-press-start text-[7px] font-black text-[#facc15]">{school.rank}위</span>
      <span className="font-mono text-[7px] text-white">{school.score.toLocaleString('ko-KR')}</span>
    </div>
  );
}

/** One selectable school on a map. Position comes from the caller; the label offset handles leader lines. */
export function SchoolMarker({
  school,
  style,
  selected,
  highlighted,
  large = false,
  labelOffset,
  onSelect,
}: {
  school: SchoolView;
  style: React.CSSProperties;
  selected: boolean;
  highlighted: boolean;
  large?: boolean;
  labelOffset?: { dx: number; dy: number };
  onSelect: (schoolId: SchoolId) => void;
}) {
  const zIndex = highlighted ? 35 : 20;
  const markerZIndex = selected && !highlighted ? 30 : zIndex;
  let labelBackground = 'bg-[#fdf5df]/95';
  if (selected) labelBackground = 'bg-[#ffffff]';
  if (highlighted) labelBackground = 'bg-[#fef08a]';
  if (school.isMySchool) labelBackground = 'bg-[#fecdd3]';
  return (
    <button
      type="button"
      aria-label={`${school.name} 전국 ${school.rank}위`}
      aria-pressed={selected}
      data-school-id={school.id}
      data-top-rank={highlighted ? school.rank : undefined}
      data-no-touch44
      onClick={() => {
        soundManager.playTabSound();
        onSelect(school.id);
      }}
      className="absolute cursor-pointer group"
      style={{ ...style, zIndex: markerZIndex }}
    >
      {highlighted && <RankRibbon school={school} />}

      {labelOffset && (
        <svg aria-hidden="true"
          className="absolute pointer-events-none"
          style={{ width: '24px', height: '24px', left: `${labelOffset.dx < 0 ? labelOffset.dx : 0}px`, top: '18px' }}
        >
          <line x1="12" y1="0" x2={12 + labelOffset.dx} y2={labelOffset.dy} stroke="#261608" strokeWidth="1.5" strokeDasharray="2,2" />
        </svg>
      )}

      <div className="flex justify-center">
        <SchoolBadge
          school={school}
          className={`relative flex items-center justify-center font-pixel font-black text-white transition-all ${
            large
              ? 'w-7 h-7 border-3 border-[#261608] shadow-[2px_2px_0px_#261608] text-xs'
              : 'w-5 h-5 border-2 border-[#261608] shadow-[1px_1px_0px_#261608] text-[9px]'
          } ${selected ? 'scale-110 ring-2 ring-[#ffffff]' : ''}`}
        />
      </div>

      <div
        className="flex justify-center"
        style={labelOffset ? { transform: `translate(${labelOffset.dx}px, ${labelOffset.dy}px)` } : undefined}
      >
        <div
          className={`mt-0.5 px-1 py-0.2 border border-[#261608] text-[8px] font-pixel font-bold text-center whitespace-nowrap shadow-[1px_1px_0px_#261608] text-[#261608] ${
            labelBackground
          }`}
        >
          {school.shortName}
        </div>
      </div>
    </button>
  );
}

export function SchoolListSheet({
  title,
  schools,
  query,
  selectedSchoolId,
  onQueryChange,
  onSelectSchool,
  onNavigateTab = () => {},
}: {
  title: string;
  schools: readonly SchoolView[];
  query: string;
  selectedSchoolId?: SchoolId;
  onQueryChange: (query: string) => void;
  onSelectSchool: (schoolId: SchoolId) => void;
  onNavigateTab?: (tab: NavTab) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const select = (schoolId: SchoolId) => {
    soundManager.playTabSound();
    onSelectSchool(schoolId);
  };

  // The collapsed height stays in the layout; the expanded sheet slides over the map so the map area never resizes (M1-D2).
  // 225/420px: the home bar that sat inside the sheet (10px) moved to PhoneShell, so the list keeps its height (T1b).
  return (
    <div className="relative w-full h-[225px] shrink-0 z-30">
    <div
      data-testid="school-list-sheet"
      className={`absolute inset-x-0 bottom-0 flex flex-col bg-[#fdf5df] border-t-3 border-[#261608] transition-all duration-300 ease-in-out shadow-[0_-4px_0px_#261608] ${
        expanded ? 'h-[420px]' : 'h-full'
      }`}
    >
      <button
        type="button"
        onClick={() => {
          setExpanded(!expanded);
          soundManager.playTabSound();
        }}
        className="w-full min-h-11 bg-[#faecc7] border-b-2 border-[#261608] px-3 py-1 flex items-center justify-between cursor-pointer hover:bg-[#f6e5b8] select-none shrink-0"
      >
        <div className="flex items-center gap-1.5">
          <span className="w-2 h-2 bg-[#b45309] border border-[#261608]" />
          <h3 className="font-pixel text-xs font-black text-[#261608]">
            {title} {expanded ? '▼' : '▲'}
          </h3>
          <span className="font-pixel text-[9px] text-[#8b5327] hidden sm:inline">(터치하여 {expanded ? '접기' : '더보기'})</span>
        </div>
        <div className="flex items-center gap-1">
          <span className="font-pixel text-[9px] text-[#8b5327] font-bold" data-testid="school-result-count">
            {schools.length}개교
          </span>
          {expanded ? <ChevronDown className="w-3.5 h-3.5 text-[#261608]" /> : <ChevronUp className="w-3.5 h-3.5 text-[#261608]" />}
        </div>
      </button>

      <ul className="flex-1 overflow-y-auto p-1.5 flex flex-col gap-1 scrollbar-none" aria-label="학교 목록">
        {schools.length === 0 && (
          <li className="p-3 flex flex-col items-center gap-2 text-center" role="status">
            <span className="font-pixel text-[10px] font-bold text-[#8b5327]">
              ‘{query.trim()}’에 맞는 학교가 없습니다.
            </span>
            <button
              type="button"
              onClick={() => onQueryChange('')}
              className="pixel-plaque plaque-yellow px-2 py-0.5 text-[9px] font-pixel font-black text-[#261608] cursor-pointer"
            >
              검색어 지우기
            </button>
          </li>
        )}
        {schools.map((school) => {
          const selected = school.id === selectedSchoolId;
          let rowStyle = 'bg-[#ffffff] border-[#261608]/40 hover:bg-[#fffbeb]';
          if (school.isMySchool) rowStyle = 'bg-[#faecc7]/90 border-[#facc15]';
          if (selected) rowStyle = 'bg-[#fef08a] border-[#261608] shadow-[1px_1px_0px_#261608]';
          const rankColors: Record<number, string> = { 1: 'text-[#eab308]', 2: 'text-[#94a3b8]', 3: 'text-[#b45309]' };
          return (
            <li
              key={school.id}
              data-school-id={school.id}
              onClick={() => select(school.id)}
              onKeyDown={(event) => { if (event.target === event.currentTarget && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); select(school.id); } }}
              tabIndex={0}
              className={`p-1.5 border-2 flex items-center justify-between gap-1.5 transition-colors cursor-pointer ${rowStyle}`}
            >
              <div className="flex items-center gap-1.5 min-w-0">
                <div className="w-5 text-center shrink-0">
                  <span
                    className={`font-press-start text-[8px] font-black ${
                      rankColors[school.rank] ?? 'text-[#8b5327]'
                    }`}
                  >
                    {school.rank}
                  </span>
                </div>
                <SchoolBadge
                  school={school}
                  className="w-6 h-6 border-2 border-[#261608] flex items-center justify-center text-white font-pixel font-bold text-[10px] shrink-0 shadow-[1px_1px_0px_#261608]"
                />
                <div className="min-w-0">
                  <div className="flex items-center gap-1">
                    <span className="font-pixel text-xs font-black text-[#261608] truncate">{school.name}</span>
                    {school.isMySchool && (
                      <span className="text-[7px] font-pixel font-bold bg-[#e11d48] text-white px-1 border border-[#261608] shrink-0">내 학교</span>
                    )}
                    {school.isTop3 && (
                      <span className="text-[7px] font-pixel font-bold bg-[#facc15] text-[#261608] px-0.5 border border-[#261608] shrink-0">
                        TOP{school.rank}
                      </span>
                    )}
                  </div>
                  <p className="font-pixel text-[8px] text-[#8b5327] truncate">
                    {school.region} · {school.city}
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-1.5 shrink-0">
                <div className="text-right">
                  <div className="font-press-start text-[7px] font-bold text-[#261608]">{school.score.toLocaleString('ko-KR')}</div>
                  <div className="font-pixel text-[7px] text-[#8b5327]">점수</div>
                </div>
                <button
                  type="button"
                  aria-label={`${school.name} 방문`}
                  onClick={(e) => {
                    e.stopPropagation();
                    soundManager.playBuySound();
                    onSelectSchool(school.id);
                  }}
                  className="pixel-plaque plaque-yellow px-1.5 py-0.5 text-[9px] font-pixel font-black text-[#261608] shadow-[1px_1px_0px_#261608] hover:scale-105 active:scale-95 cursor-pointer whitespace-nowrap"
                >
                  방문
                </button>
              </div>
            </li>
          );
        })}
      </ul>

      <div className="px-2 pt-1 pb-1 shrink-0 bg-[#fdf5df] border-t-2 border-[#eeddb6]">
        <BottomNav
          activeTab="map"
          onChangeTab={(tab) => {
            soundManager.playTabSound();
            onNavigateTab(tab);
          }}
          hasShopUpgrade={false}
          hasRankChange={false}
        />
      </div>

    </div>
    </div>
  );
}
