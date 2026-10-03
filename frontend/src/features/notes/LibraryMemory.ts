import type { AuthRuntime } from '../auth/AuthRuntime'
import type { NoteCore } from './NotesApi'

type LibraryPosition = { lifecycle: NoteCore['lifecycle']; scroll: number; focused: string | null }
const positions = new WeakMap<AuthRuntime, LibraryPosition>()
export function libraryMemory(auth: AuthRuntime): LibraryPosition {
  let position = positions.get(auth)
  if (!position) {
    position = { lifecycle: 'active', scroll: 0, focused: null }
    positions.set(auth, position)
    auth.sensitive.register(() => { if (position) { position.lifecycle = 'active'; position.scroll = 0; position.focused = null } })
  }
  return position
}
