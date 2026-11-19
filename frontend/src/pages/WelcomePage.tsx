import { useState, type FormEvent } from 'react'
import { ArrowRight, Radio, Radar, Server as ServerIcon } from 'lucide-react'
import { api, message, type Server, type Status } from '../services/api'

export function WelcomePage({ onConnect }: { onConnect: (status: Status) => void }) {
  const [name, setName] = useState('')
  const [host, setHost] = useState('')
  const [port, setPort] = useState(import.meta.env.VITE_TLS_PORT || '42001')
  const [servers, setServers] = useState<Server[]>([])
  const [searched, setSearched] = useState(false)
  const [searching, setSearching] = useState(false)
  const [connecting, setConnecting] = useState(false)
  const [error, setError] = useState('')

  async function discover() {
    setSearching(true)
    setError('')
    try {
      setServers(await api.discover())
      setSearched(true)
    } catch (failure) {
      setError(message(failure))
    } finally {
      setSearching(false)
    }
  }

  async function connect(server: Server) {
    if (!name.trim()) {
      setError('Enter a display name before connecting.')
      return
    }
    setConnecting(true)
    setError('')
    try {
      onConnect(await api.connect(name.trim(), server))
    } catch (failure) {
      setError(message(failure))
    } finally {
      setConnecting(false)
    }
  }

  function connectManually(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void connect({ host: host.trim(), port: Number(port) })
  }

  return (
    <main className="welcome">
      <header className="brand"><Radio aria-hidden="true" /> CampusShare</header>
      <section className="welcome-intro">
        <h1>Welcome to CampusShare</h1>
        <p>Connect to a server on your local network to share files and follow live activity.</p>
      </section>
      <form className="panel connection-form" onSubmit={connectManually}>
        <label htmlFor="display-name">Display name</label>
        <input id="display-name" value={name} maxLength={40} required onChange={(event) => setName(event.target.value)}
          placeholder="e.g. Sam" aria-describedby="identity-note" />
        <p className="hint" id="identity-note">Your display name is unverified and lasts for this connection.</p>
        <div className="section-title">
          <h2>Find a server</h2>
          <button className="secondary" type="button" onClick={discover} disabled={searching || connecting}>
            <Radar size={17} /> {searching ? 'Searching…' : 'Discover servers'}
          </button>
        </div>
        {searched && !servers.length && <p role="status">No servers found. Try entering the server address below.</p>}
        {servers.length > 0 && <ul className="server-list">
          {servers.map((server) => <li key={server.host + ':' + server.port}>
            <ServerIcon size={19} aria-hidden="true" />
            <div><strong>{server.host}</strong><small>TCP port {server.port}</small></div>
            <button type="button" className="secondary" disabled={connecting || searching} onClick={() => connect(server)}>
              Connect <ArrowRight size={15} />
            </button>
          </li>)}
        </ul>}
        <h2>Connect manually</h2>
        <div className="manual">
          <label>Server IP or hostname<input value={host} required onChange={(event) => setHost(event.target.value)} placeholder="192.168.1.20" /></label>
          <label>TCP port<input type="number" min={1} max={65535} required value={port} onChange={(event) => setPort(event.target.value)} /></label>
        </div>
        <button className="primary" disabled={connecting || searching}>{connecting ? 'Verifying server…' : 'Connect'} <ArrowRight size={17} /></button>
        {error && <p role="alert" className="error">{error}</p>}
      </form>
    </main>
  )
}
