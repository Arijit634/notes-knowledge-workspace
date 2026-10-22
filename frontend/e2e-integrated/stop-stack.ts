import { access, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

// Runs before Playwright tears down the webServer (including on Windows where
// process-tree termination alone cannot promise Java/worker finally blocks).
export default async function stop() {
  const directory = process.env.NKW_INTEGRATION_RUNTIME
  if (!directory) return
  try { await access(resolve(directory, 'ready.json')) } catch { return }
  await writeFile(resolve(directory, 'stop'), '')
  for (let attempt = 0; attempt < 240; attempt++) {
    try {
      const receipt = JSON.parse(await readFile(resolve(directory, 'stopped.json'), 'utf8'))
      if (receipt.applicationClosed && receipt.disposableServicesClosed) return
    } catch { /* Wait for the explicit post-cleanup receipt, not an arbitrary sleep. */ }
    await delay(500)
  }
  throw new Error('Integrated backend graceful shutdown receipt missing; inspect exact-run services')
}
