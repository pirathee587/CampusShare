import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Activity, Download, Folder, Gauge, Radio, ShieldCheck, Upload } from 'lucide-react'
import { Link, NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { WelcomePage } from './pages/WelcomePage'
import { api, message, type SharedFile, type Status, type Transfer } from './services/api'

const links = [
  { path: '/', label: 'Dashboard', Icon: Gauge },
  { path: '/files', label: 'Shared files', Icon: Folder },
  { path: '/transfers', label: 'Transfers', Icon: Upload },
  { path: '/activity', label: 'Collaboration', Icon: Activity },
  { path: '/security', label: 'Security', Icon: ShieldCheck },
]

const eventLabels: Record<string, string> = {
  FILE_LIST_CHANGED: 'Shared files updated',
  TRANSFER_COMPLETED: 'Server transfer completed',
  SERVER_STATUS_CHANGED: 'Server status changed',
  ONLINE_COUNT_CHANGED: 'Notification client count changed',
}

export default function App() {
  const [status, setStatus] = useState<Status>()
  const [ready, setReady] = useState(false)
  const [error, setError] = useState('')
  const [files, setFiles] = useState<SharedFile[]>()
  const [transfers, setTransfers] = useState<Transfer[]>()
  const [filesError, setFilesError] = useState('')
  const [transfersError, setTransfersError] = useState('')
  const [streamOpen, setStreamOpen] = useState(false)
  const [activity, setActivity] = useState<{ text: string; time: string }[]>([])
  const [disconnecting, setDisconnecting] = useState(false)
  const revision = useRef(0)
  const wasConnected = useRef(false)
  const [connectionLost, setConnectionLost] = useState(false)
  const navigate = useNavigate()
  const connected = status?.connected === true

  useEffect(() => {
    let mounted = true
    let timer: ReturnType<typeof setTimeout>
    async function refresh() {
      const snapshot = revision.current
      try {
        const next = await api.status()
        if (mounted && snapshot === revision.current) {
          if (wasConnected.current && !next.connected) setConnectionLost(true)
          wasConnected.current = next.connected
          setStatus(next)
          setError('')
        }
      } catch (failure) {
        if (mounted && snapshot === revision.current) {
          setStatus(undefined)
          setError(message(failure))
        }
      } finally {
        if (mounted) {
          setReady(true)
          timer = setTimeout(refresh, 1500)
        }
      }
    }
    void refresh()
    return () => { mounted = false; clearTimeout(timer) }
  }, [])

  useEffect(() => {
    if (!connected) return
    let mounted = true
    const timers = new Set<ReturnType<typeof setTimeout>>()
    function poll<T>(load: () => Promise<T>, update: (data: T) => void, failed: (error: string) => void) {
      async function refresh() {
        try {
          const data = await load()
          if (mounted) {
            update(data)
            failed('')
          }
        } catch (error) {
          if (mounted) failed(message(error))
        } finally {
          if (mounted) {
            const timer = setTimeout(() => {
              timers.delete(timer)
              void refresh()
            }, 1200)
            timers.add(timer)
          }
        }
      }
      void refresh()
    }
    poll(api.list, setFiles, setFilesError)
    poll(api.transfers, setTransfers, setTransfersError)
    const stream = new EventSource('/api/events')
    stream.onopen = () => { if (mounted) setStreamOpen(true) }
    stream.onerror = () => { if (mounted) setStreamOpen(false) }
    stream.onmessage = (event) => {
      if (mounted && Object.hasOwn(eventLabels, event.data)) {
        const label = eventLabels[event.data]
        const time = new Date().toLocaleTimeString()
        setActivity((previous) => [{ text: label, time }, ...previous].slice(0, 30))
      }
    }
    return () => {
      mounted = false
      timers.forEach(clearTimeout)
      stream.close()
      setStreamOpen(false)
      setFilesError('')
      setTransfersError('')
      setFiles(undefined)
      setTransfers(undefined)
      setActivity([])
    }
  }, [connected])

  function accept(next: Status) {
    revision.current++
    wasConnected.current = next.connected
    setConnectionLost(false)
    setError('')
    setStatus(next)
    navigate('/', { replace: true })
  }

  async function disconnect() {
    setDisconnecting(true)
    try {
      accept(await api.disconnect())
    } catch (failure) {
      setError(message(failure))
    } finally {
      setDisconnecting(false)
    }
  }

  if (!ready) return <main className="welcome"><p role="status">Checking local agent…</p></main>
  if (!connected || !status) return <>
    {error && <p className="agent-warning error" role="alert">{error}</p>}
    {!error && connectionLost && <p className="agent-warning error" role="status">Server connection lost. Connect again to continue.</p>}
    <WelcomePage onConnect={accept} />
  </>

  return <div className="shell">
    <aside>
      <div className="brand"><Radio aria-hidden="true" /> CampusShare</div>
      <nav aria-label="Main navigation">{links.map(({ path, label, Icon }) =>
        <NavLink key={path} to={path} end><Icon size={18} /> {label}</NavLink>)}</nav>
      <div className="identity"><strong>{status.displayName}</strong><small>Unverified display name</small></div>
      <button className="secondary" onClick={disconnect} disabled={disconnecting}>{disconnecting ? 'Disconnecting…' : 'Disconnect'}</button>
    </aside>
    <main className="workspace">
      <div className="topbar">
        <span>{status.host}:{status.port}</span>
        {status.tlsVerified && <span className="verified"><ShieldCheck size={17} /> Server certificate verified</span>}
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <Routes>
        <Route path="/" element={<>
          <h1>Dashboard</h1>
          <div className="metrics">
            <div className="panel"><small>Notification clients</small><strong>{status.onlineCount}</strong></div>
            <div className="panel"><small>Shared files</small><strong>{files?.length ?? '—'}</strong></div>
            <div className="panel"><small>Local transfers in progress</small><strong>{transfers?.filter(isRunning).length ?? '—'}</strong></div>
          </div>
          <Link className="primary" to="/files"><Upload size={17} /> Share a file</Link>
          <h2>Recent activity</h2>
          <ActivityList events={activity.slice(0, 4)} />
        </>} />
        <Route path="/files" element={<FilesPage files={files} filesError={filesError} />} />
        <Route path="/transfers" element={<TransfersPage transfers={transfers} status={status} transfersError={transfersError} />} />
        <Route path="/activity" element={<>
          <h1>Collaboration</h1>
          <p>{status.eventsConnected && streamOpen ? 'Live notification channel connected' : 'Notification channel reconnecting'} · {status.onlineCount} notification clients</p>
          <ActivityList events={activity} />
        </>} />
        <Route path="/security" element={<>
          <h1>Connection security</h1>
          <section className="panel security">
            <h2>Server verification</h2>
            <p>{status.tlsVerified ? 'TLS server certificate verified using the local truststore.' : 'Verification pending.'}</p>
            <h2>File integrity</h2>
            <p>The server checks each upload with SHA-256. The local agent checks each download before saving it through the browser.</p>
            <h2>Display name</h2>
            <p>{status.displayName} · Unverified</p>
          </section>
        </>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </main>
  </div>
}

function FilesPage({ files, filesError }: { files?: SharedFile[]; filesError: string }) {
  const [selection, setSelection] = useState<File>()
  const [uploading, setUploading] = useState(false)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const input = useRef<HTMLInputElement>(null)

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selection) return
    setUploading(true)
    setNotice('')
    setError('')
    try {
      const uploaded = await api.upload(selection)
      setNotice(uploaded.filename + ' uploaded and verified.')
      setSelection(undefined)
      if (input.current) input.current.value = ''
    } catch (failure) {
      setError(message(failure))
    } finally {
      setUploading(false)
    }
  }

  return <>
    <h1>Shared files</h1>
    <form className="panel upload-form" onSubmit={upload}>
      <label>Choose a file<input ref={input} type="file" disabled={uploading}
        onChange={(event) => setSelection(event.target.files?.[0])} /></label>
      <button className="primary" disabled={!selection || uploading}><Upload size={17} /> {uploading ? 'Uploading…' : 'Upload'}</button>
      {uploading && <Link to="/transfers">View progress</Link>}
      {notice && <p role="status">{notice}</p>}
      {error && <p role="alert" className="error">{error}</p>}
    </form>
    <section className="panel">
      {filesError && <p className="error" role="alert">Could not refresh shared files. Retrying…</p>}
      {!filesError && files === undefined && <p>Waiting for the server’s file list…</p>}
      {files?.length === 0 && <p>No files have been shared yet.</p>}
      {files && files.length > 0 && <ul className="file-list">{files.map((file) => <li key={file.id}>
        <div><strong>{file.filename}</strong><small>{formatBytes(file.size)}</small></div>
        <a className="secondary" href={api.downloadUrl(file.id)} target="_blank" rel="noopener"><Download size={16} /> Download</a>
      </li>)}</ul>}
    </section>
  </>
}

function isRunning(transfer: Transfer) {
  return transfer.state === 'Waiting for server' || transfer.state === 'Transferring'
}

function TransfersPage({ transfers, status, transfersError }: {
  transfers?: Transfer[]; status: Status; transfersError: string
}) {
  const [error, setError] = useState('')
  return <>
    <h1>Transfers</h1>
    <p>{status.activeWorkers} active server workers · {status.queuedRequests} queued requests</p>
    <section className="panel">
      {error && <p role="alert" className="error">{error}</p>}
      {transfersError && <p className="error" role="alert">Could not refresh transfers. Retrying…</p>}
      {!transfersError && transfers === undefined && <p>Loading transfers…</p>}
      {transfers?.length === 0 && <p>No transfers on this connection.</p>}
      {transfers && transfers.length > 0 && <ul className="file-list">{transfers.map((item) => {
        const percent = item.size > 0 ? Math.round(100 * item.bytes / item.size) : item.state === 'Verified' ? 100 : 0
        return <li key={item.id}>
          <div><strong>{item.filename}</strong><small>{item.direction} · {item.state} · {percent}%</small>
            <progress aria-label={item.direction + ' progress'} max={100} value={percent} /></div>
          {isRunning(item) && <button className="secondary" onClick={() => {
            setError('')
            void api.cancel(item.id).catch((failure) => setError(message(failure)))
          }}>Cancel</button>}
        </li>
      })}</ul>}
    </section>
  </>
}

function ActivityList({ events }: { events: { text: string; time: string }[] }) {
  return <section className="panel">{events.length === 0 ? <p>No events received yet.</p> :
    <ul className="file-list">{events.map((event, index) => <li key={event.time + ':' + index}><span>{event.text}</span><small>{event.time}</small></li>)}</ul>
  }</section>
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 ** 2) return (bytes / 1024).toFixed(1) + ' KiB'
  return (bytes / 1024 ** 2).toFixed(1) + ' MiB'
}
