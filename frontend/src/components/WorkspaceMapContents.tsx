import { useId, useLayoutEffect, useRef } from 'react';
import type { SchoolView } from '../data/schoolDirectory';
import type { SchoolId } from '../data/schoolIds';
import { soundManager } from '../utils/sound';
import { SchoolBadge } from './MapParts';
import { WorkspaceMenuLabel } from './GameWorkspace';

export function WorkspaceMapSchoolList({ schools, selectedSchoolId, query, onSelect, onVisit, blocked, revealMarker }: {
  schools: readonly SchoolView[]; selectedSchoolId?: SchoolId; query: string;
  onSelect: (id: SchoolId) => void; onVisit: (id: SchoolId) => void; blocked?: string; revealMarker: number;
}) {
  const listRef = useRef<HTMLElement>(null);
  const noticeId = useId();
  const isSearching = query.trim().length > 0;
  const heading = isSearching ? `검색 학교 목록 - ${schools.length}개교` : `학교 목록 · ${schools.length}개교`;

  // Marker selections may point below the visible rows; never scroll the surrounding page or move focus.
  useLayoutEffect(() => {
    if (!revealMarker || selectedSchoolId === undefined) return;
    const list = listRef.current;
    const row = list?.querySelector<HTMLElement>(`[data-map-school-row="${selectedSchoolId}"]`);
    if (!list || !row) return;
    const bounds = list.getBoundingClientRect();
    const item = row.getBoundingClientRect();
    if (item.top < bounds.top) list.scrollTop += item.top - bounds.top;
    else if (item.bottom > bounds.bottom) list.scrollTop += item.bottom - bounds.bottom;
  }, [revealMarker, selectedSchoolId]);

  return <section ref={listRef} aria-label="학교 목록" className="game-map-list">
    <h3 className="font-bold">{heading}</h3>
    {schools.length === 0 && <p role="status">‘{query.trim()}’에 맞는 학교가 없습니다.</p>}
    <ul className="flex flex-col gap-1">
      {schools.map((school) => {
        const selected = selectedSchoolId === school.id;
        return <li key={school.id} data-map-school-row={school.id} className={`game-map-row ${selected ? 'is-selected' : ''}`}>
          <button type="button" className="game-map-row-select" aria-expanded={selected}
            onClick={() => { soundManager.playTabSound(); onSelect(school.id); }}>
            <span aria-hidden="true" className="shrink-0"><SchoolBadge school={school} className="size-5 border border-current text-white" /></span>
            <span className="wrap-anywhere">{school.name}{school.isMySchool && <small className="block">내 학교</small>}</span>
          </button>
          {selected && <>
            <div className="game-map-row-details">
              <small>{school.region} · {school.city} · {school.score.toLocaleString('ko-KR')}점</small>
              {blocked && <p id={noticeId} role="status">{blocked}</p>}
            </div>
            <button type="button" className="game-workspace-action game-nav-control game-map-row-visit" aria-label={`${school.name} 방문`}
              aria-disabled={!!blocked} aria-describedby={blocked ? noticeId : undefined}
              onClick={() => { if (!blocked) { soundManager.playBuySound(); onVisit(school.id); } }}><WorkspaceMenuLabel icon="visit">방문</WorkspaceMenuLabel></button>
          </>}
        </li>;
      })}
    </ul>
  </section>;
}
