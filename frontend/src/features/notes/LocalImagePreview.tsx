import { useEffect, useState } from 'react'
import { Icon } from '../../app/ui/Icon'

/** Local previews contain freshly encoded raster pixels, never original file bytes. */
export async function rasterThumbnail(file: File): Promise<Blob | null> {
  if (file.size > 5 * 1024 * 1024 || typeof createImageBitmap !== 'function') return null
  const bytes = new Uint8Array(await file.slice(0, 12).arrayBuffer())
  const png = [137, 80, 78, 71, 13, 10, 26, 10].every((n, i) => bytes[i] === n)
  const jpeg = bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255
  if (!png && !jpeg) return null
  const bitmap = await createImageBitmap(file)
  try {
    if (!bitmap.width || !bitmap.height || bitmap.width * bitmap.height > 16_000_000) return null
    const canvas = document.createElement('canvas'), scale = Math.min(1, 320 / Math.max(bitmap.width, bitmap.height))
    canvas.width = Math.max(1, Math.round(bitmap.width * scale)); canvas.height = Math.max(1, Math.round(bitmap.height * scale))
    const context = canvas.getContext('2d'); if (!context) return null
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    return await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/png'))
  } finally { bitmap.close() }
}

export function LocalImagePreview({ file }: { file: File }) {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    let disposed = false, owned: string | null = null
    void rasterThumbnail(file).then(image => {
      if (disposed || !image) return
      owned = URL.createObjectURL(image); setUrl(owned)
    }).catch(() => { /* An undecodable preview keeps its file icon; upload validation remains authoritative. */ })
    return () => { disposed = true; if (owned) URL.revokeObjectURL(owned) }
  }, [file])
  return url ? <img src={url} alt="" /> : <Icon name="attach" />
}
