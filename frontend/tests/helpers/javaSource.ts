import { readFileSync } from 'node:fs';

// Swagger annotations are documentation, unlike validation annotations which are part of the contract.
// Swagger's @RequestBody and @Header are excluded: they share simple names with Spring's binding annotations.
const swagger = new Set(['Schema', 'Parameter', 'ArraySchema', 'Content', 'ExampleObject', 'ApiResponse', 'ApiResponses', 'Operation', 'Tag', 'Tags', 'Parameters', 'SecurityRequirement', 'SecurityRequirements', 'Extension', 'ExtensionProperty', 'ExternalDocumentation', 'Link', 'LinkParameter']);

export function stripSwagger(source: string): string {
  // Sticky (y) matches at lastIndex without copying the rest of the source on every character.
  const annotation = /@([A-Za-z_$][\w$]*)\b/y;
  let output = '';
  let i = 0;
  while (i < source.length) {
    // Preserve Java literals and comments, including text resembling annotations within them.
    if (source[i] === '"' || source[i] === "'") {
      const quote = source[i];
      const start = i++;
      while (i < source.length) {
        if (source[i] === '\\') { i += 2; continue; }
        if (source[i++] === quote) break;
      }
      output += source.slice(start, i);
      continue;
    }
    if (source.startsWith('//', i) || source.startsWith('/*', i)) {
      const block = source[i + 1] === '*';
      const end = source.indexOf(block ? '*/' : '\n', i + 2);
      const next = end < 0 ? source.length : end + (block ? 2 : 1);
      output += source.slice(i, next);
      i = next;
      continue;
    }
    annotation.lastIndex = i;
    const match = source[i] === '@' ? annotation.exec(source) : null;
    if (!match || !swagger.has(match[1])) { output += source[i++]; continue; }
    i += match[0].length;
    while (/\s/.test(source[i] ?? '') && i < source.length) i++;
    if (source[i] === '(') {
      let depth = 0;
      let quote = '';
      do {
        const ch = source[i++];
        if (quote) {
          if (ch === '\\') i++;
          else if (ch === quote) quote = '';
        } else if (ch === '"' || ch === "'") quote = ch;
        else if (ch === '(') depth++;
        else if (ch === ')') depth--;
      } while (i < source.length && depth > 0);
      if (depth !== 0) throw new Error(`Unclosed Swagger annotation: ${match[0]}`);
    }
    output += ' ';
  }
  return output;
}

export const readJava = (url: URL): string => stripSwagger(readFileSync(url, 'utf8'));
