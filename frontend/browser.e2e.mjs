import assert from 'node:assert/strict'
import { spawn, execFile } from 'node:child_process'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { createServer } from 'node:net'
import { createSocket } from 'node:dgram'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { promisify } from 'node:util'
import { once } from 'node:events'
import { test } from 'node:test'
import { chromium } from 'playwright'

const execute = promisify(execFile)
const root = resolve('..')
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'java') : 'java'
const keytool = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'keytool') : 'keytool'
const allocated = new Set()

async function freePort(udp = false) {
  while (true) {
    const socket = udp ? createSocket('udp4') : createServer()
    if (udp) socket.bind(0, '127.0.0.1')
    else socket.listen(0, '127.0.0.1')
    await once(socket, 'listening')
    const { port } = socket.address()
    const closed = once(socket, 'close')
    socket.close()
    await closed
    if (!allocated.has(port)) {
      allocated.add(port)
      return port
    }
  }
}

async function eventually(check) {
  const deadline = Date.now() + 8000
  while (Date.now() < deadline) {
    try {
      if (await check()) return
    } catch {
      // The process or UI may still be starting.
    }
    await delay(100)
  }
  throw new Error('Timed out waiting for CampusShare')
}

test('real browser uses UDP discovery, TLS, transfers and NIO/SSE through the Vite proxy', { timeout: 60000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), 'campusshare-browser-'))
  const processes = []
  let browser
  function launch(command, args, extra = {}) {
    const child = spawn(command, args, {
      env: { ...process.env, ...extra }, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
    })
    let output = ''
    child.stdout.on('data', (data) => { output += data })
    child.stderr.on('data', (data) => { output += data })
    child.on('error', (error) => { output += error.message })
    processes.push({ child, output: () => output })
  }

  try {
    const password = 'browser-fixture'
    const key = join(directory, 'server.p12')
    const cert = join(directory, 'server.cer')
    const trust = join(directory, 'trust.p12')
    await execute(keytool, ['-genkeypair', '-alias', 'server', '-keyalg', 'EC', '-dname', 'CN=Browser Test',
      '-ext', 'SAN=IP:127.0.0.1', '-validity', '2', '-storetype', 'PKCS12', '-keystore', key, '-storepass', password])
    await execute(keytool, ['-exportcert', '-alias', 'server', '-keystore', key, '-storepass', password, '-file', cert])
    await execute(keytool, ['-importcert', '-noprompt', '-alias', 'server', '-file', cert,
      '-storetype', 'PKCS12', '-keystore', trust, '-storepass', password])

    const udp = await freePort(true)
    const tls = await freePort()
    const events = await freePort()
    const agent = await freePort()
    const web = await freePort()
    const origin = 'http://127.0.0.1:' + web
    const env = {
      CAMPUSSHARE_UDP_PORT: String(udp), CAMPUSSHARE_TLS_PORT: String(tls),
      CAMPUSSHARE_EVENT_PORT: String(events), CAMPUSSHARE_AGENT_PORT: String(agent),
      CAMPUSSHARE_DISCOVERY_ADDRESS: '127.0.0.1', CAMPUSSHARE_FRONTEND_ORIGIN: origin,
      CAMPUSSHARE_FRONTEND_HOST: '127.0.0.1', CAMPUSSHARE_FRONTEND_PORT: String(web),
      CAMPUSSHARE_TRUSTSTORE: trust, CAMPUSSHARE_TRUSTSTORE_PASSWORD: password,
      CAMPUSSHARE_KEYSTORE: key, CAMPUSSHARE_KEYSTORE_PASSWORD: password,
      CAMPUSSHARE_STORAGE: join(directory, 'shared'),
    }
    const classpath = ['-cp', join(root, 'backend', 'target', 'campusshare-1.0.0.jar')]
    launch(java, [...classpath, 'com.campusshare.app.LocalAgentMain'], env)
    launch(process.execPath, ['node_modules/vite/bin/vite.js', 'preview'], env)
    await eventually(async () => (await fetch(origin + '/api/health')).ok)

    browser = await chromium.launch({ channel: process.env.CAMPUSSHARE_BROWSER || undefined })
    const page = await browser.newPage({ viewport: { width: 360, height: 800 } })
    const uiErrors = []
    page.on('pageerror', (error) => uiErrors.push(error.message))
    await page.goto(origin)
    const discover = page.getByRole('button', { name: 'Discover servers' })
    await discover.click()
    await page.getByText('No servers found.', { exact: false }).waitFor()
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 360)
    const missingHeader = await page.evaluate(async () => (await fetch('/api/disconnect', { method: 'POST' })).status)
    assert.equal(missingHeader, 403)

    launch(java, [...classpath, 'com.campusshare.app.ServerMain'], env)
    await eventually(() => processes.at(-1).output().includes('server ready'))
    await page.getByLabel('Display name').fill('Review client')
    await page.getByLabel('Server IP or hostname').fill('127.0.0.1')
    await page.getByLabel('TCP port').fill(String(udp))
    await page.getByRole('button', { name: 'Connect', exact: true }).click()
    await page.getByRole('alert').waitFor()
    await page.getByLabel('TCP port').fill(String(tls))
    await page.getByRole('button', { name: 'Connect', exact: true }).click()
    await page.getByText('Server certificate verified', { exact: true }).waitFor()
    await page.getByRole('button', { name: 'Disconnect' }).click()
    await discover.click()
    await page.getByRole('listitem').filter({ hasText: '127.0.0.1' }).waitFor()
    await page.getByLabel('Display name').fill('Same local agent')
    await page.getByRole('listitem').filter({ hasText: '127.0.0.1' }).getByRole('button', { name: 'Connect' }).click()
    await page.getByRole('link', { name: 'Collaboration' }).click()
    await page.getByText('Live notification channel connected', { exact: false }).waitFor()

    await page.getByRole('link', { name: 'Shared files' }).click()
    const original = Buffer.from('CampusShare browser verification\n')
    await page.getByLabel('Choose a file').setInputFiles({
      name: 'integration-long-file-name-that-must-wrap-on-small-screens.txt', mimeType: 'text/plain', buffer: original,
    })
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await page.getByText('uploaded and verified.', { exact: false }).waitFor()
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 360)
    const download = page.waitForEvent('download')
    await page.getByRole('link', { name: 'Download', exact: true }).click()
    assert.deepEqual(await readFile(await (await download).path()), original)

    await page.getByRole('link', { name: 'Transfers' }).click()
    await page.getByText('Download · Verified · 100%', { exact: true }).waitFor()
    await page.getByRole('link', { name: 'Collaboration' }).click()
    await page.getByText('Shared files updated', { exact: true }).first().waitFor()

    const server = processes.at(-1).child
    const stopped = once(server, 'exit')
    server.kill()
    await stopped
    await page.getByText('Server connection lost.', { exact: false }).waitFor({ timeout: 15000 })
    assert.equal(await page.getByLabel('Display name').inputValue(), '')
    launch(java, [...classpath, 'com.campusshare.app.ServerMain'], env)
    await eventually(() => processes.at(-1).output().includes('server ready'))
    await discover.click()
    await page.getByRole('listitem').filter({ hasText: '127.0.0.1' }).waitFor()
    await page.getByLabel('Display name').fill('After restart')
    await page.getByRole('listitem').filter({ hasText: '127.0.0.1' }).getByRole('button', { name: 'Connect' }).click()
    await page.getByRole('link', { name: 'Shared files' }).click()
    await page.getByText('integration-long-file-name-that-must-wrap-on-small-screens.txt', { exact: true }).waitFor()
    await page.setViewportSize({ width: 1280, height: 900 })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 1280)
    assert.deepEqual(uiErrors, [])
  } catch (error) {
    for (const { output } of processes) console.error(output())
    throw error
  } finally {
    await browser?.close()
    for (const { child } of processes.reverse()) {
      if (child.pid && child.exitCode === null && child.signalCode === null) {
        const exited = once(child, 'exit')
        child.kill()
        await exited
      }
    }
    await rm(directory, { recursive: true, force: true })
  }
})

