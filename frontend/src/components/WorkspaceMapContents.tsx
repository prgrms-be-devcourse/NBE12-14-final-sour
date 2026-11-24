import { useId } from 'react';
import type { SchoolView } from '../data/schoolDirectory';
import type { SchoolId } from '../data/schoolIds';
import { soundManager } from '../utils/sound';
import { SchoolBadge } from './MapParts';
import { WorkspaceMenuLabel } from './GameWorkspace';

export function WorkspaceMapPreview({
  school,
  onVisit,
  blocked,
  excludedFromSearch = false,
}: {
  school?: SchoolView;
  onVisit: (id: SchoolId) => void;
  blocked?: string;
  /** True when a search query is active and the currently selected school is not in the filtered results. */
  excludedFromSearch?: boolean;
}) {
  const noticeId = useId();
  return <section aria-label="학교 미리보기" className="game-map-preview">
    <div role="status" className="min-w-0">
      {school ? <>
        <h3 className="font-bold wrap-anywhere">{school.name}</h3>
        <p className="text-xs">{school.city} · {school.score.toLocaleString('ko-KR')}점</p>
      </> : <p className="text-xs">지도나 목록에서 학교를 선택하세요.</p>}
    </div>
    {school && <button type="button" className="game-workspace-action game-nav-control" aria-label={`${school.name} 방문`} aria-disabled={!!blocked} aria-describedby={blocked ? noticeId : undefined}
      onClick={() => { if (!blocked) { soundManager.playBuySound(); onVisit(school.id); } }}><WorkspaceMenuLabel icon="visit">방문</WorkspaceMenuLabel></button>}
    {school && (
      <p role="status" data-testid="map-preview-search-notice" className="game-map-preview-search-notice text-xs">
        {excludedFromSearch ? '현재 검색 결과에 없음' : ''}
      </p>
    )}
    {school && blocked && <p id={noticeId} role="status" className="game-map-visit-notice text-xs">{blocked}</p>}
  </section>;
}

export function WorkspaceMapSchoolList({ schools, selectedSchoolId, query, onSelect }: {
  schools: readonly SchoolView[]; selectedSchoolId?: SchoolId; query: string; onSelect: (id: SchoolId) => void;
}) {
  const isSearching = query.trim().length > 0;
  const heading = isSearching ? `검색 학교 목록 - ${schools.length}개교` : `학교 목록 · ${schools.length}개교`;
  return <section aria-label="학교 목록" className="game-map-list">
    <h3 className="font-bold">{heading}</h3>
    {schools.length === 0 && <p role="status">‘{query.trim()}’에 맞는 학교가 없습니다.</p>}
    <ul className="flex flex-col gap-1">
      {schools.map((school) => <li key={school.id}>
        <button type="button" className="game-workspace-action game-map-row" aria-pressed={selectedSchoolId === school.id}
          onClick={() => { soundManager.playTabSound(); onSelect(school.id); }}>
          <span aria-hidden="true" className="shrink-0"><SchoolBadge school={school} className="size-5 border border-current text-white" /></span>
          <span className="wrap-anywhere">{school.name}{school.isMySchool && <small className="block">내 학교</small>}</span>
        </button>
      </li>)}
    </ul>
  </section>;
}
