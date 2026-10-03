import { useEffect, useRef, useState } from 'react'
import { Editor, rootCtx, defaultValueCtx, editorViewCtx, editorViewOptionsCtx, serializerCtx, parserCtx, remarkCtx } from '@milkdown/kit/core'
import { commonmark, imageSchema, linkSchema } from '@milkdown/kit/preset/commonmark'
import { gfm } from '@milkdown/kit/preset/gfm'
import { history } from '@milkdown/kit/plugin/history'
import { Plugin } from '@milkdown/kit/prose/state'
import { toggleMark, setBlockType } from '@milkdown/kit/prose/commands'
import { wrapInList } from '@milkdown/kit/prose/schema-list'
import { $prose } from '@milkdown/kit/utils'
import { MarkdownView } from './MarkdownView'

export function safeWritingLink(value: string): string | null {
  try { const url = new URL(value); return ['https:', 'http:', 'mailto:'].includes(url.protocol) ? url.href : null } catch { return null }
}

function semanticTree(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(semanticTree)
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(Object.entries(value).filter(([key]) => !['position', 'spread'].includes(key)).sort(([a], [b]) => a.localeCompare(b)).map(([key, child]) => [key, semanticTree(child)]))
}

function hasUnusedDefinition(tree: unknown): boolean {
  const definitions: string[] = [], references = new Set<string>()
  function visit(value: unknown) {
    if (!value || typeof value !== 'object') return
    const node = value as { type?: string; identifier?: string; children?: unknown[] }
    if (node.identifier && ['definition', 'footnoteDefinition'].includes(node.type ?? '')) definitions.push(node.identifier)
    if (node.identifier && ['linkReference', 'imageReference', 'footnoteReference'].includes(node.type ?? '')) references.add(node.identifier)
    node.children?.forEach(visit)
  }
  visit(tree)
  return definitions.some(identifier => !references.has(identifier))
}

function roundTrips(editor: Editor, source: string): boolean {
  return editor.action(ctx => {
    const remark = ctx.get(remarkCtx), parsed = remark.parse(source)
    if (hasUnusedDefinition(parsed)) return false
    const roundTrip = ctx.get(serializerCtx)(ctx.get(editorViewCtx).state.doc)
    const before = remark.runSync(parsed, { value: source })
    const after = remark.runSync(remark.parse(roundTrip), { value: roundTrip })
    return JSON.stringify(semanticTree(before)) === JSON.stringify(semanticTree(after))
  })
}

/** The document reducer owns Markdown; this component owns only editing mechanics. */
export function FormattedEditor({ value, onChange, disabled = false }: {
  value: string; onChange: (value: string) => void; disabled?: boolean
}) {
  const host = useRef<HTMLDivElement>(null), instance = useRef<Editor | null>(null)
  const current = useRef(value), change = useRef(onChange), locked = useRef(disabled)
  const reconciling = useRef(false)
  const [source, setSource] = useState(false), [ready, setReady] = useState(false), [failed, setFailed] = useState(false)
  const [linkOpen, setLinkOpen] = useState(false), [url, setUrl] = useState(''), [linkError, setLinkError] = useState('')
  const [marks, setMarks] = useState<string[]>([])
  const [block, setBlock] = useState('paragraph')
  change.current = onChange; locked.current = disabled
  useEffect(() => {
    if (source || !host.current) return
    const mount = host.current
    let disposed = false
    let qualified = false
    setReady(false); setFailed(false)
    const editor = Editor.make().config(ctx => {
      ctx.set(rootCtx, mount)
      ctx.set(defaultValueCtx, current.current)
      ctx.update(imageSchema.key, original => context => ({ ...original(context),
        // Preserve the Markdown locator but never construct a fetching DOM element.
        toDOM: node => ['span', { class: 'writing-image', contenteditable: 'false' }, node.attrs.alt || 'Image (not loaded)'],
        parseDOM: [],
        parseMarkdown: { match: node => node.type === 'image', runner: (state, node, type) => {
          state.addNode(type, { src: node.url ?? '', alt: node.alt ?? '', title: node.title ?? '' })
        } },
      }))
      ctx.update(linkSchema.key, original => context => ({ ...original(context),
        toDOM: mark => ['a', { href: safeWritingLink(mark.attrs.href) ?? undefined,
          rel: 'noopener noreferrer', referrerpolicy: 'no-referrer', target: '_blank' }, 0],
      }))
      ctx.update(editorViewOptionsCtx, options => ({ ...options,
        editable: () => qualified && !locked.current,
        nodeViews: { ...options.nodeViews, list_item: (initial, view, getPos) => {
          const dom = document.createElement('li'), contentDOM = document.createElement('div'), check = document.createElement('button')
          let node = initial
          check.type = 'button'; check.contentEditable = 'false'; check.className = 'writing-task-check'
          check.setAttribute('role', 'checkbox'); check.setAttribute('aria-label', 'Task completed')
          function render() {
            check.hidden = node.attrs.checked == null
            check.setAttribute('aria-checked', String(node.attrs.checked === true))
            check.textContent = node.attrs.checked ? '✓' : ''
            dom.className = node.attrs.checked == null ? '' : 'writing-task'
          }
          check.addEventListener('mousedown', event => event.preventDefault())
          check.addEventListener('click', () => {
            const pos = getPos()
            if (locked.current || pos === undefined) return
            view.dispatch(view.state.tr.setNodeMarkup(pos, undefined, { ...node.attrs, checked: !node.attrs.checked }))
          })
          dom.append(check, contentDOM); render()
          return { dom, contentDOM, update: next => {
            if (next.type !== node.type) return false
            node = next; render(); return true
          }, stopEvent: event => check.contains(event.target as Node), ignoreMutation: mutation =>
            mutation.type !== 'selection' && (check.contains(mutation.target) || (mutation.type === 'attributes' && mutation.target === dom)) }
        } },
        attributes: { role: 'textbox', 'aria-label': 'Note body', 'aria-multiline': 'true', spellcheck: 'true' },
        handlePaste: (view, event) => {
          // Rich clipboard HTML is never parsed into DOM; ordinary text is always safe.
          const text = event.clipboardData?.getData('text/plain')
          if (text === undefined || locked.current) return true
          view.dispatch(view.state.tr.insertText(text)); return true
        },
        handleDrop: () => true,
      }))
    }).use(commonmark).use(gfm).use(history).use($prose(ctx => new Plugin({
      view: () => ({ update: (view, previous) => {
        if (disposed || reconciling.current) return
        const selected = view.state.storedMarks ?? view.state.selection.$from.marks()
        if (!view.state.selection.eq(previous.selection) || view.state.storedMarks !== previous.storedMarks || !view.state.doc.eq(previous.doc)) {
          const ancestors = Array.from({ length: view.state.selection.$from.depth }, (_, index) => view.state.selection.$from.node(index + 1).type.name)
          setMarks([...selected.map(mark => mark.type.name), ...ancestors])
          setBlock(ancestors.includes('heading') ? 'heading' : ancestors.includes('code_block') ? 'code_block' : 'paragraph')
        }
        if (view.state.doc.eq(previous.doc)) return
        const next = ctx.get(serializerCtx)(view.state.doc)
        if (next !== current.current) { current.current = next; change.current(next) }
      } }),
    })))
    const creation = editor.create().then(() => {
      if (disposed) return
      const faithful = roundTrips(editor, current.current)
      if (!faithful) {
        editor.action(ctx => ctx.get(editorViewCtx).setProps({ editable: () => false }))
        mount.replaceChildren(); setFailed(true); return
      }
      qualified = true
      editor.action(ctx => ctx.get(editorViewCtx).setProps({ editable: () => !locked.current }))
      instance.current = editor; setReady(true)
    }).catch(() => { if (!disposed) { mount.replaceChildren(); setFailed(true) } })
    return () => {
      disposed = true; instance.current = null
      // Creation is asynchronous; a previous instance must never clear a newer host.
      mount.replaceChildren()
      void creation.then(() => editor.destroy()).catch(() => undefined)
    }
  }, [source])
  useEffect(() => {
    if (value === current.current) return
    current.current = value
    const editor = instance.current
    if (editor) editor.action(ctx => {
      const view = ctx.get(editorViewCtx), doc = ctx.get(parserCtx)(value)
      // External reconciliation must not make old content reachable through undo.
      const State = view.state.constructor as typeof import('@milkdown/kit/prose/state').EditorState
      reconciling.current = true
      try { view.updateState(State.create({ schema: view.state.schema, doc, plugins: view.state.plugins })) }
      finally { reconciling.current = false }
      if (!roundTrips(editor, value)) {
        view.setProps({ editable: () => false }); host.current?.replaceChildren()
        instance.current = null; setReady(false); setFailed(true)
      }
    })
  }, [value])
  useEffect(() => { instance.current?.action(ctx => ctx.get(editorViewCtx).setProps({ editable: () => !disabled })) }, [disabled])
  function format(kind: string) {
    instance.current?.action(ctx => {
      const view = ctx.get(editorViewCtx), { schema } = view.state
      if (kind === 'task') {
        if (!Array.from({ length: view.state.selection.$from.depth }, (_, index) => view.state.selection.$from.node(index + 1).type.name).includes('list_item')) wrapInList(schema.nodes.bullet_list)(view.state, view.dispatch)
        const position = view.state.selection.$from
        for (let depth = position.depth; depth > 0; depth--) {
          const item = position.node(depth)
          if (item.type.name === 'list_item') { view.dispatch(view.state.tr.setNodeMarkup(position.before(depth), undefined, { ...item.attrs, checked: item.attrs.checked ?? false })); break }
        }
        view.focus(); return
      }
      const command = kind === 'bullet_list' || kind === 'ordered_list' ? wrapInList(schema.nodes[kind])
        : ['paragraph', 'heading', 'code_block'].includes(kind) ? setBlockType(schema.nodes[kind], kind === 'heading' ? { level: 2 } : undefined)
        : toggleMark(schema.marks[kind])
      command(view.state, view.dispatch, view); view.focus()
    })
  }
  return <div className="writing-adapter">
    <div className="writing-toolbar" role="toolbar" aria-label="Formatting">
      {!source && <><select aria-label="Text style" disabled={!ready || disabled} value={block} onChange={event => format(event.target.value)}>
        <option value="paragraph">Paragraph</option><option value="heading">Heading</option><option value="code_block">Code block</option></select>
        {([['strong', 'Bold', 'B'], ['emphasis', 'Italic', 'I'], ['bullet_list', 'Bulleted list', '• List'], ['ordered_list', 'Numbered list', '1. List'], ['inlineCode', 'Inline code', '</>']] as const).map(([kind, label, text]) =>
          <button key={kind} type="button" aria-label={label} title={label} aria-pressed={marks.includes(kind)} disabled={!ready || disabled}
            onMouseDown={event => event.preventDefault()} onClick={() => format(kind)}>{text}</button>)}
        <button type="button" disabled={!ready || disabled} onMouseDown={event => event.preventDefault()} onClick={() => setLinkOpen(value => !value)}>Link</button></>}
      {!source && <button type="button" disabled={!ready || disabled} onMouseDown={event => event.preventDefault()} onClick={() => format('task')}>Checklist</button>}
      <button type="button" className="source-toggle" aria-pressed={source} onClick={() => { setLinkOpen(false); setSource(value => !value) }}>{source ? 'Formatted writing' : 'Markdown source'}</button>
    </div>
    {linkOpen && <div className="writing-link"><label htmlFor="writing-link">Link address</label><input id="writing-link" value={url} onChange={event => setUrl(event.target.value)} placeholder="https://" />
      <button type="button" onClick={() => {
        const href = safeWritingLink(url)
        if (!href) { setLinkError('Use an https, http or email link.'); return }
        instance.current?.action(ctx => { const view = ctx.get(editorViewCtx); toggleMark(view.state.schema.marks.link, { href, title: null })(view.state, view.dispatch); view.focus() })
        setUrl(''); setLinkError(''); setLinkOpen(false)
      }}>Apply link</button><button type="button" onClick={() => setLinkOpen(false)}>Cancel</button>{linkError && <p role="alert">{linkError}</p>}</div>}
    {source ? <><label className="sr-only" htmlFor="note-markdown">Markdown</label><textarea id="note-markdown" className="writing-source" value={value} disabled={disabled} maxLength={1_000_000} rows={18}
      onChange={event => { current.current = event.target.value; onChange(event.target.value) }} /><details className="source-preview"><summary>Preview</summary><MarkdownView markdown={value} /></details></>
      : <><div ref={host} className="writing-surface" />{!ready && !failed && <p role="status">Preparing writing surface…</p>}
        {failed && <p role="alert">This note could not be opened in formatted view. Your original text is unchanged. Use Markdown source to inspect it.</p>}</>}
  </div>
}
