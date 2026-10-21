import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { Editor, parserCtx, remarkCtx, rootCtx, serializerCtx } from '@milkdown/kit/core'
import { commonmark } from '@milkdown/kit/preset/commonmark'
import { gfm } from '@milkdown/kit/preset/gfm'
import { MarkdownEditorAdapter, markdownMeaning, requiresSource } from './MarkdownEditorAdapter'

const corpus = [
  ['paragraphs', 'An ordinary paragraph.\n\nAnother paragraph.'],
  ['headings', '# One\n\n## Two\n\n### Three'],
  ['emphasis', '**bold and *italic*** with ~~old~~ text'],
  ['nested lists', '- First\n  - Child\n\n1. One\n2. Two'],
  ['checklists', '- [ ] Next\n- [x] Done'],
  ['quotes', '> A thought\n>\n> Another thought'],
  ['links', '[Example](https://example.invalid "Title")'],
  ['inline code', 'Use `const a = 1` here.'],
  ['fenced code', '```typescript\nconst example = "safe"\n```'],
  ['GFM table', '| Left | Right |\n| :--- | ---: |\n| Alpha | 42 |'],
  ['escapes', 'Escaped \\*star\\* and \\[bracket\\]'],
  ['empty', ''],
  ['Unicode', 'বাংলা · 日本語 · café · 🌈👩🏽‍💻'],
  ['long document', 'A saved paragraph with **formatting** and Unicode 日本語.\n\n'.repeat(4000)],
  ['external Markdown', 'Title\n=====\n\nA paragraph with a hard  \nbreak.\n\n---'],
] as const
let editor: Editor, root: HTMLDivElement
beforeAll(async () => {
  root = document.createElement('div'); document.body.append(root)
  editor = await Editor.make().config(ctx => ctx.set(rootCtx, root)).use(commonmark).use(gfm).create()
})
afterAll(async () => { await editor.destroy(); root.remove() })
afterEach(cleanup)
describe('qualified Markdown contract', () => {
  it.each(corpus)('preserves %s semantics through parse/serialize/parse', (_name, source) => {
    editor.action(ctx => {
      const parse = ctx.get(parserCtx), serialize = ctx.get(serializerCtx), remark = ctx.get(remarkCtx)
      const doc = parse(source), result = serialize(doc)
      expect(parse(result).toJSON()).toEqual(doc.toJSON())
      expect(markdownMeaning(remark.parse(result))).toEqual(markdownMeaning(remark.parse(source)))
    })
  }, 15000)
  it.each(['<!-- retain comment -->', '<div>retain raw source</div>', '[key]: https://example.invalid', '[^note]: retain footnote', ':::custom\ncontent\n:::'])('keeps unusual syntax in source mode: %s', source => {
    expect(requiresSource(source)).toBe(true)
    const change = vi.fn(), rendered = render(<MarkdownEditorAdapter value={source} onChange={change} />)
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe(source)
    expect(rendered.container.querySelector('script')).toBeNull()
    fireEvent.focus(screen.getByLabelText('Markdown')); fireEvent.blur(screen.getByLabelText('Markdown'))
    expect(change).not.toHaveBeenCalled()
  })
  it('does not normalize an untouched document on opening, focus, reading or source switching', async () => {
    const source = '## Existing heading\n\nA **saved** paragraph.  \nKeep the break.\n', change = vi.fn()
    render(<MarkdownEditorAdapter value={source} onChange={change} />)
    const body = await screen.findByRole('textbox', { name: 'Note body' })
    fireEvent.focus(body); fireEvent.blur(body)
    fireEvent.click(screen.getByRole('button', { name: 'Markdown source' }))
    expect((screen.getByLabelText('Markdown') as HTMLTextAreaElement).value).toBe(source)
    fireEvent.click(screen.getByRole('button', { name: 'Read' }))
    expect(change).not.toHaveBeenCalled()
  })
  it('never renders or fetches a Markdown image in rich mode', async () => {
    const { container } = render(<MarkdownEditorAdapter value={'![External](https://untrusted.invalid/x.png)'} onChange={vi.fn()} />)
    await screen.findByRole('textbox', { name: 'Note body' })
    expect(container.querySelector('img[src],iframe,script')).toBeNull()
    expect(await screen.findByText('[Image: External]')).toBeTruthy()
  })
})
