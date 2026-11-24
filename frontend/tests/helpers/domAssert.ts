import assert from 'node:assert/strict';

/*
 * Compare DOM nodes by identity without handing the nodes to assert.
 * A failing assert.equal(node, …) keeps the node as `actual`, and printing a React-rendered node walks its
 * fiber graph (__reactFiber$…): a failing test grew past 2GB in 12s and systemd-oomd killed the terminal
 * (2026-11-10, .agent-workflow/261110-t2-t5-mobile-stats/deferred.md). These pass booleans only.
 */

const describe = (node: unknown) =>
  node instanceof Object && 'tagName' in node ? `<${String((node as Element).tagName).toLowerCase()}${(node as Element).getAttribute?.('aria-label') ? ` aria-label="${(node as Element).getAttribute('aria-label')}"` : ''}>` : String(node);

/** The query found nothing. */
export function assertAbsent(node: unknown, message?: string) {
  assert.ok(node === null || node === undefined, message ?? `expected nothing, found ${describe(node)}`);
}

/** Both are the same node (focus return, containment checks). */
export function assertSameNode(actual: unknown, expected: unknown, message?: string) {
  assert.ok(actual === expected, message ?? `expected ${describe(expected)}, got ${describe(actual)}`);
}
