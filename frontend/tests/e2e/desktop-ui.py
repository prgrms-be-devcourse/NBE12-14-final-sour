"""Capture PC UI measurements; --check exits 1 when a visual invariant fails.

Run against a local Vite dev server with VITE_API_MODE=mock (`npm run dev:mock`). For an API shelf
control, pass --api-url for a second local server with VITE_API_MODE=api (`npm run dev`).
Requires Python Playwright and Chromium; not part of npm test.
"""

import argparse
import json
from itertools import pairwise
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright

VIEWPORTS = [(1024, 720, 1), (1280, 720, 1), (1440, 900, 1), (1920, 1080, 1), (2560, 1440, 2)]
CREAM = 'rgb(253, 245, 223)'
TOLERANCE = 1  # CSS px; DOM geometry can round fractional borders


def ready(page, url):
    page.goto(url)
    panel = page.get_by_role('complementary', name='전국 경쟁 패널')
    expect(panel).to_be_visible()
    page.evaluate('async () => { await document.fonts.ready; }')
    return panel


def snapshot(page, path):
    page.screenshot(path=path, animations='disabled')


def menu_metrics(controls):
    return controls.evaluate_all('''controls => controls.map(button => {
        const rect = button.getBoundingClientRect();
        const icon = button.querySelector('.game-nav-icon');
        const label = button.querySelector('.game-nav-label');
        const labelRect = label.getBoundingClientRect();
        const range = document.createRange();
        range.selectNodeContents(label);
        const lines = [...range.getClientRects()];
        const style = getComputedStyle(button);
        return {label: label.textContent, lines: lines.length,
            fits: rect.left >= 0 && rect.right <= innerWidth + 1 && lines.length > 0 && lines.length <= 2 && lines.every(line =>
                line.left >= rect.left - 1 && line.right <= rect.right + 1 && line.bottom <= rect.bottom + 1),
            iconLeft: icon.getBoundingClientRect().width > 0 && icon.getBoundingClientRect().right <= labelRect.left + 1,
            height: rect.height, fontSize: parseFloat(style.fontSize), raised: style.boxShadow !== 'none',
        };
    })''')


def mock_menu_flow(page, panel, path):
    left = page.get_by_role('complementary', name='학교 패널')
    detail = left.get_by_role('tab', name='상세')
    chat = left.get_by_role('tab', name='채팅')
    guestbook = left.get_by_role('tab', name='방명록')
    detail.focus()
    page.keyboard.press('ArrowRight')
    expect(chat).to_be_focused()
    expect(detail).to_have_attribute('aria-selected', 'true')
    assert chat.evaluate("e => getComputedStyle(e).outlineStyle") == 'solid'
    assert chat.evaluate("e => getComputedStyle(e).backgroundColor") != detail.evaluate(
        "e => getComputedStyle(e).backgroundColor")
    page.keyboard.press('End')
    expect(guestbook).to_be_focused()
    page.keyboard.press('Home')
    expect(detail).to_be_focused()
    page.keyboard.press('ArrowRight')
    page.keyboard.press('Enter')
    expect(chat).to_have_attribute('aria-selected', 'true')
    page.keyboard.press('Home')
    page.keyboard.press('Space')
    expect(detail).to_have_attribute('aria-selected', 'true')

    map_tab = panel.get_by_role('tab', name='지도', exact=True)
    resting_shadow = map_tab.evaluate('e => getComputedStyle(e).boxShadow')
    rect = map_tab.bounding_box()
    assert rect is not None
    page.mouse.move(rect['x'] + rect['width'] / 2, rect['y'] + rect['height'] / 2)
    page.mouse.down()
    assert map_tab.evaluate('e => getComputedStyle(e).boxShadow') != resting_shadow
    page.mouse.up()

    panel.get_by_role('button', name='KAIST 전국 1위').click()
    visit = panel.get_by_role('button', name='KAIST 방문', exact=True)
    menus = menu_metrics(visit)
    visit.click()
    expect(page.get_by_test_id('visit-tag')).to_be_visible()
    guestbook.click()
    visited_book = left.get_by_role('tabpanel', name='방명록')
    expect(visited_book.get_by_role('heading', name='KAIST 방명록')).to_be_visible()
    expect(visited_book.get_by_text('SOON', exact=True)).to_be_visible()
    expect(visited_book.get_by_placeholder('한마디 남기기 (최대 50자)')).to_be_visible()
    detail.click()
    page.get_by_role('button', name='상점', exact=True).click()
    back = page.get_by_role('button', name='캠퍼스로 돌아가기')
    menus += menu_metrics(back)
    snapshot(page, path)
    back.click()
    expect(page.get_by_test_id('visit-tag')).to_be_visible()
    return menus


def shop_metrics(page, path=None, *, open_shop=True, api=False):
    if open_shop:
        page.get_by_role('button', name='상점', exact=True).click()
    shop = page.locator('[data-game-layout="shop"]')
    expect(shop.get_by_text('아이템 진열대' if api else '원목 선반 진열대', exact=True)).to_be_visible()
    reference = shop.get_by_role('region', name='활성 효과 상세') if api else shop.get_by_text('내 클리커 강화 제단', exact=True)
    cards = shop.locator('.game-shelf-card')
    expect(cards.first).to_be_visible()
    cards.first.scroll_into_view_if_needed()
    reference_edges = reference.evaluate('''e => {
        const {left, right} = e.closest('.rpg-box').getBoundingClientRect();
        return {left, right};
    }''')
    edges = cards.evaluate_all('''cards => {
        const rects = cards.map(card => card.getBoundingClientRect());
        const firstRow = rects.filter(rect => rect.top < rects[0].bottom && rect.bottom > rects[0].top);
        const grid = cards[0].parentElement;
        return {
            count: cards.length, firstLeft: rects[0].left,
            rightmost: firstRow.length > 1 ? firstRow[1].right : grid.getBoundingClientRect().right,
            firstLayoutLeft: cards[0].offsetParent.getBoundingClientRect().left + cards[0].offsetLeft,
            columns: getComputedStyle(grid).gridTemplateColumns.split(' ').length,
            firstScale: getComputedStyle(cards[0]).scale,
            inner: cards.map(card => {
                const bounds = card.getBoundingClientRect();
                const icon = card.querySelector('.game-shelf-card-body').firstElementChild.getBoundingClientRect();
                const copy = card.querySelector('.game-shelf-card-copy').getBoundingClientRect();
                const paddingX = parseFloat(getComputedStyle(card).paddingLeft) + card.clientLeft;
                const paddingY = parseFloat(getComputedStyle(card).paddingTop) + card.clientTop;
                return Math.abs(icon.top - bounds.top - paddingY) <= 1
                    && Math.abs(icon.left - bounds.left - paddingX) <= 1
                    && Math.abs(copy.top - icon.top) <= 1 && copy.left > icon.right;
            }).every(Boolean),
        };
    }''')
    if path is not None:
        snapshot(page, path)
    left_delta = edges['firstLeft'] - reference_edges['left']
    right_delta = edges['rightmost'] - reference_edges['right']
    return {**edges, 'reference': reference_edges, 'leftDelta': left_delta, 'rightDelta': right_delta,
            'aligned': edges['columns'] == 2 and edges['inner']
                       and max(abs(left_delta), abs(right_delta)) <= TOLERANCE}


def wrapping_text(locator):
    return locator.evaluate('''node => {
        const range = document.createRange();
        range.selectNodeContents(node);
        const lines = [...range.getClientRects()];
        const box = node.getBoundingClientRect();
        const card = node.closest('button').getBoundingClientRect();
        return { lines: lines.length, fits: lines.every(line => line.left >= box.left - 1 && line.right <= box.right + 1
            && line.top >= box.top - 1 && line.bottom <= box.bottom + 1 && line.right <= card.right && line.bottom <= card.bottom) };
    }''')


def mock_purchase(page):
    card = page.get_by_role('button', name='커피 한 잔 구매 선택')
    dialog = page.get_by_role('dialog', name='아이템 구매 확인')
    card.click()
    expect(dialog.get_by_text('구매 후 기여도 18,440점.', exact=False)).to_be_visible()
    dialog.get_by_role('button', name='아니오').click()
    expect(dialog).to_have_count(0)
    card.click()
    expect(dialog.get_by_text('구매 후 기여도 18,440점.', exact=False)).to_be_visible()
    dialog.get_by_role('button', name='구매 및 사용').click()
    expect(dialog).to_have_count(0)
    shop = page.locator('[data-game-layout="shop"]')
    expect(shop.get_by_text('커피 한 잔 ·', exact=False)).to_contain_text('현재 클릭당 60점')


REGIONS = ['전체', '수도권', '강원', '충청', '영남', '호남', '제주']
SEA = 'rgb(56, 189, 248)'


def chip_metrics(regions):
    metrics = regions.evaluate('''group => {
        const box = group.getBoundingClientRect();
        const right = box.left + group.clientWidth;
        const buttons = [...group.querySelectorAll('button')].map(button => {
            const r = button.getBoundingClientRect();
            const style = getComputedStyle(button);
            const label = document.createRange();
            label.selectNodeContents(button);
            const text = label.getBoundingClientRect();
            return {label: button.textContent.trim(), x: r.left, y: r.top, right: r.right, bottom: r.bottom, width: r.width,
                textFits: text.left >= r.left + parseFloat(style.borderLeftWidth) - .5
                    && text.right <= r.right - parseFloat(style.borderRightWidth) + .5,
                clearance: Math.min(r.left - box.left, right - r.right, r.top - box.top, box.bottom - r.bottom)};
        });
        return {width: group.clientWidth, scrollWidth: group.scrollWidth, scrollLeft: group.scrollLeft,
            display: getComputedStyle(group).display, rows: new Set(buttons.map(b => b.y)).size, buttons};
    }''')
    assert [chip['label'] for chip in metrics['buttons']] == REGIONS
    metrics['allVisible'] = metrics['width'] == metrics['scrollWidth'] and metrics['scrollLeft'] == 0 \
        and all(chip['textFits'] and chip['clearance'] >= 4 - 0.1 for chip in metrics['buttons'])
    first, *rest = metrics['buttons']
    upper, lower = rest[:3], rest[3:]
    metrics['gridFits'] = (
        metrics['display'] == 'grid' and metrics['rows'] == 2 and metrics['allVisible']
        and all(abs(chip['y'] - first['y']) <= TOLERANCE for chip in upper)
        and all(abs(chip['bottom'] - first['bottom']) <= TOLERANCE for chip in lower)
        # Keep at least 7px between the 3px shadow and its neighbor's 4px focus outline.
        and upper[0]['x'] - first['right'] >= 7 - 0.1
        and all(b['x'] - a['right'] >= 7 - 0.1 for a, b in pairwise(upper))
        and all(abs(chip['width'] - upper[0]['width']) <= TOLERANCE for chip in upper)
        and all(b['y'] - a['bottom'] >= 7 - 0.1
                and abs(a['x'] - b['x']) <= TOLERANCE and abs(a['width'] - b['width']) <= TOLERANCE
                for a, b in zip(upper, lower, strict=True))
    )
    return metrics


def map_background(panel):
    metrics = panel.get_by_role('region', name='학교 목록').evaluate('''list => {
        const body = list.closest('[data-testid="map-scroll-body"]');
        const area = body.querySelector('[data-testid="map-area"]');
        const bodyRect = body.getBoundingClientRect();
        const end = Math.max(list.getBoundingClientRect().bottom, area.getBoundingClientRect().bottom);
        const gap = Math.max(0, Math.min(bodyRect.bottom, innerHeight) - end);
        const visibleBacking = nodes => nodes.map(node => getComputedStyle(node).backgroundColor)
            .find(color => color !== 'rgba(0, 0, 0, 0)') ?? null;
        return {height: gap, listBeforeMap: Boolean(list.compareDocumentPosition(area) & Node.DOCUMENT_POSITION_FOLLOWING),
            backing: visibleBacking([body, body.closest('[data-phone-shell]')]),
            sea: getComputedStyle(area).backgroundColor,
            color: gap > 1 ? visibleBacking(document.elementsFromPoint(bodyRect.left + 8, end + Math.min(8, gap / 2))) : null};
    }''')
    metrics['matchesPanel'] = metrics['backing'] == CREAM and metrics['sea'] == SEA \
        and (metrics['height'] <= TOLERANCE or metrics['color'] == CREAM)
    return metrics


def map_region_flow(page, panel):
    regions = panel.get_by_role('group', name='권역 선택')
    area = panel.get_by_test_id('map-area')
    schools = panel.get_by_role('region', name='학교 목록')
    regions.get_by_role('button', name='전체', exact=True).focus()
    for label in REGIONS[1:]:
        page.keyboard.press('Tab')
        expect(regions.get_by_role('button', name=label, exact=True)).to_be_focused()
    jeju = regions.get_by_role('button', name='제주', exact=True)
    ring = jeju.evaluate('''button => {
        const style = getComputedStyle(button);
        return {style: style.outlineStyle, width: parseFloat(style.outlineWidth), offset: parseFloat(style.outlineOffset)};
    }''')
    focused_chips = chip_metrics(regions)
    page.keyboard.press('Enter')
    expect(jeju).to_have_attribute('aria-pressed', 'true')
    expect(area).to_have_attribute('data-region', '제주')
    expect(schools.locator('.game-map-row-select')).to_have_count(2)
    # Fractional browser zoom can round an authored 2px outline to the nearest device pixel.
    return {'focusRing': ring, 'focusFits': focused_chips['gridFits'],
            'focusVisible': ring['style'] == 'solid' and ring['width'] >= 1.5, 'jeju': map_background(panel)}


def campus_corner_flow(page):
    left = page.get_by_role('complementary', name='학교 패널')
    stage = page.locator('[data-campus-stage][data-my-campus]')
    expect(stage.get_by_text('클릭!', exact=True)).to_be_visible()
    score = stage.get_by_test_id('campus-stats-contribution')
    before = score.inner_text()
    for control, target in ((stage.get_by_role('button', name='우리 학교 방명록 보기'), '방명록'),
                            (stage.get_by_test_id('campus-stats-button'), '상세')):
        control.click()
        expect(left.get_by_role('tab', name=target, exact=True)).to_have_attribute('aria-selected', 'true')
        expect(score).to_have_text(before)
        expect(stage.get_by_text('클릭!', exact=True)).to_be_visible()
    stats = left.get_by_role('tabpanel', name='상세')
    expect(stats.locator('.game-clicker-stat')).to_have_count(2)
    expect(stats.locator('.game-clicker-stat-num')).to_have_text(['30', '2'])


def map_search_selection(panel):
    area = panel.get_by_test_id('map-area')
    panel.get_by_role('button', name='전체', exact=True).click()
    schools = panel.get_by_role('region', name='학교 목록')
    panel.get_by_role('button', name='수도권', exact=True).click()
    marker = area.get_by_role('button', name='국민대학교 전국', exact=False)
    marker.focus()
    schools.evaluate('e => e.scrollTop = 0')
    outer = panel.get_by_test_id('map-scroll-body')
    before_scroll = outer.evaluate('e => e.scrollTop')
    panel.page.keyboard.press('Enter')
    expect(marker).to_be_focused()
    row = schools.get_by_role('button', name='국민대학교', exact=True)
    expect(row).to_have_attribute('aria-expanded', 'true')
    assert schools.evaluate('e => e.scrollTop') > 0
    assert outer.evaluate('e => e.scrollTop') == before_scroll
    expect(schools.get_by_role('button', name='국민대학교 방문')).to_be_enabled()
    panel.get_by_role('button', name='전체', exact=True).click()
    schools.get_by_role('button', name='서울대학교', exact=True).click()
    search = panel.get_by_role('textbox', name='학교 검색')
    search.click()
    visit = schools.get_by_role('button', name='서울대학교 방문')
    notice = panel.get_by_text('선택 학교가 검색 결과에 없습니다.', exact=True)
    top = area.bounding_box()['y']
    deltas = []
    for term, count, status in (('KAIST', 1, '1개교'), ('없는학교-SCRUM230', 0, '검색 결과 없음')):
        search.fill(term)
        expect(panel.get_by_test_id('map-search-status')).to_have_text(status)
        expect(schools.locator('.game-map-row-select')).to_have_count(count)
        expect(visit).to_have_count(0)
        expect(notice).to_be_visible()
        deltas.append(area.bounding_box()['y'] - top)
    panel.get_by_role('button', name='검색어 지우기').click()
    expect(search).to_have_value('')
    expect(notice).to_have_count(0)
    expect(visit).to_be_enabled()
    deltas.append(area.bounding_box()['y'] - top)
    assert max(map(abs, deltas)) <= TOLERANCE, deltas
    search.fill('KAIST')
    schools.get_by_role('button', name='KAIST', exact=True).click()
    expect(schools.get_by_role('button', name='KAIST 방문')).to_be_enabled()
    expect(notice).to_have_count(0)
    panel.get_by_role('button', name='검색어 지우기').click()
    return deltas


def map_search_flow(panel, path):
    regions = panel.get_by_role('group', name='권역 선택')
    area = panel.get_by_test_id('map-area')
    schools = panel.get_by_role('region', name='학교 목록')
    search = panel.get_by_role('textbox', name='학교 검색')
    regions.get_by_role('button', name='강원', exact=True).click()
    expect(area).to_have_attribute('data-region', '강원')
    gangwon = map_background(panel)
    regions.get_by_role('button', name='충청', exact=True).click()
    expect(area).to_have_attribute('data-region', '충청')
    expect(regions.get_by_role('button', name='충청', exact=True)).to_have_attribute('aria-pressed', 'true')
    chungcheong = chip_metrics(regions)
    search.fill('없는학교-SCRUM218')
    expect(schools.get_by_role('status')).to_contain_text('맞는 학교가 없습니다')
    expect(panel.get_by_test_id('map-search-status')).to_have_text('검색 결과 없음')
    expect(schools.get_by_role('heading', level=3)).to_have_text('검색 학교 목록 - 0개교')
    empty = map_background(panel)
    snapshot(panel.page, path)
    panel.get_by_role('button', name='검색어 지우기').click()
    expect(search).to_have_value('')
    expect(schools.get_by_role('button', name='KAIST')).to_be_visible()
    restored = map_background(panel)
    passed = (chungcheong['gridFits'] and gangwon['matchesPanel'] and gangwon['height'] > TOLERANCE
              and empty['matchesPanel'] and empty['height'] > TOLERANCE and not empty['listBeforeMap']
              and restored['matchesPanel'] and not restored['listBeforeMap'])
    return {'gangwon': gangwon, 'chungcheongFits': chungcheong['gridFits'], 'empty': empty, 'restored': restored, 'passed': passed}


def pin_metrics(area):
    return area.evaluate('''area => {
        const frame = area.getBoundingClientRect();
        const visible = node => getComputedStyle(node).visibility === 'visible';
        const pins = [...area.querySelectorAll('.game-map-pin')];
        const names = [...area.querySelectorAll('.game-map-pin-name')];
        const ranks = [...area.querySelectorAll('.game-map-pin-rank')];
        const clusters = [...area.querySelectorAll('[data-cluster-region], .game-map-own-tag')];
        const elements = [...pins, ...ranks, ...names.filter(visible)];
        const boxes = elements.map(node => node.getBoundingClientRect());
        const intersects = (a, b) => a.left < b.right - .1 && a.right > b.left + .1 && a.top < b.bottom - .1 && a.bottom > b.top + .1;
        const overlap = boxes.some((box, index) => boxes.slice(index + 1).some(other => intersects(box, other))
            || clusters.some(cluster => intersects(box, cluster.getBoundingClientRect())));
        const clipped = boxes.some(box => box.left < frame.left || box.top < frame.top || box.right > frame.right || box.bottom > frame.bottom);
        const sized = pins.every(pin => pin.getBoundingClientRect().width >= (pin.hasAttribute('data-top-rank') ? 31.9 : 27.9))
            && [...names.filter(visible), ...ranks].every(node => parseFloat(getComputedStyle(node).fontSize) >= 12);
        const priorities = names.filter(name => name.dataset.priority === 'true');
        return {total: pins.length, ranked: ranks.length, labels: names.filter(visible).length, overlap, clipped,
            passed: !overlap && !clipped && sized && [...pins, ...ranks, ...priorities].every(visible)};
    }''')


def map_caption_flow(page, panel, out):
    regions = panel.get_by_role('group', name='권역 선택')
    area = panel.get_by_test_id('map-area')
    caption = area.locator('.game-map-pin-caption')
    schools = panel.get_by_role('region', name='학교 목록')
    schools.get_by_role('button', name='서울대학교', exact=True).click()
    visit = schools.get_by_role('button', name='서울대학교 방문')
    search = panel.get_by_role('textbox', name='학교 검색')
    widths = {}
    for region, name in (('전체', 'KAIST'), ('수도권', '서울대학교'), ('충청', '한국기술교육대학교'), ('제주', '제주대학교')):
        regions.get_by_role('button', name=region, exact=True).click()
        hint = '지역을 눌러 대학을 확인하세요' if region == '전체' else '학교를 눌러 선택하세요'
        expect(caption).to_have_count(1)
        expect(caption).to_have_text(hint)
        assert caption.evaluate('el => getComputedStyle(el).animationName') == 'none'
        expect(area.get_by_text(hint, exact=True)).to_have_count(1)
        pin = area.get_by_role('button', name=f'{name} 전국', exact=False)
        before = pin.bounding_box()
        pressed = pin.get_attribute('aria-pressed')
        pin.hover()
        expect(caption).to_have_text(name)
        expect(caption).to_have_attribute('aria-hidden', 'true')
        expect(area.get_by_text(hint, exact=True)).to_have_count(0)
        expect(pin).to_have_attribute('aria-pressed', pressed)
        assert pin.bounding_box() == before
        assert caption.evaluate('''el => {
            const style = getComputedStyle(el);
            return style.opacity === '1' && style.animationName === 'none' && style.backgroundColor === 'rgb(253, 245, 223)';
        }''')
        single_line = caption.bounding_box()
        widths[region] = single_line['width']
        snapshot(page, out / f'caption-{region}.png')
        if region == '충청':
            # Stress the actual caption's width cap with a name longer than the fixture directory contains.
            caption.locator('span').evaluate('(el, text) => { el.textContent = text.repeat(3); }', name)
            wrapped = caption.bounding_box()
            frame = area.bounding_box()
            assert wrapped['height'] > single_line['height'] and wrapped['width'] < frame['width']
            assert pin.bounding_box() == before
            snapshot(page, out / 'caption-long-wrap.png')
        page.keyboard.press('Escape')
        expect(caption).to_have_text(hint)
        search.hover()
        pin.hover()
        expect(caption).to_have_text(name)
        search.hover()
        expect(caption).to_have_text(hint)
    assert widths['충청'] > widths['전체']
    regions.get_by_role('button', name='전체', exact=True).click()
    expect(visit).to_be_visible()
    page.emulate_media(reduced_motion='no-preference')
    assert caption.evaluate('el => getComputedStyle(el).animationName') == 'map-hint-pulse'
    area.get_by_role('button', name='KAIST 전국 1위').hover()
    expect(caption).to_have_text('KAIST')
    assert caption.evaluate('el => getComputedStyle(el).opacity') == '1'
    page.emulate_media(reduced_motion='reduce')
    search.hover()
    return widths


def reveal_folded_pin(page, panel, path):
    panel.get_by_role('button', name='수도권', exact=True).click()
    area = panel.get_by_test_id('map-area')
    expect(area).to_have_attribute('data-region', '수도권')
    pin_id = area.locator('.game-map-pin-name').filter(visible=False).first.get_attribute('data-pin-id')
    pin = area.locator(f'button[data-school-id="{pin_id}"]')
    name = pin.get_attribute('aria-label').split(' 전국 ')[0]
    caption = area.locator('.game-map-pin-caption')
    pin.hover()
    expect(caption).to_be_visible()
    expect(caption).to_have_text(name)
    panel.get_by_role('textbox', name='학교 검색').hover()
    expect(caption).to_have_text('학교를 눌러 선택하세요')
    page.keyboard.press('Tab')
    pin.focus()
    expect(caption).to_be_visible()
    expect(caption).to_have_text(name)
    snapshot(page, path)
    page.keyboard.press('Escape')
    expect(caption).to_have_text('학교를 눌러 선택하세요')
    expect(pin).to_be_focused()
    page.keyboard.press('Tab')
    expect(pin).not_to_be_focused()
    page.keyboard.press('Shift+Tab')
    expect(pin).to_be_focused()
    expect(caption).to_have_text(name)
    page.keyboard.press('Enter')
    expect(pin).to_have_attribute('aria-pressed', 'true')
    expect(pin.locator('.game-map-pin-name')).to_be_visible()
    panel.get_by_role('button', name=f'{name} 방문').click()
    expect(page.get_by_test_id('visit-tag')).to_be_visible()
    expect(page.locator('[data-game-layout="visit"] h1')).to_have_text(name)
    page.get_by_role('button', name='대대대 내 캠퍼스').click()
    expect(pin).to_have_attribute('aria-pressed', 'true')


def mock_case(browser, args, width, height, dpr):
    viewport = {'width': width, 'height': height}
    name = f'{width}x{height}-dpr{dpr}'
    page = browser.new_page(viewport=viewport, device_scale_factor=dpr, reduced_motion='reduce')
    try:
        panel = ready(page, args.mock_url)
        panel.get_by_role('tab', name='지도', exact=True).click()
        regions = panel.get_by_role('group', name='권역 선택')
        expect(regions.get_by_role('button')).to_have_count(7)
        expect(regions.get_by_role('button', name='전체', exact=True)).to_have_attribute('aria-pressed', 'true')
        page.evaluate('async () => { await document.fonts.ready; }')
        fonts = {
            'brand': page.get_by_role('button', name='대대대 내 캠퍼스').evaluate('e => getComputedStyle(e).fontFamily'),
            'heading': panel.get_by_role('heading', name='전국 경쟁', exact=True).evaluate('e => getComputedStyle(e).fontFamily'),
            'campusHud': page.locator('.game-campus-score').evaluate('e => getComputedStyle(e).fontFamily'),
            'rankScore': panel.locator('.game-map-pin-rank span').first.evaluate('e => getComputedStyle(e).fontFamily'),
            'list': panel.get_by_role('region', name='학교 목록').evaluate('e => getComputedStyle(e).fontFamily'),
            'search': panel.get_by_role('textbox', name='학교 검색').evaluate('e => getComputedStyle(e).fontFamily'),
            'faces': page.evaluate('''() => [...document.fonts].filter(face => face.family.includes('Maplestory'))
                .map(({weight, status}) => ({weight, status}))'''),
        }
        fonts['consistent'] = all(fonts[key].split(',')[0].strip('\'" ') == 'Maplestory'
                                  for key in ('brand', 'heading', 'campusHud', 'rankScore', 'list', 'search')) \
            and any(face['status'] == 'loaded' for face in fonts['faces'])
        menus = menu_metrics(page.locator('.game-panel-tabs button, .game-center-nav button'))
        assert len(menus) == 8
        chips = chip_metrics(regions)
        assert chips['gridFits'], chips
        area = panel.get_by_test_id('map-area')
        expect(area.locator('[data-top-rank]')).to_have_count(3)
        marker = pin_metrics(area)
        assert marker['ranked'] == 3
        caption = area.locator('.game-map-pin-caption').bounding_box()
        jeju_cluster = area.locator('[data-cluster-region="제주"]').bounding_box()
        assert caption['x'] >= jeju_cluster['x'] + jeju_cluster['width'], 'National hint covers Jeju region control'
        snapshot(page, args.out / f'{name}-national.png')
        region_flow = map_region_flow(page, panel)
        snapshot(page, args.out / f'{name}-jeju.png')
        pins = {'제주': pin_metrics(area)}
        assert pins['제주']['total'] == 2
        for region, school_count in (('수도권', 25), ('강원', 4), ('충청', 24), ('영남', 11), ('호남', 10)):
            regions.get_by_role('button', name=region, exact=True).click()
            expect(area).to_have_attribute('data-region', region)
            pins[region] = pin_metrics(area)
            assert pins[region]['total'] == school_count
        caption_widths = None
        if (width, height, dpr) == (1440, 900, 1):
            regions.get_by_role('button', name='전체', exact=True).click()
            campus_corner_flow(page)
            map_search_selection(panel)
            caption_widths = map_caption_flow(page, panel, args.out)
            reveal_folded_pin(page, panel, args.out / f'{name}-metro.png')
        regions.get_by_role('button', name='제주', exact=True).click()
        search_flow = map_search_flow(panel, args.out / f'{name}-empty-search.png') if (width, height) == (2560, 1440) else None
        shop = shop_metrics(page, args.out / f'{name}-shop-mock.png')
        if (width, height, dpr) == (1440, 900, 1):
            mock_purchase(page)
            page.get_by_role('button', name='캠퍼스로 돌아가기').click()
            regions.get_by_role('button', name='전체', exact=True).click()
            menus += mock_menu_flow(page, panel, args.out / f'{name}-visit-shop.png')
        return {'name': name, 'viewport': viewport, 'dpr': dpr, 'fonts': fonts, 'chips': chips,
                'top3': marker, 'regionalPins': pins, 'regionFlow': region_flow, 'searchFlow': search_flow,
                'captionWidths': caption_widths, 'mockShop': shop, 'menus': menus}
    finally:
        page.close()


def compact_ribbons(page):
    page.get_by_title('채팅창 열기').click()
    chat = page.get_by_role('dialog', name='채팅')
    expect(chat.get_by_text('SOON', exact=True)).to_be_visible()
    chat.get_by_title('채팅창 닫기').click()
    expect(chat).to_have_count(0)

    page.get_by_role('button', name='우리 학교 방명록 보기').click()
    guestbook = page.get_by_role('dialog', name='우리 학교 방명록')
    expect(guestbook.get_by_text('SOON', exact=True)).to_be_visible()
    guestbook.get_by_title('방명록 닫기').click()
    expect(guestbook).to_have_count(0)


def compact_scope(browser, url):
    for width, height, query, variant in ((390, 844, '?device=full', 'full'), (1440, 900, '?device=framed', 'framed'),
                                          (1023, 720, '', 'full')):
        page = browser.new_page(viewport={'width': width, 'height': height})
        try:
            page.goto(url + query)
            nav = page.get_by_role('navigation', name='주요 메뉴')
            expect(nav).to_be_visible()
            expect(page.locator('[data-desktop-game]')).to_have_count(0)
            expect(page.locator(f'[data-phone-shell="{variant}"]').first).to_be_visible()
            compact_ribbons(page)
            shop = nav.get_by_role('button', name='상점', exact=True)
            assert shop.evaluate('''e => {
                const icon = e.querySelector('svg').getBoundingClientRect();
                const label = document.createRange();
                label.selectNode(e.lastElementChild);
                return icon.height > 0 && icon.bottom <= label.getBoundingClientRect().top + 1;
            }''')
            nav.get_by_role('button', name='지도', exact=True).click()
            page.get_by_role('button', name='전국 지도', exact=True).click()
            area = page.get_by_test_id('map-area')
            regions = page.get_by_role('group', name='권역 선택')
            expect(regions.get_by_role('button')).to_have_count(7)
            assert regions.evaluate('''group => {
                const style = getComputedStyle(group);
                return style.display === 'flex' && style.flexWrap === 'nowrap' && style.overflowX === 'auto';
            }''')
            assert area.evaluate("e => getComputedStyle(e.closest('[data-phone-shell]')).backgroundColor") == SEA
            expect(area.locator('[data-map-layer] [data-top-rank]')).to_have_count(3)
            expect(area.locator('[data-map-pins]')).to_have_count(0)
            regions.get_by_role('button', name='제주', exact=True).click()
            expect(area).to_have_attribute('data-region', '제주')
            nav.get_by_role('button', name='순위', exact=True).click()
            expect(page.get_by_text('내 소속', exact=True)).to_be_visible()
            expect(page.get_by_role('region', name='순위 목록')).to_have_count(0)
            shop.click()
            card = page.get_by_role('button', name='커피 한 잔 구매 선택')
            expect(card).to_be_visible()
            assert card.evaluate("e => parseFloat(getComputedStyle(e).scale) > 1")
            assert card.get_by_text('커피 한 잔', exact=True).evaluate("e => getComputedStyle(e).textOverflow") == 'ellipsis'
        finally:
            page.close()


def api_manual_tabs(page, panel, calls, sockets):
    school = page.get_by_role('complementary', name='학교 패널')
    detail = school.get_by_role('tab', name='상세')
    chat = school.get_by_role('tab', name='채팅')
    rankings = panel.get_by_role('tab', name='순위')
    detail.focus()
    page.keyboard.press('ArrowRight')
    expect(chat).to_be_focused()
    expect(detail).to_have_attribute('aria-selected', 'true')
    page.keyboard.press('End')
    expect(school.get_by_role('tab', name='방명록')).to_be_focused()
    panel.get_by_role('tab', name='지도').focus()
    page.keyboard.press('ArrowRight')
    expect(rankings).to_be_focused()
    expect(panel.get_by_role('tab', name='지도')).to_have_attribute('aria-selected', 'true')
    # Cross one ranking refresh interval so accidental polling is visible as well as an initial request.
    page.clock.run_for(5100)
    assert not calls and not sockets, (calls, sockets)
    with page.expect_request(lambda req: '/rankings/universities?' in req.url and 'size=20' in req.url):
        page.keyboard.press('Space')
    with page.expect_request(lambda req: '/chat/universities/8/messages' in req.url):
        chat.press('Enter')
    expect(chat).to_have_attribute('aria-selected', 'true')
    expect(school.get_by_role('log')).to_be_visible()
    expect(school.get_by_text('SOON', exact=True)).to_have_count(0)
    page.clock.run_for(100)
    assert sockets, 'Activating chat should open its WebSocket'
    with page.expect_request(lambda req: '/universities/8/guestbooks?' in req.url):
        school.get_by_role('tab', name='방명록').click()
    expect(school.get_by_text('아직 남겨진 방명록이 없습니다.')).to_be_visible()
    expect(school.get_by_text('SOON', exact=True)).to_have_count(0)
    detail.click()
    panel.get_by_role('tab', name='지도').click()


def api_ranking_layout(page, panel, data, shot):
    page.set_viewport_size({'width': 1024, 'height': 481})
    panel.get_by_role('tab', name='순위').click()
    container = panel.locator('.game-panel-ranking')
    scroll = container.get_by_role('region', name='순위 목록')
    own = container.locator('.game-ranking-self')
    badge = own.locator('.game-ranking-self-badge')
    score = own.get_by_text('12,345,678,901', exact=True)
    expect(badge).to_have_text('내 학교')
    expect(score).to_be_in_viewport(ratio=1)
    expect(own.get_by_text('12345', exact=True)).to_be_in_viewport(ratio=1)
    expect(own).to_be_in_viewport(ratio=1)
    badge_box, score_box = badge.bounding_box(), score.bounding_box()
    assert score_box['y'] >= badge_box['y'] + badge_box['height'], (badge_box, score_box)
    snapshot(page, shot)
    before = own.bounding_box()
    rank = data['/rankings/universities']['myUniversity']
    rank['totalScore'] += 1
    page.clock.run_for(5100)
    expect(own.get_by_text('12,345,678,902', exact=True)).to_be_visible()
    assert own.bounding_box() == before

    container.get_by_role('button', name='교내 개인').click()
    expect(container.get_by_role('alert')).to_contain_text('순위 조회에 실패했습니다')
    expect(own).to_have_count(0)
    data['/rankings/universities/8/users'] = {
        'items': [], 'hasNext': False, 'page': 0, 'size': 20, 'totalCount': 0, 'me': None,
    }
    container.get_by_role('button', name='다시 시도').click()
    expect(scroll.get_by_role('status')).to_have_text('표시할 순위가 없어요.')
    expect(own).to_have_count(0)
    assert scroll.evaluate('''el => {
        const panel = el.closest('.game-panel-content');
        const gutter = parseFloat(getComputedStyle(panel).borderBottomWidth)
            + parseFloat(getComputedStyle(el.parentElement).paddingBottom);
        return Math.abs(panel.getBoundingClientRect().bottom - el.getBoundingClientRect().bottom - gutter) < 1;
    }'''), 'Empty board must not reserve a self-rank slot'
    panel.get_by_role('tab', name='지도').click()


def api_shelf(browser, args):
    page = browser.new_page(viewport={'width': 1440, 'height': 900}, reduced_motion='reduce')
    profile = {'id': 501, 'nickname': '검증학생', 'email': 'student@example.test', 'role': 'USER', 'status': 'ACTIVE',
               'profileImageUrl': None, 'university': {'id': 8, 'name': '한양대학교', 'logoUrl': None},
               'department': {'id': 1, 'name': '공학과'}, 'clicker': {'level': 1, 'clickPower': 1, 'nextLevelRequiredScore': 200},
               'totalContribScore': 1000, 'totalAttackScore': 0}
    item = {'id': 3, 'name': '테스트 커피', 'content': '검증용 아이템', 'type': 'MULTIPLIER', 'payType': 'CONTRIBUTION',
            'price': 120, 'durationSeconds': 30}
    long_item = {**item, 'id': 4,
                 'name': '캠퍼스 기념 특별 메뉴 오늘의 야간 자율 학습 한정 간식 세트',
                 'content': '응원 이벤트 동안 사용할 수 있는 특별한 아이템입니다. 효과를 적용하면 기존 아이템 효과를 교체하며, 사용 가능 시간은 구매 확인창에서도 볼 수 있습니다. 식별 코드: ' + 'CAMPUS2026' * 5}
    rank = {'rank': 12345, 'universityId': 8, 'name': '한양대학교', 'logoUrl': None,
            'totalScore': 12345678901, 'rankChange': -1}
    calls, sockets = [], []
    data = {'/rankings/universities': {'items': [rank], 'myUniversity': rank, 'hasNext': False,
                                      'page': 0, 'size': 20, 'totalCount': 1},
            '/chat/universities/8/messages': {'items': [], 'hasNext': False, 'nextCursor': None},
            '/universities/8/guestbooks': {'items': [], 'hasNext': False, 'nextCursor': None}, '/alliances/me': None,
            '/auth/reissue': {'accessToken': 'layout-fixture', 'expiresInSeconds': 3600}, '/users/me': profile,
            '/items': [item, long_item], '/items/me/active': None,
            '/map/universities': [], '/clickers': []}

    def fixture(route):
        url = urlsplit(route.request.url)
        path = url.path.removeprefix('/api/v1')
        if (path.startswith('/chat/') or path.endswith('/guestbooks')
                or (path == '/rankings/universities' and 'size=20' in url.query)):
            calls.append(path)
        if path in data:
            route.fulfill(json={'code': 'SUCCESS', 'message': '요청이 성공했습니다.', 'data': data[path]})
        else:
            route.fulfill(status=404, json={'code': 'NOT_FOUND', 'message': 'No desktop test fixture'})

    def socket(ws):
        sockets.append(ws.url)
        ws.on_message(lambda frame: ws.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\x00')
                      if isinstance(frame, str) and frame.startswith('CONNECT\n') else None)

    try:
        page.clock.install()
        page.route('**/api/v1/**', fixture)
        page.route_web_socket('**/ws', socket)
        panel = ready(page, args.api_url)
        api_manual_tabs(page, panel, calls, sockets)
        print('PASS API tab focus/activation: no ranking request, guestbook request, or chat connection on focus alone')
        api_ranking_layout(page, panel, data, args.out / '1024x481-rankings-api.png')
        print('PASS API ranking: short list, refresh, failure/retry, and empty with no self-rank slot')
        results = {}
        for width, height in ((1440, 900), (1024, 720)):
            page.set_viewport_size({'width': width, 'height': height})
            expect(page.locator('[data-desktop-game]')).to_have_count(1)
            name = f'{width}x{height}-dpr1'
            results[name] = shop_metrics(page, args.out / f'{name}-shop-api.png', open_shop=not results, api=True)
            card = page.get_by_role('button', name=f"{long_item['name']} 구매 선택")
            results[name]['copy'] = [wrapping_text(card.get_by_text(long_item[key], exact=True)) for key in ('name', 'content')]
        return results
    finally:
        page.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mock-url', default='http://127.0.0.1:4316/')
    parser.add_argument('--api-url')
    parser.add_argument('--chromium', help='Executable path; omit to use Playwright Chromium')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--check', action='store_true', help='Exit 1 on visual invariant failures')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=args.chromium)
        cases = [mock_case(browser, args, *spec) for spec in VIEWPORTS]
        compact_scope(browser, args.mock_url)
        print('PASS 1-column and framed modes retain bottom menu, scrolling map chips and existing mock shelf styling')
        api = api_shelf(browser, args) if args.api_url else None
        version = browser.version
        browser.close()

    failures = []
    for case in cases:
        for key, passed in (
            ('Maplestory inheritance and loaded face', case['fonts']['consistent']),
            ('raised menus fit in at most two lines with icon on left',
             all(m['fits'] and m['iconLeft'] and m['raised'] and m['height'] >= 44 and 13 <= m['fontSize'] <= 17
                 for m in case['menus'])),
            ('all seven region chips and focus ring fit without horizontal scroll',
             case['chips']['allVisible'] and case['regionFlow']['focusVisible'] and case['regionFlow']['focusFits']),
            ('TOP 3 and regional pins fit; logos ≥28/32 CSS px and visible text ≥12 CSS px',
             case['top3']['passed'] and all(pins['passed'] for pins in case['regionalPins'].values())),
            ('Jeju panel backing cream and map area sea blue', case['regionFlow']['jeju']['matchesPanel']),
        ):
            if not passed:
                failures.append(f"{case['name']}: {key}")
        search = case['searchFlow']
        if search and not search['passed']:
            failures.append(f"{case['name']}: short/empty map lists and search order")
        if case['mockShop'] and not case['mockShop']['aligned']:
            failures.append(f"{case['name']}: mock shelf card alignment and spacing")
    for name, shop in (api or {}).items():
        if not shop['aligned']:
            failures.append(f'{name}: API shelf card alignment and spacing')
        if not all(copy['fits'] and copy['lines'] >= 2 for copy in shop['copy']):
            failures.append(f'{name}: long API shelf name and description wrap without clipping')
    report = {'chromium': version, 'cases': cases, 'apiShop': api, 'failures': failures}
    (args.out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for failure in failures:
        print(f'FAIL {failure}')
    print(f'{len(failures)} failed observations; report: {args.out / "report.json"}')
    return int(args.check and bool(failures))


if __name__ == '__main__':
    raise SystemExit(main())
