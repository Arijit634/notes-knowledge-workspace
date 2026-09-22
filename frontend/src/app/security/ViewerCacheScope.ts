export type ViewerCacheScope =
  | Readonly<{ kind: 'anonymous' }>
  | Readonly<{ kind: 'authenticated'; viewerEpoch: string }>

export type ViewerEpochFactory = () => string

export const ANONYMOUS_VIEWER_SCOPE: ViewerCacheScope = Object.freeze({ kind: 'anonymous' })

/** A local, nonsecret cache-partition epoch; never a UserId or session identifier. */
export const randomViewerEpoch: ViewerEpochFactory = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
}

export function authenticatedViewerScope(factory: ViewerEpochFactory): ViewerCacheScope {
  const viewerEpoch = factory()
  if (!/^[A-Za-z0-9_-]{16,64}$/.test(viewerEpoch)) {
    throw new Error('Viewer scope could not be established.')
  }
  return Object.freeze({ kind: 'authenticated', viewerEpoch })
}
