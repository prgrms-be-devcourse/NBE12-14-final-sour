export function extractCodeDigits(text: string): string {
  return text.match(/(?:^|\D)(\d{6})(?!\d)/)?.[1] ?? text.replace(/\D/g, '');
}
