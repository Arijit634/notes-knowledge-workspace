import type { AuthRuntime } from '../auth/AuthRuntime'
import { array, record, text, integer, flag, uuid } from '../../app/api/Contract'
import { ApiProtocolError } from '../../app/api/ProblemDetailsDecoder'
export const processingCategories = { groundedGeneration: 'Grounded answers', mediaUnderstanding: 'Understanding eligible media', queryEmbedding: 'Understanding your question', sourceEmbedding: 'Indexing eligible content', structuredExtraction: 'Structured extraction and suggestions' } as const
export type ProcessingPolicy = { processingPolicyId: string; policyCode: string; policyVersion: number; policyFingerprint: string;
  disclosureRevision: string; effectiveAt: string; acknowledged: boolean; acknowledgementRequired: boolean;
  disclosureContent: string; providerProcessingCategories: (keyof typeof processingCategories)[] }
export function processingPolicy(value: unknown): ProcessingPolicy {
  const p = record(value)
  const content = text(p.disclosureContent, 32768), categories = array(p.providerProcessingCategories, v => {
    const category = text(v, 40)
    if (!Object.hasOwn(processingCategories, category)) throw new ApiProtocolError()
    return category as keyof typeof processingCategories
  }, 5)
  if (!content.trim() || new TextEncoder().encode(content).length > 32768 || /[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/.test(content)
      || /[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff]/.test(content)
      || !/^[a-z][a-z0-9_.-]{0,63}$/.test(text(p.policyCode, 64))
      || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(text(p.disclosureRevision, 64))
      || !categories.length || categories.join(',') !== [...new Set(categories)].sort().join(',')
      || flag(p.acknowledged) === flag(p.acknowledgementRequired)
      || !/^[0-9a-f]{64}$/.test(text(p.policyFingerprint, 64)) || integer(p.policyVersion) < 1) throw new ApiProtocolError()
  return { processingPolicyId: uuid(p.processingPolicyId), policyCode: text(p.policyCode, 64), policyVersion: integer(p.policyVersion),
    policyFingerprint: text(p.policyFingerprint, 64), disclosureRevision: text(p.disclosureRevision, 64), effectiveAt: text(p.effectiveAt, 64),
    acknowledged: flag(p.acknowledged), acknowledgementRequired: flag(p.acknowledgementRequired),
    disclosureContent: content, providerProcessingCategories: categories }
}
export const processingPolicyApi = {
  read: async (auth: AuthRuntime, signal?: AbortSignal) => processingPolicy((await auth.api.request('GET', '/api/ai/processing-policy', { signal })).body),
  acknowledge: async (auth: AuthRuntime, policy: ProcessingPolicy) => {
    const { processingPolicyId, policyCode, policyVersion, policyFingerprint, disclosureRevision } = policy
    await auth.api.request('POST', '/api/ai/processing-policy/acknowledgements', { json: {
      processingPolicyId, policyCode, policyVersion, policyFingerprint, disclosureRevision, confirmAcknowledgement: true }, retryOnCsrfInvalid: false })
  },
  bulk: async (auth: AuthRuntime, aiEnabled: boolean) => {
    const body = record((await auth.api.request('POST', '/api/notes/ai-access-bulk', {
      json: { aiEnabled, scope: 'allExisting', confirm: true }, retryOnCsrfInvalid: false })).body)
    return integer(body.affectedCount)
  },
}
