# 메이플스토리체(Maplestory) 도입 및 UI 텍스트 가독성 최적화 계획

한빛대학교 캠퍼스 키우기 MockUI의 텍스트 가독성을 대폭 개선하기 위해, 볼드 웨이트가 뭉개지던 기존 비트맵 서체(Galmuri11)를 캐주얼 게임 감성과 볼드 가독성이 뛰어난 '메이플스토리체(Maplestory)'로 전환하고 렌더링을 최적화합니다.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> 사용자가 선택한 서체 방향에 맞추어 메이플스토리체(Light 400 / Bold 700)를 메인 UI 서체로 적용합니다. 기존 8비트 아케이드 감성의 핵심인 `Press Start 2P`(점수, 배지, 순위 숫자)는 조화롭게 유지됩니다.

- **확정된 결정**: 메인 한글 폰트를 **메이플스토리체(Maplestory)**로 변경하여 Bold 및 Black 웨이트에서의 획 뭉개짐을 완벽히 해소하고 가독성을 확보.
- **렌더링 보정**: 전체 와일드카드(`*`)에 일괄 적용되어 있던 `-webkit-font-smoothing: none;`을 벡터 서체에 맞게 `antialiased`로 개선하고, 픽셀 그래픽(`img`, `canvas`, 스프라이트)에만 `pixelated` 렌더링을 격리 유지.
- **보존 요소**: 점수 및 아케이드 뱃지에 사용되는 `Press Start 2P` 폰트와 고정폭 시간 표기 `font-mono`는 기존 감성을 살려 유지.

---

### 1. 개요 및 핵심 목표

- **목적**: 모바일 목업 및 미니 UI의 작은 글자(8px~12px)에서도 한글의 자형이 또렷하게 식별되도록 개선.
- **대상 요소**: 
  - 전국 지도 화면(Screen 1, Screen 2)의 핀 라벨, 클러스터 텍스트, 대학 순위 리스트
  - 캠퍼스 화면의 다이얼로그, 말풍선, 미니 안내 문구
  - 상점(Shop) 아이템 이름, 효과 설명, 비용 버튼
  - 랭킹 뷰의 학교명 및 기여도 정보
- **기대 효과**: 레트로 아기자기함은 그대로 유지하면서, 모바일 화면에서의 가독성과 시인성을 크게 향상시킵니다.

---

### 2. 사용자 경험 및 시각 디자인

- **서체 계층 구조**:
  - **메인 텍스트 (.font-pixel / .font-maple)**: `Maplestory`, `Galmuri11`, sans-serif
    - 일반 텍스트 (`font-normal`): `Maplestory Light (400)`
    - 강조/버튼/라벨 (`font-bold`, `font-black`): `Maplestory Bold (700)` - 인위적 굵기가 아닌 전용 볼드 글립으로 선명한 가독성 보장
  - **아케이드 수치 텍스트 (.font-press-start)**: `Press Start 2P` - 점수, 순위 숫자, 아케이드 콤보
  - **상태바 및 스탯 보조 (.font-mono)**: 시스템 시간, 고정폭 수치

- **디자인 조화**:
  - 기존 우든 플라크(`pixel-plaque`), 양피지 상자(`rpg-box`), 레트로 다이얼로그의 두꺼운 외곽선(#261608)과 메이플스토리체의 둥글고 단단한 글립이 자연스럽게 결합.
  - 지나치게 날카롭던 도트 외곽선이 부드럽고 친근한 캐주얼 RPG 감성으로 업그레이드.

---

### 3. 주요 결정 사항 및 기술 전략

```
┌─────────────────────────────────────────────────────────────┐
│                       CSS Font System                       │
├──────────────────────────────┬──────────────────────────────┤
│  @font-face: Maplestory      │  @font-face: Press Start 2P  │
│  - Light (400) for body      │  - Arcade retro numbers      │
│  - Bold (700) for UI buttons │  - Ranks & multipliers       │
├──────────────────────────────┴──────────────────────────────┤
│               Rendering Layer Optimization                  │
│  - Text elements: -webkit-font-smoothing: antialiased        │
│  - Pixel art / Sprites: image-rendering: pixelated          │
└─────────────────────────────────────────────────────────────┘
```

- **결정 1**: CDN 웹폰트 선언
  - fastly jsdelivr projectnoonnu의 검증된 `MaplestoryOTFLight.woff` 및 `MaplestoryOTFBold.woff`를 `@font-face`로 매핑.
- **결정 2**: CSS 클래스 호환성 유지
  - 기존 컴포넌트 전반에 지정된 `.font-pixel` 클래스의 `font-family` 1순위를 `'Maplestory'`로 지정하여 모든 기존 화면에 즉시 무결점 적용되도록 보장.
- **결정 3**: 텍스트 렌더링 안티앨리어싱 분리
  - 기존 `* { -webkit-font-smoothing: none; }` 규칙 때문에 벡터 외곽선 폰트가 거칠어지는 현상을 방지하도록 텍스트 요소에는 선명한 안티앨리어싱을 부여.

---

### 4. 작업 단계 (승인 후 진행)

1. `src/index.css`에 `@font-face` 선언 (`Maplestory` Light & Bold) 추가.
2. `.font-pixel` 및 `--pixel-font` 토큰에 `'Maplestory'` 우선 적용.
3. 텍스트 렌더링 스타일 보정 (`-webkit-font-smoothing: antialiased` 적용 및 스프라이트 전용 `pixelated` 분리).
4. `compile_applet` 및 `lint_applet`을 통한 정상 빌드 검증.
