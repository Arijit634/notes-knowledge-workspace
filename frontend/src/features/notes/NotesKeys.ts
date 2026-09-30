import type { ViewerCacheScope } from '../../app/security/ViewerCacheScope'

/** Private query keys contain only the viewer epoch, structural filters, and opaque locators. */
export const notePreferenceKeys = {
  current: (viewer: ViewerCacheScope) => ['notes', viewer, 'preference'] as const,
}

export const noteKeys = {
  lists: (viewer: ViewerCacheScope) => ['notes', viewer, 'list'] as const,
  list: (viewer: ViewerCacheScope, filters: Readonly<{ lifecycle: string; sort: string }>) =>
    [...noteKeys.lists(viewer), filters] as const,
  core: (viewer: ViewerCacheScope, noteId: string) => ['notes', viewer, 'core', noteId] as const,
}
