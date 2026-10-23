import { describe, expect, it } from 'vitest'
import { processingPolicy } from './ProcessingPolicyApi'

const policy = { processingPolicyId: '01990a55-9e12-7ac4-8f5b-31aa4a91d401', policyCode: 'synthetic', policyVersion: 1,
  policyFingerprint: 'a'.repeat(64), disclosureRevision: 'synthetic-v1', effectiveAt: '2026-10-23T00:00:00Z',
  acknowledged: false, acknowledgementRequired: true, disclosureContent: 'SYNTHETIC ONLY. <b>plain text</b>\nUnpaid fixture.',
  providerProcessingCategories: ['queryEmbedding', 'sourceEmbedding'] }
describe('processing disclosure response validation', () => {
  it('preserves exact plain text and sorted categories without interpreting HTML', () => {
    expect(processingPolicy(policy).disclosureContent).toBe(policy.disclosureContent)
    expect(processingPolicy(policy).providerProcessingCategories).toEqual(policy.providerProcessingCategories)
  })
  it.each(['', ' ', '\r', '\u0000', '\ud800', '\udc00', 'é'.repeat(16385)])('rejects invalid or oversized disclosure %s', disclosureContent => {
    expect(() => processingPolicy({ ...policy, disclosureContent })).toThrow()
  })
  it.each([[], ['sourceEmbedding', 'queryEmbedding'], ['queryEmbedding', 'queryEmbedding'], ['unknown']].map(providerProcessingCategories => ({ providerProcessingCategories })))('rejects malformed categories %j', ({ providerProcessingCategories }) => {
    expect(() => processingPolicy({ ...policy, providerProcessingCategories })).toThrow()
  })
  it.each([{ acknowledged: true }, { policyVersion: 0 }, { policyVersion: Number.MAX_SAFE_INTEGER + 1 },
    { policyCode: 'bad code' }, { disclosureRevision: '\n' }, { policyFingerprint: 'not-a-digest' }])('rejects malformed binding %j', change => {
    expect(() => processingPolicy({ ...policy, ...change })).toThrow()
  })
})
