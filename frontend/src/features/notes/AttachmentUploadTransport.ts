import type { Etagged } from '../../app/api/ApiClient'
import { readResponseMetadata } from '../../app/api/ApiResponseMetadata'
import { ApiProtocolError, decodeProblemDetails } from '../../app/api/ProblemDetailsDecoder'
import type { CsrfManager } from '../../app/security/CsrfManager'
import { attachmentCore, type AttachmentCore } from './AttachmentsApi'

export class AttachmentUploadAbortedError extends Error {
  constructor() { super('Upload stopped in this tab.'); this.name = 'AttachmentUploadAbortedError' }
}
export type AttachmentUploadOptions = {
  signal?: AbortSignal
  onProgress?: (percent: number | null) => void
  onUploadComplete?: () => void
}

/** Only the Note-owned upload endpoint is accepted, not an arbitrary caller URL. */
export function attachmentUploadPath(noteId: string): string {
  if (!noteId || noteId.length > 128 || /[\\/:?#\s\u0000-\u001f\u007f]/.test(noteId) || noteId === '.' || noteId === '..') throw new ApiProtocolError()
  let path: string, target: URL
  try {
    path = `/api/notes/${encodeURIComponent(noteId)}/attachments`
    target = new URL(path, window.location.origin)
  } catch { throw new ApiProtocolError() }
  if (target.origin !== window.location.origin || target.pathname !== path || target.search || target.hash) throw new ApiProtocolError()
  return path
}

/** Dedicated multipart upload; JSON/byte reads remain with the Fetch ApiClient. */
export async function uploadAttachment(csrf: CsrfManager, noteId: string, file: File,
  options: AttachmentUploadOptions = {}, createXhr: () => XMLHttpRequest = () => new XMLHttpRequest()): Promise<Etagged<AttachmentCore>> {
  const path = attachmentUploadPath(noteId)
  if (options.signal?.aborted) throw new AttachmentUploadAbortedError()
  const proof = await csrf.forUnsafeRequest()
  if (options.signal?.aborted) throw new AttachmentUploadAbortedError()
  return new Promise((resolve, reject) => {
    const xhr = createXhr()
    let settled = false
    const finish = (failure?: unknown, value?: Etagged<AttachmentCore>) => {
      if (settled) return
      settled = true
      options.signal?.removeEventListener('abort', abort)
      xhr.onload = xhr.onerror = xhr.ontimeout = xhr.onabort = null
      xhr.upload.onprogress = xhr.upload.onload = null
      if (failure !== undefined) reject(failure); else resolve(value!)
    }
    const abort = () => { xhr.abort(); finish(new AttachmentUploadAbortedError()) }
    xhr.onabort = () => finish(new AttachmentUploadAbortedError())
    xhr.onerror = xhr.ontimeout = () => finish(new ApiProtocolError())
    xhr.upload.onprogress = event => {
      const valid = event.lengthComputable && Number.isFinite(event.total) && event.total > 0
        && Number.isFinite(event.loaded) && event.loaded >= 0 && event.loaded <= event.total
      options.onProgress?.(valid ? Math.floor(event.loaded / event.total * 100) : null)
    }
    xhr.upload.onload = () => options.onUploadComplete?.()
    xhr.onload = async () => {
      try {
        // Post-response redirect detection only: XHR does not expose redirect control.
        if (!xhr.responseURL) throw new ApiProtocolError()
        const final = new URL(xhr.responseURL)
        if (final.origin !== window.location.origin || final.pathname !== path || final.search || final.hash
            || final.username || final.password) throw new ApiProtocolError()
        if (xhr.status < 200 || xhr.status > 599 || xhr.responseText.length > 16_384) throw new ApiProtocolError()
        const headers = new Headers()
        for (const name of ['Content-Type', 'ETag', 'Location', 'Retry-After']) {
          const value = xhr.getResponseHeader(name); if (value !== null) headers.set(name, value)
        }
        const response = new Response(xhr.responseText, { status: xhr.status, headers })
        if (xhr.status >= 400) throw await decodeProblemDetails(response)
        if (xhr.status !== 201 || headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase() !== 'application/json') throw new ApiProtocolError()
        const entry = attachmentCore(JSON.parse(xhr.responseText) as unknown, readResponseMetadata(response).etag, noteId)
        if (readResponseMetadata(response).location !== `${path}/${encodeURIComponent(entry.value.id)}`) throw new ApiProtocolError()
        finish(undefined, entry)
      } catch (failure) {
        finish(failure instanceof SyntaxError || failure instanceof TypeError ? new ApiProtocolError() : failure)
      }
    }
    try {
      xhr.open('POST', path, true)
      // False excludes cross-origin credentials; same-origin session cookies are still sent.
      xhr.withCredentials = false
      xhr.setRequestHeader('Accept', 'application/json, application/problem+json')
      xhr.setRequestHeader('X-CSRF-TOKEN', proof)
      options.signal?.addEventListener('abort', abort, { once: true })
      if (options.signal?.aborted) { abort(); return }
      const body = new FormData(); body.append('file', file)
      xhr.send(body)
    } catch { finish(new ApiProtocolError()) }
  })
}
