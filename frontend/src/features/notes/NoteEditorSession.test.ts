import { describe, expect, it } from 'vitest'
import { emptyEditorSession, isDirty, noteEditorSession } from './NoteEditorSession'
import type { NoteCore } from './NotesApi'

const note: NoteCore = {
  id: '01990a55-9e12-7ac4-8f5b-31aa4a91d401', title: 'Original', markdown: 'First',
  lifecycle: 'active', pinned: false, tags: [], aiEnabled: false,
  createdAt: '2026-09-30T00:00:00Z', updatedAt: '2026-09-30T00:00:00Z',
}
const first = { value: note, etag: '"first"' }
const second = { value: { ...note, title: 'Server change' }, etag: '"second"' }

describe('NoteEditorSession', () => {
  it.each([
    { pinned: true, lifecycle: 'active' as const },
    { pinned: false, lifecycle: 'active' as const },
    { pinned: true, lifecycle: 'archived' as const },
    { pinned: true, lifecycle: 'trashed' as const },
  ])('adopts a same-tab organization command without advancing the Save baseline: %j', organization => {
    const loaded = noteEditorSession(emptyEditorSession, { type: 'load', server: first })
    const edited = noteEditorSession(noteEditorSession(loaded,
      { type: 'edit', field: 'title', value: ' Exact title ' }),
    { type: 'edit', field: 'markdown', value: 'Exact\nMarkdown ' })
    const server = { value: { ...note, ...organization }, etag: '"organization"' }
    const updated = noteEditorSession(edited, { type: 'coreCommandSucceeded', server })
    expect(updated.draft).toEqual(edited.draft)
    expect(updated.baseline).toEqual(loaded.baseline)
    expect(updated.serverVersion).toEqual(server)
    expect(updated.etag).toBe(server.etag)
    expect(isDirty(updated)).toBe(true)
    expect(updated.serverChangedWhileDirty).toBe(false)
    expect(noteEditorSession(updated, { type: 'observed', server })).toEqual(updated)
  })

  it('adopts same-tab tags and validator without changing the exact dirty draft or Save baseline', () => {
    const loaded = noteEditorSession(emptyEditorSession, { type: 'load', server: first })
    const edited = noteEditorSession(noteEditorSession(loaded,
      { type: 'edit', field: 'title', value: ' Unsaved title ' }),
    { type: 'edit', field: 'markdown', value: 'Exact\n**draft** ' })
    const server = { value: { ...note, tags: ['Films'] }, etag: '"tagged"' }
    const tagged = noteEditorSession(edited, { type: 'coreCommandSucceeded', server })
    expect(tagged.draft).toEqual(edited.draft)
    expect(tagged.baseline).toEqual(loaded.baseline)
    expect(tagged.etag).toBe('"tagged"')
    expect(tagged.serverVersion?.value.tags).toEqual(['Films'])
    expect(tagged.phase).toBe('Dirty')
    expect(tagged.serverChangedWhileDirty).toBe(false)
    expect(noteEditorSession(tagged, { type: 'observed', server })).toEqual(tagged)
  })

  it('keeps dirty draft and concurrency base when server refetches', () => {
    const loaded = noteEditorSession(emptyEditorSession, { type: 'load', server: first })
    const edited = noteEditorSession(loaded, { type: 'edit', field: 'markdown', value: 'My local draft' })
    const observed = noteEditorSession(edited, { type: 'observed', server: second })
    expect(observed.draft.markdown).toBe('My local draft')
    expect(observed.etag).toBe('"first"')
    expect(observed.serverChangedWhileDirty).toBe(true)
  })

  it('retains exact draft on failure and conflict until deliberate reconciliation', () => {
    const loaded = noteEditorSession(emptyEditorSession, { type: 'load', server: first })
    const edited = noteEditorSession(loaded, { type: 'edit', field: 'title', value: 'My title' })
    const failed = noteEditorSession(noteEditorSession(edited, { type: 'saving' }), { type: 'failed' })
    expect(failed.draft.title).toBe('My title')
    expect(failed.phase).toBe('SaveFailed')
    const unresolved = noteEditorSession(failed, { type: 'conflict' })
    expect(unresolved.serverVersion).toBeNull()
    expect(noteEditorSession(unresolved, { type: 'rebase' }).etag).toBe('"first"')
    const conflict = noteEditorSession(unresolved, { type: 'observed', server: second })
    expect(conflict.draft.title).toBe('My title')
    expect(conflict.etag).toBe('"first"')
    const refined = noteEditorSession(conflict, { type: 'edit', field: 'markdown', value: 'Refined draft' })
    expect(refined.phase).toBe('Conflict')
    expect(refined.draft.markdown).toBe('Refined draft')
    const rebased = noteEditorSession(refined, { type: 'rebase' })
    expect(rebased.draft.title).toBe('My title')
    expect(rebased.draft.markdown).toBe('Refined draft')
    expect(rebased.etag).toBe('"second"')
    expect(isDirty(rebased)).toBe(true)
    expect(noteEditorSession(conflict, { type: 'reloadServer' }).draft.title).toBe('Server change')
  })

  it('advances baseline only after authoritative Save', () => {
    const loaded = noteEditorSession(emptyEditorSession, { type: 'load', server: first })
    const edited = noteEditorSession(loaded, { type: 'edit', field: 'title', value: 'Saved title' })
    const saving = noteEditorSession(edited, { type: 'saving' })
    const saved = noteEditorSession(saving, { type: 'saved', server: {
      value: { ...note, title: 'Saved title' }, etag: '"saved"',
    } })
    expect(saved.phase).toBe('Clean')
    expect(saved.etag).toBe('"saved"')
    expect(isDirty(saved)).toBe(false)
  })
})
