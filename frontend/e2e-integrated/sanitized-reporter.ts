import type { Reporter, TestCase, TestResult, FullResult } from '@playwright/test/reporter'
import { writeFile } from 'node:fs/promises'
import { basename, resolve } from 'node:path'

/** No errors, steps, request payloads, DOM, cookies or environment dumps. */
export default class SanitizedReporter implements Reporter {
  private readonly rows: unknown[] = []
  private readonly counts: Record<string, number> = {}
  private globalErrors = 0
  onError() { this.globalErrors++ }
  onTestEnd(test: TestCase, result: TestResult) {
    this.counts[result.status] = (this.counts[result.status] ?? 0) + 1
    this.rows.push({ title: test.title, file: basename(test.location.file), line: test.location.line,
      project: test.parent.project()?.name, status: result.status, retry: result.retry, durationMs: result.duration,
      annotations: test.annotations.filter(item => item.type === 'qualification-limit'),
      attachments: result.attachments.map(item => ({ name: item.name, contentType: item.contentType,
        filename: item.path ? basename(item.path) : null })) })
  }
  async onEnd(result: FullResult) {
    const root = process.env.NKW_INTEGRATION_RUNTIME
    if (!root) throw new Error('Integration receipt directory missing')
    await writeFile(resolve(root, 'browser-receipt.json'), JSON.stringify({ createdUtc: new Date().toISOString(),
      status: result.status, durationMs: result.duration, counts: this.counts, globalErrors: this.globalErrors,
      mode: process.env.NKW_INTEGRATION_PACKAGED === '1' ? 'Spring-served production assets behind test TLS edge' : 'Vite TLS development proxy',
      actualBackend: true, externalMailAndObjectTransportControlled: true,
      liveProviderQualification: false, physicalDeviceQualification: false, tests: this.rows }, null, 2))
  }
}
