import { cleanup, render, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { LocalImagePreview, rasterThumbnail } from './LocalImagePreview'

const signature = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0])
const file = (bytes: Uint8Array = signature) => ({ size: bytes.length, slice: () => ({ arrayBuffer: async () => bytes.buffer }) }) as unknown as File
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
function decoder(width = 640, height = 480) {
  const bitmap = { width, height, close: vi.fn() }, decode = vi.fn().mockResolvedValue(bitmap), draw = vi.fn()
  vi.stubGlobal('createImageBitmap', decode)
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: draw } as never)
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(callback => callback(new Blob(['synthetic raster'], { type: 'image/png' })))
  return { bitmap, decode, draw }
}
it('creates only a bounded freshly encoded PNG preview and revokes its URL on removal', async () => {
  const { bitmap, draw } = decoder(), create = vi.fn((_image: Blob) => 'blob:generated-raster'), revoke = vi.fn()
  vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: revoke })
  const source = file(), view = render(<LocalImagePreview file={source} />)
  await waitFor(() => expect(view.container.querySelector('img')?.getAttribute('src')).toBe('blob:generated-raster'))
  expect(create.mock.calls[0][0]).not.toBe(source); expect((create.mock.calls[0][0] as Blob).type).toBe('image/png')
  expect(draw).toHaveBeenCalledWith(bitmap, 0, 0, 320, 240); expect(bitmap.close).toHaveBeenCalledOnce()
  view.unmount(); expect(revoke).toHaveBeenCalledWith('blob:generated-raster')
})
it.each(['<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"/>', '<html><script>alert(1)</script></html>'])('never decodes or previews active text disguised as a PNG: %s', async text => {
  const { decode } = decoder()
  expect(await rasterThumbnail(file(new TextEncoder().encode(text)))).toBeNull(); expect(decode).not.toHaveBeenCalled()
})
it('rejects oversized source and decoded pixel bounds without an original-file URL', async () => {
  const { bitmap, decode, draw } = decoder(5000, 5000)
  expect(await rasterThumbnail({ ...file(), size: 5 * 1024 * 1024 + 1 } as File)).toBeNull(); expect(decode).not.toHaveBeenCalled()
  expect(await rasterThumbnail(file())).toBeNull(); expect(draw).not.toHaveBeenCalled(); expect(bitmap.close).toHaveBeenCalledOnce()
})
it('does not create a late URL after the draft preview is unmounted', async () => {
  const { bitmap } = decoder(), create = vi.fn()
  let resolve!: (value: ImageBitmap) => void
  vi.stubGlobal('createImageBitmap', () => new Promise<ImageBitmap>(done => { resolve = done }))
  vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: vi.fn() })
  const view = render(<LocalImagePreview file={file()} />)
  await waitFor(() => expect(resolve).toBeTypeOf('function')); view.unmount(); resolve(bitmap as unknown as ImageBitmap)
  await waitFor(() => expect(bitmap.close).toHaveBeenCalledOnce()); expect(create).not.toHaveBeenCalled()
})
