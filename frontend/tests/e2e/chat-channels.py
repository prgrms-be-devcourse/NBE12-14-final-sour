"""API chat layout, focus, and alliance destinations with fixed HTTP and STOMP replies."""

import argparse
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright

PROFILE = {'id': 501, 'nickname': '검증 학생', 'email': 'student@example.test', 'role': 'USER', 'status': 'ACTIVE',
           'profileImageUrl': None, 'university': {'id': 8, 'name': '한양대학교', 'logoUrl': None},
           'department': {'id': 1, 'name': '공학과'}, 'clicker': {'level': 1, 'clickPower': 1, 'nextLevelRequiredScore': 200},
           'totalContribScore': 1000, 'totalAttackScore': 0}
ALLIANCE = {'id': 25, 'name': '테스트 연합', 'status': 'ACTIVE', 'durationHours': 24,
            'expiredAt': '2099-01-01T00:00:00Z', 'totalScore': 0}
CREATE, SEARCH = '연합 만들기', '가입할 연합 찾기'


def check_header(scope, desktop):
    heading = scope.get_by_role('heading', name='교내 채팅', exact=True)
    header = heading.locator('..')
    expect(header.get_by_role('button')).to_have_count(2 if desktop else 3)
    assert header.evaluate('''header => {
      const title = header.querySelector('h2');
      const actions = [...header.querySelectorAll('button')];
      const rect = element => element.getBoundingClientRect();
      const [left, right] = [rect(header).left, rect(header).right];
      const last = rect(actions.at(-1));
      return rect(title).left - left < 12 && right - last.right < 12 &&
        [title, ...actions].every(element => element.querySelector('svg[aria-hidden="true"]')) &&
        actions.every((action, index) => {
          const a = rect(action);
          return a.width >= 44 && a.height >= 44 && a.left >= left && a.right <= right &&
            [title, ...actions.slice(0, index)].every(b => {
              const r = rect(b);
              return a.left >= r.right || a.right <= r.left || a.top >= r.bottom || a.bottom <= r.top;
            });
        });
    }'''), 'Header clips, overlaps, loses icons, or misaligns its actions'


def verify(page, url, desktop, screenshot):
    membership = 'joined'
    subscriptions, sends, mutations = [], [], []

    def fixture(route):
        path = urlsplit(route.request.url).path.removeprefix('/api/v1')
        if path.startswith('/alliances') and route.request.method != 'GET':
            mutations.append(path)
        if path == '/alliances/me' and membership == 'error':
            route.fulfill(status=500, json={'code': 'COMMON_INTERNAL_ERROR', 'message': '연합 조회 실패'})
            return
        data = {'/auth/reissue': {'accessToken': 'test', 'expiresInSeconds': 3600}, '/users/me': PROFILE,
                '/alliances/me': ALLIANCE if membership == 'joined' else None, '/alliances/25': ALLIANCE,
                '/alliances/25/universities': [], '/alliances': {'items': [], 'nextCursor': None},
                '/map/universities': [], '/clickers': [], '/items/me/active': None,
                '/forbidden-words': {'words': ['예시 금칙어']},
                '/rankings/universities': {'items': [], 'myUniversity': None, 'hasNext': False, 'page': 0,
                                           'size': 20, 'totalCount': 0}}
        if path.startswith('/chat/'):
            room = 'ALLIANCE#25' if '/alliances/' in path else 'UNIV#8'
            data[path] = {'items': [{'messageId': i, 'roomId': room, 'userId': 2, 'nickname': '학생',
                                    'universityName': '한양대학교', 'content': f'지난 대화 {i}',
                                    'createdAt': '2026-11-28T10:00:00Z'} for i in range(20, 0, -1)],
                          'nextCursor': None, 'hasNext': False}
        if path in data:
            route.fulfill(json={'code': 'SUCCESS', 'message': '', 'data': data[path]})
        else:
            route.fulfill(status=404, json={'code': 'NOT_FOUND', 'message': 'Missing browser fixture'})

    def socket(ws):
        def receive(frame):
            if isinstance(frame, bytes):
                frame = frame.decode()
            headers, _, body = frame.partition('\n\n')
            command, *fields = headers.strip().splitlines() if headers.strip() else ['']
            if command in {'CONNECT', 'STOMP'}:
                ws.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\x00')
            if command in {'SUBSCRIBE', 'SEND'}:
                destination = dict(field.split(':', 1) for field in fields)['destination']
                if command == 'SUBSCRIBE' and destination.startswith('/topic/chat/'):
                    subscriptions.append(destination)
                if command == 'SEND':
                    sends.append((destination, json.loads(body.rstrip('\x00'))['content']))
        ws.on_message(receive)

    def activate(control):
        control.press('Enter') if desktop else control.tap()

    def open_chat():
        if desktop:
            scope = page.get_by_role('complementary', name='학교 패널')
            activate(scope.get_by_role('tab', name='채팅'))
            return scope
        activate(page.get_by_title('채팅창 열기'))
        return page.get_by_role('dialog', name='채팅', exact=True)

    def switch(scope, target):
        activate(scope.get_by_role('button', name=f'다른 채널: {target} 열기'))
        expect(scope.get_by_role('heading', name=target, exact=True)).to_be_visible()

    def words_round_trip(scope, channel):
        words = scope.get_by_role('button', name='금칙어', exact=True)
        activate(words)
        expect(scope.get_by_role('heading', name='공통 금칙어 목록')).to_be_focused()
        expect(scope.get_by_text('예시 금칙어')).to_be_visible()
        expect(scope.get_by_role('log')).to_have_count(0)
        activate(scope.get_by_role('button', name='채팅으로 돌아가기'))
        expect(scope.get_by_role('heading', name=channel, exact=True)).to_be_visible()
        expect(words).to_be_focused()

    page.route('**/api/v1/**', fixture)
    page.route_web_socket('**/ws', socket)
    page.goto(url, wait_until='networkidle')
    chat = open_chat()
    school, union = '교내 채팅', '연합 채팅'
    check_header(chat, desktop)
    words_round_trip(chat, school)
    input_box = chat.get_by_role('textbox', name='채팅 메시지')
    expect(input_box).to_be_enabled()
    input_box.fill('교내 초안')
    log = chat.get_by_role('log', name='교내 메시지')
    expect(log.get_by_text('지난 대화 1', exact=True)).to_be_attached()
    log.evaluate('node => { node.scrollTop = 88; node.dispatchEvent(new Event("scroll")); }')
    expect(chat.get_by_role('button', name='최신 메시지로')).to_be_visible()
    offset = log.evaluate('node => node.scrollTop')
    switch(chat, union)
    expect(input_box).to_be_enabled()
    for cta in [CREATE, SEARCH]:
        expect(chat.get_by_role('button', name=cta)).to_have_count(0)
    input_box.fill('연합 초안')
    words_round_trip(chat, union)
    expect(input_box).to_have_value('연합 초안')
    activate(chat.get_by_role('button', name='전송'))
    switch(chat, school)
    expect(input_box).to_have_value('교내 초안')
    assert offset > 0 and abs(log.evaluate('node => node.scrollTop') - offset) <= 1
    activate(chat.get_by_role('button', name='전송'))
    expect(input_box).to_have_value('')
    assert sends == [('/app/chat/alliance', '연합 초안'), ('/app/chat/university', '교내 초안')], sends
    assert subscriptions == ['/topic/chat/universities/8', '/topic/chat/alliances/25'], subscriptions

    membership = 'error'
    switch(chat, union)
    expect(chat.get_by_role('alert').filter(has_text='연합 조회 실패')).to_be_visible()
    for cta in [CREATE, SEARCH]:
        expect(chat.get_by_role('button', name=cta)).to_have_count(0)
    membership = 'none'
    activate(chat.get_by_role('button', name='연합 다시 확인'))
    expect(chat.get_by_text(re.compile('우리 학교가 속한 연합이 없어요'))).to_be_visible()
    if screenshot:
        page.screenshot(path=screenshot)
    activate(chat.get_by_role('button', name=CREATE, exact=True))
    destination = page.get_by_role('complementary', name='전국 경쟁 패널') if desktop else page
    form = destination.get_by_role('form', name=CREATE)
    expect(form).to_be_focused()
    name = form.get_by_role('textbox', name='연합 이름')
    name.fill('작성 중인 연합 이름')

    if not desktop:
        expect(chat).to_be_hidden()
        activate(destination.get_by_role('button', name='이전 화면으로 돌아가기'))
        chat = open_chat()
        switch(chat, union)
    activate(chat.get_by_role('button', name=SEARCH, exact=True))
    expect(destination.get_by_role('heading', name='연합 찾기', exact=True)).to_be_focused()
    destination.get_by_role('textbox', name='연합 ID', exact=True).fill('25')
    activate(destination.get_by_role('button', name='찾기', exact=True))
    expect(destination.get_by_role('button', name='이 연합에 가입 신청')).to_be_visible()
    if desktop:
        activate(chat.get_by_role('button', name=CREATE, exact=True))
        expect(form).to_be_focused()
        expect(name).to_have_value('작성 중인 연합 이름')
        activate(destination.get_by_role('tab', name='연합 찾기', exact=True))
        expect(destination.get_by_role('textbox', name='연합 ID', exact=True)).to_have_value('25')
    else:
        expect(chat).to_be_hidden()
        activate(destination.get_by_role('button', name='이전 화면으로 돌아가기'))
        chat = open_chat()
        activate(chat.get_by_role('button', name='채팅창 닫기'))
        expect(chat).to_be_hidden()
        expect(page.get_by_title('채팅창 열기')).to_be_focused()
    assert subscriptions.count('/topic/chat/alliances/25') == 1, subscriptions
    assert not mutations, mutations
    print(f'PASS {page.viewport_size}: channels, focus, drafts, scroll, STOMP sends, membership states and both destinations')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url')
    parser.add_argument('--chromium')
    parser.add_argument('--screenshots', type=Path)
    args = parser.parse_args()
    if args.screenshots:
        args.screenshots.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=args.chromium)
        for width, height, desktop in [(1440, 900, True), (1024, 720, True), (320, 740, False), (667, 375, False)]:
            page = browser.new_page(viewport={'width': width, 'height': height}, has_touch=not desktop, reduced_motion='reduce')
            try:
                screenshot = args.screenshots / f'{width}x{height}.png' if args.screenshots else None
                verify(page, args.url, desktop, screenshot)
            finally:
                page.close()
        browser.close()
