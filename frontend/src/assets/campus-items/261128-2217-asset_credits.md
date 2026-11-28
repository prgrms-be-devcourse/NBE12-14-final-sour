# 캠퍼스 픽셀 에셋 (SCRUM-338)

아래 SVG는 이 프로젝트용으로 24×24 논리 픽셀 그리드에 정수 좌표로 직접 그렸다. 생성형 이미지 시안은 형태와 소재를 비교하는 데만 쓰고, 자르거나 변환해 제품에 넣지 않았다. 외부 스프라이트·실존 상표·학교 로고는 사용하지 않았다. Lv.10 문장은 가상의 학교 건물 모티프다.

- 시안: Gemini Nano Banana 2.1 (Vertex AI), 2026-11-28, `generated-images/20261128-220924-scrum338-pixel-sprite-anchor-concepts.png` (작업용, 앱에 미포함). SHA-256 `a1db60c4ac44c535b53350200077860d101d8e3630b75dcc7b8a2cbf909c5e42`.
- 최종본: 밝은 양피지 배경을 기준으로 어두운 테두리와 투명 여백을 공통으로 사용한다. `shape-rendering="crispEdges"`와 24/48 CSS px 표시를 사용한다. 레벨과 상품명은 별도 텍스트가 제공한다.

| 파일 | 서버 레벨 / 상품명 | 구분점 |
| --- | --- | --- |
| `clicker-01.svg` | 1 | 왼쪽으로 굽은 코드와 둥근 아이보리 외장 |
| `clicker-02.svg` | 2 | 오른쪽 코드와 옆면 홈이 있는 각진 외장 |
| `clicker-03.svg` | 3 | 코드 없는 민트색 외장과 하단 상태 등 |
| `clicker-04.svg` | 4 | 비대칭 엄지 받침과 황동색 휠 |
| `clicker-05.svg` | 5 | 은빛 테두리와 청록색 발광 휠 |
| `clicker-06.svg` | 6 | 분할된 어깨 패널과 주황색 하단 띠 |
| `clicker-07.svg` | 7 | 넓은 보호 레일과 등판의 V자 장식 |
| `clicker-08.svg` | 8 | 단계형 측면과 등판의 마름모 인레이 |
| `clicker-09.svg` | 9 | 분리된 클릭판과 양쪽 청동 레일 |
| `clicker-10.svg` | 10 | 금빛 외장과 학교 건물을 넣은 방패 문장 |
| `iced-americano.svg` | 아메리카노 | 투명한 뚜껑·빨대·얼음이 보이는 커피 컵 |
| `energy-can.svg` | 에너지 드링크 | 은색 탭과 번개 문양이 있는 산호색 캔 |
| `moon-vial.svg` | 밤샘 각성제 | 코르크와 초승달 라벨이 있는 보라색 약병 |

매핑: `frontend/src/data/campusItemArt.ts`. 상품 ID는 DB가 생성하므로 [V16 시드](../../../../src/main/resources/db/migration/V16__seed_clicker_levels_and_potions.sql)의 이름·유형·결제 방식으로 그림만 고른다. 알 수 없는 항목은 각 화면에서 공용 심볼을 표시한다. 가격·효과·강화 가능 여부에는 관여하지 않는다.
