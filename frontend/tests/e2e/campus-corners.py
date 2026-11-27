"""Chromium regression for my-campus corner sizing; run Vite in mock mode for the sample unread badge."""

import sys

from playwright.sync_api import expect, sync_playwright

STAGE = '[data-campus-stage]'


def measurements(stage):
    return stage.evaluate('''stage => {
        const rect = stage.getBoundingClientRect();
        const pick = selector => stage.querySelector(selector);
        const all = selectors => selectors.map(pick);
        const box = el => el.getBoundingClientRect();
        const inside = (a, b) => a.left >= b.left - 1 && a.right <= b.right + 1 && a.top >= b.top - 1 && a.bottom <= b.bottom + 1;
        const overlaps = (a, b) => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
        const font = el => parseFloat(getComputedStyle(el).fontSize);
        const textFits = el => {
            const range = document.createRange();
            range.selectNodeContents(el);
            return [...range.getClientRects()].every(line => inside(line, box(el)));
        };
        const labels = all(['.game-campus-label-level', '.game-campus-label-feature']);
        const buttons = all(['.game-campus-guestbook-btn', '.game-campus-sign-btn']);
        const texts = all(['.game-campus-label-level .game-campus-label-text', '.game-campus-label-feature .game-campus-label-text',
            '.game-campus-guestbook-tag span', '.game-campus-guestbook-badge', '.game-campus-sign-value']);
        const corners = [...labels, ...buttons].map(box);
        return {width: rect.width, height: rect.height, labelFont: font(labels[0]),
                titleFont: font(pick('.game-campus-sign-title')), badgeFont: font(texts[3]), valueFont: font(texts[4]),
                boardWidth: box(pick('.game-campus-guestbook-board')).width,
                boardHeight: box(pick('.game-campus-guestbook-board')).height,
                touches: buttons.every(button => box(button).width >= 44 && box(button).height >= 44),
                cornersFit: corners.every((corner, i) => inside(corner, rect) &&
                    corners.slice(i + 1).every(other => !overlaps(corner, other))),
                textsFit: texts.every((text, i) => inside(box(text), box(i < 2 ? labels[i] : buttons[i === 4 ? 1 : 0])) && textFits(text))};
    }''')


def verify(browser, url, width, height):
    desktop = width >= 1024
    page = browser.new_page(viewport={'width': width, 'height': height}, reduced_motion='reduce', has_touch=not desktop)

    def activate(locator):
        if desktop:
            locator.click()
        else:
            locator.tap()

    try:
        page.goto(url, wait_until='networkidle')
        stage = page.locator(f'{STAGE}[data-my-campus]')
        expect(stage).to_be_visible()
        page.evaluate('document.fonts.ready')
        # Freeze the decorative bounce so geometry can be measured deterministically.
        page.add_style_tag(content='.game-campus-guestbook-tags { animation: none !important; }')
        m = measurements(stage)
        assert m['touches'] and m['cornersFit'] and m['textsFit'], m
        assert 12 <= m['labelFont'] <= 14 and 14 <= m['titleFont'] <= 18, m
        assert min(m['badgeFont'], m['valueFont']) >= 12 and m['titleFont'] > m['labelFont'], m
        assert m['height'] <= (m['width'] - 8) * 392 / 358 + 9, m
        # Size containment must not alter the established flex height, width or frame aspect cap.
        previous_size = stage.evaluate('''stage => {
            stage.style.containerType = 'normal';
            const {width, height} = stage.getBoundingClientRect();
            stage.style.removeProperty('container-type');
            return {width, height};
        }''')
        assert abs(previous_size['width'] - m['width']) < 1 and abs(previous_size['height'] - m['height']) < 1

        if desktop and height >= 800:
            # Same inline size but a shorter stage: block units cap its controls independently of viewport width.
            stage.evaluate("stage => Object.assign(stage.style, {flex: 'none', height: '220px'})")
            short = measurements(stage)
            assert short['width'] == m['width'] and short['height'] == 220, short
            assert short['boardHeight'] < m['boardHeight'] and short['titleFont'] < m['titleFont'], (short, m)
            assert short['touches'] and short['cornersFit'] and short['textsFit'], short
            stage.evaluate("stage => stage.removeAttribute('style')")

        score = stage.get_by_test_id('campus-stats-contribution')
        before = score.inner_text()
        for control, tab, dialog in [
            (stage.get_by_role('button', name='우리 학교 방명록 보기'), '방명록', '우리 학교 방명록'),
            (stage.get_by_test_id('campus-stats-button'), '상세', '상세 스탯'),
        ]:
            activate(control)
            if desktop:
                target = page.get_by_role('complementary', name='학교 패널').get_by_role('tab', name=tab, exact=True)
                expect(target).to_have_attribute('aria-selected', 'true')
            else:
                sheet = page.get_by_role('dialog', name=dialog)
                expect(sheet).to_be_visible()
                page.keyboard.press('Escape')
                expect(sheet).to_be_hidden()
            expect(score).to_have_text(before)

        # Stress real rendered controls with worst-case data; no game score changes are needed for geometry testing.
        stage.evaluate('''stage => {
            stage.querySelector('.game-campus-label-level .game-campus-label-text').textContent = 'Lv.10 · 확장된 벽돌 교사와 아주 긴 캠퍼스 숲길';
            stage.querySelector('.game-campus-guestbook-badge').textContent = 'N 999999';
            stage.querySelector('.game-campus-sign-value').textContent = '9223372.9T';
        }''')
        long = measurements(stage)
        assert long['touches'] and long['cornersFit'] and long['textsFit'], long

        if desktop:
            panel = page.get_by_role('complementary', name='전국 경쟁 패널')
            panel.get_by_role('tab', name='순위').click()
            ranking = panel
        else:
            page.get_by_role('navigation', name='주요 메뉴').get_by_role('button', name='순위', exact=True).tap()
            ranking = page
        activate(ranking.get_by_role('button', name='KAIST 학교 메뉴').first)
        activate(page.get_by_role('dialog', name='KAIST').get_by_role('button', name='캠퍼스로 이동'))
        visit = page.locator(f'{STAGE}:not([data-my-campus])')
        expect(visit).to_be_visible()
        assert visit.evaluate('''stage => {
            const font = selector => getComputedStyle(stage.querySelector(selector)).fontSize;
            return getComputedStyle(stage).containerType === 'normal' && font('.game-campus-label') === '9px' &&
                font('.game-campus-sign-title') === '10px' && stage.querySelector('.game-campus-guestbook-board').offsetWidth === 40;
        }'''), 'the visited campus still uses the original sizes'
        print(f'PASS {width}×{height}: {m["width"]:.0f}×{m["height"]:.0f} stage; no overlap; corner actions and visit unchanged')
        return m
    finally:
        page.close()


if __name__ == '__main__':
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=sys.argv[2] if len(sys.argv) > 2 else None)
        try:
            wide, _, narrow, _ = [verify(browser, sys.argv[1], *size) for size in [(1440, 900), (1024, 481), (320, 740), (667, 375)]]
            assert wide['boardWidth'] > narrow['boardWidth'] and wide['titleFont'] > narrow['titleFont'], (wide, narrow)
        finally:
            browser.close()
