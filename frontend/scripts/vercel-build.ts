import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

export function vercelBuildArgs(env: Readonly<Record<string, string | undefined>>): string[] {
  return env.VERCEL_ENV === 'preview' ? ['build', '--mode', 'campus-test'] : ['build'];
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = vercelBuildArgs(process.env);
  console.log(`[vercel-build] VERCEL_ENV=${process.env.VERCEL_ENV ?? ''} → vite ${args.join(' ')}`);
  const vite = fileURLToPath(new URL('../node_modules/vite/bin/vite.js', import.meta.url));
  const result = spawnSync(process.execPath, [vite, ...args], { stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.signal) process.kill(process.pid, result.signal);
  else process.exitCode = result.status ?? 1;
}
