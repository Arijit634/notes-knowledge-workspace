import { render, screen, cleanup } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { KnowledgeResults } from './AskKnowledgePage'
import type { KnowledgeResult } from './KnowledgeApi'

afterEach(cleanup)
const result = (degraded: string): KnowledgeResult => ({ deterministicResults: [], aiAnswer: null, citations: [],
  coverage: { boundary: 'bounded', inspectedSources: 0, completed: false, corpusChanged: false, truncated: false,
    retryRequired: false, classificationUncertain: false }, degraded, insufficientEvidence: false })

describe('Knowledge processing status', () => {
  it('explains a policy block rather than presenting it as a provider outage', () => {
    render(<MemoryRouter><KnowledgeResults result={result('processingPolicyRequired')} /></MemoryRouter>)
    expect(screen.getByRole('status').textContent).toContain('not permitted')
    expect(screen.getByRole('status').textContent).not.toContain('degraded or unavailable')
  })
  it('retains truthful availability wording for a provider outage', () => {
    render(<MemoryRouter><KnowledgeResults result={result('providerUnavailable')} /></MemoryRouter>)
    expect(screen.getByRole('status').textContent).toContain('degraded or unavailable')
  })
})
