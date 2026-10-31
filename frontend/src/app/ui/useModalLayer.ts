import { useLayoutEffect } from 'react'

const layers = new WeakMap<HTMLElement, { count: number; original: boolean }>()

/** Portals live beside the app root. Keep the app inert until the last dialog closes. */
export function useModalLayer() {
  useLayoutEffect(() => {
    const root = document.getElementById('root')
    if (!root) return
    const state = layers.get(root) ?? { count: 0, original: root.inert === true }
    state.count++; layers.set(root, state); root.inert = true
    return () => {
      if (--state.count === 0) { root.inert = state.original; layers.delete(root) }
    }
  }, [])
}
