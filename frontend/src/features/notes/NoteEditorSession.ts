import type { Etagged } from '../../app/api/ApiClient'
import type { NoteCore } from './NotesApi'

export type EditorPhase = 'Clean' | 'Dirty' | 'Saving' | 'SaveFailed' | 'Conflict'
export type EditorDraft = { title: string; markdown: string }
export type EditorSession = {
  id: string | null
  baseline: EditorDraft
  draft: EditorDraft
  etag: string | null
  phase: EditorPhase
  pending: EditorDraft | null
  serverChangedWhileDirty: boolean
  serverVersion: Etagged<NoteCore> | null
}
export type EditorAction =
  | { type: 'load'; server: Etagged<NoteCore> }
  | { type: 'edit'; field: keyof EditorDraft; value: string }
  | { type: 'saving' }
  | { type: 'saved'; server: Etagged<NoteCore> }
  | { type: 'failed' }
  | { type: 'conflict' }
  | { type: 'observed'; server: Etagged<NoteCore> }
  | { type: 'rebase' }
  | { type: 'reloadServer' }

export const emptyEditorSession: EditorSession = {
  id: null, baseline: { title: '', markdown: '' }, draft: { title: '', markdown: '' },
  etag: null, phase: 'Clean', pending: null, serverChangedWhileDirty: false,
  serverVersion: null,
}

export function isDirty(session: EditorSession): boolean {
  return session.draft.title !== session.baseline.title
    || session.draft.markdown !== session.baseline.markdown
}

function fromServer(server: Etagged<NoteCore>): EditorSession {
  const baseline = { title: server.value.title, markdown: server.value.markdown }
  return { id: server.value.id, baseline, draft: baseline, etag: server.etag,
    phase: 'Clean', pending: null, serverChangedWhileDirty: false, serverVersion: server }
}

export function noteEditorSession(state: EditorSession, action: EditorAction): EditorSession {
  switch (action.type) {
    case 'load': return fromServer(action.server)
    case 'edit': {
      const draft = { ...state.draft, [action.field]: action.value }
      return { ...state, draft, phase: state.phase === 'Conflict' ? 'Conflict'
        : draft.title === state.baseline.title && draft.markdown === state.baseline.markdown
          ? 'Clean' : 'Dirty' }
    }
    case 'saving': return { ...state, phase: 'Saving', pending: { ...state.draft } }
    case 'saved': {
      const baseline = { title: action.server.value.title, markdown: action.server.value.markdown }
      const draft = state.pending && (state.draft.title !== state.pending.title
        || state.draft.markdown !== state.pending.markdown) ? state.draft : baseline
      return { ...state, id: action.server.value.id, baseline, draft, etag: action.server.etag,
        pending: null, phase: draft.title === baseline.title && draft.markdown === baseline.markdown
          ? 'Clean' : 'Dirty', serverChangedWhileDirty: false, serverVersion: action.server }
    }
    case 'failed': return { ...state, pending: null, phase: 'SaveFailed' }
    case 'conflict': return { ...state, pending: null, phase: 'Conflict',
      serverChangedWhileDirty: true, serverVersion: null }
    case 'observed':
      if (state.etag === action.server.etag) return state
      if (isDirty(state) || state.phase === 'Saving' || state.phase === 'Conflict') {
        return { ...state, serverChangedWhileDirty: true, serverVersion: action.server }
      }
      return fromServer(action.server)
    case 'rebase': {
      if (!state.serverVersion) return state
      const baseline = { title: state.serverVersion.value.title,
        markdown: state.serverVersion.value.markdown }
      return { ...state, baseline, etag: state.serverVersion.etag,
        phase: state.draft.title === baseline.title && state.draft.markdown === baseline.markdown
          ? 'Clean' : 'Dirty', serverChangedWhileDirty: false }
    }
    case 'reloadServer': return state.serverVersion ? fromServer(state.serverVersion) : state
  }
}
