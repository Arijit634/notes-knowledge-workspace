import { Component, useEffect, useRef, useState, type ReactNode } from 'react'
import { Milkdown, MilkdownProvider, useEditor } from '@milkdown/react'
import { CrepeBuilder } from '@milkdown/crepe/builder'
import { toolbar } from '@milkdown/crepe/feature/toolbar'
import { editorViewCtx, editorViewOptionsCtx, parserCtx, remarkCtx, serializerCtx } from '@milkdown/kit/core'
import type { CmdKey } from '@milkdown/kit/core'
import type { Ctx } from '@milkdown/kit/ctx'
import type { EditorView } from '@milkdown/kit/prose/view'
import type { Node as ProseNode } from '@milkdown/kit/prose/model'
import { addRowAfter, addColumnAfter, deleteRow, deleteColumn, deleteTable } from '@milkdown/kit/prose/tables'
import type { Command } from '@milkdown/kit/prose/state'
import { uploadConfig } from '@milkdown/kit/plugin/upload'
import { imageSchema, linkSchema, toggleStrongCommand, toggleEmphasisCommand,
  wrapInBulletListCommand, wrapInOrderedListCommand, wrapInBlockquoteCommand,
  createCodeBlockCommand, turnIntoTextCommand, wrapInHeadingCommand, toggleLinkCommand } from '@milkdown/kit/preset/commonmark'
import { insertTableCommand } from '@milkdown/kit/preset/gfm'
import { undoCommand, redoCommand } from '@milkdown/kit/plugin/history'
import { callCommand, replaceAll } from '@milkdown/kit/utils'
import { MarkdownView, safeLink } from './MarkdownView'
import { taskListView } from './TaskListView'
import { codeHighlighting } from './CodeHighlighting'
import '@milkdown/kit/prose/view/style/prosemirror.css'
import '@milkdown/crepe/theme/common/toolbar.css'
import '@milkdown/kit/prose/tables/style/tables.css'

export type MarkdownEditorProps = { value: string; onChange: (value: string) => void; disabled?: boolean }

function importDraft(ctx: Ctx, source: string) {
  replaceAll(source, true)(ctx)
  const view = ctx.get(editorViewCtx), last = view.state.doc.lastChild
  // Crepe needs an editable exit after a terminal quote/list/table/code block.
  // Normalize only its view, outside undo history, while draft emission is suppressed.
  // Otherwise its trailing plugin can make an untouched Note dirty on first focus.
  if (last && !['paragraph', 'heading'].includes(last.type.name)) {
    view.dispatch(view.state.tr.insert(view.state.doc.content.size, view.state.schema.nodes.paragraph.create()).setMeta('addToHistory', false))
  }
  return view.state.doc
}

/** Positions and serialization layout are not meaning. Everything else must survive. */
export function markdownMeaning(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(markdownMeaning)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value)
    .filter(([key]) => key !== 'position' && key !== 'spread')
    .sort(([a], [b]) => a.localeCompare(b)).map(([key, child]) => [key, markdownMeaning(child)]))
  return value
}

/** Conservative early fallback; the editor additionally checks its actual AST round trip. */
export function requiresSource(markdown: string): boolean {
  const prose = markdown.replace(/(^|\n)[ \t]*(`{3,}|~{3,})[^\n]*\n[\s\S]*?\n[ \t]*\2[ \t]*(?=\n|$)/g, '\n')
    .replace(/(`+)[^\n]*?\1/g, '')
  return /(^|\n)\s*(?:<|\[\^[^\]]+\]:|\[[^\]]+\]:|:::|\$\$|---\s*\n\w+\s*:)/.test(prose)
    || /(?<!\\)<\/?[a-zA-Z!][^>]*>/.test(prose)
}

function RichEditor({ value, onChange, disabled, onUnsupported }: MarkdownEditorProps & { onUnsupported: () => void }) {
  const [linkOpen, setLinkOpen] = useState(false), [link, setLink] = useState('')
  const [inTable, setInTable] = useState(false)
  const [textStyle, setTextStyle] = useState('paragraph'), [codeLanguage, setCodeLanguage] = useState('')
  const current = useRef({ value, onChange, disabled, onUnsupported })
  current.current = { value, onChange, disabled, onUnsupported }
  const emitted = useRef(value), armed = useRef(false), suppressed = useRef(false)
  const imported = useRef<{ doc: ProseNode; source: string } | null>(null)
  const { loading, get } = useEditor(root => {
    // Start empty; only import content after the supported-AST/fidelity check.
    const crepe = new CrepeBuilder({ root, defaultValue: '' })
      .addFeature(toolbar, { buildToolbar: builder => {
        const group = builder.getGroup('function').group
        group.items = group.items.filter(item => item.key !== 'link')
      } })
    crepe.editor.use(taskListView).use(codeHighlighting)
    crepe.editor.config(ctx => {
      ctx.update(uploadConfig.key, previous => ({ ...previous, uploader: async () => [] }))
      ctx.update(editorViewOptionsCtx, previous => ({ ...previous,
        editable: () => !current.current.disabled,
        dispatchTransaction: function (this: EditorView, transaction) {
          const previous = this.state.doc
          this.updateState(this.state.apply(transaction))
          const from = this.state.selection.$from
          setInTable(Array.from({ length: from.depth }, (_, i) => from.node(i + 1).type.name).some(name => name === 'table'))
          setTextStyle(from.parent.type.name === 'heading' ? `h${from.parent.attrs.level}` : from.parent.type.name === 'code_block' ? 'code' : 'paragraph')
          setCodeLanguage(String(from.parent.attrs.language ?? ''))
          if (!armed.current || suppressed.current || current.current.disabled || previous.eq(this.state.doc)) return
          // Milkdown's listener is debounced. Save must see the latest transaction,
          // even when Ctrl/Cmd+S immediately follows the final typed character.
          const markdown = imported.current?.doc.eq(this.state.doc) ? imported.current.source : ctx.get(serializerCtx)(this.state.doc)
          emitted.current = markdown
          current.current.onChange(markdown)
        },
        attributes: { role: 'textbox', 'aria-label': 'Note body', 'aria-multiline': 'true', spellcheck: 'true' },
        handleDOMEvents: {
          // Files belong exclusively to the authenticated Attachment transport.
          drop: (_view, event) => !!event.dataTransfer?.files.length,
          paste: (_view, event) => {
            const clipboard = event.clipboardData
            if (clipboard?.files.length || requiresSource(clipboard?.getData('text/plain') ?? '')
                || /<(?:img|script|iframe)\b/i.test(clipboard?.getData('text/html') ?? '')) {
              event.preventDefault(); current.current.onUnsupported(); return true
            }
            return false
          },
        },
      }))
      ctx.update(imageSchema.key, original => context => ({ ...original(context),
        toDOM: node => ['span', { 'data-image-placeholder': '' }, `[Image: ${node.attrs.alt || 'Not loaded'}]`],
        parseDOM: [],
        parseMarkdown: { match: node => node.type === 'image', runner: (state, node, type) => state.addNode(type, {
          src: node.url ?? '', alt: node.alt ?? '', title: node.title ?? '',
        }) },
      }))
      ctx.update(linkSchema.key, original => context => ({ ...original(context),
        // Editing a link never navigates or dispatches a URL. Preview has a safe URL policy.
        toDOM: () => ['span', { class: 'studio-editor-link' }, 0],
      }))
    })
    const create = crepe.create, destroy = crepe.destroy
    crepe.create = async () => { try { return await create() } catch { current.current.onUnsupported(); throw new Error('Writing tools unavailable') } }
    crepe.destroy = async () => { try { return await destroy() } catch { throw new Error('Writing tools cleanup unavailable') } }
    return crepe
  }, [])
  useEffect(() => {
    if (loading) return
    const editor = get()
    if (!editor) return
    editor.action(ctx => {
      const source = current.current.value
      const parser = ctx.get(remarkCtx)
      const allowed = new Set(['root','text','paragraph','heading','strong','emphasis','delete','blockquote','list','listItem','break','thematicBreak','inlineCode','code','link','image','table','tableRow','tableCell'])
      const supported = (node: unknown): boolean => {
        if (!node || typeof node !== 'object') return false
        const value = node as { type: string; children?: unknown[] }
        return allowed.has(value.type) && (!value.children || value.children.every(supported))
      }
      if (!supported(parser.parse(source))) { current.current.onUnsupported(); return }
      const serialized = ctx.get(serializerCtx)(ctx.get(parserCtx)(source))
      if (JSON.stringify(markdownMeaning(parser.parse(source))) !== JSON.stringify(markdownMeaning(parser.parse(serialized)))) {
        current.current.onUnsupported(); return
      }
      suppressed.current = true
      imported.current = { doc: importDraft(ctx, source), source }
      const parent = ctx.get(editorViewCtx).state.selection.$from.parent
      setTextStyle(parent.type.name === 'heading' ? `h${parent.attrs.level}` : parent.type.name === 'code_block' ? 'code' : 'paragraph')
      setCodeLanguage(String(parent.attrs.language ?? ''))
      emitted.current = source
      suppressed.current = false; armed.current = true
    })
    return () => { armed.current = false }
  }, [loading])
  useEffect(() => {
    if (loading || !armed.current || emitted.current === value) return
    suppressed.current = true
    get()?.action(ctx => { imported.current = { doc: importDraft(ctx, value), source: value } })
    emitted.current = value
    suppressed.current = false
  }, [value, loading])
  useEffect(() => {
    if (!loading) get()?.action(ctx => ctx.get(editorViewCtx).setProps({ editable: () => !current.current.disabled }))
  }, [disabled, loading])
  const command = <T,>(key: CmdKey<T>, payload?: T) => {
    if (loading || disabled) return
    get()?.action(callCommand(key, payload))
    get()?.action(ctx => ctx.get(editorViewCtx).focus())
  }
  function tableCommand(command: Command) {
    get()?.action(ctx => { const view = ctx.get(editorViewCtx); command(view.state, view.dispatch, view); view.focus() })
  }
  const controls: [string, () => void][] = [
    ['Bold', () => command(toggleStrongCommand.key)], ['Italic', () => command(toggleEmphasisCommand.key)],
    ['Bullets', () => command(wrapInBulletListCommand.key)], ['Numbered list', () => command(wrapInOrderedListCommand.key)],
    ['Quote', () => command(wrapInBlockquoteCommand.key)], ['Code block', () => command(createCodeBlockCommand.key)],
    ['Table', () => command(insertTableCommand.key, { row: 3, col: 3 })], ['Undo', () => command(undoCommand.key)], ['Redo', () => command(redoCommand.key)],
    ['Table: add row', () => tableCommand(addRowAfter)], ['Table: add column', () => tableCommand(addColumnAfter)],
    ['Table: remove row', () => tableCommand(deleteRow)], ['Table: remove column', () => tableCommand(deleteColumn)], ['Table: remove table', () => tableCommand(deleteTable)],
    ['Checklist', () => { command(wrapInBulletListCommand.key); get()?.action(ctx => {
      const view = ctx.get(editorViewCtx), position = view.state.selection.$from
      for (let depth = position.depth; depth > 0; depth--) if (position.node(depth).type.name === 'list_item') {
        view.dispatch(view.state.tr.setNodeMarkup(position.before(depth), undefined, { ...position.node(depth).attrs, checked: false })); break
      }
    }) }],
    ['Link', () => setLinkOpen(v => !v)],
  ]
  const primary = new Set(['Bold','Italic','Bullets','Checklist','Link'])
  const button = ([label, run]: [string, () => void]) =>
    <button key={label} type="button" disabled={loading || disabled || (label.startsWith('Table:') && !inTable)} onMouseDown={event => event.preventDefault()}
      onClick={run}>{label}</button>
  return <><div className="studio-format" role="group" aria-label="Text formatting">
    <select aria-label="Text style" value={textStyle} disabled={loading || disabled} onChange={e => {
      if (e.target.value === 'paragraph') command(turnIntoTextCommand.key)
      else if (e.target.value === 'code') command(createCodeBlockCommand.key)
      else command(wrapInHeadingCommand.key, Number(e.target.value.slice(1)))
    }}><option value="paragraph">Paragraph</option><option value="h1">Heading 1</option><option value="h2">Heading 2</option><option value="h3">Heading 3</option><option value="code">Code block</option></select>
    {controls.filter(([label]) => primary.has(label)).map(button)}
    <details className="studio-more-format"><summary>More</summary><div>{controls.filter(([label]) => !primary.has(label)).map(button)}</div></details></div>
    {textStyle === 'code' && <label className="studio-code-language">Code language<select aria-label="Code language" value={codeLanguage} disabled={loading || disabled} onChange={event => {
      const language = event.target.value
      get()?.action(ctx => { const view = ctx.get(editorViewCtx), from = view.state.selection.$from
        if (from.parent.type.name === 'code_block') view.dispatch(view.state.tr.setNodeMarkup(from.before(), undefined, { ...from.parent.attrs, language }))
        view.focus()
      })
    }}>{!['','javascript','typescript','python','java','sql','json','css','html','markdown'].includes(codeLanguage) && <option value={codeLanguage}>Other language</option>}
      <option value="">Plain text</option>{['javascript','typescript','python','java','sql','json','css','html','markdown'].map(language => <option key={language} value={language}>{language}</option>)}</select></label>}
    {linkOpen && <div className="studio-link-editor"><label htmlFor="editor-link">Link destination</label><input id="editor-link" autoFocus type="url" value={link} maxLength={2048} onKeyDown={event => { if (event.key === 'Enter') event.preventDefault() }} onChange={e => setLink(e.target.value)} />
      <button type="button" disabled={!safeLink(link) || !link.trim()} onClick={() => { command(toggleLinkCommand.key, { href: safeLink(link) }); setLinkOpen(false); setLink('') }}>Apply link</button><button type="button" className="button-secondary" onClick={() => setLinkOpen(false)}>Cancel link</button></div>}
    {loading && <p className="studio-editor-loading" role="status">Preparing writing tools…</p>}
    <div className="studio-rich-editor"><Milkdown />{!value && <span className="studio-rich-placeholder" aria-hidden="true">Start with a thought…</span>}</div></>
}

class WritingBoundary extends Component<{ children: ReactNode; onFailure: () => void }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() { return { failed: true } }
  componentDidCatch() { this.props.onFailure() }
  render() { return this.state.failed ? <p>Writing tools are unavailable. Your Markdown draft is retained.</p> : this.props.children }
}

/** The sole editor integration boundary. The reducer, not the editor, owns Markdown. */
export function MarkdownEditorAdapter(props: MarkdownEditorProps) {
  const [mode, setMode] = useState<'write' | 'source' | 'preview'>(requiresSource(props.value) ? 'source' : 'write')
  const [unsupported, setUnsupported] = useState(requiresSource(props.value))
  const sourceRequired = unsupported || (mode !== 'write' && requiresSource(props.value))
  return <div className="studio-markdown-adapter">
    <div className="studio-editor-modes" role="group" aria-label="Writing mode">
      <button className="button-secondary" type="button" aria-pressed={mode === 'write'} disabled={sourceRequired}
        onClick={() => setMode('write')}>Write</button>
      <button className="button-secondary" type="button" aria-pressed={mode === 'source'} onClick={() => setMode('source')}>Markdown source</button>
      <button className="button-secondary" type="button" aria-pressed={mode === 'preview'} onClick={() => setMode('preview')}>Read</button>
    </div>
    {sourceRequired && <p className="studio-source-fallback">Source mode protects content that the writing tools cannot safely preserve. Nothing was converted or removed. If you were pasting, paste again here; files use Attachments.</p>}
    {mode === 'source' ? <><label htmlFor="note-markdown">Markdown</label><textarea id="note-markdown" rows={16} maxLength={1_000_000}
      aria-describedby="markdown-help" value={props.value} disabled={props.disabled} onChange={e => props.onChange(e.target.value)} /></>
      : mode === 'preview' ? <section aria-label="Markdown preview"><h2 className="studio-section-label">Preview</h2><MarkdownView markdown={props.value} /></section>
        : <WritingBoundary onFailure={() => { setUnsupported(true); setMode('source') }}><MilkdownProvider><RichEditor {...props} onUnsupported={() => { setUnsupported(true); setMode('source') }} /></MilkdownProvider></WritingBoundary>}
  </div>
}
