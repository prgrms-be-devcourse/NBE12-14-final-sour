/** Additional real-browser M6 flows. Physical keyboard/IME and devices remain separate. */
import assert from 'node:assert/strict';

export async function runMobileFlows({ send, evaluate, waitFor, pause, button, screen, fill, key, viewport, load, screenshot, touch, checkSheet, results }) {
  // T5: the main screen shows a compact contribution (1.2M); the stats button's accessible name keeps the full number.
  // V1 (docs/261110-2005-visit_ui_jeju_map_plan.md §4): my campus and a visited campus share one layout.
  const layoutBoxes = () => evaluate(`(() => {
    const layout = document.querySelector('[data-game-layout]'), main = layout.querySelector('main[data-game-main]');
    // Relative to the layout: the framed page may be scrolled differently on the two screens.
    const o = layout.getBoundingClientRect();
    const box = (e) => { const r = e.getBoundingClientRect(); return [r.left - o.left, r.top - o.top, r.width, r.height].map(Math.round); };
    return { kind: layout.dataset.gameLayout, layout: [o.width, o.height].map(Math.round), header: box(layout.firstElementChild),
      frame: box(main.querySelector('[data-campus-stage]')), footerTop: Math.round(layout.querySelector('footer').getBoundingClientRect().top - o.top),
      mainOverflow: main.scrollHeight - main.clientHeight };
  })()`);
  const contribution = () => evaluate(`Number(document.querySelector('[data-testid="campus-stats-button"]').getAttribute('aria-label').replace(/[^0-9]/g,''))`);
  async function sizeTargets(selector) {
    const sizes = await evaluate(`[...document.querySelectorAll(${JSON.stringify(selector)})].map(e=>{const r=e.getBoundingClientRect();return {name:e.getAttribute('aria-label')||e.textContent.trim(),width:r.width,height:r.height}})`);
    assert.ok(sizes.length > 0, `Missing target: ${selector}`);
    for (const s of sizes) assert.ok(s.width >= 24 && s.height >= 24, JSON.stringify(s));
    return sizes;
  }
  async function code(value) {
    for (let i=0;i<6;i++) await fill(`[aria-label="인증 코드 ${i+1}번째 자리"]`, value[i]);
    await button('코드 확인');
  }
  async function request(email) {
    await fill('input[type="email"]', email); await button('인증 코드 받기');
    await waitFor(`!!document.querySelector('[data-testid="mock-mailbox-code"]')`);
    return evaluate(`document.querySelector('[data-testid="mock-mailbox-code"]').textContent`);
  }
  // T5: clicker level and buff live in the campus stats sheet, so open it, read, and close it again.
  async function gameSnapshot() {
    await touch('[data-testid="campus-stats-button"]');
    await waitFor(`!!document.querySelector('dialog[open][aria-label="상세 스탯"]')`);
    const snapshot = await evaluate(`(() => {
      const text=document.querySelector('dialog[open][aria-label="상세 스탯"]').textContent;
      const clicker=text.match(/클리커 Lv\\.(\\d+)/),buff=text.match(/커피 한 잔 · (\\d+)초 남음/);
      if(!clicker||!buff)throw new Error('Expected upgraded clicker and active coffee');
      return {clicker:Number(clicker[1]),buffSeconds:Number(buff[1]),contribution:Number(document.querySelector('[data-testid="campus-stats-button"]').getAttribute('aria-label').replace(/[^0-9]/g,'')),school:document.querySelector('[data-game-layout="my-campus"] > div:first-child').textContent};
    })()`);
    await key('Escape');
    await waitFor(`!document.querySelector('dialog[open]')`);
    return snapshot;
  }
  // Fixed random only around a tap: eliminate critical-hit ambiguity when detecting double scoring.
  async function scoredTouch() {
    const before = await contribution();
    await evaluate('window.__m6Random = Math.random; Math.random = () => 0.99');
    try { await touch('[aria-label="캠퍼스 클릭 영역"]'); }
    finally { await evaluate('Math.random = window.__m6Random; delete window.__m6Random'); }
    assert.equal(await contribution(), before + 30, 'One touch must score exactly once (unbuffed initial power 30)');
  }
  async function drag(selector, distance = 150) {
    await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'center'})`);
    const point = await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)}),r=e.getBoundingClientRect();return {x:r.left+r.width/2,y:${distance > 0 ? 'Math.min(r.bottom-20,innerHeight-20)' : 'Math.max(r.top+20,20)'}}})()`);
    assert.ok(point.y-distance > 0 && point.y-distance < (await evaluate('innerHeight')), 'Enough visible space for the touch drag');
    await send('Input.dispatchTouchEvent', { type:'touchStart', touchPoints:[point] });
    for (let i=1;i<=10;i++) {
      await send('Input.dispatchTouchEvent', {type:'touchMove',touchPoints:[{x:point.x,y:point.y-distance*i/10}]});
      await pause(20);
    }
    await send('Input.dispatchTouchEvent', {type:'touchEnd',touchPoints:[]}); await pause(500);
  }

  for (const [width,height] of [[390,844],[844,390]]) {
    await viewport(width,height); await load(); await screen('🎮 메인 게임 화면');
    await scoredTouch(); const afterTap = await contribution();
    await touch('[title="우리 학교 방명록 보기"]'); await waitFor(`!!document.querySelector('dialog[open]')`);
    const sheetList = 'dialog div[class*="overflow-y-auto"][class*="px-2.5"]';
    const ownBefore = await evaluate(`document.querySelector(${JSON.stringify(sheetList)}).scrollTop`);
    await drag(sheetList,100);
    assert.ok(await evaluate(`document.querySelector(${JSON.stringify(sheetList)}).scrollTop`) > ownBefore, 'Guestbook list responds to real touch drag');
    await key('Escape'); assert.equal(await contribution(), afterTap, 'Guestbook touch must not score');
    await evaluate(`document.querySelector('[title="우리 학교 방명록 보기"]').focus()`);
    await key(' '); await waitFor(`!!document.querySelector('dialog[open]')`); await key('Escape');
    assert.equal(await contribution(), afterTap, 'Guestbook keyboard activation must not score');
    await touch('[title="채팅창 열기"]'); await waitFor(`!!document.querySelector('dialog[open]')`);
    // Populate via the real form so portrait also has an overflowing history to drag.
    for (let i=0;i<12;i++) {
      await fill('dialog [aria-label="채팅 메시지"]',`M6 스크롤용 메시지 ${i}`); await button('전송');
    }
    await pause(700);
    await waitFor(`document.querySelector(${JSON.stringify(sheetList)}).scrollTop > 0`);
    const chatBefore = await evaluate(`document.querySelector(${JSON.stringify(sheetList)}).scrollTop`);
    await drag(sheetList,-100);
    assert.ok(await evaluate(`document.querySelector(${JSON.stringify(sheetList)}).scrollTop`) < chatBefore, 'Chat history responds to real touch drag');
    await touch('dialog [aria-label="채팅 메시지"]');
    await send('Input.insertText',{text:'M6 채팅 전송 확인'});
    await touch('dialog button[type="submit"]');
    await waitFor(`document.querySelector('dialog').textContent.includes('M6 채팅 전송 확인')`);
    assert.equal(await evaluate(`document.querySelector('dialog [aria-label="채팅 메시지"]').value`),'');
    await touch('dialog > button'); await waitFor(`!document.querySelector('dialog[open]')`);
    results.push({width,height,sheets:'own guestbook/chat touch scrolling; native text insertion, touch send, close'});

    // Map overview contains nine region clusters and three score ribbons (logos are placeholders, M1 D1).
    await screen('🗺️ 단일 폰 지도');
    assert.equal(await evaluate(`document.querySelectorAll('[data-cluster-region]').length`),9);
    assert.equal(await evaluate(`document.querySelectorAll('[data-top-rank]').length`),3);
    await touch('[data-cluster-region="강원"]');
    assert.equal(await evaluate(`document.querySelectorAll('button[data-school-id]').length`),4, 'The current Gangwon fixture has four campuses');
    await fill('[aria-label="학교 검색"]','강원');
    const mapTargets = await sizeTargets('[aria-label="학교 검색"], button[aria-label="검색어 지우기"]');
    const schoolId = await evaluate(`document.querySelector('button[data-school-id]').dataset.schoolId`);
    await touch(`button[data-school-id="${schoolId}"]`);
    await waitFor(`!!document.querySelector('[aria-label$="캠퍼스 (관전 모드)"]')`);
    const title = await evaluate(`document.querySelector('[aria-label$="캠퍼스 (관전 모드)"]').getAttribute('aria-label').replace(' 캠퍼스 (관전 모드)','')`);
    const visitScore = await evaluate(`document.querySelector('[data-testid="visit-score"]').textContent`);
    const visitBoxes = await layoutBoxes();
    assert.equal(visitBoxes.kind, 'visit');
    assert.ok(visitBoxes.mainOverflow <= 0, `Visit main does not scroll: ${JSON.stringify(visitBoxes)}`);
    // 학교 정보 sheet (V2, Q2) opens from the sign and keeps the visited school's score.
    await checkSheet('[data-testid="visit-info-button"]', `${title} 학교 정보`, width, height);
    await touch('[data-testid="visit-info-button"]');
    await waitFor(`!!document.querySelector('dialog[open] [data-testid="visit-info-score"]')`);
    assert.equal(await evaluate(`document.querySelector('dialog[open] [data-testid="visit-info-score"]').textContent`), visitScore);
    await screenshot(`${width}x${height}-visit-info`);
    await key('Escape');
    await waitFor(`!document.querySelector('dialog[open]')`);
    await touch('[aria-label$="캠퍼스 (관전 모드)"]');
    assert.equal(await evaluate(`document.querySelector('[data-testid="visit-score"]').textContent`),visitScore,'Spectator touch does not score');
    await evaluate(`document.querySelector('[data-campus-stage] [aria-label="${title} 방명록 보기"]').setAttribute('data-m6-opener','')`);
    await checkSheet('[data-m6-opener]', `${title} 방명록`, width,height);
    await touch('[data-m6-opener]');
    await fill('dialog textarea','M6 방문 입력 확인');
    await viewport(height,width);
    assert.equal(await evaluate(`document.querySelector('dialog textarea').value`),'M6 방문 입력 확인');
    assert.equal(await evaluate(`document.querySelector('[aria-label$="캠퍼스 (관전 모드)"]').getAttribute('aria-label')`),`${title} 캠퍼스 (관전 모드)`);
    await viewport(width,height);
    await touch('dialog button[type="submit"]');
    await waitFor(`document.querySelector('dialog').textContent.includes('M6 방문 입력 확인')`);
    await screenshot(`${width}x${height}-visit-guestbook`);
    await key('Escape');
    assert.equal(await evaluate(`document.activeElement.matches('[data-m6-opener]')`),true);
    await touch('header [aria-label="지도로 돌아가기"]');
    assert.equal(await evaluate(`document.querySelector('[aria-label="학교 검색"]').value`),'강원');
    assert.equal(await evaluate(`document.querySelector('[data-testid="map-area"]').dataset.region`),'강원');
    assert.equal(await evaluate(`document.querySelector('button[data-school-id="${schoolId}"]').getAttribute('aria-pressed')`),'true');
    await fill('[aria-label="학교 검색"]','');
    const another = await evaluate(`[...document.querySelectorAll('button[data-school-id]')].find(b=>b.dataset.schoolId!==${JSON.stringify(schoolId)}).dataset.schoolId`);
    await touch(`button[data-school-id="${another}"]`);
    await waitFor(`!!document.querySelector('[data-testid="visit-score"]')`);
    assert.notEqual(await evaluate(`document.querySelector('[aria-label$="캠퍼스 (관전 모드)"]').getAttribute('aria-label')`),`${title} 캠퍼스 (관전 모드)`,'Consecutive visit changes school');
    await touch('[data-testid="spectator-bar"] [aria-label="내 학교로 돌아가기"]');
    await waitFor(`document.querySelector('[data-game-layout]')?.dataset.gameLayout === 'my-campus'`);
    await pause(300);
    const mineBoxes = await layoutBoxes();
    for (const part of ['layout', 'header', 'frame', 'footerTop']) {
      const a = [mineBoxes[part]].flat(), b = [visitBoxes[part]].flat();
      assert.ok(a.every((v, i) => Math.abs(v - b[i]) <= 2), `${width}x${height} ${part}: my campus ${JSON.stringify(a)} vs visit ${JSON.stringify(b)}`);
    }
    assert.equal(await contribution(), afterTap, 'Spectating/guestbooks do not change own contribution'); await scoredTouch();
    results.push({width,height,visit:'clusters/top scores, two school visits, layout parity with my campus, info sheet, spectator no-score, guestbook keyboard/touch/send/rotation/focus, map query/selection return, scoring resumes',mapTargets});

    // Onboarding failures and success at both orientations. Reload resets only this local mock session.
    await load(); await screen('🎮 메인 게임 화면'); await button('상점');
    await evaluate(`[...document.querySelectorAll('[data-game-main] button')].find(b=>b.textContent.includes('업그레이드')).click()`); await pause(100);
    await touch('[aria-label="커피 한 잔 구매 선택"]'); await button('구매 및 사용'); await button('캠퍼스');
    const gameBefore = await gameSnapshot(); assert.equal(gameBefore.clicker,4);
    await viewport(height,width); const rotatedBefore = await gameSnapshot();
    assert.equal(rotatedBefore.contribution,gameBefore.contribution); assert.equal(rotatedBefore.clicker,4);
    assert.ok(rotatedBefore.buffSeconds <= gameBefore.buffSeconds && rotatedBefore.buffSeconds > 0);
    await viewport(width,height); await screen('📱 순차 온보딩 (Single Phone)');
    const emailTargets = await sizeTargets('[aria-label="목업 샘플 이메일"] button');
    await fill('input[type="email"]','m6@unknown.example'); await button('인증 코드 받기');
    assert.equal(await evaluate(`document.querySelector('[role="alert"]').textContent.includes('지원하지 않는')`),true);
    await fill('input[type="email"]','m6@shared-demo.ac.kr'); await button('인증 코드 받기');
    assert.equal(await evaluate(`document.querySelector('[role="alert"]').textContent.includes('여러 학교')`),true);
    const expired = await request('expired@kaist.ac.kr');
    const codeTargets = await sizeTargets('form button[type="button"]:not(.self-center)');
    await button('[시연] 코드 만료시키기'); await code(expired);
    assert.equal(await evaluate(`document.querySelector('[role="alert"]').textContent.includes('유효 시간이')`),true);
    await button('이메일 수정');
    const issued = await request('m6@hanyang.ac.kr');
    const wrong = issued==='000000'?'111111':'000000'; await code(wrong);
    assert.equal(await evaluate(`document.querySelector('[data-testid="attempts-left"]').textContent.includes('4회')`),true);
    assert.equal(await evaluate(`[...document.querySelectorAll('[aria-label^="인증 코드 "]')].map(e=>e.value).join('')`),wrong);
    await code(issued);
    await waitFor(`!!document.querySelector('[data-testid="verified-school"]')`);
    assert.equal(await evaluate(`document.querySelectorAll('[aria-label^="인증 코드 "]').length`),0,'Success removes the code form; UI cannot re-submit the used code');
    await fill('input[placeholder="열정학번사자"]','M6검증');
    await viewport(height,width);
    assert.equal(await evaluate(`document.querySelector('input[placeholder="열정학번사자"]').value`),'M6검증');
    await viewport(width,height);
    await button('입학하기!'); await button('캠퍼스로 이동');
    // Check the enrolled nickname in a real game interaction rather than the removed presentation header.
    await touch('[title="채팅창 열기"]');
    await fill('dialog [aria-label="채팅 메시지"]', '입학 후 닉네임 확인');
    await button('전송');
    await waitFor(`document.querySelector('dialog[open]').textContent.includes('입학 후 닉네임 확인')`);
    assert.ok(await evaluate(`document.querySelector('dialog[open]').textContent.includes('M6검증')`));
    await key('Escape');
    await waitFor(`!document.querySelector('dialog[open]')`);
    await viewport(height,width); await viewport(width,height);
    const gameAfter = await gameSnapshot();
    assert.equal(gameAfter.contribution,gameBefore.contribution,'Same-school enrollment and rotation keep contribution');
    assert.equal(gameAfter.clicker,gameBefore.clicker,'Same-school enrollment and rotation keep upgraded clicker');
    assert.equal(gameAfter.school,gameBefore.school,'School identity, score, rank and campus level stay unchanged');
    assert.ok(gameAfter.buffSeconds > 0 && gameAfter.buffSeconds <= gameBefore.buffSeconds,'Buff continues without reset/extension');
    results.push({width,height,rotationGameState:{before:gameBefore,after:gameAfter},enrollment:'same school, progress kept'});
    await screenshot(`${width}x${height}-onboarded`);
    results.push({width,height,auth:'unsupported/shared domain, explicit mock expiry, wrong then correct, profile rotation, enrollment; code form gone after success (not already-used branch proof)',emailTargets,codeTargets});
  }

  // Genuine touch drag rather than assigning scrollTop. Start with the standalone ranking's 15 rows.
  await viewport(390,844); await load(); await screen('🏆 랭킹 화면 (Single Phone)');
  const rankList = '[style*="touch-action: pan-y"]';
  const before = await evaluate(`document.querySelector(${JSON.stringify(rankList)}).scrollTop`);
  await drag(rankList);
  assert.ok(await evaluate(`document.querySelector(${JSON.stringify(rankList)}).scrollTop`) > before, 'Touch drag scrolls ranking');
  // Continue native touch drags until pagination grows. No forced scroll event or assigned scrollTop.
  const rowCount = () => evaluate(`document.querySelector(${JSON.stringify(rankList)}).querySelectorAll(':scope > div > div.rpg-box').length`);
  const initialRows = await rowCount();
  for(let i=0;i<25;i++) await drag(rankList,240);
  const finalRows = await rowCount();
  assert.ok(finalRows > initialRows, `Infinite list must grow: ${initialRows} -> ${finalRows}`);
  await screenshot('ranking-touch-scroll');
  results.push({scroll:'real touch drag and infinite loading',initialRows,finalRows});

  // Growth-stage screenshots use the actual game renderer, not the comparison sprite sheet.
  for(const [width,height] of [[360,740],[740,360]]) {
    await viewport(width,height); await load(); await screen('🎮 메인 게임 화면');
    for(let level=1;level<=10;level++) {
      await screen('🏛️ 10단계 캠퍼스 성장 시트 (5x2)');
      await evaluate(`document.querySelector('[title="Lv.${level} 단계 확인"]').click()`); await pause(50);
      await button('성장 시연: 학교 점수를 기준값으로 설정'); await button('인게임으로');
      assert.equal(await evaluate(`Number(document.querySelector('[data-testid="campus-scene"]').dataset.level)`),level);
      await screenshot(`${width}x${height}-growth-${String(level).padStart(2,'0')}`, '[data-testid="campus-scene"]');
    }
    results.push({width,height,growth:'Lv.1–10 renderer/screenshots captured; visual review is separate'});
  }
}
