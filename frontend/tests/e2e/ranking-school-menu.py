"""Chromium API-mode smoke test; run Vite with VITE_ALLIANCE_INVITATIONS=true."""

import sys
from urllib.parse import parse_qs, urlsplit

from playwright.sync_api import expect, sync_playwright


def verify(browser, url, width, height):
    phone = width < 1024
    page = browser.new_page(viewport={'width': width, 'height': height}, has_touch=phone, reduced_motion='reduce')
    profile = {'id': 501, 'nickname': '학생', 'email': 'student@example.test', 'role': 'USER', 'status': 'ACTIVE',
               'profileImageUrl': None, 'university': {'id': 8, 'name': '한양대학교', 'logoUrl': None},
               'department': {'id': 1, 'name': '공학과'}, 'clicker': {'level': 1, 'clickPower': 1, 'nextLevelRequiredScore': 200},
               'totalContribScore': 1000, 'totalAttackScore': 0}
    alliance = {'id': 25, 'name': '테스트 연합', 'status': 'ACTIVE', 'joinable': True}
    rows = [(5, '대상대학교'), (6, '다른대학교'), (7, '세번째대학교'), (3, '방문대학교'), (8, '한양대학교')]
    reordered = False
    requests = []
    checks = []
    visits = []

    def fixture(route):
        parsed = urlsplit(route.request.url)
        path = parsed.path.removeprefix('/api/v1')
        data = {'/auth/reissue': {'accessToken': 'test', 'expiresInSeconds': 3600}, '/users/me': profile,
                '/map/universities': [], '/clickers': [], '/items/me/active': None}
        if path == '/rankings/universities':
            ranking = rows[1:4] + rows[:1] + rows[4:] if reordered and 'page' in parse_qs(parsed.query) else rows
            items = [{'universityId': id_, 'name': name, 'logoUrl': None, 'totalScore': 1000 - i,
                      'rank': i + 1, 'rankChange': 0} for i, (id_, name) in enumerate(ranking)]
            result = {'items': items, 'myUniversity': items[-1], 'hasNext': False, 'page': 0, 'size': 20, 'totalCount': len(items)}
        elif path.endswith('/alliance') and path.startswith('/universities/'):
            checks.append(path)
            result = alliance if path == ('/universities/5/alliance' if phone else '/universities/8/alliance') else None
        elif path in ['/alliances/25/invitations', '/alliances/25/applications'] and route.request.method == 'POST':
            requests.append((path, route.request.post_data_json))
            result = {'id': 61, 'allianceId': 25, 'universityId': 8 if phone else 5, 'status': 'PENDING'}
        elif path == '/universities/3':
            visits.append(path)
            result = {'id': 3, 'name': '방문대학교', 'logoUrl': None, 'totalScore': 997, 'rank': 4, 'rankChange': 0,
                      'memberCount': 1, 'topDepartments': [], 'topContributors': [], 'isMyUniversity': False, 'isMyAlly': False}
        elif path in data:
            result = data[path]
        else:
            route.fulfill(status=404, json={'code': 'NOT_FOUND', 'message': 'No browser test fixture'})
            return
        route.fulfill(status=200, json={'code': 'SUCCESS', 'message': '', 'data': result})

    def activate(button):
        if phone:
            button.tap()
        else:
            button.click()

    try:
        page.route('**/api/v1/**', fixture)
        page.goto(url, wait_until='networkidle')
        if phone:
            page.get_by_role('navigation', name='주요 메뉴').get_by_role('button', name='순위', exact=True).tap()
            ranking = page
        else:
            ranking = page.get_by_role('complementary', name='전국 경쟁 패널')
            ranking.get_by_role('tab', name='순위', exact=True).click()
        opener = ranking.get_by_role('button', name='대상대학교 학교 메뉴', exact=True).first
        dialog = page.get_by_role('dialog', name='대상대학교')
        expect(opener).to_be_visible()
        assert not checks
        if phone:
            opener.tap()
        else:
            opener.focus()
            page.keyboard.press('Enter')
        expect(dialog.get_by_role('button', name='닫기')).to_be_focused()
        page.keyboard.press('Escape')
        expect(dialog).to_be_hidden()
        expect(opener).to_be_focused()
        activate(opener)

        label = '연합 가입 신청' if phone else '연합 초대'
        action = dialog.get_by_role('button', name=label, exact=True)
        activate(action)
        expect(dialog.get_by_role('button', name='취소')).to_be_focused()
        activate(dialog.get_by_role('button', name='취소'))
        expect(action).to_be_focused()
        assert not requests
        activate(action)
        if not phone:
            with page.expect_response(lambda response: '/rankings/universities?' in response.url and 'page=0' in response.url):
                reordered = True
            expect(dialog).to_be_visible()
        activate(dialog.get_by_role('button', name='가입 신청 보내기' if phone else '초대 보내기'))
        expect(dialog.get_by_role('status')).to_have_text('연합 가입 신청을 보냈어요.' if phone else '연합 초대를 보냈어요.')
        assert requests == [('/alliances/25/applications', None) if phone else ('/alliances/25/invitations', {'universityId': 5})]
        assert set(checks) == {'/universities/5/alliance', '/universities/8/alliance'}
        activate(dialog.get_by_role('button', name='닫기'))
        activate(ranking.get_by_role('button', name='방문대학교 학교 메뉴', exact=True))
        with page.expect_response(lambda response: urlsplit(response.url).path == '/api/v1/universities/3'):
            activate(page.get_by_role('dialog', name='방문대학교').get_by_role('button', name='캠퍼스로 이동'))
        assert visits == ['/universities/3']
        print(f'PASS {width}×{height}: {label}, keyboard/touch, cancel, campus visit')
    finally:
        page.close()


if __name__ == '__main__':
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=sys.argv[2] if len(sys.argv) > 2 else None)
        try:
            for viewport in [(1440, 900), (390, 844)]:
                verify(browser, sys.argv[1], *viewport)
        finally:
            browser.close()
