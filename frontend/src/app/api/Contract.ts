import { ApiProtocolError } from './ProblemDetailsDecoder'

export function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiProtocolError()
  return value as Record<string, unknown>
}
export function text(value: unknown, limit = 1_000_000): string {
  if (typeof value !== 'string' || value.length > limit) throw new ApiProtocolError()
  return value
}
export function flag(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new ApiProtocolError()
  return value
}
export function integer(value: unknown): number {
  if (!Number.isSafeInteger(value) || Number(value) < 0) throw new ApiProtocolError()
  return Number(value)
}
export function array<T>(value: unknown, decode: (value: unknown) => T, limit = 2048): T[] {
  if (!Array.isArray(value) || value.length > limit) throw new ApiProtocolError()
  return value.map(decode)
}
export function uuid(value: unknown): string {
  const id = text(value, 36)
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id)) throw new ApiProtocolError()
  return id
}
