#!/usr/bin/env bash
# 사용법: test-summary.sh <구분 이름> <테스트 결과 XML 폴더>
# GitHub Actions 요약(GITHUB_STEP_SUMMARY)에 테스트 수, 실패 수, 컨텍스트 캐시 통계를 한 줄로 남긴다
set -u
name=$1
dir=$2

{
  echo "## $name 테스트 요약"
  echo ""
  echo "| 구분 | 테스트 | 실패 | 건너뜀 | 컨텍스트 생성 |"
  echo "|---|---|---|---|---|"
} >> "$GITHUB_STEP_SUMMARY"

if ! ls "$dir"/*.xml >/dev/null 2>&1; then
  echo "| $name | 실행 안 됨 | - | - | - |" >> "$GITHUB_STEP_SUMMARY"
  exit 0
fi

tests=0 failures=0 skipped=0
for f in "$dir"/*.xml; do
  head=$(grep -m1 -ao '<testsuite [^>]*' "$f")
  t=$(echo "$head"  | grep -o 'tests="[0-9]*"'    | grep -o '[0-9]*')
  fl=$(echo "$head" | grep -o 'failures="[0-9]*"' | grep -o '[0-9]*')
  er=$(echo "$head" | grep -o 'errors="[0-9]*"'   | grep -o '[0-9]*')
  sk=$(echo "$head" | grep -o 'skipped="[0-9]*"'  | grep -o '[0-9]*')
  tests=$((tests + t)); failures=$((failures + fl + er)); skipped=$((skipped + sk))
done

# 컨텍스트 캐시 통계는 실행 순서대로 누적되므로 missCount가 가장 큰 줄이 최종값
stats=$(grep -aho 'hitCount = [0-9]*, missCount = [0-9]*' "$dir"/*.xml | sort -t '=' -k3 -n | tail -1 || true)
hit=$(echo "$stats"  | grep -o 'hitCount = [0-9]*'  | grep -o '[0-9]*' || true)
miss=$(echo "$stats" | grep -o 'missCount = [0-9]*' | grep -o '[0-9]*' || true)
echo "| $name | $tests | $failures | $skipped | ${miss:--} (재사용 ${hit:--}) |" >> "$GITHUB_STEP_SUMMARY"