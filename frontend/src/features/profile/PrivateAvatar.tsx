import { useEffect, useState } from 'react'
import type { AuthRuntime } from '../auth/AuthRuntime'
import { profileApi } from './ProfileApi'
import { Icon } from '../../app/ui/Icon'

/** Object custody belongs to this viewer/component lifetime, never persistent storage. */
export function PrivateAvatar({ auth }: { auth: AuthRuntime }) {
  const scope = auth.session.viewerScope
  const [url, setUrl] = useState<string | null>(null), [failed, setFailed] = useState(false)
  useEffect(() => {
    const controller = new AbortController()
    let localUrl: string | null = null
    setUrl(null); setFailed(false)
    void profileApi.avatarContent(auth, controller.signal).then(blob => {
      if (controller.signal.aborted || auth.session.viewerScope !== scope || auth.state !== 'authenticated') return
      localUrl = URL.createObjectURL(blob); setUrl(localUrl)
    }).catch(() => { if (!controller.signal.aborted && auth.session.viewerScope === scope) setFailed(true) })
    return () => { controller.abort(); if (localUrl) URL.revokeObjectURL(localUrl) }
  }, [auth, scope])
  return <span className="studio-avatar-symbol">{url ? <img src={url} alt="Your private profile avatar" style={{ width: '100%', height: '100%', objectFit: 'cover', borderRadius: 'inherit' }} />
    : <><Icon name="profile" /><span className="attachment-sr-only" role="status">{failed ? 'Private avatar unavailable' : 'Loading private avatar'}</span></>}</span>
}
