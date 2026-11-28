"""Chromium regression for the shared profile bar (Vite API mode, stubbed HTTP)."""

import sys
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright

NICKNAME = '아주긴닉네임' * 7
BRAND = '대대대 / 대학교 대 대학교 - 캠퍼스 대항전'
ITEMS = [('👤', '내 정보'), ('💬', '문의'), ('🚪', '로그아웃')]


def verify_service_brand(page, header, trigger, activate, tools):
    brand = header.get_by_role('button', name='대대대 내 캠퍼스')
    expect(brand).to_have_text(BRAND, use_inner_text=True)
    logo = brand.locator('img[alt=""]')
    expect(logo).to_have_attribute('src', '/favicon.svg')
    expect(logo).to_be_visible()
    assert logo.evaluate('image => image.complete && image.naturalWidth > 0')
    expect(trigger).to_have_accessible_name(f'프로필 · {NICKNAME}')
    expect(trigger.locator('svg[aria-hidden="true"]')).to_be_visible()
    tool_button = header.get_by_role('button', name='테스트 도구', exact=True)
    if tools:
        expect(tool_button.locator('svg[aria-hidden="true"]')).to_be_visible()
        activate(tool_button)
        expect(page.get_by_role('dialog', name='테스트 도구')).to_be_visible()
        page.keyboard.press('Escape')
        expect(tool_button).to_be_focused()
    else:
        expect(tool_button).to_have_count(0)
    page.evaluate('document.fonts.ready')
    assert header.evaluate('''header => {
        const brand = header.querySelector('.game-brand').getBoundingClientRect();
        const actions = header.querySelector('.game-top-bar-actions').getBoundingClientRect();
        const copy = header.querySelector('.game-brand-copy');
        return copy.scrollWidth <= copy.clientWidth && brand.right <= innerWidth && actions.right <= innerWidth &&
            (brand.right <= actions.left || brand.bottom <= actions.top) && header.scrollWidth <= header.clientWidth;
    }''')


def verify_back_and_guestbook(page, activate, guestbook_reads, touch, height):
    activate(page.get_by_role('button', name='뒤로가기'))
    campus = page.locator('[data-game-layout="my-campus"]')
    expect(campus).to_be_visible()
    expect(campus.get_by_role('main')).to_be_focused()
    expect(page.get_by_text('로그인 · 서버 연동')).to_have_count(0)
    if touch and height > 480:
        assert page.locator('[data-phone-shell="full"]').evaluate('shell => Math.abs(shell.getBoundingClientRect().bottom - innerHeight) <= 1')

    activate(page.get_by_role('button', name='우리 학교 방명록 보기'))
    refresh = page.get_by_role('button', name='방명록 새로고침', exact=True)
    expect(refresh).to_be_enabled()
    expect(refresh.locator('svg[aria-hidden="true"]')).to_be_visible()
    assert guestbook_reads == ['GET']
    with page.expect_response(lambda response: urlsplit(response.url).path.endswith('/universities/8/guestbooks')):
        activate(refresh)
    expect(refresh).to_be_enabled()
    assert guestbook_reads == ['GET', 'GET']
    if touch:
        activate(page.get_by_role('button', name='방명록 닫기'))


def verify(browser, url, width, height, touch=False, tools=False):
    page = browser.new_page(viewport={'width': width, 'height': height}, has_touch=touch, reduced_motion='reduce')
    profile = {'id': 501, 'nickname': NICKNAME, 'email': 'student@example.test', 'role': 'USER', 'status': 'ACTIVE',
               'profileImageUrl': None, 'university': {'id': 8, 'name': '한양대학교', 'logoUrl': None},
               'department': {'id': 1, 'name': '공학과'}, 'clicker': {'level': 1, 'clickPower': 1, 'nextLevelRequiredScore': 200},
               'totalContribScore': 1000, 'totalAttackScore': 0}
    writes = []
    guestbook_reads = []

    def fixture(route):
        path = urlsplit(route.request.url).path.removeprefix('/api/v1')
        data = {'/auth/reissue': {'accessToken': 'test', 'expiresInSeconds': 3600}, '/users/me': profile,
                '/map/universities': [], '/clickers': [], '/items/me/active': None,
                '/rankings/universities': {'items': [], 'myUniversity': None, 'hasNext': False, 'page': 0,
                                           'size': 20, 'totalCount': 0},
                '/supports/me': {'items': [], 'page': 0, 'size': 20, 'totalCount': 0, 'hasNext': False}}
        if path == '/universities/8/guestbooks':
            guestbook_reads.append(route.request.method)
            data[path] = {'items': [], 'nextCursor': None, 'hasNext': False}
        if path == '/auth/logout':
            writes.append(route.request.method)
            if len(writes) == 1:
                route.fulfill(status=500, json={'code': 'COMMON_INTERNAL_SERVER_ERROR', 'message': 'Fixture failure'})
                return
            route.fulfill(status=204)
        elif path in data:
            route.fulfill(status=200, json={'code': 'SUCCESS', 'message': '', 'data': data[path]})
        else:
            route.fulfill(status=404, json={'code': 'NOT_FOUND', 'message': 'No browser test fixture'})

    def activate(control):
        control.tap() if touch else control.press('Enter')

    try:
        page.route('**/api/v1/**', fixture)
        page.goto(url, wait_until='networkidle')
        header = page.get_by_role('banner').filter(has=page.get_by_role('button', name='대대대 내 캠퍼스'))
        trigger = header.locator('summary')
        verify_service_brand(page, header, trigger, activate, tools)

        def typography():
            return trigger.evaluate('''trigger => {
                const {fontFamily, fontSize, fontWeight, lineHeight} = getComputedStyle(trigger);
                const label = trigger.querySelector('.game-profile-label');
                const name = trigger.querySelector('.game-profile-name');
                const rect = trigger.getBoundingClientRect();
                const center = el => { const r = el.getBoundingClientRect(); return (r.top + r.bottom) / 2; };
                return {fontFamily, fontSize, fontWeight, lineHeight,
                        inBounds: rect.height >= 44 && rect.left >= 0 && rect.right <= window.innerWidth,
                        centered: Math.abs(center(trigger) - center(label)) < 1 && Math.abs(center(trigger) - center(name)) < 1,
                        fitsLabel: label.clientWidth >= label.scrollWidth,
                        clippedName: name.clientWidth < name.scrollWidth,
                        ellipsis: getComputedStyle(name).textOverflow};
            }''')

        def header_styles():
            return header.evaluate('''header => {
                const elements = [header.querySelector('.game-brand'), header.querySelector('.game-profile-trigger'),
                    header.querySelector('.game-workspace-action')].filter(Boolean);
                return elements.map(el => {
                    const s = getComputedStyle(el), r = el.getBoundingClientRect();
                    return {fontFamily: s.fontFamily, fontSize: s.fontSize, fontWeight: s.fontWeight, lineHeight: s.lineHeight,
                            visible: r.width >= 44 && r.height >= 44 && r.left >= 0 && r.right <= innerWidth};
                });
            }''')

        def assert_shell():
            shell = page.locator('[data-phone-shell]')
            expect(shell).to_have_count(1)
            expect(shell.locator('[data-phone-status-bar]')).to_have_count(0)
            if shell.get_attribute('data-phone-shell') == 'framed':
                expect(shell.locator('[data-phone-home-bar]')).to_have_count(1)
                gap = shell.evaluate('''shell => shell.firstElementChild.getBoundingClientRect().top
                    - shell.getBoundingClientRect().top - parseFloat(getComputedStyle(shell).borderTopWidth)''')
                assert abs(gap) <= 1, gap
            else:
                expect(shell.locator('[data-phone-home-bar]')).to_have_count(0)

        styles = header_styles()
        assert len(styles) == (3 if tools else 2), styles
        assert all(style == styles[0] for style in styles), styles
        assert all(style['visible'] for style in styles), styles
        before = typography()
        assert before['inBounds'] and before['centered'] and before['fitsLabel'], before
        assert before['fontSize'] == '17px' and before['fontWeight'] == '700' and before['lineHeight'] == '21.25px', before
        assert before['ellipsis'] == 'ellipsis', before
        if width <= 390 or width >= 1024:
            assert before['clippedName'] == (width <= 390), before

        menu = header.locator('.game-profile-actions')
        expect(menu).to_be_hidden()
        activate(trigger)
        expect(menu).to_be_visible()
        buttons = menu.get_by_role('button')
        expect(buttons).to_have_count(3)
        icon_lefts = []
        for index, (emoji, accessible_name) in enumerate(ITEMS):
            button = buttons.nth(index)
            expect(button).to_have_accessible_name(accessible_name)
            icon = button.locator('span[aria-hidden="true"]')
            expect(icon).to_have_text(emoji)
            icon_box, button_box = icon.bounding_box(), button.bounding_box()
            assert button_box['height'] >= 44
            assert abs(icon_box['y'] + icon_box['height'] / 2 - button_box['y'] - button_box['height'] / 2) < 1
            icon_lefts.append(icon_box['x'])
        assert len(set(icon_lefts)) == 1
        menu_box = menu.bounding_box()
        assert menu_box['x'] >= 0 and menu_box['x'] + menu_box['width'] <= width

        if not touch:
            page.keyboard.press('Tab')
            expect(buttons.first).to_be_focused()
            page.keyboard.press('Escape')
            expect(menu).to_be_hidden()
            expect(trigger).to_be_focused()
            activate(trigger)
        activate(menu.get_by_role('button', name='내 정보', exact=True))
        expect(page.get_by_role('heading', name='내 정보', exact=True)).to_be_visible()
        assert_shell()
        assert header_styles() == styles
        expect(menu).to_be_hidden()
        after = typography()
        for key in ('fontFamily', 'fontSize', 'fontWeight', 'lineHeight'):
            assert after[key] == before[key], (key, before, after)
        assert after['inBounds'] and after['centered'] and after['fitsLabel'], after

        verify_back_and_guestbook(page, activate, guestbook_reads, touch, height)

        activate(trigger)
        activate(menu.get_by_role('button', name='문의', exact=True))
        expect(page.get_by_role('heading', name='내 문의', exact=True)).to_be_visible()
        assert_shell()
        assert header_styles() == styles
        expect(menu).to_be_hidden()
        activate(trigger)
        activate(menu.get_by_role('button', name='로그아웃', exact=True))
        expect(menu.get_by_role('alert')).to_contain_text('로그아웃하지 못했어요.')
        activate(menu.get_by_role('button', name='로그아웃', exact=True))
        expect(trigger).to_have_count(0)
        assert writes == ['POST', 'POST']
        print(f'PASS {width}×{height}: brand, typography, menu, campus return, guestbook refresh and logout')
        return before['fontFamily']
    finally:
        page.close()


def verify_guest(browser, url, width, height):
    page = browser.new_page(viewport={'width': width, 'height': height}, has_touch=width < 500)
    def fixture(route):
        path = urlsplit(route.request.url).path
        if path.endswith('/auth/reissue'):
            route.fulfill(status=401, json={'code': 'AUTH_INVALID_REFRESH_TOKEN', 'message': 'signed out'})
        else:
            route.fulfill(status=200, json={'code': 'SUCCESS', 'message': '', 'data': {'items': [], 'hasNext': False, 'page': 0, 'size': 20, 'totalCount': 0}})
    try:
        page.route('**/api/v1/**', fixture)
        page.goto(url, wait_until='networkidle')
        page.get_by_role('button', name='비회원 문의').click()
        expect(page.get_by_role('heading', name='비회원 문의')).to_be_visible()
        shell = page.locator('[data-phone-shell]')
        expect(shell).to_have_attribute('data-phone-shell', 'full')
        expect(shell.locator('[data-phone-status-bar], [data-phone-home-bar]')).to_have_count(0)
        gap = shell.evaluate('''shell => shell.firstElementChild.getBoundingClientRect().top
            - shell.getBoundingClientRect().top - parseFloat(getComputedStyle(shell).borderTopWidth)''')
        assert abs(gap) <= 1, gap
        page.get_by_label('받을 이메일').fill('guest@example.com')
        page.get_by_label('제목').fill('문의 제목')
        page.get_by_label('본문').fill('문의 내용')
        assert page.get_by_label('제목').input_value() == '문의 제목'
        print(f'PASS guest {width}×{height}: shell and form')
    finally:
        page.close()


if __name__ == '__main__':
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=sys.argv[2] if len(sys.argv) > 2 else None)
        try:
            tools = len(sys.argv) > 3 and sys.argv[3] == 'dev'
            fonts = {verify(browser, sys.argv[1], *viewport, tools=tools) for viewport in [(1440, 900), (320, 740, True), (667, 375, True)]}
            assert len(fonts) == 1, fonts
            for width, height in [(1440, 900), (320, 740)]:
                verify_guest(browser, sys.argv[1], width, height)
        finally:
            browser.close()
