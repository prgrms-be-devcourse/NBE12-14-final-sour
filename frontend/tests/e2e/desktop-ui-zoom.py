"""Check mock PC UI at actual Chromium tab zoom and one-pixel layout boundaries.

Requires Python Playwright and a local Vite server with VITE_API_MODE=mock (`npm run dev:mock`).
"""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory

from playwright.sync_api import expect, sync_playwright

SPEC = importlib.util.spec_from_file_location('desktop_ui', Path(__file__).with_name('desktop-ui.py'))
assert SPEC is not None and SPEC.loader is not None
ui = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ui)


def capture(page, path):
    session = page.context.new_cdp_session(page)
    try:
        png = session.send('Page.captureScreenshot', {'captureBeyondViewport': False})['data']
        path.write_bytes(base64.b64decode(png))
    finally:
        session.detach()


def layout(page, wide):
    expect(page.locator('[data-game-workspace="wide"]')).to_have_count(int(wide))
    if not wide:
        expect(page.get_by_role('navigation', name='주요 메뉴').get_by_role('button')).to_have_count(5)
    size = page.evaluate('''() => ({ width: innerWidth, height: innerHeight, dpr: devicePixelRatio,
        scrollWidth: document.documentElement.scrollWidth })''')
    assert size['scrollWidth'] <= size['width'], size
    return {**size, 'wide': wide}


def set_zoom(context, page, factor):
    worker = context.service_workers[0] if context.service_workers else context.wait_for_event('serviceworker')
    assert worker.evaluate('''async factor => {
        const [tab] = await chrome.tabs.query({active: true});
        await chrome.tabs.setZoom(tab.id, factor);
        return chrome.tabs.getZoom(tab.id);
    }''', factor) == factor
    page.wait_for_function('factor => Math.abs(devicePixelRatio - factor) < .02', arg=factor)


def check_rankings(page, panel, path):
    panel.get_by_role('tab', name='순위').click()
    rankings = panel.get_by_role('tabpanel', name='순위')
    scroll = rankings.get_by_role('region', name='순위 목록')
    own = rankings.locator('.game-ranking-self')
    expect(own).to_be_in_viewport(ratio=1)
    expect(own.locator('.game-ranking-self-badge')).to_have_text('내 학교')
    before = own.bounding_box()
    rows = scroll.locator('.game-ranking-row')
    expect(rows).to_have_count(12)
    scroll.evaluate("el => el.scrollTo({ top: el.scrollHeight, behavior: 'instant' })")
    expect(rows).to_have_count(22)
    assert own.bounding_box() == before
    assert scroll.bounding_box()['y'] + scroll.bounding_box()['height'] < before['y']
    capture(page, path)
    panel.get_by_role('tab', name='지도', exact=True).click()
    return True


def check_wide(page, key, out):
    result = layout(page, True)
    panel = page.get_by_role('complementary', name='전국 경쟁 패널')
    area = panel.get_by_test_id('map-area')
    regions = panel.get_by_role('group', name='권역 선택')
    search = panel.get_by_role('textbox', name='학교 검색')
    page.evaluate('document.fonts.ready.then(() => true)')
    font = search.evaluate('e => getComputedStyle(e).fontFamily')
    assert font.split(',')[0].strip('"\' ') == 'Maplestory', font
    menu = ui.menu_metrics(page.locator('.game-panel-tabs button, .game-center-nav button'))
    assert len(menu) == 8 and all(m['fits'] and m['raised'] and m['iconLeft'] for m in menu), menu
    chips = ui.chip_metrics(regions)
    assert chips['gridFits'], chips
    region_flow = ui.map_region_flow(page, panel)
    assert region_flow['focusFits'] and region_flow['focusVisible'], region_flow
    capture(page, out / f'{key}-region-focus.png')
    maps = {}
    for region in ui.REGIONS:
        regions.get_by_role('button', name=region, exact=True).click()
        expect(area).to_have_attribute('data-region', region)
        maps[region] = ui.pin_metrics(area)
        assert maps[region]['passed'] and maps[region]['total'] > 0, (region, maps[region])
    jeju = ui.map_background(panel)
    assert jeju['matchesPanel'], jeju
    capture(page, out / f'{key}-jeju.png')
    search_deltas = ui.map_search_selection(panel)
    rankings_pinned = check_rankings(page, panel, out / f'{key}-rankings.png')
    shop = ui.shop_metrics(page)
    assert shop['aligned'], shop
    capture(page, out / f'{key}-shop.png')
    page.get_by_role('button', name='캠퍼스로 돌아가기').click()
    return {**result, 'menuCount': len(menu), 'chips': chips['rows'], 'regionGrid': chips,
            'regionKeyboard': region_flow, 'maps': maps, 'jeju': jeju, 'shop': shop,
            'searchMapTopDeltas': search_deltas, 'rankingsPinned': rankings_pinned}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mock-url', default='http://127.0.0.1:4316/')
    parser.add_argument('--chromium', help='Executable path; omit to use Chromium installed via playwright install chromium')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as pw, TemporaryDirectory() as directory:
        folder = Path(directory)
        browser = pw.chromium.launch(channel='chromium', executable_path=args.chromium)
        page = browser.new_page(viewport={'width': 1023, 'height': 720}, reduced_motion='reduce')
        page.goto(args.mock_url)
        expect(page.get_by_role('navigation', name='주요 메뉴')).to_be_visible()
        boundaries = {}
        for width, height, wide in ((1023, 720, False), (1024, 720, True), (1024, 480, False), (1024, 481, True)):
            page.set_viewport_size({'width': width, 'height': height})
            result = layout(page, wide)
            if wide:
                panel = page.get_by_role('complementary', name='전국 경쟁 패널')
                regions = panel.get_by_role('group', name='권역 선택')
                page.evaluate('document.fonts.ready.then(() => true)')
                chips = ui.chip_metrics(regions)
                assert chips['gridFits'], chips
                result['regionGrid'] = chips
                ui.campus_corner_flow(page)
                result['campusCorners'] = True
            boundaries[f'{width}x{height}'] = result
            capture(page, args.out / f'boundary-{width}x{height}.png')
        browser.close()

        extension = folder / 'zoom-extension'
        extension.mkdir()
        (extension / 'manifest.json').write_text(json.dumps({
            'manifest_version': 3, 'name': 'Local zoom test', 'version': '1', 'permissions': ['tabs'],
            'background': {'service_worker': 'worker.js'},
        }))
        (extension / 'worker.js').write_text('chrome.runtime.onInstalled.addListener(() => {});')
        zoom = {}
        for width, window_name in ((1536, 'wide'), (1440, 'cutoff')):
            context = pw.chromium.launch_persistent_context(
                folder / window_name, channel='chromium', executable_path=args.chromium,
                no_viewport=True, reduced_motion='reduce', args=[
                    f'--window-size={width},960', '--force-device-scale-factor=1',
                    f'--disable-extensions-except={extension}', f'--load-extension={extension}',
                ],
            )
            page = context.pages[0]
            page.bring_to_front()
            ui.ready(page, args.mock_url)
            for factor in (1.25, 1.5):
                set_zoom(context, page, factor)
                key = f'{window_name}-{round(factor * 100)}'
                if window_name == 'cutoff' and factor == 1.5:
                    zoom[key] = layout(page, False)
                    capture(page, args.out / f'{key}.png')
                else:
                    zoom[key] = check_wide(page, key, args.out)
            context.close()
    report = {'boundaries': boundaries, 'zoom': zoom}
    (args.out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for key, value in [*boundaries.items(), *zoom.items()]:
        print(key, value['width'], value['height'], value['dpr'], 'wide' if value['wide'] else 'compact')


if __name__ == '__main__':
    main()
