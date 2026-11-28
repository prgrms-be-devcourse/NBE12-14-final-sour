"""Verify service item art, upgrades in campus stats, and shop purchases with fixed API responses."""

import argparse
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright

DRINKS = [('아메리카노', 711, 250, 60), ('에너지 드링크', 208, 1500, 180), ('밤샘 각성제', 850, 5000, 300)]


def serve(page, level, balance=1000, upgrade_status=200):
    profile = {'id': 501, 'nickname': '검증 학생', 'email': 'student@example.test', 'role': 'USER', 'status': 'ACTIVE',
               'profileImageUrl': None, 'university': {'id': 8, 'name': '한양대학교', 'logoUrl': None},
               'department': {'id': 1, 'name': '공학과'}, 'clicker': {'level': level, 'clickPower': level,
                                                                 'nextLevelRequiredScore': None if level >= 10 else 200},
               'totalContribScore': balance, 'totalAttackScore': 0}
    items = [{'id': id_, 'name': name, 'content': '검증용 효과', 'type': 'MULTIPLIER', 'payType': 'CONTRIBUTION',
              'price': price, 'durationSeconds': duration} for name, id_, price, duration in DRINKS]
    items += [{**items[0], 'id': 5, 'name': '새 음료'},
              {**items[0], 'id': 4, 'name': '크레딧 상품', 'payType': 'CREDIT'}]
    data = {'/auth/reissue': {'accessToken': 'test', 'expiresInSeconds': 3600}, '/users/me': profile,
            '/alliances/me': None, '/items': items, '/items/me/active': None, '/map/universities': [],
            '/clickers': [{'level': i, 'clickPower': i, 'requiredScore': 0 if i == 1 else 200} for i in range(1, 12)],
            '/rankings/universities': {'items': [], 'myUniversity': None, 'hasNext': False, 'page': 0,
                                       'size': 20, 'totalCount': 0}}

    calls, pending = [], []
    purchase_path = f"/items/{items[0]['id']}/purchase"

    def fixture(route):
        request = route.request
        path = urlsplit(request.url).path.removeprefix('/api/v1')
        calls.append((request.method, path))
        if path == '/clickers/upgrade':
            assert request.method == 'POST' and request.post_data is None
            if upgrade_status == 200:
                pending.append(route)
            else:
                route.fulfill(status=upgrade_status, json={'code': 'CLICKER_UPGRADE_CONFLICT', 'message': '서버 강화 충돌', 'data': None})
        elif path == purchase_path:
            item = items[0]
            assert request.method == 'POST' and request.post_data is None and request.headers.get('idempotency-key')
            profile['totalContribScore'] -= item['price']
            effect = {'itemId': item['id'], 'name': item['name'], 'multiplier': 1.5,
                      'expiredAt': '2030-01-01T12:00:00+09:00', 'remainingSeconds': 60}
            data['/items/me/active'] = effect
            route.fulfill(status=201, json={'code': 'SUCCESS', 'message': '', 'data': {'userItemId': 1,
                'item': {key: item[key] for key in ('id', 'name', 'payType')}, 'price': item['price'],
                'expiredAt': effect['expiredAt'], 'myContribScore': profile['totalContribScore'], 'myCreditBalance': 0}})
        elif path in data:
            route.fulfill(json={'code': 'SUCCESS', 'message': '', 'data': data[path]})
        else:
            route.fulfill(status=404, json={'code': 'NOT_FOUND', 'message': 'Missing browser fixture'})

    page.route('**/api/v1/**', fixture)
    return profile, calls, pending, purchase_path


def check_image(container, size):
    image = container.locator('img[alt=""]')
    expect(image).to_be_visible()
    assert image.evaluate('''async (image, size) => {
      await image.decode();
      const box = image.getBoundingClientRect();
      const parent = image.parentElement.getBoundingClientRect();
      return image.naturalWidth > 0 && box.width === size && box.height === size &&
        box.left >= parent.left && box.right <= parent.right && box.bottom <= parent.bottom;
    }''', size), 'Expected a decoded, unclipped decorative sprite at its intended size'
    expect(container.get_by_role('img')).to_have_count(0)
    return image.get_attribute('src')


def open_stats(page, desktop):
    if desktop:
        return page.get_by_role('complementary', name='학교 패널').get_by_role('tabpanel', name='상세')
    page.get_by_test_id('campus-stats-button').tap()
    return page.get_by_role('dialog', name='상세 스탯')


def open_altar(stats, desktop):
    expect(stats.get_by_role('button', name='상점에서 강화하기')).to_have_count(0)
    altar = stats.get_by_role('region', name='내 클리커 강화 제단')
    if desktop:
        expect(altar.get_by_role('button', name='내 클리커 강화 제단')).to_have_count(0)
    else:
        toggle = altar.get_by_role('button', name='내 클리커 강화 제단')
        expect(toggle).to_have_attribute('aria-expanded', 'false')
        expect(altar.get_by_role('button', name='클리커 강화하기')).to_have_count(0)
        toggle.press('Space')
        expect(toggle).to_have_attribute('aria-expanded', 'true')
        expect(toggle).to_be_focused()
        toggle.tap()
        expect(toggle).to_have_attribute('aria-expanded', 'false')
        expect(altar.get_by_role('button', name='클리커 강화하기')).to_have_count(0)
        toggle.tap()
    return altar


def verify(page, url, level, desktop, out):
    balance = 199 if level == 5 else 1000
    _, calls, _, _ = serve(page, level, balance)
    page.goto(url, wait_until='networkidle')
    stats = open_stats(page, desktop)
    label = stats.get_by_text(f'클리커 Lv.{level}', exact=True)
    expect(label).to_be_visible()
    stats_art = check_image(label, 24) if level <= 10 else None
    if stats_art is None:
        expect(label.locator('img')).to_have_count(0)
        expect(label.locator('svg[aria-hidden="true"]')).to_have_count(1)
    clicker = open_altar(stats, desktop)
    upgrade = clicker.get_by_role('button', name='클리커 강화하기')
    expect(upgrade).to_be_visible()
    if level >= 10 or balance < 200:
        expect(upgrade).to_be_disabled()
        expect(upgrade).to_have_accessible_description('최고 단계입니다.' if level >= 10 else '업그레이드에 필요한 기여도가 부족합니다.')
        upgrade.press('Enter')
        assert ('POST', '/clickers/upgrade') not in calls
    else:
        expect(upgrade).to_be_enabled()
    pedestal = clicker.get_by_test_id('clicker-altar-art')
    if stats_art is not None:
        assert check_image(pedestal, 48) == stats_art, 'The summary and altar must use the art for the same level'
    else:
        expect(pedestal.locator('img')).to_have_count(0)
        expect(pedestal.locator('svg[aria-hidden="true"]')).to_have_count(1)
    if out:
        clicker.screenshot(path=out / 'altar.png', animations='disabled')
    if not desktop:
        page.keyboard.press('Escape')
        expect(stats).to_be_hidden()
    shop_button = page.get_by_role('button', name='상점', exact=True)
    shop_button.press('Enter') if desktop else shop_button.tap()
    shop = page.locator('[data-game-layout="shop"]')
    expect(shop.get_by_role('heading', name='캠퍼스 잡화점')).to_be_visible()
    expect(shop.get_by_role('heading', name='활성 효과 상세')).to_be_visible()
    expect(shop.get_by_role('heading', name='아이템 진열대')).to_be_visible()
    expect(shop.get_by_role('heading', name='내 클리커 강화 제단')).to_have_count(0)
    expect(shop.get_by_role('button', name='클리커 강화하기')).to_have_count(0)
    urls = [check_image(shop.get_by_role('button', name=f'{name} 구매 선택'), 24) for name, *_ in DRINKS]
    assert len(set(urls)) == 3, urls
    for name in ['새 음료 구매 선택', '크레딧 상품 구매 준비 중']:
        card = shop.get_by_role('button', name=name)
        expect(card.locator('svg[aria-hidden="true"]')).to_have_count(1)
        expect(card.locator('img')).to_have_count(0)
    if out:
        shop.screenshot(path=out / 'shelf.png', animations='disabled')
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), 'Unexpected horizontal page scroll'
    print(f'PASS {page.viewport_size} Lv.{level}: disclosure, availability, item art and fallback')


def verify_purchase_after_upgrade(page, url, desktop):
    profile, calls, pending, purchase_path = serve(page, level=1)
    page.goto(url, wait_until='networkidle')
    stats = open_stats(page, desktop)
    altar = open_altar(stats, desktop)
    expect(altar.get_by_text('Lv.1 · 기본 클릭당 1점')).to_be_visible()
    expect(altar.get_by_text('Lv.2 · 기본 클릭당 2점')).to_be_visible()
    expect(altar.get_by_role('progressbar', name='다음 강화 비용 대비 보유 기여도')).to_have_attribute('value', '100')
    altar.get_by_text('클리커 레벨표 보기', exact=True).press('Enter')
    table = altar.get_by_role('table', name='서버 클리커 레벨표 · 해당 레벨 도달 비용')
    expect(table.get_by_role('row', name='Lv.2 2점 200점')).to_be_visible()
    table_region = altar.get_by_role('region', name='클리커 레벨표 스크롤')
    table_region.focus()
    table_region.press('PageDown')
    page.wait_for_function('(el) => el.scrollTop > 0', arg=table_region.element_handle(), timeout=2500)
    upgrade = altar.get_by_role('button', name='클리커 강화하기')
    expect(upgrade).to_be_enabled()
    upgrade.evaluate('(button) => { button.click(); button.click(); }')
    expect(altar.get_by_role('button', name='요청 중…')).to_be_disabled()
    expect(stats.get_by_text('보유 기여도 1,000점', exact=True)).to_be_visible()
    if not desktop:
        page.keyboard.press('Escape')
        expect(stats).to_be_hidden()
    page.get_by_role('button', name='캠퍼스 클릭 영역').press('Enter')
    expect(page.get_by_text('구매·강화·공격 결과를 확인하는 동안 클릭을 잠시 멈춥니다.')).to_be_visible()
    page.get_by_role('button', name='상점', exact=True).click()
    shop = page.locator('[data-game-layout="shop"]')
    card = shop.get_by_role('button', name='아메리카노 구매 선택')
    card.click()
    purchase = page.get_by_role('dialog', name='구매 확인 · 아메리카노')
    expect(purchase.get_by_role('button', name='250점 구매 및 사용')).to_be_disabled()
    expect(purchase.get_by_text('미확정 클릭 또는 구매·강화가 끝난 뒤 구매할 수 있습니다.')).to_be_visible()
    assert len(pending) == 1
    profile.update(clicker={'level': 2, 'clickPower': 2, 'nextLevelRequiredScore': 200}, totalContribScore=800)
    pending.pop().fulfill(json={'code': 'SUCCESS', 'message': '', 'data': {**profile['clicker'], 'myContribScore': 800}})
    buy = purchase.get_by_role('button', name='250점 구매 및 사용')
    expect(buy).to_be_enabled()
    expect(purchase.get_by_text('구매 후 550점.', exact=False)).to_be_visible()
    buy.click()
    expect(purchase).to_be_hidden()
    expect(shop.get_by_text('아메리카노 구매 완료. 적용 효과는 서버 조회 기준입니다.')).to_be_visible()
    expect(shop.get_by_role('region', name='활성 효과 상세').get_by_text('아메리카노 · 서버 배율 ×1.5', exact=False)).to_be_visible()
    expect(shop.get_by_text('보유 기여도 550점')).to_be_visible()
    page.get_by_role('button', name='캠퍼스로 돌아가기').click()
    stats = open_stats(page, desktop)
    altar = open_altar(stats, desktop)
    expect(altar.get_by_text('클리커 Lv.2 강화 완료! 클릭당 2점입니다.')).to_be_visible()
    expect(stats.get_by_text('클리커 Lv.2', exact=True)).to_be_visible()
    assert [path for method, path in calls if method == 'POST' and path != '/auth/reissue'] == ['/clickers/upgrade', purchase_path]
    print(f'PASS {page.viewport_size}: one upgrade, lock, route change, purchase and active effect')


def verify_upgrade_conflict(page, url):
    _, calls, _, _ = serve(page, level=1, upgrade_status=409)
    page.goto(url, wait_until='networkidle')
    stats = open_stats(page, desktop=False)
    altar = open_altar(stats, desktop=False)
    upgrade = altar.get_by_role('button', name='클리커 강화하기')
    upgrade.tap()
    expect(altar.get_by_role('alert')).to_have_text('서버 강화 충돌')
    expect(upgrade).to_be_disabled()
    upgrade.press('Enter')
    reads = calls.count(('GET', '/users/me'))
    altar.get_by_role('button', name='서버 상태 확인').tap()
    expect(altar.get_by_text('서버 상태를 다시 불러왔습니다.', exact=False)).to_be_visible()
    expect(upgrade).to_be_enabled()
    expect(stats.get_by_text('보유 기여도 1,000점', exact=True)).to_be_visible()
    expect(stats.get_by_text('클리커 Lv.1', exact=True)).to_be_visible()
    assert calls.count(('POST', '/clickers/upgrade')) == 1 and calls.count(('GET', '/users/me')) > reads
    print(f'PASS {page.viewport_size}: conflict, GET-only reconciliation, unchanged level and balance')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url')
    parser.add_argument('--chromium')
    parser.add_argument('--screenshots', type=Path)
    parser.add_argument('--production', action='store_true')
    args = parser.parse_args()
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=args.chromium)
        cases = [(1440, 900, i, True) for i in [1, 5, 10]] + [(320, 740, i, False) for i in [1, 5, 10]]
        cases += [(390, 844, 11, False), (667, 375, 5, False)]
        for width, height, level, desktop in cases:
            page = browser.new_page(viewport={'width': width, 'height': height}, has_touch=not desktop, reduced_motion='reduce')
            try:
                out = args.screenshots / f'{width}x{height}-lv{level:02}' if args.screenshots else None
                if out:
                    out.mkdir(parents=True, exist_ok=True)
                verify(page, args.url, level, desktop, out)
                if args.production:
                    expect(page.get_by_role('button', name='테스트 도구', exact=True)).to_have_count(0)
            finally:
                page.close()
        for width, height, desktop in [(1440, 900, True), (320, 740, False)]:
            with browser.new_page(viewport={'width': width, 'height': height}, has_touch=not desktop, reduced_motion='reduce') as page:
                verify_purchase_after_upgrade(page, args.url, desktop)
        with browser.new_page(viewport={'width': 667, 'height': 375}, has_touch=True, reduced_motion='reduce') as page:
            verify_upgrade_conflict(page, args.url)
        browser.close()
