import { languages } from '@codemirror/language-data'
import { classHighlighter, highlightTree } from '@lezer/highlight'
import { $prose } from '@milkdown/kit/utils'
import { Plugin, PluginKey, type EditorState } from '@milkdown/kit/prose/state'
import { Decoration, DecorationSet, type EditorView } from '@milkdown/kit/prose/view'

type LanguageSupport = Awaited<ReturnType<(typeof languages)[number]['load']>>
const supported = new Set(['JavaScript','TypeScript','Python','Java','SQL','JSON','CSS','HTML','Markdown'])

/** Bounded token decorations, not another editor or an executable code preview. */
export const codeHighlighting = $prose(() => {
  const key = new PluginKey<DecorationSet>('noteCodeHighlighting')
  const loaded = new Map<string, LanguageSupport>(), pending = new Set<string>()
  let view: EditorView | null = null, alive = true
  function decorations(state: EditorState) {
    const items: Decoration[] = []
    let blocks = 0, remaining = 200_000
    state.doc.descendants((node, position) => {
      if (node.type.name !== 'code_block' || ++blocks > 50 || node.textContent.length > 100_000 || node.textContent.length > remaining) return
      remaining -= node.textContent.length
      const name = String(node.attrs.language ?? '').toLowerCase()
      const description = languages.find(language => supported.has(language.name)
        && (language.name.toLowerCase() === name || language.alias.includes(name)))
      if (!description) return
      const language = loaded.get(description.name)
      if (language) {
        try { highlightTree(language.language.parser.parse(node.textContent), classHighlighter, (from, to, className) => {
          if (to > from) items.push(Decoration.inline(position + 1 + from, position + 1 + to, { class: className }))
        }) } catch { /* Highlighting may fail; canonical content remains plain editable text. */ }
      } else if (!pending.has(description.name)) {
        pending.add(description.name)
        void description.load().then(language => {
          if (!alive) return
          loaded.set(description.name, language)
          view?.dispatch(view.state.tr.setMeta(key, true).setMeta('addToHistory', false))
        }).catch(() => { /* Keep plain code if a same-origin language chunk is unavailable. */ })
      }
    })
    return DecorationSet.create(state.doc, items)
  }
  return new Plugin<DecorationSet>({ key,
    state: { init: (_config, state) => decorations(state), apply: (transaction, old, _before, state) =>
      transaction.docChanged || transaction.getMeta(key) ? decorations(state) : old },
    props: { decorations: state => key.getState(state) },
    view: current => { alive = true; view = current; return { destroy: () => { alive = false; view = null; loaded.clear(); pending.clear() } } },
  })
})
