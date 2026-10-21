/** UX preflight only. Magic bytes, codecs, pages and duration remain server authority. */
export function attachmentPreflight(file: File): string | null {
  const suffix = file.name.toLowerCase().split('.').pop()
  const limits: Record<string, { bytes: number; types: string[]; label: string }> = {
    png: { bytes: 5 * 1024 * 1024, types: ['image/png'], label: '5 MiB' },
    jpg: { bytes: 5 * 1024 * 1024, types: ['image/jpeg'], label: '5 MiB' },
    jpeg: { bytes: 5 * 1024 * 1024, types: ['image/jpeg'], label: '5 MiB' },
    pdf: { bytes: 10 * 1024 * 1024, types: ['application/pdf'], label: '10 MiB' },
    wav: { bytes: 10 * 1024 * 1024, types: ['audio/wav', 'audio/x-wav', 'audio/wave'], label: '10 MiB' },
    mp4: { bytes: 20_000_000, types: ['video/mp4'], label: '20,000,000 bytes' },
  }
  const rule = suffix ? limits[suffix] : undefined
  if (!rule || (file.type && file.type !== 'application/octet-stream' && !rule.types.includes(file.type))) return 'Choose a PNG, JPEG, PDF, PCM WAV or video-only AVC MP4.'
  if (!file.size) return 'Choose a file that is not empty.'
  return file.size > rule.bytes ? `That file is too large. The limit for this format is ${rule.label}.` : null
}
