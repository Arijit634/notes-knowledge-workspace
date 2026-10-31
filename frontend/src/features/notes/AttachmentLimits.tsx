export function AttachmentLimits() {
  return <details className="attachment-limits"><summary>Supported files &amp; sizes</summary>
    <dl><div><dt>Photos</dt><dd>PNG or JPG · up to 5 MiB each</dd></div>
      <div><dt>Documents</dt><dd>PDF · up to 10 MiB and 100 pages</dd></div>
      <div><dt>Audio</dt><dd>WAV · up to 10 MiB and 5 minutes</dd></div>
      <div><dt>Video</dt><dd>MP4 · up to 20 MB and 5 minutes</dd></div></dl>
    <p>Videos need H.264, with optional AAC sound. Audio needs uncompressed PCM WAV. Password-protected PDFs, active forms and embedded files aren't supported.</p>
  </details>
}
