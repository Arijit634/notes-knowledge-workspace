export interface ApiResponseMetadata {
  readonly status: number
  readonly etag: string | null
  readonly location: string | null
  readonly retryAfter: string | null
  readonly contentRange: string | null
}

function boundedHeader(response: Response, name: string): string | null {
  const value = response.headers.get(name)
  return value !== null && value.length <= 256 && !/[\u0000-\u001f\u007f]/.test(value) ? value : null
}

/** Only allowlisted, bounded transport metadata crosses the API boundary. */
export function readResponseMetadata(response: Response): ApiResponseMetadata {
  return {
    status: response.status,
    etag: boundedHeader(response, 'ETag'),
    location: boundedHeader(response, 'Location'),
    retryAfter: boundedHeader(response, 'Retry-After'),
    contentRange: boundedHeader(response, 'Content-Range'),
  }
}
