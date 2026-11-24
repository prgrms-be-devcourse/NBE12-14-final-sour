import { useId, useRef } from 'react';
import { errorMessage } from '../api/errors';
import { usePublicUniversityTop } from '../api/rankings';

const formatScore = new Intl.NumberFormat('ko-KR');

/** Only the public scores; no links to personal, department, or visit views. */
export function PublicUniversityTop() {
  const titleId = useId();
  const title = useRef<HTMLHeadingElement>(null);
  const { data, error, isPending, isFetching, isPaused, refetch } = usePublicUniversityTop();
  const failed = !isPaused && !isFetching && error;
  const loading = !isPaused && (isPending || isFetching) && !data;

  return (
    <section className="guest-rankings rpg-box" aria-labelledby={titleId}>
      <h2 ref={title} id={titleId} tabIndex={-1}>전국 대학 TOP 10</h2>
      <p className="guest-rankings-caption">로그인 없이 볼 수 있는 대학별 누적 점수예요.</p>
      <div role="status" className="guest-rankings-status">
        {isPaused && '인터넷 연결을 기다리는 중이에요.'}
        {loading && '대학 순위를 불러오는 중이에요.'}
        {failed && '대학 순위를 불러오지 못했어요.'}
        {!isPaused && !failed && data?.length === 0 && '아직 공개된 대학 순위가 없어요.'}
      </div>
      {failed && (
        <div className="guest-rankings-error">
          <p>{errorMessage(failed)}</p>
          <button type="button" className="game-workspace-action" onClick={() => {
            title.current?.focus({ preventScroll: true });
            void refetch({ cancelRefetch: false });
          }}>순위 다시 불러오기</button>
        </div>
      )}
      {!!data?.length && (
        <table aria-labelledby={titleId}>
          <thead>
            <tr><th scope="col">순위</th><th scope="col">대학</th><th scope="col">점수</th></tr>
          </thead>
          <tbody>
            {data.map(({ universityId, rank, name, totalScore }) => (
              <tr key={universityId}>
                <td>{rank}위</td>
                <th scope="row">{name}</th>
                <td>{formatScore.format(totalScore)}점</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
