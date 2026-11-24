import React, { useState } from 'react';
import { useApiFaultMode } from '../api/ApiProvider';
import { errorMessage } from '../api/errors';
import { API_MODE, type ApiMode } from '../api/mode';
import { useUniversitySearch } from '../api/universities';
import { MOCK_REQUEST_MODES, type MockRequestMode } from '../utils/mockRequest';
import { LoadNotice } from './LoadNotice';

interface ApiCheckScreenProps {
  /** Tests pass the mode; the app uses VITE_API_MODE. */
  mode?: ApiMode;
}

/**
 * Dev-only check that the client, the proxy and TanStack Query reach the backend (SCRUM-147): searches schools
 * with GET /api/v1/universities and shows the result. Opened with ?apiCheck in `npm run dev` only (production builds
 * drop it), so its fault-injection control stays for developers; in the app, the admin test tool (SCRUM-175) owns it.
 */
export const ApiCheckScreen: React.FC<ApiCheckScreenProps> = ({ mode = API_MODE }) => {
  const [draft, setDraft] = useState('');
  const [keyword, setKeyword] = useState('');
  const [faultMode, setFaultMode] = useApiFaultMode();
  const search = useUniversitySearch(mode === 'api' ? keyword : '');

  return (
    <main className="min-h-screen bg-[#faecc7] p-4 flex flex-col items-center">
      <div className="w-full max-w-[480px] flex flex-col gap-3 font-pixel text-[#261608]">
        <h1 className="text-lg font-black">API 연결 확인</h1>
        <p className="text-xs">
          모드: <strong data-api-mode>{mode}</strong> · 학교 검색 <code>GET /api/v1/universities</code>
        </p>

        {mode === 'mock' ? (
          <p role="note" className="rpg-box p-3 text-xs bg-[#fffbeb]">
            목업 모드에서는 API를 부르지 않습니다. <code>VITE_API_MODE=api</code>로 개발 서버를 다시 시작하세요.
          </p>
        ) : (
          <>
            <form
              className="flex gap-2"
              onSubmit={(event) => {
                event.preventDefault();
                setKeyword(draft);
              }}
            >
              <label htmlFor="api-check-keyword" className="sr-only">학교 검색어</label>
              <input
                id="api-check-keyword"
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
                placeholder="학교 이름 (예: 한양)"
                className="flex-1 border-2 border-[#261608] bg-white px-2 py-1 text-sm"
              />
              <button type="submit" className="pixel-plaque plaque-yellow px-3 py-1 text-xs font-black cursor-pointer">검색</button>
            </form>

            <label className="flex items-center gap-2 text-xs">
              요청 실패 주입
              <select
                value={faultMode}
                onChange={(event) => setFaultMode(event.target.value as MockRequestMode)}
                className="border-2 border-[#261608] bg-white px-1 py-0.5"
              >
                {MOCK_REQUEST_MODES.map((option) => <option key={option.mode} value={option.mode}>{option.label}</option>)}
              </select>
            </label>

            {!keyword.trim() ? (
              <p className="text-xs text-[#8b5327]">검색어를 입력하고 검색을 누르세요.</p>
            ) : search.isPending ? (
              <LoadNotice status="loading" name="학교 검색" failureDetail="" onRetry={() => {}} variant="panel" />
            ) : search.isError ? (
              <LoadNotice
                status="failed"
                name="학교 검색"
                failureDetail={errorMessage(search.error)}
                onRetry={() => void search.refetch()}
                variant="panel"
              />
            ) : search.data.length === 0 ? (
              <p role="status" className="text-xs">검색 결과가 없습니다</p>
            ) : (
              <section aria-label="학교 검색 결과" className="flex flex-col gap-1">
                <p role="status" className="text-xs">{search.data.length}개 학교{search.isFetching ? ' · 새로 불러오는 중…' : ''}</p>
                <ul className="rpg-box bg-white divide-y-2 divide-[#261608]/20">
                  {search.data.map((school) => (
                    <li key={school.id} className="flex items-center gap-2 px-2 py-1.5 text-sm">
                      {school.logoUrl
                        ? <img src={school.logoUrl} alt="" className="w-6 h-6 object-contain" />
                        : <span aria-hidden className="w-6 h-6 bg-[#e5d3a8]" />}
                      <span className="font-bold">{school.name}</span>
                      <span className="ml-auto text-[10px] text-[#8b5327]">ID {school.id}</span>
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </>
        )}
      </div>
    </main>
  );
};
