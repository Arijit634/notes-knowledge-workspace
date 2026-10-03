import { useState } from 'react'

export function TagDraftEditor({ value, onChange, disabled }: { value: string; onChange: (value: string) => void; disabled: boolean }) {
  const [entry, setEntry] = useState('')
  const tags = value.split('\n').filter(Boolean)
  function add() {
    const next = entry.trim()
    if (!next || tags.length >= 50) return
    if (!tags.includes(next)) onChange([...tags, next].join('\n'))
    setEntry('')
  }
  return <>
    <ul className="tag-draft-tokens" aria-label="Draft tags">{tags.map((tag, index) => <li key={`${index}-${tag}`}>
      <span>{tag}</span><button type="button" disabled={disabled} aria-label={`Remove tag ${tag}`} onClick={() => onChange(tags.filter((_, item) => item !== index).join('\n'))}>×</button>
    </li>)}</ul>
    <label htmlFor="tag-entry">Add a tag</label>
    <div className="tag-entry"><input id="tag-entry" maxLength={100} value={entry} disabled={disabled} onChange={event => setEntry(event.target.value)} onKeyDown={event => {
      if (event.key === 'Enter' && !event.nativeEvent.isComposing) { event.preventDefault(); add() }
    }} /><button type="button" disabled={disabled || !entry.trim() || tags.length >= 50} onClick={add}>Add</button></div>
    <details className="tag-bulk"><summary>Edit tags as lines</summary><label htmlFor="note-tags">Tags, one per line</label>
      <textarea id="note-tags" rows={4} maxLength={5100} value={value} disabled={disabled} onChange={event => onChange(event.target.value)} aria-describedby="tags-help" />
    </details>
  </>
}
