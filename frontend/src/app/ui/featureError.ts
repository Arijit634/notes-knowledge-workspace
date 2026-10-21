import { ApiProblemError } from '../api/ProblemDetailsDecoder'
export function featureError(error: unknown): string {
  if (error instanceof ApiProblemError) {
    switch (error.problem.status) {
      case 401: return 'Your session is no longer available. Sign in again.'
      case 403: return 'This action needs permission or identity confirmation. Nothing was changed.'
      case 404: return 'This item is unavailable.'
      case 409: return 'The current state changed. Refresh this section and review before trying again.'
      case 412: return 'This item changed. Your draft was kept; review the latest version.'
      case 413: return 'This file is too large.'
      case 415: return 'This file format is not supported.'
      case 400: case 422: return 'Review your information and try again.'
      case 429: return 'Too many requests. Wait before trying again.'
      case 503: return 'This feature is temporarily unavailable. Your other work is still here.'
    }
  }
  return 'The request could not be completed. Check your connection and try again.'
}
