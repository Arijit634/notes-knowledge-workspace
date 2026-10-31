import { render, screen, cleanup } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { AuthRuntime } from '../auth/AuthRuntime'
import { NoteProcessingPanel } from './NoteProcessingPanel'
afterEach(() => { cleanup(); vi.restoreAllMocks() })
it('separates paused Note text from ready media without claiming a provider outage', async () => {
  const auth = new AuthRuntime()
  vi.spyOn(auth.api, 'request').mockResolvedValue({ body: { note: { status: 'blocked', reason: 'noteTextIndexingPaused' }, attachments: [{ attachmentId: '01990a55-9e12-7ac4-8f5b-31aa4a91d401', status: 'ready', reason: null }] }, metadata: {} } as never)
  render(<MemoryRouter><NoteProcessingPanel auth={auth} noteId="01990a55-9e12-7ac4-8f5b-31aa4a91d402" dirty={false} /></MemoryRouter>)
  expect(await screen.findByText(/Note text indexing is paused/)).toBeTruthy()
  expect(screen.getByText(/Attachment 1/).parentElement?.textContent).toContain('Ready')
  expect(screen.queryByText(/The provider is unavailable/)).toBeNull(); auth.queries.clear()
})
