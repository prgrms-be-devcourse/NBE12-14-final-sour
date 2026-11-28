"""Chromium smoke test for the API-mode alliance dialog; use VITE_ALLIANCE_INVITATIONS=true."""

import sys
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright


def verify(browser, url, width, height):
    page = browser.new_page(viewport={'width': width, 'height': height}, reduced_motion='reduce')
    profile = {'id': 501, 'nickname': '학생', 'email': 'student@example.test', 'role': 'USER', 'status': 'ACTIVE',
               'profileImageUrl': None, 'university': {'id': 8, 'name': '한양대학교', 'logoUrl': None},
               'department': {'id': 1, 'name': '공학과'}, 'clicker': {'level': 1, 'clickPower': 1, 'nextLevelRequiredScore': 200},
               'totalContribScore': 1000, 'totalAttackScore': 0}
    alliance = {'id': 25, 'name': '새 연합', 'status': 'ACTIVE', 'durationHours': 24,
                'expiredAt': '2099-01-01T00:00:00Z', 'totalScore': 0}
    invite = {'id': 61, 'alliance': {'id': 25, 'name': '새 연합'}, 'university': profile['university'],
              'status': 'PENDING', 'approvalBlockReason': None, 'createdAt': '2026-11-27T00:00:00Z'}
    patches = []
    creates = []
    joined = False

    def fixture(route):
        nonlocal joined
        path = urlsplit(route.request.url).path.removeprefix('/api/v1')
        method = route.request.method
        data = {'/auth/reissue': {'accessToken': 'test', 'expiresInSeconds': 3600}, '/users/me': profile,
                '/alliances/me': alliance if joined else None, '/alliances/25/universities': [],
                '/alliances/25/invitations': {'items': [], 'nextCursor': None},
                '/alliances/invitations/me': {'items': [{**invite, 'status': 'ACCEPTED'} if joined else invite], 'nextCursor': None},
                '/map/universities': [], '/clickers': [], '/items/me/active': None,
                '/rankings/universities': {'items': [], 'myUniversity': None, 'hasNext': False, 'page': 0,
                                           'size': 20, 'totalCount': 0}}
        response_status = 200
        if method == 'PATCH' and path == '/alliances/invitations/61/approve':
            patches.append(path)
            joined = True
            result = {'id': 61, 'allianceId': 25, 'universityId': 8, 'status': 'ACCEPTED'}
        elif method == 'POST' and path == '/alliances':
            creates.append(route.request.post_data_json)
            joined = True
            result, response_status = alliance, 201
        elif path in data:
            result = data[path]
        else:
            route.fulfill(status=404, json={'code': 'NOT_FOUND', 'message': 'No browser test fixture'})
            return
        route.fulfill(status=response_status, json={'code': 'SUCCESS', 'message': '', 'data': result})

    try:
        page.route('**/api/v1/**', fixture)
        page.goto(url, wait_until='networkidle')
        if width >= 1024:
            page.get_by_role('complementary', name='전국 경쟁 패널').get_by_role('tab', name='연합 대결').click()
        else:
            page.get_by_role('button', name='연합·대결').click()
        form = page.get_by_role('form', name='연합 만들기')
        name = form.get_by_role('textbox', name='연합 이름')
        name.fill('미완성 이름')
        opener = form.get_by_role('button', name='연합 만들기')
        opener.click()
        dialog = page.get_by_role('dialog', name='연합 만들기 확인')
        expect(dialog.get_by_role('button', name='취소')).to_be_focused()
        page.keyboard.press('Escape')
        expect(dialog).to_be_hidden()
        expect(opener).to_be_focused()
        expect(name).to_have_value('미완성 이름')
        assert not creates

        page.get_by_role('tab', name='연합 초대').click()
        received = page.get_by_role('region', name='받은 연합 초대')
        received.get_by_role('button', name='새 연합 초대 승인').click()
        approval = page.get_by_role('dialog', name='초대 승인 확인')
        expect(approval.get_by_role('button', name='취소')).to_be_focused()
        approval.get_by_role('button', name='초대 승인').click()
        expect(page.get_by_text('연합에 가입했어요.')).to_be_visible()
        expect(received.get_by_text('새 연합 · 승인')).to_be_visible()
        expect(page.get_by_role('region', name='보낸 연합 초대')).to_be_visible()
        assert len(patches) == 1
        print(f'PASS {width}×{height}: modal focus, Escape/cancel, retained input and invitation approval')
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
