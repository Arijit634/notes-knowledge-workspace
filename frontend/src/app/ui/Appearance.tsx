import { useLayoutEffect, useSyncExternalStore } from 'react'
import { Icon } from './Icon'

type Appearance = 'system' | 'light' | 'dark'
const storageKey = 'nkw.appearance'
function rememberedAppearance(): Appearance {
  try { const value = window.localStorage.getItem(storageKey); return value === 'light' || value === 'dark' ? value : 'system' }
  catch { return 'system' }
}
let preference: Appearance = rememberedAppearance()
const listeners = new Set<() => void>()
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener) } }

/** Only a harmless display enum is remembered. Private/account state never enters this store. */
export function AppearanceRoot() {
  const selected = useSyncExternalStore(subscribe, () => preference)
  useLayoutEffect(() => {
    const media = typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-color-scheme: dark)') : null
    const apply = () => {
      const theme = selected === 'system' ? media?.matches ? 'dark' : 'light' : selected
      document.documentElement.dataset.theme = theme
      document.documentElement.style.colorScheme = theme
    }
    apply(); media?.addEventListener('change', apply)
    return () => media?.removeEventListener('change', apply)
  }, [selected])
  return null
}

export function AppearanceControl() {
  const selected = useSyncExternalStore(subscribe, () => preference)
  return <label className="appearance-control"><Icon name="appearance" /><span className="visually-hidden">Appearance</span>
    <select aria-label="Appearance" value={selected} onChange={event => {
      const value = event.target.value
      if (value === 'system' || value === 'light' || value === 'dark') {
        preference = value
        try { if (value === 'system') window.localStorage.removeItem(storageKey); else window.localStorage.setItem(storageKey, value) }
        catch { /* Restricted storage must not prevent using the app. */ }
        listeners.forEach(listener => listener())
      }
    }}><option value="system">System</option><option value="light">Light</option><option value="dark">Dark</option></select>
  </label>
}
