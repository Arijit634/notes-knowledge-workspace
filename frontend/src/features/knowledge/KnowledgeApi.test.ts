import { describe, expect, it } from 'vitest'
import { knowledgeResult } from './KnowledgeApi'

const noteId = '01990a55-9e12-7ac4-8f5b-31aa4a91d401'
const attachmentId = '01990a55-9e12-7ac4-8f5b-31aa4a91d402'
const wholeNote = { noteId, attachmentId: null, location: null }
const image = { noteId, attachmentId, location: { kind: 'whole_image', start: null, end: null, page: null,
  timeStart: null, timeEnd: null, x: null, y: null, width: null, height: null } }
const result = { deterministicResults: [{ kind: 'note', value: 'Synthetic match', occurrences: [wholeNote], aiEnabled: false }],
  aiAnswer: { claims: ['Synthetic supported answer'], conflicting: false }, citations: [image],
  coverage: { boundary: 'bounded', inspectedSources: 1, completed: false, corpusChanged: false, truncated: false,
    retryRequired: false, classificationUncertain: false }, degraded: null, insufficientEvidence: false }

describe('Knowledge response provenance', () => {
  it('keeps a valid image answer alongside a whole-Note deterministic match without fabricating a position', () => {
    const decoded = knowledgeResult(result)
    expect(decoded.aiAnswer?.claims).toEqual(['Synthetic supported answer'])
    expect(decoded.citations).toEqual([image])
    expect(decoded.deterministicResults[0]).toMatchObject({ aiEnabled: false, occurrences: [wholeNote] })
  })
  it('still rejects AI citations without detailed backend provenance', () => {
    expect(() => knowledgeResult({ ...result, citations: [wholeNote] })).toThrow()
    expect(() => knowledgeResult({ ...result, citations: [{ ...image, location: null }] })).toThrow()
  })
  it.each([
    { kind: 'image', occurrence: { ...wholeNote, attachmentId } },
    { kind: 'note', occurrence: { ...wholeNote, attachmentId } },
    { kind: 'note', occurrence: { noteId, attachmentId: null } },
    { kind: 'note', occurrence: { ...wholeNote, noteId: 'invalid' } },
  ])('does not extend the whole-Note exception to invalid or media provenance ($kind)', ({ kind, occurrence }) => {
    expect(() => knowledgeResult({ ...result, deterministicResults: [{ kind, value: 'Synthetic', occurrences: [occurrence], aiEnabled: false }] })).toThrow()
  })
})
