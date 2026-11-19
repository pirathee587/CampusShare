export type Server = { host: string; port: number }

export type Status = {
  connected: boolean
  tlsVerified: boolean
  displayName?: string
  host?: string
  port: number
  eventsConnected: boolean
  onlineCount: number
  activeWorkers: number
  queuedRequests: number
}

export type SharedFile = { id: string; filename: string; size: number; sha256: string }
export type Transfer = { id: string; direction: string; filename: string; size: number; bytes: number; state: string }

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  if (init?.method === 'POST') {
    init = { ...init, headers: { ...init.headers, 'X-CampusShare-Request': '1' } }
  }
  const response = await fetch('/api' + path, init).catch(() => {
    throw new Error('Local agent request failed. Check that the Java agent is running.')
  })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.error || 'Local agent unavailable. Check that the Java agent is running.')
  }
  return response.json() as Promise<T>
}

export const api = {
  discover: () => request<Server[]>('/discovery'),
  status: () => request<Status>('/status'),
  connect: (displayName: string, server: Server) =>
    request<Status>('/connect', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ displayName, ...server }),
    }),
  disconnect: () => request<Status>('/disconnect', { method: 'POST' }),
  list: () => request<SharedFile[]>('/files', { signal: AbortSignal.timeout(5000) }),
  transfers: () => request<Transfer[]>('/transfers', { signal: AbortSignal.timeout(5000) }),
  cancel: (id: string) => request('/transfers/' + id + '/cancel', { method: 'POST' }),
  upload: (file: File) =>
    request<SharedFile>('/files/upload', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/octet-stream',
        'X-Filename': encodeURIComponent(file.name),
        'X-Transfer-Id': crypto.randomUUID(),
      },
      body: file,
    }),
  downloadUrl: (fileId: string) => '/api/files/' + fileId + '/download',
}

export function message(error: unknown) {
  return error instanceof Error ? error.message : 'Request failed'
}
