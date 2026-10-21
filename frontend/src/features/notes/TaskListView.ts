import { $view } from '@milkdown/kit/utils'
import { listItemSchema } from '@milkdown/kit/preset/commonmark'

/** Native keyboard/screen-reader checkbox; no content HTML or editor-owned uploads. */
export const taskListView = $view(listItemSchema.node, () => (initial, view, getPos) => {
  let node = initial
  const dom = document.createElement('li'), contentDOM = document.createElement('div')
  const checkbox = document.createElement('input')
  checkbox.type = 'checkbox'; checkbox.contentEditable = 'false'
  checkbox.setAttribute('aria-label', 'Complete task')
  const sync = () => {
    dom.className = node.attrs.checked === null ? 'studio-list-item' : 'studio-task-item'
    checkbox.hidden = node.attrs.checked === null
    checkbox.checked = node.attrs.checked === true
    checkbox.disabled = !view.editable
  }
  const change = () => {
    const position = getPos()
    if (!view.editable || position === undefined) return
    view.dispatch(view.state.tr.setNodeMarkup(position, undefined, { ...node.attrs, checked: checkbox.checked }))
  }
  checkbox.addEventListener('change', change)
  dom.append(checkbox, contentDOM); sync()
  return { dom, contentDOM, update: next => {
    if (next.type !== node.type) return false
    node = next; sync(); return true
  }, stopEvent: event => event.target === checkbox,
  ignoreMutation: mutation => mutation.target === checkbox,
  destroy: () => checkbox.removeEventListener('change', change) }
})
