import { useEffect, useRef, useState, type DragEvent, type ReactNode } from 'react'

const hasFiles = (event: DragEvent | globalThis.DragEvent) => Array.from(event.dataTransfer?.types ?? []).includes('Files')

/** File drops reuse staging/upload. Text and URL drags never trigger remote fetching. */
export function FileDropSurface({ children, onFiles, disabled = false, label, className = '', inert }: {
  children: ReactNode; onFiles: (files: File[]) => void; disabled?: boolean; label: string; className?: string; inert?: boolean
}) {
  const [active, setActive] = useState(false), depth = useRef(0)
  const reset = () => { depth.current = 0; setActive(false) }
  useEffect(() => {
    // An outside file drop must not navigate away and lose a draft.
    const preventOutside = (event: globalThis.DragEvent) => { if (hasFiles(event)) event.preventDefault() }
    const drop = (event: globalThis.DragEvent) => { preventOutside(event); reset() }
    window.addEventListener('dragover', preventOutside); window.addEventListener('drop', drop)
    window.addEventListener('dragend', reset); window.addEventListener('blur', reset)
    return () => { window.removeEventListener('dragover', preventOutside); window.removeEventListener('drop', drop); window.removeEventListener('dragend', reset); window.removeEventListener('blur', reset) }
  }, [])
  useEffect(() => { if (disabled) reset() }, [disabled])
  return <div className={`${className} media-drop-surface${active && !disabled ? ' media-drop-active' : ''}`} role="region" aria-label={label} inert={inert}
    onDragEnterCapture={event => { if (!hasFiles(event)) return; event.preventDefault(); depth.current++; if (!disabled) setActive(true) }}
    onDragOverCapture={event => { if (!hasFiles(event)) return; event.preventDefault(); event.dataTransfer.dropEffect = disabled ? 'none' : 'copy'; if (!disabled) setActive(true) }}
    onDragLeaveCapture={() => { depth.current = Math.max(0, depth.current - 1); if (!depth.current) setActive(false) }}
    onDropCapture={event => { if (!hasFiles(event)) return; event.preventDefault(); event.stopPropagation(); reset(); if (!disabled) onFiles(Array.from(event.dataTransfer.files)) }}>
    {children}{active && !disabled && <div className="media-drop-overlay" role="status">Drop files to add them</div>}
  </div>
}
