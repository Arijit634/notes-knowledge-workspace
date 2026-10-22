import assert from 'node:assert/strict'
import childProcess from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { runNpm, verifyBackend } from './package-application.mjs'

test('npm CLI paths and arguments containing shell syntax stay literal argv', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'packaging & boundary-'))
  try {
    const cli = join(directory, 'npm & echo INJECTED & cli.cjs')
    const output = join(directory, 'argv.json')
    await writeFile(cli, "require('node:fs').writeFileSync(process.argv[2], JSON.stringify(process.argv.slice(3)))")
    const argumentsWithShellSyntax = ['ci', '& echo INJECTED', '; echo INJECTED', '$(echo INJECTED)', '%PATH%', 'a b']
    runNpm(cli, [output, ...argumentsWithShellSyntax], directory)
    assert.deepEqual(JSON.parse(await readFile(output, 'utf8')), argumentsWithShellSyntax)
  } finally { await rm(directory, { recursive: true, force: true }) }
})

test('failed direct Node commands fail packaging', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'packaging-failure-'))
  try {
    const cli = join(directory, 'npm.cjs')
    await writeFile(cli, 'process.exitCode = 7')
    assert.throws(() => runNpm(cli, [], directory), /Application packaging command failed/)
  } finally { await rm(directory, { recursive: true, force: true }) }
})

test('fixed Maven wrapper runs from a directory containing spaces and shell metacharacters', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'packaging & boundary-'))
  try {
    if (process.platform === 'win32') {
      await writeFile(join(directory, 'mvnw.cmd'), '@echo off\r\n> wrapper-ran.txt echo reached\r\nexit /b 0\r\n')
    } else {
      await writeFile(join(directory, 'mvnw'), '#!/bin/sh\nprintf reached > wrapper-ran.txt\n', { mode: 0o755 })
    }
    verifyBackend(directory)
    assert.equal((await readFile(join(directory, 'wrapper-ran.txt'), 'utf8')).trim(), 'reached')
  } finally { await rm(directory, { recursive: true, force: true }) }
})

for (const platform of ['win32', 'linux']) {
  test(`Maven ${platform} receives no interpolated shell command or path`, (t) => {
    const cwd = join(tmpdir(), 'repository & echo INJECTED')
    let invocation
    t.mock.method(childProcess, 'spawnSync', (...args) => { invocation = args; return { status: 0 } })
    verifyBackend(cwd, platform)
    const [command, args, options] = invocation
    assert.equal(options.cwd, cwd)
    assert.equal(options.shell, false)
    assert.equal(options.windowsHide, true)
    if (platform === 'win32') {
      assert.equal(command, 'cmd.exe')
      assert.deepEqual(args, ['/d', '/s', '/c', '.\\mvnw.cmd --batch-mode --no-transfer-progress -Pwith-frontend clean verify -q'])
    } else {
      assert.equal(command, './mvnw')
      assert.deepEqual(args, ['--batch-mode', '--no-transfer-progress', '-Pwith-frontend', 'clean', 'verify', '-q'])
    }
  })
}

test('Maven launch errors and nonzero exit statuses fail packaging', (t) => {
  const spawn = t.mock.method(childProcess, 'spawnSync', () => ({ status: 9 }))
  assert.throws(() => verifyBackend(tmpdir(), 'win32'), /Application packaging command failed/)
  spawn.mock.mockImplementation(() => ({ error: new Error('controlled launch failure'), status: null }))
  assert.throws(() => verifyBackend(tmpdir(), 'linux'), /Application packaging command failed/)
})
