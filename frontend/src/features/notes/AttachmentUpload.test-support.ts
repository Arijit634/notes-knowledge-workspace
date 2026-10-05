import { vi } from 'vitest'
import type { AttachmentCore } from './AttachmentsApi'

export const syntheticAttachment: AttachmentCore = {
  id: '01990a55-9e12-7ac4-8f5b-31aa4a91d402', noteId: '01990a55-9e12-7ac4-8f5b-31aa4a91d401',
  mediaKind: 'image', displayFilename: 'synthetic.png', mediaType: 'image/png', sizeBytes: 1024,
  width: 32, height: 24, durationSeconds: null, pageCount: null, storageState: 'stored', validationState: 'accepted',
  cleanupState: 'retained', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
}

/** Deterministic browser transport double. Never imported by application code. */
export class AttachmentXhrDouble {
  method = ''; path = ''; body: FormData | null = null; withCredentials = true
  status = 0; responseText = ''; responseURL = ''
  headers: Record<string, string> = {}; responseHeaders = new Headers()
  onload: ((event: ProgressEvent) => unknown) | null = null
  onerror: ((event: ProgressEvent) => unknown) | null = null
  ontimeout: ((event: ProgressEvent) => unknown) | null = null
  onabort: ((event: ProgressEvent) => unknown) | null = null
  upload = { onprogress: null as ((event: ProgressEvent) => unknown) | null, onload: null as ((event: ProgressEvent) => unknown) | null }
  constructor(private readonly onSend?: (xhr: AttachmentXhrDouble) => void) {}
  open = vi.fn((method: string, path: string, _async: boolean) => { this.method = method; this.path = path })
  setRequestHeader = vi.fn((name: string, value: string) => { this.headers[name] = value })
  getResponseHeader(name: string) { return this.responseHeaders.get(name) }
  send = vi.fn((body: FormData) => { this.body = body; this.onSend?.(this) })
  abort = vi.fn(() => { this.onabort?.(new ProgressEvent('abort')) })
  progress(loaded: number, total: number, lengthComputable = true) {
    this.upload.onprogress?.(new ProgressEvent('progress', { loaded, total, lengthComputable }))
  }
  uploaded() { this.upload.onload?.(new ProgressEvent('load')) }
  async respond(response: Response, url = new URL(this.path, window.location.origin).href) {
    this.status = response.status; this.responseHeaders = response.headers; this.responseText = await response.text(); this.responseURL = url
    await this.onload?.(new ProgressEvent('load'))
  }
  asXhr() { return this as unknown as XMLHttpRequest }
}
