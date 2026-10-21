import { useEffect, useRef } from 'react'
import type { AuthRuntime } from '../../features/auth/AuthRuntime'

/** Reject late completions after unmount or an authority-epoch transition. */
export function usePrivateScope(auth: AuthRuntime) {
  const scope = auth.session.viewerScope, alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  return () => alive.current && auth.state === 'authenticated' && auth.session.viewerScope === scope
}
