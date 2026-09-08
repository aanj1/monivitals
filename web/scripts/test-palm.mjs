import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const output = mkdtempSync(join(tmpdir(), 'monivitals-palm-tests-'));
try {
  const compile = spawnSync(process.execPath, [
    'node_modules/typescript/bin/tsc', '--module', 'commonjs', '--moduleResolution', 'node',
    '--target', 'es2020', '--lib', 'es2020,dom', '--skipLibCheck', '--strict',
    '--outDir', output, '--rootDir', 'src',
    'src/palm/scan/_lib/ppg.ts', 'src/palm/scan/_lib/hand.ts', 'src/palm/scan/_lib/quality.ts',
  ], { stdio: 'inherit' });
  if (compile.status !== 0) process.exitCode = compile.status ?? 1;
  else {
    const files = process.argv.length > 2 ? process.argv.slice(2) : readdirSync('tests').filter((file) => file.endsWith('.test.cjs')).sort().map((file) => join('tests', file));
    const tests = spawnSync(process.execPath, ['--test', ...files], {
      stdio: 'inherit', env: { ...process.env, MONIVITALS_TEST_BUILD: resolve(output) },
    });
    process.exitCode = tests.status ?? 1;
  }
} finally { rmSync(output, { recursive: true, force: true }); }
