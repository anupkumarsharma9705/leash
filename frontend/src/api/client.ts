import type {
  AdminAgent,
  AdminApproval,
  AdminCreatedAgent,
  AdminPaymentEvent,
  AdminPaymentRefresh,
  AdminRotatedKey,
  ConfigResponse,
  CreateAgentRequest,
  DemoMode,
  DemoRunResponse,
  DemoStatus,
  HealthResponse,
  PaymentPage,
  PaymentQuery,
  ProblemDetails,
  UpdatePolicyRequest,
} from './types'

/**
 * Every HTTP call in the console goes through this module.
 *
 * The admin API uses HTTP Basic for identity and a readable cookie plus a
 * mirrored header for CSRF. Credentials live in module memory only: never
 * localStorage, never sessionStorage, never a URL. A reload signs the operator
 * out, which is the right trade-off for a sandbox console.
 */

const API_BASE_URL = (
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? ''
).replace(/\/+$/, '')

const ADMIN_BASE = '/api/v1/admin'
const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'

/** Fired when an authenticated admin request comes back 401. */
export const UNAUTHORIZED_EVENT = 'leash:unauthorized'

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'
type QueryValue = string | number | boolean | null | undefined

interface RequestOptions {
  method?: HttpMethod
  query?: Record<string, QueryValue>
  body?: unknown
  /** Set false for public routes so the admin header is never attached. */
  admin?: boolean
  /** Optional signal for request cancellation (e.g., on component unmount). */
  signal?: AbortSignal
}

let authorization: string | null = null
let csrfPriming: Promise<void> | null = null

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly detail: string
  readonly correlationId: string | null
  readonly problem: ProblemDetails | null

  constructor(status: number, problem: ProblemDetails | null, fallback?: string) {
    const detail =
      problem?.detail ??
      problem?.title ??
      fallback ??
      `Request failed with status ${status}`
    super(detail)
    this.name = 'ApiError'
    this.status = status
    this.code = problem?.code ?? (status === 0 ? 'NETWORK_ERROR' : `HTTP_${status}`)
    this.detail = detail
    this.correlationId = problem?.correlationId ?? null
    this.problem = problem
  }
}

function base64(value: string): string {
  const bytes = new TextEncoder().encode(value)
  let binary = ''
  for (const byte of bytes) {
    binary += String.fromCharCode(byte)
  }
  return btoa(binary)
}

function readCookie(name: string): string | null {
  const prefix = `${name}=`
  for (const part of document.cookie.split(';')) {
    const entry = part.trim()
    if (entry.startsWith(prefix)) {
      return decodeURIComponent(entry.slice(prefix.length))
    }
  }
  return null
}

function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const url = `${API_BASE_URL}${path}`
  if (!query) {
    return url
  }
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') {
      continue
    }
    params.set(key, String(value))
  }
  const search = params.toString()
  return search ? `${url}?${search}` : url
}

async function toApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetails | null = null
  try {
    const text = await response.text()
    if (text) {
      problem = JSON.parse(text) as ProblemDetails
    }
  } catch {
    // A proxy or gateway can answer with plain text or HTML. Fall through to a
    // status-based message rather than masking the real failure.
  }
  return new ApiError(response.status, problem)
}

async function send(
  path: string,
  method: HttpMethod,
  options: RequestOptions,
  bodyText?: string,
): Promise<Response> {
  const headers = new Headers({ Accept: 'application/json' })
  if (authorization && options.admin !== false) {
    headers.set('Authorization', authorization)
  }
  if (bodyText !== undefined) {
    headers.set('Content-Type', 'application/json')
  }
  if (method !== 'GET') {
    const token = readCookie(CSRF_COOKIE)
    if (token) {
      headers.set(CSRF_HEADER, token)
    }
  }

  const controller = new AbortController()
  const signal = options.signal

  // If caller provided a signal, link it to our controller
  let abortHandler: (() => void) | null = null
  if (signal) {
    if (signal.aborted) {
      controller.abort(signal.reason)
    } else {
      abortHandler = () => controller.abort(signal.reason)
      signal.addEventListener('abort', abortHandler)
    }
  }

  try {
    return await fetch(buildUrl(path, options.query), {
      method,
      headers,
      body: bodyText,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal,
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new ApiError(0, null, 'Request was cancelled')
    }
    throw new ApiError(
      0,
      null,
      'Cannot reach the Leash API. Check that the backend is running on port 8080.',
    )
  } finally {
    if (abortHandler && signal) {
      signal.removeEventListener('abort', abortHandler)
    }
  }
}

/**
 * Ensures a CSRF cookie exists before the first write.
 *
 * The backend forces the token on every admin response, and signing in already
 * performs an admin GET. This guard covers a cookie cleared while the tab is
 * open.
 *
 * Uses a proper mutex to prevent the "thundering herd" problem: if multiple
 * mutations fire concurrently before the first priming completes, they all wait
 * on the same promise instead of each triggering their own GET.
 */
async function primeCsrf(): Promise<void> {
  if (readCookie(CSRF_COOKIE)) {
    return
  }
  if (csrfPriming) {
    return csrfPriming
  }
  // Create a promise that we control the resolution of
  csrfPriming = new Promise<void>((resolve, reject) => {
    // Actually do the priming
    ;(async () => {
      try {
        const response = await send(`${ADMIN_BASE}/agents`, 'GET', { admin: true })
        if (!response.ok) {
          throw await toApiError(response)
        }
        await response.text()
        resolve()
      } catch (e) {
        reject(e)
      } finally {
        csrfPriming = null
      }
    })()
  })
  return csrfPriming
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const bodyText = options.body === undefined ? undefined : JSON.stringify(options.body)

  if (method !== 'GET') {
    await primeCsrf()
  }

  let response = await send(path, method, options, bodyText)

  if (method !== 'GET' && response.status === 403) {
    const csrfError = await toApiError(response.clone())
    if (csrfError.code === 'INVALID_CSRF_TOKEN') {
      await primeCsrf()
      response = await send(path, method, options, bodyText)
    } else {
      throw csrfError
    }
  }

  if (!response.ok) {
    const error = await toApiError(response)
    if (error.status === 401 && options.admin !== false) {
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    }
    throw error
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

/** Validates the credential against the admin API before the shell opens. */
export async function signIn(username: string, password: string): Promise<void> {
  authorization = `Basic ${base64(`${username}:${password}`)}`
  try {
    await request<AdminAgent[]>(`${ADMIN_BASE}/agents`, { admin: true })
  } catch (error) {
    authorization = null
    throw error
  }
}

export function signOut(): void {
  authorization = null
}

export const api = {
  health(): Promise<HealthResponse> {
    return request<HealthResponse>('/actuator/health', { admin: false })
  },

  listAgents(): Promise<AdminAgent[]> {
    return request<AdminAgent[]>(`${ADMIN_BASE}/agents`)
  },

  createAgent(body: CreateAgentRequest): Promise<AdminCreatedAgent> {
    return request<AdminCreatedAgent>(`${ADMIN_BASE}/agents`, {
      method: 'POST',
      body,
    })
  },

  updatePolicy(agentId: string, body: UpdatePolicyRequest): Promise<AdminAgent> {
    return request<AdminAgent>(`${ADMIN_BASE}/agents/${agentId}/policy`, {
      method: 'PUT',
      body,
    })
  },

  suspendAgent(agentId: string): Promise<AdminAgent> {
    return request<AdminAgent>(`${ADMIN_BASE}/agents/${agentId}/suspend`, {
      method: 'POST',
    })
  },

  resumeAgent(agentId: string): Promise<AdminAgent> {
    return request<AdminAgent>(`${ADMIN_BASE}/agents/${agentId}/resume`, {
      method: 'POST',
    })
  },

  rotateKey(agentId: string): Promise<AdminRotatedKey> {
    return request<AdminRotatedKey>(`${ADMIN_BASE}/agents/${agentId}/rotate-key`, {
      method: 'POST',
    })
  },

  listPayments(query: PaymentQuery = {}): Promise<PaymentPage> {
    return request<PaymentPage>(`${ADMIN_BASE}/payments`, {
      query: { ...query },
    })
  },

  paymentEvents(paymentId: string): Promise<AdminPaymentEvent[]> {
    return request<AdminPaymentEvent[]>(
      `${ADMIN_BASE}/payments/${paymentId}/events`,
    )
  },

  refreshPayment(paymentId: string): Promise<AdminPaymentRefresh> {
    return request<AdminPaymentRefresh>(
      `${ADMIN_BASE}/payments/${paymentId}/refresh`,
      { method: 'POST' },
    )
  },

  listApprovals(): Promise<AdminApproval[]> {
    return request<AdminApproval[]>(`${ADMIN_BASE}/approvals`)
  },

  approve(approvalId: string, note?: string): Promise<AdminApproval> {
    return request<AdminApproval>(`${ADMIN_BASE}/approvals/${approvalId}/approve`, {
      method: 'POST',
      body: { note: note?.trim() || null },
    })
  },

  reject(approvalId: string, note?: string): Promise<AdminApproval> {
    return request<AdminApproval>(`${ADMIN_BASE}/approvals/${approvalId}/reject`, {
      method: 'POST',
      body: { note: note?.trim() || null },
    })
  },

  demoStatus(): Promise<DemoStatus> {
    return request<DemoStatus>(`${ADMIN_BASE}/demo/status`)
  },

  runDemo(mode: DemoMode): Promise<DemoRunResponse> {
    return request<DemoRunResponse>(`${ADMIN_BASE}/demo/run`, {
      method: 'POST',
      query: { mode },
    })
  },

  getConfig(): Promise<ConfigResponse> {
    return request<ConfigResponse>('/api/v1/config', { admin: false })
  },
}
