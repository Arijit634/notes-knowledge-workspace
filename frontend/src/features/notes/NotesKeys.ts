import type { ViewerCacheScope } from '../../app/security/ViewerCacheScope'

/** Private query keys contain only the viewer epoch, structural filters, and opaque locators. */
export const notePreferenceKeys = {
  current: (viewer: ViewerCacheScope) => ['notes', viewer, 'preference'] as const,
}

export const noteKeys = {
  attachments: (viewer: ViewerCacheScope, noteId: string) => ['notes', viewer, 'attachments', noteId] as const,
  attachment: (viewer: ViewerCacheScope, noteId: string, attachmentId: string) =>
    ['notes', viewer, 'attachment', noteId, attachmentId] as const,
  lists: (viewer: ViewerCacheScope) => ['notes', viewer, 'list'] as const,
  list: (viewer: ViewerCacheScope, filters: Readonly<{ lifecycle: string; sort: string }>) =>
    [...noteKeys.lists(viewer), filters] as const,
  core: (viewer: ViewerCacheScope, noteId: string) => ['notes', viewer, 'core', noteId] as const,
  history: (viewer: ViewerCacheScope, noteId: string) => ['notes', viewer, 'history', noteId] as const,
  versions: (viewer: ViewerCacheScope, noteId: string) => [...noteKeys.history(viewer, noteId), 'list'] as const,
  version: (viewer: ViewerCacheScope, noteId: string, versionId: string) =>
    [...noteKeys.history(viewer, noteId), 'detail', versionId] as const,
}
