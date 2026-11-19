import { afterEach, expect, test, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { WelcomePage } from '../pages/WelcomePage'
import { api } from './api'

afterEach(() => vi.unstubAllGlobals())

test('welcome offers an unverified display name, discovery, and manual connection', () => {
  const markup = renderToStaticMarkup(<WelcomePage onConnect={() => {}} />)
  expect(markup).toContain('Your display name is unverified')
  expect(markup).toContain('Discover servers')
  expect(markup).toContain('Connect manually')
  expect(markup).not.toContain('type="password"')
})

test('connect sends the display name and selected server to the local agent', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ connected: true, tlsVerified: true })))
  vi.stubGlobal('fetch', fetch)
  await expect(api.connect('Sam', { host: '192.168.1.2', port: 42001 })).resolves.toMatchObject({ tlsVerified: true })
  expect(fetch).toHaveBeenCalledWith('/api/connect', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-CampusShare-Request': '1' },
    body: JSON.stringify({ displayName: 'Sam', host: '192.168.1.2', port: 42001 }),
  })
})

test('connect surfaces a local agent rejection', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
    new Response(JSON.stringify({ error: 'TLS handshake failed' }), { status: 502 }),
  ))
  await expect(api.connect('Sam', { host: 'localhost', port: 42001 })).rejects.toThrow('TLS handshake failed')
})
