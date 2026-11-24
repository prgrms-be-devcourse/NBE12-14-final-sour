import assert from 'node:assert/strict';
import { after, afterEach, test } from 'node:test';
import { registerHooks } from 'node:module';
import React from 'react';
import { JSDOM } from 'jsdom';

// DOM 자산 로더 등록 (웹 상대 경로로 정규화하여 JSDOM에서 유효한 http: URL 생성)
const assetHooks = registerHooks({
  load(url, context, nextLoad) {
    if (/\.(jpg|png|webp|svg)(\?.*)?$/.test(url)) {
      const pathname = new URL(url).pathname;
      const match = pathname.match(/\/src\/assets\/.+$/);
      const webPath = match ? match[0] : '/src/assets/images/mock_identity_sample.svg';
      return { format: 'module', source: `export default ${JSON.stringify(webPath)};`, shortCircuit: true };
    }
    return nextLoad(url, context);
  },
});

// DOM 환경 초기화
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost', pretendToBeVisual: true });
for (const [key, value] of Object.entries({
  window: dom.window,
  document: dom.window.document,
  navigator: dom.window.navigator,
  HTMLElement: dom.window.HTMLElement,
  Node: dom.window.Node,
  requestAnimationFrame: dom.window.requestAnimationFrame.bind(dom.window),
  cancelAnimationFrame: dom.window.cancelAnimationFrame.bind(dom.window),
  IS_REACT_ACT_ENVIRONMENT: true,
})) {
  Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
}
dom.window.HTMLElement.prototype.scrollIntoView = function () {};

const { render, screen, fireEvent, act, cleanup } = await import('@testing-library/react');
const { clickTestView } = await import('./helpers/mockTools');
const { MockGameProvider } = await import('../src/hooks/useMockGame.tsx');
const { default: App } = await import('../src/App.tsx');

afterEach(() => cleanup());

// 목업 초기 데이터에는 다른 연합들의 대기 대결도 있으므로, 시연 수락·상태 확인은 반드시 우리 연합 대결로 한정한다.
const OUR_ALLIANCE = '한양대·부산대 연합';
const OPPONENT_ALLIANCE = '수도권 동맹';

/** 대결 신청 화면의 시연 제어에서 우리 연합 → 상대 연합 대기 신청 행의 수락 버튼을 누른다. */
function demoAcceptOurBattle() {
  const rows = Array.from(document.querySelectorAll('div')).filter((d) =>
    d.firstElementChild?.textContent?.startsWith(`${OUR_ALLIANCE} → ${OPPONENT_ALLIANCE}`) && d.querySelector('button'));
  assert.equal(rows.length, 1, '우리 연합의 대기 대결 행은 하나여야 한다');
  const acceptButton = Array.from(rows[0].querySelectorAll('button')).find((b) => b.textContent?.includes('상대 연합 명의로 수락'));
  assert.ok(acceptButton, '우리 연합 대기 대결의 시연 수락 버튼');
  act(() => { fireEvent.click(acceptButton); });
}

/** 대결 현황 화면에서 우리 연합 대결 카드와 상태 배지 문구를 찾는다. */
function getOurBattleCard() {
  const cards = Array.from(document.querySelectorAll('div.rpg-box')).filter((d) =>
    d.firstElementChild?.textContent?.includes('(연합별')
    && d.textContent?.includes(OUR_ALLIANCE) && d.textContent?.includes(OPPONENT_ALLIANCE));
  assert.equal(cards.length, 1, '우리 연합 대결 카드는 하나여야 한다');
  const status = cards[0].firstElementChild?.querySelector(':scope > span')?.textContent?.trim();
  return { card: cards[0], status };
}

/** 우리 대결 카드가 기대 상태가 될 때까지 가상 시계를 앞당긴다. 기본 시작 시각이 실행 시각(KST 09–22시 정각)에 따라 달라지므로 횟수 제한 반복으로 실행 시각과 무관하게 만든다. */
function fastForwardUntilOurBattle(expected: string, buttonName: '+1h' | '+24h', maxClicks: number) {
  for (let i = 0; i < maxClicks && getOurBattleCard().status !== expected; i++) {
    act(() => { fireEvent.click(screen.getAllByRole('button', { name: buttonName })[0]); });
  }
  assert.equal(getOurBattleCard().status, expected);
}
after(() => {
  assetHooks.deregister();
  dom.window.close();
});

test('A. 크로스 화면 통합 시나리오 (Gameplay -> Shop -> Alliance -> Battle -> Navigation)', async (t) => {
  cleanup();

  // 단 1회 마운트 (MockGameProvider 내부의 SharedImageProvider, App 내부의 AllianceProvider, BattleProvider 모두 공유)
  render(React.createElement(MockGameProvider, null, React.createElement(App)));

  // =========================================================================
  // 1. Gameplay: 메인 게임 화면 진입, 클릭 득점 및 커피 버프 구매
  // =========================================================================
  clickTestView('🎮 메인 게임 화면');

  // 초기 한양대학교 캠퍼스 HUD 확인
  assert.ok(screen.getAllByText(/한양대학교/).length > 0);

  // 캠퍼스 클릭 영역 터치하여 개인 기여도 획득. 클릭 사이를 100ms씩 띄워 M5 간격 규칙(30ms)에 걸리지 않게 하고,
  // 20회 모두 기여도에 쌓이는지 확인한다 (M5 이연 #1: 같은 시각 연타는 대부분 거절돼 원래 의도와 달랐다).
  const tapArea = screen.getByRole('button', { name: '캠퍼스 클릭 영역' });
  // The main screen shows contribution on the stats sign (T5); its accessible name carries the full number.
  const contribution = () => Number(screen.getByRole('button', { name: /^상세 스탯 보기/ }).getAttribute('aria-label')!.replace(/\D/g, ''));
  let clock = Date.now();
  const clockMock = t.mock.method(Date, 'now', () => clock);
  // CampusView rolls a 25% critical hit per click; fix the roll so every click scores the base points.
  const randomMock = t.mock.method(Math, 'random', () => 0.99);
  const startContribution = contribution();
  act(() => { fireEvent.click(tapArea); });
  const perClick = contribution() - startContribution;
  assert.ok(perClick > 0, '첫 클릭이 기여도에 반영돼야 한다');
  act(() => {
    for (let i = 1; i < 20; i++) {
      clock += 100;
      fireEvent.click(tapArea);
    }
  });
  assert.equal(contribution() - startContribution, perClick * 20, '간격을 둔 20회 클릭은 모두 반영된다');
  clockMock.mock.restore();
  randomMock.mock.restore();

  // 하단 탭에서 '상점' 탭 선택하여 커피 버프 구매
  const shopTabBtn = screen.getByRole('button', { name: '상점' });
  act(() => {
    fireEvent.click(shopTabBtn);
  });

  // 아이템 상점에서 커피 버프 구매
  const coffeeCard = screen.getByRole('button', { name: '커피 한 잔 구매 선택' });
  act(() => {
    fireEvent.click(coffeeCard);
  });
  const confirmBuyBtn = screen.getByRole('button', { name: '구매 및 사용' });
  act(() => {
    fireEvent.click(confirmBuyBtn);
  });

  // 상점에 활성 버프 '커피 한 잔 · 30초 남음' 표시 확인
  assert.ok(screen.getByText(/커피 한 잔 · \d+초 남음/));

  // =========================================================================
  // 2. Shop: 이미지 변경 상품에서 학교 로고 로컬 샘플 모의 결제 적용
  // =========================================================================
  const schoolImgChoiceBtn = screen.getByRole('button', { name: '학교 이미지 변경 · 500원' });
  act(() => {
    fireEvent.click(schoolImgChoiceBtn);
  });

  // 로컬 샘플 이미지 적용 버튼 클릭
  const useSampleBtn = screen.getByRole('button', { name: '로컬 샘플 사용' });
  act(() => {
    fireEvent.click(useSampleBtn);
  });

  // 모의 결제 성공 및 즉시 적용 클릭
  const applySuccessBtn = screen.getByRole('button', { name: '모의 결제 성공 및 즉시 적용' });
  await act(async () => {
    fireEvent.click(applySuccessBtn);
  });

  // 상점을 닫고 직전 캠퍼스에 돌아오면 HUD의 학교 로고에도 새 커스텀 SVG가 적용된다.
  fireEvent.click(screen.getByRole('button', { name: '캠퍼스로 돌아가기' }));
  const hudSchoolImg = screen.getByRole('img', { name: '한양대학교 이미지' });
  assert.ok(hudSchoolImg.getAttribute('src')?.includes('mock_identity_sample.svg'));

  // 순위 화면에서도 한양대학교 로고가 커스텀 이미지로 렌더링됨 확인
  const rankingTabBtn = screen.getByRole('button', { name: '순위' });
  act(() => {
    fireEvent.click(rankingTabBtn);
  });
  const rankingImgs = screen.getAllByRole('img', { name: '한양대학교 이미지' });
  assert.ok(rankingImgs.length >= 1);

  // =========================================================================
  // 3. Alliance: 현재 연합 빈 상태 -> 연합 신청 -> 시연 수락 -> 결성 확인
  // =========================================================================
  clickTestView('🛡️ 현재 연합 (Current Alliance)');

  // 현재 소속 연합 없음 상태 확인
  assert.ok(screen.getByRole('heading', { name: '현재 연합' }));
  assert.ok(screen.getByText('소속 연합 없음'));

  // 연합 신청 화면으로 이동
  const gotoAllianceApplyBtn = screen.getByRole('button', { name: '연합 신청하러 가기' });
  act(() => {
    fireEvent.click(gotoAllianceApplyBtn);
  });

  // 부산대학교에 연합 신청
  const pusanCandidateBtn = screen.getByRole('button', { name: /부산대학교/ });
  act(() => {
    fireEvent.click(pusanCandidateBtn);
  });

  // 연합 기간 72시간 선택: 아래에서 가상 시계를 최대 약 58시간 앞당겨도 연합이 만료되지 않게 한다
  act(() => {
    fireEvent.click(screen.getByRole('button', { name: '72h' }));
  });

  // 연합 신청 보내기
  const sendAllianceBtn = screen.getByRole('button', { name: '연합 신청 보내기' });
  act(() => {
    fireEvent.click(sendAllianceBtn);
  });

  // 시연 제어 영역에서 상대 학교 명의로 수락 (연합 결성 확정)
  const demoAcceptAllianceBtn = screen.getByRole('button', { name: '[시연] 상대 학교 명의로 수락' });
  act(() => {
    fireEvent.click(demoAcceptAllianceBtn);
  });

  // 현재 연합 화면으로 이동하여 결성된 연합 확인
  const curAllianceHeaderBtn = screen.getByRole('button', { name: '현재 연합' });
  act(() => {
    fireEvent.click(curAllianceHeaderBtn);
  });
  assert.ok(screen.getByText('한양대·부산대 연합'));

  // 결성된 연합 카드 내 한양대학교 로고가 변경된 커스텀 이미지로 렌더링됨 확인
  const allianceSchoolImg = screen.getAllByRole('img', { name: '한양대학교 이미지' });
  assert.ok(allianceSchoolImg.length >= 1);

  // =========================================================================
  // 4. Battle: 현재 연합 -> 대결 신청 -> 시연 수락(예정) -> 시계 조작(진행/종료)
  // =========================================================================
  const gotoBattleApplyBtn = screen.getByRole('button', { name: /대항전 신청하기/ });
  act(() => {
    fireEvent.click(gotoBattleApplyBtn);
  });

  // 대결 신청 화면 헤더 및 내 연합 정보 확인
  assert.ok(screen.getByRole('heading', { name: '대결 신청' }));
  assert.ok(screen.getByText('한양대·부산대 연합'));

  // 대결 가능한 상대 연합(수도권 동맹) 선택 (2개교 연합끼리 2:2 고정, 형식 선택 없음)
  const capitalOpponentBtn = screen.getByRole('button', { name: /수도권 동맹/ });
  act(() => {
    fireEvent.click(capitalOpponentBtn);
  });

  // 대결 신청 전송
  const sendBattleBtn = screen.getByRole('button', { name: '대결 신청 보내기' });
  act(() => {
    fireEvent.click(sendBattleBtn);
  });

  // 시연 제어 영역에서 우리 연합 대기 신청을 상대 연합 명의로 수락 (대결 확정 -> '경기 예정' 단계)
  // (이전에는 마지막 수락 버튼을 눌러 다른 목업 연합의 대결을 수락하던 결함이 있었다. 2026-11-08 수정)
  demoAcceptOurBattle();

  // 대결 현황 화면으로 이동
  const gotoBattleStatusBtn = screen.getByRole('button', { name: '대결 현황' });
  act(() => {
    fireEvent.click(gotoBattleStatusBtn);
  });

  // 우리 연합 대결 카드에서 '경기 예정' 상태 확인
  assert.ok(screen.getByRole('heading', { name: '대결 현황' }));
  assert.equal(getOurBattleCard().status, '경기 예정');

  // 우리 대결 카드에도 한양대학교 커스텀 로고가 유지/표시됨 확인
  assert.ok(getOurBattleCard().card.querySelector('img[alt="한양대학교 이미지"]'));

  // 가상 시계를 +1h씩 전진 -> 우리 대결 '진행 중' (active) 전환 확인
  // 기본 시작은 현재+60분 이후의 09–22시(KST) 정각이므로 최대 다음 날 09시, 즉 약 34시간 이내다.
  fastForwardUntilOurBattle('진행 중', '+1h', 36);

  // 가상 시계를 +24h 전진 -> 우리 대결 '종료됨' (finished) 전환 및 점수 기반 결과 확인 (기본 경기 3시간, 최대 24시간)
  fastForwardUntilOurBattle('종료됨', '+24h', 2);
  const finishedCard = getOurBattleCard().card;
  assert.match(finishedCard.textContent ?? '', /무승부 \(DRAW\)/, '수락 시 점수 0:0으로 시작해 무승부');
  assert.match(finishedCard.textContent ?? '', /결과: 샘플 점수 기준/);

  // =========================================================================
  // 5. Back Navigation & 게임 상태(기여도, 버프, 순위) 불변 검증
  // =========================================================================
  // 대결 현황 -> 뒤로가기(현재 연합)
  const backBtn1 = screen.getByRole('button', { name: '뒤로가기' });
  act(() => {
    fireEvent.click(backBtn1);
  });
  assert.ok(screen.getByRole('heading', { name: '현재 연합' }));
  assert.ok(screen.getByText('한양대·부산대 연합'));

  // 현재 연합 -> 뒤로가기(메인 게임 화면)
  const backBtn2 = screen.getByRole('button', { name: '뒤로가기' });
  act(() => {
    fireEvent.click(backBtn2);
  });

  // 메인 게임 화면 복귀 후:
  // 상점 탭 열어서 커피 버프 '커피 한 잔' 유지 확인
  act(() => {
    fireEvent.click(screen.getByRole('button', { name: '상점' }));
  });
  assert.ok(screen.getByText(/커피 한 잔 · \d+초 남음/));

  // 돌아온 캠퍼스에서 학교 로고가 커스텀 SVG로 계속 표시되는지 확인
  fireEvent.click(screen.getByRole('button', { name: '캠퍼스로 돌아가기' }));
  const finalHudImg = screen.getByRole('img', { name: '한양대학교 이미지' });
  assert.ok(finalHudImg.getAttribute('src')?.includes('mock_identity_sample.svg'));

  // 연합·대결 상태가 보존되어 런처를 통해 재진입 시 연합 및 대결 현황이 그대로 유지됨 확인
  clickTestView('🛡️ 현재 연합 (Current Alliance)');
  assert.ok(screen.getByText('한양대·부산대 연합'));
});

test('B. 하단 내비 연합·대결 배지: 보낸 대결 신청 대기 중에는 꺼지고, 우리 대결이 확정되면 켜진다', () => {
  render(React.createElement(MockGameProvider, null, React.createElement(App)));
  const click = (name: string | RegExp) => act(() => { fireEvent.click(screen.getByRole('button', { name })); });
  const goMainAndReadAlert = () => {
    clickTestView('🎮 메인 게임 화면');
    return screen.getByRole('button', { name: '연합·대결' }).dataset.alert;
  };

  assert.equal(goMainAndReadAlert(), undefined, '초기 상태: 연합 없음');

  // 연합 결성
  clickTestView('🛡️ 현재 연합 (Current Alliance)');
  click('연합 신청하러 가기');
  click(/부산대학교/);
  click('연합 신청 보내기');
  click('[시연] 상대 학교 명의로 수락');
  assert.equal(goMainAndReadAlert(), undefined, '연합만 결성되고 대결은 없음');

  // 우리 연합이 대결을 신청(우리가 신청자) → 상대 응답 대기는 확인할 소식이 아니다
  clickTestView('🛡️ 현재 연합 (Current Alliance)');
  click(/대항전 신청하기/);
  click(/수도권 동맹/);
  click('대결 신청 보내기');
  assert.equal(goMainAndReadAlert(), undefined, '보낸 대결 신청 대기');

  // 우리 연합의 대기 신청 행을 골라 시연 수락 → 경기 예정(점유) → 배지
  clickTestView('⚔️ 대결 신청 (Battle Apply)');
  demoAcceptOurBattle();
  assert.equal(goMainAndReadAlert(), 'true', '우리 연합 대결 예정·진행 중');
});
