import { useEffect, useId, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useModalLayer } from './useModalLayer'

export function restoreFocus(opener: HTMLElement | null, fallback: HTMLElement | null = document.getElementById('notes-content')) {
  let hidden = false
  for (let node: HTMLElement | null = opener; node; node = node.parentElement) {
    const style = getComputedStyle(node)
    if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || style.opacity === '0') { hidden = true; break }
  }
  const valid = opener?.isConnected && !opener.closest('[inert],[hidden]')
    && !opener.matches(':disabled') && !hidden
  let target = valid ? opener : fallback
  const activeModal = Array.from(document.querySelectorAll<HTMLElement>('[role="dialog"][aria-modal="true"]')).at(-1)
  // A closing/nested dialog must never steal focus from a newly opened modal.
  if (activeModal && (!target || !activeModal.contains(target))) {
    target = activeModal.querySelector<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled)') ?? activeModal
  }
  target?.focus({ preventScroll: true })
}
export function ConfirmDialog({ title, children, onCancel, onConfirm, confirmText, cancelText = 'Cancel', busy = false, opener, role = 'dialog', description }: {
  title: string; children?: ReactNode; onCancel: () => void; onConfirm: () => void; confirmText: string; cancelText?: string; busy?: boolean; opener?: HTMLElement | null; role?: 'dialog' | 'alertdialog'; description?: string
}) {
  useModalLayer()
  const ref = useRef<HTMLDivElement>(null), id = useId()
  useEffect(() => {
    const previous = opener ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null)
    ref.current?.querySelector<HTMLButtonElement>('button')?.focus({ preventScroll: true })
    // Wait until the parent has removed inert from the workspace.
    return () => { requestAnimationFrame(() => restoreFocus(previous)) }
  }, [])
  useEffect(() => { if (busy) ref.current?.focus({ preventScroll: true }) }, [busy])
  return createPortal(<div className="dialog-backdrop"><div ref={ref} tabIndex={-1} className="confirm-dialog" role={role} aria-modal="true" aria-labelledby={id} aria-describedby={description ? `${id}-description` : undefined}
    onKeyDown={event => {
      if (event.key === 'Escape' && !busy) { event.preventDefault(); onCancel() }
      if (event.key === 'Tab') {
        const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'))
        event.preventDefault(); const at = buttons.indexOf(document.activeElement as HTMLButtonElement)
        if (buttons.length) buttons[(at + (event.shiftKey ? buttons.length - 1 : 1)) % buttons.length]?.focus()
        else event.currentTarget.focus({ preventScroll: true })
      }
    }}><h2 id={id}>{title}</h2>{description && <p id={`${id}-description`}>{description}</p>}{children}<div className="actions"><button type="button" className="button-secondary" disabled={busy} onClick={onCancel}>{cancelText}</button>
      <button type="button" disabled={busy} onClick={onConfirm}>{confirmText}</button></div></div></div>, document.body)
}
