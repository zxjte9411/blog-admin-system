import { spawnSync } from 'node:child_process';

const args = process.argv.slice(2).filter((argument) => argument !== '--run');
const result = spawnSync('npx', ['ng', 'test', '--watch=false', ...args], {
  stdio: 'inherit',
  shell: process.platform === 'win32',
});

process.exit(result.status ?? 1);
