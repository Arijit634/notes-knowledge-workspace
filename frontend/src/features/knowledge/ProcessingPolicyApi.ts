import type { AuthRuntime } from '../auth/AuthRuntime'
import { record, text, integer, flag, uuid } from '../../app/api/Contract'
export type ProcessingPolicy = { processingPolicyId: string; policyCode: string; policyVersion: number; policyFingerprint: string;
  disclosureRevision: string; effectiveAt: string; acknowledged: boolean; acknowledgementRequired: boolean }
export function processingPolicy(value: unknown): ProcessingPolicy {
  const p = record(value)
  return { processingPolicyId: uuid(p.processingPolicyId), policyCode: text(p.policyCode, 64), policyVersion: integer(p.policyVersion),
    policyFingerprint: text(p.policyFingerprint, 64), disclosureRevision: text(p.disclosureRevision, 64), effectiveAt: text(p.effectiveAt, 64),
    acknowledged: flag(p.acknowledged), acknowledgementRequired: flag(p.acknowledgementRequired) }
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
