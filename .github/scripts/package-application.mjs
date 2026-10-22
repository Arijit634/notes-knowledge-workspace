import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { readFile, stat } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// Build one same-origin application artifact. No deployment, registry, credentials
// or implicit dependency upgrades. Every backend test remains enabled.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
if (process.version !== 'v24.21.0') throw new Error('Use approved Node 24.21.0')
const npm = [resolve(dirname(process.execPath), 'node_modules/npm/bin/npm-cli.js'),
  resolve(dirname(process.execPath), '../lib/node_modules/npm/bin/npm-cli.js')].find(existsSync)
if (!npm) throw new Error('Approved bundled npm CLI unavailable')
function run(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, env: process.env, stdio: 'inherit', windowsHide: true })
  if (result.error || result.status !== 0) throw new Error('Application packaging command failed')
}
const version = spawnSync(process.execPath, [npm, '--version'], { encoding: 'utf8' })
if (version.status !== 0 || version.stdout.trim() !== '11.19.0') throw new Error('Use approved npm 11.19.0')
run(process.execPath, [npm, 'ci'], resolve(root, 'frontend'))
run(process.execPath, [npm, 'run', 'build'], resolve(root, 'frontend'))
const index = await readFile(resolve(root, 'frontend/dist/index.html'), 'utf8')
if (!/src="\/assets\/[A-Za-z0-9_-]+-[A-Za-z0-9_-]{8,}\.js"/.test(index) || index.includes('/src/')) throw new Error('Production asset graph missing')
if (process.platform === 'win32') {
  run('cmd.exe', ['/d', '/s', '/c', '.\\mvnw.cmd --batch-mode --no-transfer-progress -Pwith-frontend clean verify -q'], root)
} else {
  run('./mvnw', ['--batch-mode', '--no-transfer-progress', '-Pwith-frontend', 'clean', 'verify', '-q'], root)
}
const jar = resolve(root, 'target/notes-knowledge-workspace-0.0.1-SNAPSHOT.jar')
if ((await stat(jar)).size === 0) throw new Error('Packaged application missing')
console.log('Frontend and backend verified and packaged together; no deployment performed')
