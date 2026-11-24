"""Capture PC 3-column follow-up UI measurements (SCRUM-222 / SCRUM-223).

Run against a local Vite dev server with VITE_API_MODE=mock (`npm run dev:mock`).
Requires Python Playwright and Chromium; not part of npm test.
"""

import argparse
import json
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

VIEWPORTS = [(1024, 720, 1), (1440, 900, 1), (1920, 1080, 1)]
TOLERANCE = 2  # CSS px


def ready(page, url):
    page.goto(url)
    panel = page.get_by_role('complementary', name='전국 경쟁 패널')
    expect(panel).to_be_visible()
    page.evaluate('async () => { await document.fonts.ready; }')
    return panel


def snapshot(page, path):
    page.screenshot(path=path, animations='disabled')


def campus_metrics(page):
    return page.evaluate('''() => {
        const frame = document.querySelector('[data-campus-stage]');
        const fr = frame.getBoundingClientRect();
        const level = frame.querySelector('[data-testid="campus-level-label"]');
        const feature = level.nextElementSibling;
        const guestbook = frame.querySelector('button[aria-label="우리 학교 방명록 보기"]');
        const stats = frame.querySelector('[data-testid="campus-stats-button"]');
        const measure = (el) => {
            const r = el.getBoundingClientRect();
            const cs = getComputedStyle(el);
            return {
                width: Number(r.width.toFixed(2)),
                height: Number(r.height.toFixed(2)),
                fontSize: Number(parseFloat(cs.fontSize).toFixed(2)),
                left: Number((r.left - fr.left).toFixed(2)),
                top: Number((r.top - fr.top).toFixed(2)),
                right: Number((fr.right - r.right).toFixed(2)),
                bottom: Number((fr.bottom - r.bottom).toFixed(2)),
            };
        };
        const lr = level.getBoundingClientRect();
        const tr = feature.getBoundingClientRect();
        const gr = guestbook.getBoundingClientRect();
        const sr = stats.getBoundingClientRect();
        const hand = [...frame.querySelectorAll('div')].find(
            el => el.children.length === 0 && (el.textContent === '터치!' || el.textContent === '클릭!')
        );
        return {
            frame: { width: Number(fr.width.toFixed(2)), height: Number(fr.height.toFixed(2)) },
            corners: {
                level: measure(level),
                feature: measure(feature),
                guestbook: measure(guestbook),
                stats: measure(stats),
            },
            gaps: {
                topHorizontal: Number((tr.left - lr.right).toFixed(2)),
                bottomHorizontal: Number((sr.left - gr.right).toFixed(2)),
                leftVertical: Number((gr.top - lr.bottom).toFixed(2)),
                rightVertical: Number((sr.top - tr.bottom).toFixed(2)),
            },
            handText: hand ? hand.textContent.trim() : null,
        };
    }''')


def stats_metrics(page):
    left = page.get_by_role('complementary', name='학교 패널')
    return left.evaluate('''panel => {
        const nodes = [...panel.querySelectorAll('p, li, dt, dd')];
        const baseNode = nodes.find(el => el.textContent && el.textContent.includes('기본 세기'));
        if (!baseNode) return { found: false };
        const text = baseNode.textContent.replace(/\\s+/g, ' ').trim();
        const critNode = nodes.find(el => el !== baseNode && el.textContent && el.textContent.includes('치명타'));
        const sameLine = text.includes('기본 세기') && text.includes('치명타');
        const box = baseNode.closest('.rpg-box');
        const boldNums = box ? [...box.querySelectorAll('strong, b')]
            .map(el => el.textContent.trim()) : [];
        return {
            found: true,
            text,
            separateCritLine: !sameLine && Boolean(critNode),
            boldSegments: boldNums,
        };
    }''')


def region_grid_metrics(panel):
    regions = panel.get_by_role('group', name='권역 선택')
    return regions.evaluate('''group => {
        const buttons = [...group.querySelectorAll('button')];
        const rects = buttons.map(b => {
            const r = b.getBoundingClientRect();
            return {
                label: b.textContent.trim(),
                left: Number(r.left.toFixed(2)),
                top: Number(r.top.toFixed(2)),
                width: Number(r.width.toFixed(2)),
                height: Number(r.height.toFixed(2)),
                right: Number(r.right.toFixed(2)),
                bottom: Number(r.bottom.toFixed(2)),
            };
        });
        const allBtn = rects[0];
        const row1 = rects.slice(1, 4);
        const row2 = rects.slice(4, 7);
        const row1Aligned = row1.every(r => Math.abs(r.top - row1[0].top) <= 2);
        const row2Aligned = row2.every(r => Math.abs(r.top - row2[0].top) <= 2 && r.top > row1[0].bottom - 1);
        const allSpansTwoRows = allBtn.top <= row1[0].top + 2 && allBtn.bottom >= row2[0].bottom - 2;
        const style = getComputedStyle(group);
        return {
            display: style.display,
            horizontalOverflow: Number((group.scrollWidth - group.clientWidth).toFixed(2)),
            isTwoRowGrid: row1Aligned && row2Aligned && allSpansTwoRows,
            buttons: rects,
        };
    }''')


def map_hover_metrics(page, panel):
    pin = panel.get_by_role('button', name='KAIST 전국 1위')
    pin.hover()
    metrics = panel.evaluate('''panel => {
        const area = panel.querySelector('[data-testid="map-area"]');
        const caption = area.querySelector('.game-map-pin-caption');
        const captionVisible = Boolean(caption && getComputedStyle(caption).visibility !== 'hidden'
            && parseFloat(getComputedStyle(caption).opacity) > 0 && caption.getBoundingClientRect().width > 0);
        const hint = [...area.querySelectorAll('span, div')].find(
            el => el.children.length === 0 && (el.textContent?.includes('지역을 눌러') || el.textContent?.includes('학교를 눌러'))
        );
        const hintVisible = Boolean(hint && getComputedStyle(hint).visibility !== 'hidden'
            && parseFloat(getComputedStyle(hint).opacity) > 0 && hint.getBoundingClientRect().width > 0);
        return {
            bottomBoxCount: Number(captionVisible) + Number(hintVisible),
            captionVisible,
            captionText: caption ? caption.textContent.trim() : null,
            hintVisible,
            hintText: hint ? hint.textContent.trim() : null,
        };
    }''')
    page.mouse.move(1, 1)
    return metrics


def map_search_metrics(panel, search_shot):
    def snapshot_layout():
        return panel.evaluate('''panel => {
            const area = panel.querySelector('[data-testid="map-area"]');
            const list = panel.querySelector('.game-map-list');
            const heading = list?.querySelector('h3');
            const status = panel.querySelector('[data-testid="map-search-status"]');
            const ar = area.getBoundingClientRect();
            const lr = list.getBoundingClientRect();
            return {
                mapTop: Number(ar.top.toFixed(2)),
                mapBottom: Number(ar.bottom.toFixed(2)),
                listTop: Number(lr.top.toFixed(2)),
                listBelowMap: lr.top >= ar.bottom - 2,
                listHeading: heading ? heading.textContent.trim() : null,
                searchStatus: status ? status.textContent.trim() : null,
            };
        }''')

    before = snapshot_layout()
    search_input = panel.get_by_role('textbox', name='학교 검색')
    search_input.fill('KAIST')
    expect(panel.locator('.game-map-list li')).to_have_count(1)
    after = snapshot_layout()
    snapshot(panel.page, search_shot)
    search_input.fill('')
    expect(panel.locator('.game-map-list li')).not_to_have_count(1)
    cleared = snapshot_layout()
    return {
        'before': before,
        'after': after,
        'cleared': cleared,
        'mapTopDelta': round(after['mapTop'] - before['mapTop'], 2),
        'mapStable': abs(after['mapTop'] - before['mapTop']) <= TOLERANCE,
    }


def rankings_metrics(page, panel, shot):
    panel.get_by_role('tab', name='순위').click()
    container = panel.locator('.game-panel-ranking')
    scroll = container.get_by_role('region', name='순위 목록')
    expect(scroll).to_be_visible()
    expect(container.locator('.game-ranking-self-badge')).to_have_text('내 학교')
    snapshot(page, shot)
    before = ranking_layout(container)
    rows = scroll.locator('.game-ranking-row')
    expect(rows).to_have_count(12)
    scroll.focus()
    expect(scroll).to_be_focused()
    # A smooth End animation can keep following a moving bottom as mock pages append.
    scroll.evaluate("el => el.scrollTo({ top: el.scrollHeight, behavior: 'instant' })")
    expect(rows).to_have_count(22)
    after = ranking_layout(container)
    assert after['scrollTop'] > 0 and after['myRowTop'] == before['myRowTop'], after
    assert after['podiumBottom'] < before['podiumBottom'] and after['scrollAreas'] == 1, after
    for tab, badge in (('교내 학과', '내 학과'), ('교내 개인', '내 순위'), ('학교 순위', '내 학교')):
        container.get_by_role('button', name=tab).click()
        expect(container.locator('.game-ranking-self-badge')).to_have_text(badge)
        metrics = ranking_layout(container)
        assert metrics['scrollTop'] == 0 and metrics['pinned'], metrics
    return before


def ranking_layout(container):
    return container.evaluate('''ranking => {
        const content = ranking.closest('.game-panel-content');
        const contentRect = content.getBoundingClientRect();
        const scrollList = ranking.querySelector('.game-ranking-scroll');
        const listRect = scrollList.getBoundingClientRect();
        const rows = [...scrollList.querySelectorAll('.game-ranking-row')];
        const visibleRows = listRect ? rows.filter(row => {
            const r = row.getBoundingClientRect();
            return r.bottom > listRect.top + 1 && r.top < listRect.bottom - 1;
        }) : [];
        const fullVisibleRows = listRect ? rows.filter(row => {
            const r = row.getBoundingClientRect();
            return r.top >= listRect.top - 1 && r.bottom <= listRect.bottom + 1;
        }) : [];
        const myRow = ranking.querySelector('.game-ranking-self');
        const myRect = myRow?.getBoundingClientRect();
        const badge = myRow?.querySelector('.game-ranking-self-badge');
        const textFits = el => {
            const range = document.createRange();
            range.selectNodeContents(el);
            const text = range.getBoundingClientRect();
            return text.width > 0 && text.left >= myRect.left && text.right <= myRect.right;
        };
        const scrollAreas = [content, content.parentElement, ...ranking.querySelectorAll('div')]
            .filter(el => ['auto', 'scroll'].includes(getComputedStyle(el).overflowY)
                && el.scrollHeight > el.clientHeight + 1).length;
        const pinned = myRect && myRect.top >= listRect.bottom + 4 && contentRect.bottom - myRect.bottom >= 4
            && contentRect.bottom - myRect.bottom <= 12 && myRect.bottom <= innerHeight
            && scrollList.scrollWidth === scrollList.clientWidth
            && [badge, myRow.querySelector('.game-ranking-self-rank'), myRow.lastElementChild.firstElementChild]
                .every(textFits);
        return {
            panelContentHeight: Number(contentRect.height.toFixed(2)),
            listHeight: listRect ? Number(listRect.height.toFixed(2)) : 0,
            listScrollHeight: scrollList ? Number(scrollList.scrollHeight.toFixed(2)) : 0,
            listMaxHeight: scrollList ? getComputedStyle(scrollList).maxHeight : null,
            totalLoadedRows: rows.length,
            visibleRowCount: visibleRows.length,
            fullVisibleRowCount: fullVisibleRows.length,
            myRowTop: myRect ? Number((myRect.top - contentRect.top).toFixed(2)) : null,
            myRowBottomGap: myRect ? Number((contentRect.bottom - myRect.bottom).toFixed(2)) : null,
            myBadgeText: badge?.textContent.trim() ?? null,
            scrollTop: scrollList.scrollTop,
            scrollAreas,
            podiumBottom: scrollList.querySelector('.game-ranking-podium')?.getBoundingClientRect().bottom ?? null,
            pinned,
        };
    }''')


def soon_metrics(page, chat_shot, guestbook_shot):
    left = page.get_by_role('complementary', name='학교 패널')
    left.get_by_role('tab', name='우리 학교 채팅').click()
    chat = left.get_by_role('tabpanel', name='우리 학교 채팅')
    expect(chat.get_by_text('필터링된 메시지입니다').first).to_be_visible()
    chat_soon = chat.get_by_text('SOON', exact=True).count()
    snapshot(page, chat_shot)
    composer = chat.get_by_role('textbox', name='채팅 메시지')
    composer.fill('PC 채팅 전송 확인')
    chat.get_by_role('button', name='전송').click()
    expect(chat.get_by_text('PC 채팅 전송 확인', exact=True)).to_be_in_viewport()
    expect(composer).to_have_value('')

    left.get_by_role('tab', name='방명록').click()
    guestbook = left.get_by_role('tabpanel', name='방명록')
    entries = guestbook.get_by_test_id('our-guestbook-entry')
    expect(entries.first).to_be_visible()
    guestbook_soon = guestbook.get_by_text('SOON', exact=True).count()
    snapshot(page, guestbook_shot)
    guestbook.get_by_role('button', name='인기순').click()
    counts = [int(text.strip()) for text in entries.get_by_role('button').all_text_contents()]
    assert counts == sorted(counts, reverse=True), counts
    like = entries.first.get_by_role('button')
    before = int(like.inner_text())
    like.click()
    expect(like).to_have_text(str(before + 1))
    like.click()
    expect(like).to_have_text(str(before))
    guestbook.get_by_role('button', name='지도에서 학교 찾기 >').click()
    expect(page.get_by_role('tab', name='지도', exact=True)).to_have_attribute('aria-selected', 'true')

    left.get_by_role('tab', name='상세').click()
    return {'chatSoonCount': chat_soon, 'guestbookSoonCount': guestbook_soon}


def run_case(browser, args, width, height, dpr):
    name = f'{width}x{height}-dpr{dpr}'
    page = browser.new_page(viewport={'width': width, 'height': height}, device_scale_factor=dpr)
    try:
        panel = ready(page, args.mock_url)
        snapshot(page, args.out / f'{name}-campus-national.png')
        campus = campus_metrics(page)
        stats = stats_metrics(page)
        regions = region_grid_metrics(panel)
        hover = map_hover_metrics(page, panel)
        search = map_search_metrics(panel, args.out / f'{name}-map-search.png')
        rankings = rankings_metrics(page, panel, args.out / f'{name}-rankings.png')
        soon = soon_metrics(page, args.out / f'{name}-chat.png', args.out / f'{name}-guestbook.png')
        return {
            'name': name,
            'viewport': {'width': width, 'height': height},
            'dpr': dpr,
            'campus': campus,
            'stats': stats,
            'regions': regions,
            'mapHover': hover,
            'mapSearch': search,
            'rankings': rankings,
            'soon': soon,
        }
    finally:
        page.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mock-url', default='http://127.0.0.1:4323/')
    parser.add_argument('--chromium', help='Executable path; omit to use Playwright Chromium')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--check', action='store_true', help='Exit 1 when target follow-up invariants fail')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=args.chromium)
        cases = [run_case(browser, args, *spec) for spec in VIEWPORTS]
        min_page = browser.new_page(viewport={'width': 1024, 'height': 481}, reduced_motion='reduce')
        try:
            panel = ready(min_page, args.mock_url)
            minimum = rankings_metrics(min_page, panel, args.out / '1024x481-rankings.png')
            assert minimum['pinned'] and minimum['scrollAreas'] == 1, minimum
        finally:
            min_page.close()
        version = browser.version
        browser.close()

    failures = []
    for case in cases:
        n = case['name']
        if case['campus']['handText'] != '클릭!':
            failures.append(f"{n}: PC campus hand prompt is {case['campus']['handText']!r}, expected '클릭!'")
        if not case['stats'].get('separateCritLine'):
            failures.append(f"{n}: stats base power and crit are not on separate lines")
        if not case['regions']['isTwoRowGrid']:
            failures.append(f"{n}: region buttons are not arranged in 2-row grid with 전체 spanning 2 rows")
        if not (case['mapSearch']['mapStable'] and case['mapSearch']['after']['listBelowMap']):
            failures.append(f"{n}: map shifted during search (delta={case['mapSearch']['mapTopDelta']}px) or list moved above map")
        hover = case['mapHover']
        if hover['bottomBoxCount'] != 1 or not hover['captionVisible'] or hover['captionText'] != 'KAIST':
            failures.append(f"{n}: map bottom hint and hovered school name are not unified into one box")
        rankings = case['rankings']
        if (rankings['listMaxHeight'] != 'none' or not rankings['pinned'] or rankings['scrollAreas'] != 1
                or (case['viewport']['width'] == 1920 and rankings['fullVisibleRowCount'] <= 3)):
            failures.append(f'{n}: ranking list and pinned self-rank geometry failed: {rankings}')
        if case['soon']['chatSoonCount'] > 0 or case['soon']['guestbookSoonCount'] > 0:
            failures.append(f"{n}: SOON ribbon still shown in PC mock chat/guestbook")

    report = {'chromium': version, 'cases': cases, 'rankingsMinimum': minimum, 'failures': failures}
    (args.out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for failure in failures:
        print(f'FAIL {failure}')
    print(f'{len(failures)} failed follow-up invariants; report: {args.out / "report.json"}')
    return int(args.check and bool(failures))


if __name__ == '__main__':
    raise SystemExit(main())
