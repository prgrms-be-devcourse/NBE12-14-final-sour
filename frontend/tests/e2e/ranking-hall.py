"""Check the service Hall of Fame with fixed ranking pages, a virtual clock and Chromium."""

import argparse
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from playwright.sync_api import expect, sync_playwright

LONG_NAME = '동해바다를품은국제미래기술융합캠퍼스대학교'
LATE_NAME = '페이지경계대학교'
PROFILE = {'id': 501, 'nickname': '검증 학생', 'email': 'student@example.test', 'role': 'USER', 'status': 'ACTIVE',
           'profileImageUrl': None, 'university': {'id': 8, 'name': '한양대학교', 'logoUrl': None},
           'department': {'id': 7, 'name': '공학과'}, 'clicker': {'level': 1, 'clickPower': 1, 'nextLevelRequiredScore': 200},
           'totalContribScore': 1000, 'totalAttackScore': 0}


def school(id_, name, rank=1, score=1234567):
    return {'universityId': id_, 'name': name, 'rank': rank, 'totalScore': score, 'rankChange': None, 'logoUrl': None}


def dept(id_, name, rank, logo=None):
    return {'departmentId': id_, 'name': name, 'rank': rank, 'totalScore': 900 // rank, 'rankChange': None, 'logoUrl': logo}


def person(id_, name, rank, image=None):
    return {'userId': id_, 'nickname': name, 'rank': rank, 'contribScore': 900 // rank, 'rankChange': None,
            'department': None, 'profileImageUrl': image}


def verify(page, url, touch, standalone, snapshot):
    requests = []
    broken_tail = True
    score = 1234567
    broken_image = url.rstrip('/') + '/missing-hall-image.png'

    def fixture(route):
        parsed = urlsplit(route.request.url)
        path = parsed.path.removeprefix('/api/v1')
        query = parse_qs(parsed.query)
        size = int(query.get('size', ['20'])[0])
        index = int(query.get('page', ['0'])[0])
        data = {'/auth/reissue': {'accessToken': 'test', 'expiresInSeconds': 3600}, '/users/me': PROFILE,
                '/map/universities': [], '/clickers': [], '/items/me/active': None, '/alliances/me': None}
        result = data.get(path)
        if path.startswith('/rankings/universities'):
            requests.append((path, index, size))
            if path.endswith('/departments'):
                rows, mine = [dept(7, '공학과', 1, broken_image), dept(12, '국문학과', 2), dept(13, '경제학과', 2)], {'myDepartment': None}
            elif path.endswith('/users'):
                rows = [person(501, '검증 학생', 1, broken_image), person(502, '개인 둘', 2), person(503, '개인 셋', 3), person(504, '개인 넷', 3)]
                mine = {'me': None}
            else:
                if index == 1 and broken_tail:
                    route.fulfill(status=500, json={'code': 'COMMON_INTERNAL_ERROR', 'message': '검증용 추가 페이지 오류'})
                    return
                rows = [school(701, LONG_NAME, score=score), school(702, '두 번째 대학교', score=score)]
                rows += [school(id_, f'대학교 {id_}', score=score) for id_ in range(703, 721)]
                # Simulate offset drift by repeating the last ID. The first copy wins.
                rows += [rows[-1], school(721, LATE_NAME, score=score), school(8, '한양대학교', 22, 100)]
                mine = {'myUniversity': rows[-1]}
            result = {**mine, 'items': rows[index * size:(index + 1) * size], 'page': index, 'size': size,
                      'totalCount': len(rows), 'hasNext': (index + 1) * size < len(rows)}
        route.fulfill(json={'code': 'SUCCESS', 'message': '', 'data': result})

    page.route('**/api/v1/**', fixture)
    image_failures = []

    def fail_image(route):
        image_failures.append(route.request.url)
        route.fulfill(status=404, body='fixture')

    page.route('**/missing-hall-image.png', fail_image)
    # Drive only the 3s carousel interval so React rendering, CSS animation and fetching use real clocks.
    page.add_init_script('''(() => {
        const set = window.setInterval.bind(window), clear = window.clearInterval.bind(window);
        const ticks = new Map();
        let id = 0;
        window.setInterval = (fn, delay, ...args) => {
            if (delay !== 3000) return set(fn, delay, ...args);
            ticks.set(--id, () => fn(...args));
            return id;
        };
        window.clearInterval = id => id < 0 ? ticks.delete(id) : clear(id);
        window.stepHall = () => {
            for (const fn of [...ticks.values()]) fn();
            return ticks.size;
        };
    })()''')
    page.goto(url, wait_until='networkidle')
    if touch:
        nav = page.get_by_role('navigation', name='주요 메뉴')
        if standalone:
            nav.get_by_role('button', name='지도', exact=True).tap()
            page.get_by_role('button', name='전국 지도', exact=True).tap()
        nav.get_by_role('button', name='순위', exact=True).tap()
        ranking = page
        if standalone:
            expect(ranking.get_by_role('heading', name='전국 랭킹 대항전')).to_be_visible()
    else:
        ranking = page.get_by_role('complementary', name='전국 경쟁 패널')
        ranking.get_by_role('tab', name='순위', exact=True).click()
    def count(index):
        return requests.count(('/rankings/universities', index, 20))
    hall = ranking.get_by_role('region', name='명예의 전당')
    current = hall.locator('[data-hall-current]')
    feedback = hall.get_by_role('status')
    expect(feedback).to_have_text('공동 순위를 모두 불러오지 못했어요.')
    expect(hall.get_by_role('heading', level=3)).to_have_text('공동 1위')
    expect(hall.get_by_text('· 1/20', exact=True)).to_be_visible()
    expect(current).to_contain_text(LONG_NAME)
    assert count(0) == 1 and count(1) == 1, requests
    broken_tail = False
    hall.get_by_role('button', name='다시 시도').click()
    expect(hall.get_by_text('· 1/21', exact=True)).to_be_visible()
    expect(feedback).to_have_count(0)
    expect(ranking.get_by_role('button', name='한양대학교 학교 메뉴', exact=True)).to_have_count(1)  # pinned row only
    assert count(1) == 2 and count(2) == 0, requests

    if not touch:
        assert page.evaluate('stepHall()') == 1
        expect(current).to_contain_text('두 번째 대학교')
        outgoing = hall.locator('.game-hall-outgoing')
        expect(outgoing).to_have_attribute('aria-hidden', 'true')
        expect(outgoing.locator('button')).to_have_count(0)
        expect(outgoing).to_have_count(0)
        opener = hall.get_by_role('button', name='두 번째 대학교 학교 메뉴')
        page.keyboard.press('Tab')
        opener.focus()
        assert opener.evaluate('el => el.matches(":focus-visible")')
        assert page.evaluate('stepHall()') == 1
        expect(opener).to_be_focused()
        opener.press('Enter')
        dialog = page.get_by_role('dialog', name='두 번째 대학교')
        expect(dialog.get_by_text('1,234,567점', exact=True)).to_be_visible()
        assert page.evaluate('stepHall()') == 0
        with page.expect_response(lambda response: '/rankings/universities?page=0&size=20' in response.url):
            score = 2000000
        expect(dialog.get_by_text('1,234,567점', exact=True)).to_be_visible()
        page.keyboard.press('Escape')
        expect(opener).to_be_focused()
        expect(current).to_contain_text('2,000,000점')
        hall.get_by_role('button', name='1위 일시정지').click()
        assert page.evaluate('stepHall()') == 0
    else:
        assert page.evaluate('stepHall()') == 0
        expect(current).to_contain_text(LONG_NAME)
        expect(hall.get_by_role('button', name='1위 재생')).to_be_visible()

    if not touch:
        expect(current).to_contain_text('두 번째 대학교')
        hall.get_by_role('button', name='1위 이전').click()
    expect(current).to_contain_text(LONG_NAME)
    assert current.evaluate('el => el.scrollWidth === el.clientWidth')
    assert current.locator('.game-hall-score').evaluate('''el =>
        el.clientHeight <= parseFloat(getComputedStyle(el).lineHeight) + 1
    '''), 'The score must not split its digits across lines'
    if snapshot:
        current.first.scroll_into_view_if_needed()
        page.screenshot(path=snapshot, animations='disabled')
    previous = hall.get_by_role('button', name='1위 이전')
    previous.tap() if touch else previous.click()
    expect(current).to_contain_text(LATE_NAME)
    assert current.evaluate('el => el.scrollWidth === el.clientWidth')
    assert hall.locator('button').evaluate_all('buttons => buttons.every(b => b.getBoundingClientRect().height >= 44)')
    count_before = count(1)
    more = ranking.get_by_role('button', name='더 보기' if standalone else '더 불러오기', exact=False)
    expect(more).to_contain_text('20 / 23')
    # Activate without scrolling to isolate the explicit button from the existing scroll threshold.
    more.dispatch_event('click')
    expect(ranking.get_by_role('button', name=f'{LATE_NAME} 학교 메뉴', exact=True)).to_have_count(2)
    assert count(1) > count_before and count(2) == 0, requests

    ranking.get_by_role('button', name='교내 학과', exact=True).click()
    expect(hall.get_by_role('heading', level=3)).to_have_text(['1위', '공동 2위'])
    expect(hall.locator('[data-hall-symbol="dept"]:visible')).to_have_count(2)
    assert image_failures == [broken_image]
    hall.get_by_role('button', name='2위 다음').click()
    expect(hall.locator('[data-hall-rank="2"] [data-hall-current]')).to_contain_text('경제학과')
    assert not any(path.endswith('/users') for path, _, _ in requests)
    ranking.get_by_role('button', name='교내 개인', exact=True).click()
    expect(hall.get_by_role('heading', level=3)).to_have_text(['1위', '2위', '공동 3위'])
    expect(hall.locator('[data-hall-symbol="personal"]:visible')).to_have_count(3)
    expect(hall.get_by_role('button', name='3위 다음')).to_be_visible()
    expect(hall.locator('[aria-live]')).to_have_count(0)
    assert image_failures == [broken_image, broken_image]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url')
    parser.add_argument('--chromium')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=args.chromium)
        for width, height, touch, standalone in ((1440, 900, False, False), (320, 740, True, False), (390, 844, True, True)):
            page = browser.new_page(viewport={'width': width, 'height': height}, has_touch=touch,
                                    reduced_motion='reduce' if touch else 'no-preference')
            try:
                verify(page, args.url, touch, standalone, args.out / f'{width}x{height}.png' if args.out else None)
                print(f'PASS {width}×{height}: ranks, page boundary/retry, controls, image fallback and listing')
            finally:
                page.close()
        browser.close()


if __name__ == '__main__':
    main()
