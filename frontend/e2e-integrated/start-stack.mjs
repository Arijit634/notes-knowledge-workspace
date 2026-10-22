import { spawn } from 'node:child_process'
import { createWriteStream, existsSync } from 'node:fs'
import { access, readFile, writeFile } from 'node:fs/promises'
import { resolve, dirname, delimiter, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { createServer as httpsServer } from 'node:https'
import { request as backendRequest } from 'node:http'

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const runtime = process.env.NKW_INTEGRATION_RUNTIME
const tls = process.env.NKW_INTEGRATION_TLS_DIRECTORY
if (!tls) throw new Error('Explicit freshly trusted test TLS directory required')
if (!runtime || !resolve(runtime).startsWith(resolve(repo, '.runtime') + '/'.replace('/', process.platform === 'win32' ? '\\' : '/'))) {
  throw new Error('Explicit ignored repository runtime directory required')
}
// Prepare this with the Maven Wrapper on the same OS as this launcher.
const classpath = await readFile(resolve(repo, 'target/browser-classpath.txt'), 'utf8')
const complete = [resolve(repo, 'target/test-classes'), resolve(repo, 'target/classes'), classpath.trim()].join(delimiter)
const java = process.env.NKW_INTEGRATION_JAVA ?? (process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java')
const argfile = resolve(repo, 'target/browser-integration.args')
const quote = value => `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`
await writeFile(argfile, `-Xmx768m\n-XX:MaxDirectMemorySize=128m\n-XX:ActiveProcessorCount=2\n${quote('-XX:ErrorFile=' + resolve(runtime, 'hs_err_pid%p.log'))}\n-Duser.timezone=UTC\n${quote('-Djava.io.tmpdir=' + (process.env.TEMP ?? process.env.TMP ?? resolve(repo, 'target')))}\n-cp\n${quote(complete)}\norg.notesknowledge.BrowserIntegrationServer\n${quote(runtime)}\n`)
const environment = Object.fromEntries(Object.entries(process.env).filter(([key]) =>
  !/^(SPRING_|GOOGLE_|GEMINI_|GROQ_|AWS_|OCI_|IDENTITY_|KNOWLEDGE_)/i.test(key)))
const log = createWriteStream(resolve(repo, 'target', basename(runtime) + '.log'), { flags: 'wx' })
const child = spawn(java, ['@' + argfile], { cwd: repo, env: environment, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
child.stdout.pipe(log); child.stderr.pipe(log)
let exited = false, spawnFailed = false, stopping = false, vite, edge
async function closeFrontend() {
  await vite?.close()
  if (edge?.listening) await new Promise(resolve => edge.close(resolve))
}
child.on('error', () => { spawnFailed = true; exited = true })
child.on('exit', () => {
  exited = true
  if (existsSync(resolve(runtime, 'stop'))) stopping = true
  if (!stopping) { console.error('Real backend stopped unexpectedly'); process.exitCode = 1 }
  void closeFrontend(); log.end()
})
async function stop() {
  if (stopping) return
  stopping = true
  await closeFrontend()
  if (!exited) {
    await writeFile(resolve(runtime, 'stop'), '')
    for (let attempt = 0; attempt < 180 && !exited; attempt++) await delay(500)
    if (!exited) { console.error('Backend did not stop within its graceful shutdown budget'); process.exitCode = 1 }
  }
  log.end()
}
process.once('SIGINT', () => void stop())
process.once('SIGTERM', () => void stop())
try {
  let ready = false
  for (let attempt = 0; attempt < 480 && !exited; attempt++) {
    try { await access(resolve(runtime, 'ready.json')); ready = true; break } catch { await delay(500) }
  }
  if (!ready || spawnFailed || exited) throw new Error('Real integration backend startup failed; inspect the matching local target/browser-integration-*.log, never publish raw logs')
  const https = { pfx: await readFile(resolve(tls, 'localhost.p12')), passphrase: 'synthetic-localhost-only' }
  if (process.env.NKW_INTEGRATION_PACKAGED === '1') {
    await access(resolve(repo, 'target/classes/static/index.html'))
    // TLS test edge only; every page, hashed asset and API byte comes from Spring.
    // Fixed loopback upstream, no body/headers recorded and no auth interception.
    edge = httpsServer(https, (incoming, outgoing) => {
      const request = backendRequest({ hostname: '127.0.0.1', port: 18080, path: incoming.url,
        method: incoming.method, headers: incoming.headers }, response => {
        outgoing.writeHead(response.statusCode ?? 502, response.headers); response.pipe(outgoing)
      })
      request.on('error', () => { if (!outgoing.headersSent) outgoing.writeHead(503); outgoing.end() })
      incoming.on('aborted', () => request.destroy())
      outgoing.on('close', () => request.destroy())
      incoming.pipe(request)
    })
    await new Promise((resolve, reject) => { edge.once('error', reject); edge.listen(4175, '127.0.0.1', resolve) })
  } else {
    process.env.NKW_BACKEND_ORIGIN = 'http://127.0.0.1:18080'
    const { createServer } = await import('vite')
    vite = await createServer({ root: resolve(repo, 'frontend'), envDir: false,
      server: { host: '127.0.0.1', port: 4175, strictPort: true, https } })
    await vite.listen()
  }
  console.log('Real Spring/PostgreSQL browser stack ready; external mail and object service controlled; no live AI/OIDC')
} catch (failure) {
  await stop()
  throw failure
}
