import childProcess from 'node:child_process'
import { existsSync } from 'node:fs'
import { readFile, stat } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// Build one same-origin application artifact. No deployment, registry, credentials
// or implicit dependency upgrades. Every backend test remains enabled.
export function runNpm(npm, args, cwd) {
  // Node receives the bundled CLI path as one argv item, never as shell text.
  const result = childProcess.spawnSync(process.execPath, [npm, ...args], {
    cwd, env: process.env, shell: false, stdio: 'inherit', windowsHide: true,
  })
  requireSuccess(result)
}

function requireSuccess(result) {
  if (result.error || result.status !== 0) throw new Error('Application packaging command failed')
}

export function verifyBackend(root, platform = process.platform) {
  // Keep the Windows batch boundary separate: its entire command is a literal.
  // No executable/CLI path or environment value is interpolated into shell text.
  if (platform === 'win32') {
    requireSuccess(childProcess.spawnSync('cmd.exe', ['/d', '/s', '/c',
      '.\\mvnw.cmd --batch-mode --no-transfer-progress -Pwith-frontend clean verify -q'],
    { cwd: root, env: process.env, shell: false, stdio: 'inherit', windowsHide: true }))
  } else {
    requireSuccess(childProcess.spawnSync('./mvnw',
      ['--batch-mode', '--no-transfer-progress', '-Pwith-frontend', 'clean', 'verify', '-q'],
      { cwd: root, env: process.env, shell: false, stdio: 'inherit', windowsHide: true }))
  }
}

async function packageApplication() {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
  if (process.version !== 'v24.21.0') throw new Error('Use approved Node 24.21.0')
  const npm = [resolve(dirname(process.execPath), 'node_modules/npm/bin/npm-cli.js'),
    resolve(dirname(process.execPath), '../lib/node_modules/npm/bin/npm-cli.js')].find(existsSync)
  if (!npm) throw new Error('Approved bundled npm CLI unavailable')
  const version = childProcess.spawnSync(process.execPath, [npm, '--version'], { shell: false, encoding: 'utf8' })
  if (version.status !== 0 || version.stdout.trim() !== '11.19.0') throw new Error('Use approved npm 11.19.0')
  runNpm(npm, ['ci'], resolve(root, 'frontend'))
  runNpm(npm, ['run', 'build'], resolve(root, 'frontend'))
  const index = await readFile(resolve(root, 'frontend/dist/index.html'), 'utf8')
  if (!/src="\/assets\/[A-Za-z0-9_-]+-[A-Za-z0-9_-]{8,}\.js"/.test(index) || index.includes('/src/')) throw new Error('Production asset graph missing')
  verifyBackend(root)
  const jar = resolve(root, 'target/notes-knowledge-workspace-0.0.1-SNAPSHOT.jar')
  if ((await stat(jar)).size === 0) throw new Error('Packaged application missing')
  console.log('Frontend and backend verified and packaged together; no deployment performed')
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await packageApplication()
