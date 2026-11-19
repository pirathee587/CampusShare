# CampusShare

CampusShare is a five-member Network Programming project for LAN file sharing and live collaboration. Open the Welcome / Connect page, enter a display name, discover a server using UDP or enter its address, and connect. A display name is a temporary, unverified label held by the local agent. Anyone who can reach the file service can use the common shared library. There are no user accounts, passwords, or per-user permissions.

## How it works

| Link | Purpose | Default |
| --- | --- | --- |
| React → local Java agent | File actions, connection state, SSE updates | Loopback HTTP 8085 via Vite |
| Java agent → server | UDP discovery | UDP 42000 |
| Java agent → server | TLS 1.2/1.3 with certificate and endpoint verification, framed TCP transfers | TCP 42001 |
| Java agent → server | Non-sensitive NIO notifications | TCP 42002 |

The server verifies uploaded bytes with SHA-256 before publishing a file. The local agent verifies a download before delivering it to the browser. Uploaded files and their metadata persist in the server storage directory.

## Run locally

Install Java 21, Maven 3.9+, and Node.js 22.12+ with npm. The build needs dependencies from Maven Central and npm on first setup. The running application works without internet access.

From the project root, build the Java JAR and install frontend dependencies.

```powershell
mvn -f backend/pom.xml package
npm.cmd --prefix frontend ci
```

Create a server keystore and a client truststore using the [development certificate setup](docs/SECURITY.md). Use absolute paths to the generated PKCS#12 files. Set the store passphrases in each process's environment. The passphrases protect the local stores.

In a server terminal, set `CAMPUSSHARE_KEYSTORE` and `CAMPUSSHARE_KEYSTORE_PASSWORD`. Then run:

```powershell
$env:CAMPUSSHARE_STORAGE = "$PWD/data/shared"
java -cp backend/target/campusshare-1.0.0.jar com.campusshare.app.ServerMain
```

In a client terminal, set `CAMPUSSHARE_TRUSTSTORE` and `CAMPUSSHARE_TRUSTSTORE_PASSWORD`. For a loopback-only test, set `CAMPUSSHARE_DISCOVERY_ADDRESS` as shown. Leave it unset on a client discovering a server over the LAN.

```powershell
$env:CAMPUSSHARE_DISCOVERY_ADDRESS = "127.0.0.1"
java -cp backend/target/campusshare-1.0.0.jar com.campusshare.app.LocalAgentMain
```

Start the local frontend in a third terminal.

```powershell
npm.cmd --prefix frontend run dev
```

Open [CampusShare on localhost](http://localhost:5173). Enter a display name, select **Discover servers**, then **Connect**. For a manual test with the example certificate, use `127.0.0.1` and port `42001`. Use **Disconnect** to switch servers or display names.

## Run across laptops

1. Connect the laptops to the same LAN. Set up Laptop A as the server and permit inbound UDP 42000 and TCP 42001–42002 on the private network.
2. Include Laptop A's LAN IP in the server certificate's Subject Alternative Name (SAN). Distribute only the verified public certificate to each client and import it into each local truststore.
3. On Laptop B, start the local Java agent and frontend. Discover Laptop A. If the LAN blocks broadcasts, enter Laptop A's matching LAN IP and TLS port manually.
4. Start the agent and frontend on a second client to observe live file-list changes. Both clients can use the same display name. Their labels appear locally and carry no permissions.

The backend and frontend [example configuration](backend/.env.example) lists supported environment variables. Export backend values in the process environment. Vite can load [frontend settings](frontend/.env.example) from `frontend/.env`. When changing the agent port, set it for both the agent and Vite. Match `CAMPUSSHARE_FRONTEND_ORIGIN` to the exact frontend base URL when changing its port or hostname.

The [two-laptop Windows verification procedure](docs/TESTING.md) includes the exact startup, firewall, TLS, discovery, hash comparison, and restart checks.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Discovery returns no servers | Confirm the UDP port, firewall, subnet, and Wi-Fi client isolation. Try the manual address. |
| TLS handshake fails | Confirm the certificate is trusted, currently valid, and lists the exact IP or hostname used to connect. |
| Local API unavailable or HTTP 403 | Start the agent and check its port and the configured frontend origin. |
| Connection works but activity is offline | Confirm the event port is reachable from the client. |
| A file fails integrity verification | Check server storage and retry using an intact copy. |

## Team and repository

| Member | Networking ownership |
| --- | --- |
| 1 | UDP discovery, manual connection, Welcome / Connect |
| 2 | TCP framing, streaming file upload/download, Shared Files |
| 3 | Bounded concurrent transfer processing, queue, progress and cancellation, Transfers |
| 4 | JSSE TLS, server certificate and endpoint validation, SHA-256, connection security UI |
| 5 | Non-blocking NIO selector, notifications, counts, SSE, Collaboration |

`backend/` contains the Java server, client agent, protocol, and JUnit suite. `frontend/` contains the React UI and Vitest suite. `docs/` contains the specifications, test instructions and demo. `scripts/` contains shell launchers. `data/` holds local server data.

```powershell
mvn -f backend/pom.xml test
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
```

See [architecture](docs/ARCHITECTURE.md), [requirements](docs/SRS.md), [team scope](docs/TEAM_SCOPE.md), and the [demo plan](docs/DEMO_PLAN.md). The automated suite includes a headless browser scenario through Vite preview and the packaged Java services. See [testing](docs/TESTING.md) for the browser command and the physical LAN procedure.
