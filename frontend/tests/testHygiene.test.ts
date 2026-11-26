import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { test } from 'node:test';

// A failing assert.equal/deepEqual that holds a React-rendered DOM node grew past 2GB in 12s and systemd-oomd
// killed the whole terminal, other agents included (2026-11-10). Node checks go through tests/helpers/domAssert.ts,
// which passes booleans to assert. This sensor finds node-valued arguments to value asserts.
// `npm test` runs it first (pretest): test files run in parallel, so a node-holding failure elsewhere could take the
// terminal down before this file reports it (again on 2026-11-19, SCRUM-189).

const VALUE_ASSERT = /\bassert\.(?:equal|notEqual|strictEqual|notStrictEqual|deepEqual|notDeepEqual|deepStrictEqual|notDeepStrictEqual)\(/g;
/** Members whose value is a DOM node or a list of nodes: Testing Library queries and DOM lookups. */
const NODE_CALL = /^(?:(?:get|query|find)(?:All)?By\w+|querySelector(?:All)?|closest)(?:<[^>]*>)?\(/;
const NODE_PROPERTY = /^(?:activeElement|parentElement|firstElementChild|lastElementChild|nextElementSibling|previousElementSibling)$/;

/** Splits `a.b(c.d).e` into its top-level member links ['a', 'b(c.d)', 'e'], ignoring `?.` and `!`. */
function memberChain(expression: string): string[] {
  const links: string[] = [];
  let depth = 0;
  let from = 0;
  let quote: string | null = null;
  for (let i = 0; i < expression.length; i++) {
    const c = expression[i];
    if (quote) { if (c === '\\') i++; else if (c === quote) quote = null; continue; }
    if (c === '\'' || c === '"' || c === '`') quote = c;
    else if ('([{'.includes(c)) depth++;
    else if (')]}'.includes(c)) depth--;
    else if (c === '.' && depth === 0) { links.push(expression.slice(from, i).replace(/[?!]+$/, '')); from = i + 1; }
  }
  links.push(expression.slice(from).replace(/[?!]+$/, ''));
  return links;
}

/** Whether the expression's value is a DOM node (not a property of one): its last member link is a node lookup. */
function isNodeExpression(expression: string): boolean {
  const chain = memberChain(expression.replace(/\s+as\s+[\w<>[\]]+$/, '').trim());
  const last = chain.at(-1) ?? '';
  return chain.length > 1 && (NODE_CALL.test(last) ? last.endsWith(')') : NODE_PROPERTY.test(last));
}

/** Top-level arguments of the call that opens at `start` (just after its "("). */
function callArguments(source: string, start: number): string[] {
  const args: string[] = [];
  let depth = 0;
  let quote: string | null = null;
  let from = start;
  for (let i = start; i < source.length; i++) {
    const c = source[i];
    if (quote) { if (c === '\\') i++; else if (c === quote) quote = null; continue; }
    if (c === '\'' || c === '"' || c === '`') quote = c;
    else if (c === '/' && /[(,=:]\s*$/.test(source.slice(from, i))) { // regex literal
      for (i++; i < source.length && source[i] !== '/'; i++) if (source[i] === '\\' || source[i] === '[') { if (source[i] === '[') while (source[++i] !== ']'); else i++; }
    } else if ('([{'.includes(c)) depth++;
    else if (')]}'.includes(c)) { if (depth === 0) { args.push(source.slice(from, i).trim()); return args; } depth--; }
    else if (c === ',' && depth === 0) { args.push(source.slice(from, i).trim()); from = i + 1; }
  }
  return args;
}

export function nodeValuedAsserts(source: string): string[] {
  const found: string[] = [];
  for (const match of source.matchAll(VALUE_ASSERT)) {
    const [actual, expected] = callArguments(source, match.index! + match[0].length);
    const line = source.slice(0, match.index).split('\n').length;
    for (const arg of [actual, expected]) {
      if (arg && isNodeExpression(arg)) found.push(`${line}: ${arg}`);
    }
  }
  return found;
}

test('센서 자체: DOM 노드를 값으로 넘기는 단언은 잡고, 노드의 속성·boolean 비교는 통과시킨다', () => {
  const bad = [
    "assert.equal(document.activeElement, trigger);",
    "assert.equal(screen.queryByRole('dialog', { name: '채팅' }), null, '닫힘');",
    "assert.equal(screen.getByRole('dialog', { name: '채팅' }), dialog);",
    "assert.equal(within(sheet).queryByText(/a|b/), null);",
    "assert.equal(nav.querySelector('[data-alert]'), null);",
    "assert.deepEqual(button.closest('main'), main);",
    "assert.strictEqual(x, document.activeElement);",
  ];
  for (const line of bad) assert.equal(nodeValuedAsserts(line).length, 1, line);
  const fine = [
    "assert.equal(screen.getAllByTestId('map-area').length, 1);",
    "assert.equal(screen.getByTestId('visit-rank').textContent?.trim(), '4위');",
    "assert.equal((screen.getByRole('button') as HTMLButtonElement).disabled, true);",
    "assert.equal(track.closest('[aria-hidden=\"true\"]') !== null, true);",
    "assert.equal(like.querySelector('svg')?.classList.contains('fill'), false);",
    "assert.equal(campus.getAttribute('aria-label'), '캠퍼스');",
    "assertAbsent(screen.queryByRole('dialog'));",
  ];
  for (const line of fine) assert.deepEqual(nodeValuedAsserts(line), [], line);
});

test('테스트 파일은 DOM 노드를 assert.equal 등에 직접 넘기지 않는다 (tests/helpers/domAssert.ts 사용)', () => {
  const dir = new URL('./', import.meta.url);
  const offenders = readdirSync(dir).filter((name) => name.endsWith('.test.ts') && name !== 'testHygiene.test.ts')
    .flatMap((name) => nodeValuedAsserts(readFileSync(new URL(name, dir), 'utf8')).map((hit) => `tests/${name}:${hit}`));
  assert.deepEqual(offenders, [], 'assertAbsent(node) 또는 assertSameNode(a, b)로 바꾸세요. 실패 시 노드를 출력하며 메모리가 폭주합니다.');
});

test('복구한 계약 센서는 Java 파일을 readJava로 읽는다', () => {
  // Other historical sensors still use direct reads; expand this list when migrating them.
  const names = ['apiAccountSecurity', 'apiAdmin', 'apiAdminSupports', 'apiAdminUsers', 'apiMap', 'apiMyInfo',
    'apiMyProfile', 'apiPurchaseHistory', 'apiRankings', 'apiSupport', 'clickBatcher'];
  const directJavaRead = /readFileSync\s*\(\s*new URL\s*\(\s*['`][^'`]*\.java['`]/;
  assert.equal(directJavaRead.test("readFileSync(new URL('dto/Test.java', root), 'utf8')"), true);
  for (const name of names) {
    const source = readFileSync(new URL(`${name}.test.ts`, import.meta.url), 'utf8');
    assert.doesNotMatch(source, directJavaRead, `${name}: Java 파일은 readJava를 사용하세요`);
    assert.match(source, /\breadJava\b/, `${name}: Java 계약 센서를 유지하세요`);
  }
});

test('npm test는 이 센서를 먼저 따로 실행한다 (병렬 실행 중 다른 파일이 메모리를 터뜨리기 전에 멈춘다)', () => {
  const { scripts } = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')) as { scripts: Record<string, string> };
  assert.equal(scripts.pretest, 'node --import tsx --test tests/testHygiene.test.ts');
});
