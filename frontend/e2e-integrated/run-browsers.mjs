import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'

// Each engine gets a fresh real database/rate store. Sharing one stack across
// engines would legitimately exhaust the production registration budget.
// Never increase that budget, sleep through it, or retry rejected registrations.
const cli = createRequire(import.meta.url).resolve('@playwright/test/cli')
const engines = ['chromium', 'firefox', 'webkit'], selected = [], forwarded = []
const args = process.argv.slice(2)
for (let index = 0; index < args.length; index++) {
  if (args[index] === '--project') selected.push(args[++index])
  else if (args[index].startsWith('--project=')) selected.push(args[index].slice(10))
  else forwarded.push(args[index])
}
if (selected.some(engine => !engines.includes(engine))) throw new Error('Unknown integration browser')
let failed = false
for (const engine of selected.length ? [...new Set(selected)] : engines) {
  console.log(`Fresh isolated real backend qualification: ${engine}`)
  const result = spawnSync(process.execPath, [cli, 'test', '--config', 'playwright.integration.config.ts',
    ...forwarded, '--project=' + engine], { env: process.env, stdio: 'inherit', windowsHide: true })
  if (result.error || result.status !== 0) failed = true
}
process.exitCode = failed ? 1 : 0
