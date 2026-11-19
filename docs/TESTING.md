# CampusShare verification

## Automated checks

Run from the workspace root with Java 21, Maven and Node.js 22.12+. Build both artifacts before the browser integration. The browser test starts the packaged Java server and agent and the production Vite preview on loopback ports. It generates temporary certificates and storage.

```powershell
mvn -f backend/pom.xml package
npm.cmd --prefix frontend ci
npm.cmd --prefix frontend run build
npm.cmd --prefix frontend test
$env:CAMPUSSHARE_BROWSER = "msedge"
npm.cmd --prefix frontend run test:e2e
```

The browser command uses installed Microsoft Edge. For managed Chromium, run `npx playwright install chromium` from `frontend`, clear `CAMPUSSHARE_BROWSER` in that terminal, then run the same test.

JUnit covers real UDP loopback and discovery timeout, fragmented TCP frames, unfinished and corrupt data, concurrent transfers, active byte progress, cancellation, TLS trust, endpoint matching and certificate expiry, storage, NIO events, and the local HTTP/SSE boundary. Vitest tests Welcome rendering and the Fetch contract. The browser scenario uses the production bundle and verifies discovery, manual connect failure and recovery, TLS status, upload and download, SSE delivery, narrow layout, session loss, and restart with persistent files.

The browser test uses process termination for restart coverage. The Ctrl+C procedure below exercises interactive Windows shutdown. Record loopback results and cross-device Wi-Fi results independently.

## Two Windows laptops on Wi-Fi

Use Laptop A for the server and one client and Laptop B for the second client. Run PowerShell from the project root on both laptops. Use the same revision and leave the sample ports unchanged for the first run.

### 1. Check network and build

On both laptops, confirm that Wi-Fi is connected to the same trusted private network. Compare the Wi-Fi IPv4 addresses and prefix lengths. Avoid guest network isolation and temporarily disconnect a VPN if it changes the LAN routing.

```powershell
Get-NetIPConfiguration
Get-NetConnectionProfile
mvn -f backend/pom.xml package
npm.cmd --prefix frontend ci
```

On Laptop A, in an elevated PowerShell session, allow the three server ports from the local subnet on the Private network profile. The agent and Vite stay on loopback on each client.

```powershell
New-NetFirewallRule -DisplayName "CampusShare UDP discovery" -Direction Inbound -Action Allow -Protocol UDP -LocalPort 42000 -Profile Private -RemoteAddress LocalSubnet
New-NetFirewallRule -DisplayName "CampusShare TCP services" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 42001,42002 -Profile Private -RemoteAddress LocalSubnet
```

### 2. Create and distribute TLS trust

On A, substitute the actual Wi-Fi address below. Use a fresh keystore alias or file for another IP and reimport its public certificate on each client. If clients will manually connect by DNS name, add a matching `DNS:hostname` SAN as well. Discovery returns an IP address.

```powershell
$serverIp = '192.168.1.20'
keytool -genkeypair -alias campusshare -keyalg EC -dname "CN=CampusShare LAN" -ext "SAN=IP:$serverIp,IP:127.0.0.1" -validity 90 -storetype PKCS12 -keystore data/lan-server.p12
keytool -exportcert -alias campusshare -keystore data/lan-server.p12 -file data/lan-server.cer
keytool -printcert -file data/lan-server.cer
```

Copy `lan-server.cer` into `data` on B. Compare the printed SHA-256 certificate fingerprint on both laptops through a trusted channel. On both laptops, import the verified public certificate into a local truststore. Keep the server's private keystore on A.

```powershell
keytool -printcert -file data/lan-server.cer
keytool -importcert -alias campusshare -file data/lan-server.cer -storetype PKCS12 -keystore data/lan-trust.p12
```

### 3. Start the server and both clients

On A, start the server in its own terminal. Enter the chosen keystore passphrase at the prompt.

```powershell
$env:CAMPUSSHARE_KEYSTORE = "$PWD/data/lan-server.p12"
$env:CAMPUSSHARE_KEYSTORE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Keystore passphrase' -AsSecureString)).Password
$env:CAMPUSSHARE_STORAGE = "$PWD/data/shared"
java -cp backend/target/campusshare-1.0.0.jar com.campusshare.app.ServerMain
```

On both A and B, start each local agent in its own terminal. Keep broadcast discovery enabled on B.

```powershell
$env:CAMPUSSHARE_TRUSTSTORE = "$PWD/data/lan-trust.p12"
$env:CAMPUSSHARE_TRUSTSTORE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Truststore passphrase' -AsSecureString)).Password
$env:CAMPUSSHARE_DISCOVERY_ADDRESS = '255.255.255.255'
java -cp backend/target/campusshare-1.0.0.jar com.campusshare.app.LocalAgentMain
```

On both A and B, start the frontend in another terminal and open [http://localhost:5173](http://localhost:5173).

```powershell
npm.cmd --prefix frontend run dev
```

### 4. Perform and record the checks

1. On B, click **Discover servers** and record whether A's Wi-Fi IP and TCP port 42001 appear. Connect using a display name and verify the server-certificate indicator. On A, connect manually to the same Wi-Fi IP or to `127.0.0.1`.
2. Keep **Collaboration** open on B. Upload a file on A. Confirm that B receives **Shared files updated**, then download the file on B. Run `Get-FileHash -Algorithm SHA256 -LiteralPath '<path-to-file>'` against the original and the downloaded copy and record both hashes.
3. Start several transfers. Check active bytes and outcomes on **Transfers**. Cancel an in-progress upload and check that an upload cancelled before commit is absent from the shared list.
4. Disconnect and reconnect one client. Try an IP or DNS name absent from the certificate SAN that still resolves to A. The TLS connection must fail. Restore the trusted endpoint.
5. Stop A's server with Ctrl+C. Observe the notification channel disconnect and the clients return to Welcome after status probe failures. Restart the server with the same storage, rediscover from B, reconnect, and confirm the previously uploaded file still downloads.
6. Record both laptop IPs, network profile, firewall rules, discovery results, any fallback used, and transfer hashes.

### 5. Troubleshoot discovery and connectivity

From B, replace the sample IP with A's address.

```powershell
Test-NetConnection 192.168.1.20 -Port 42001
Test-NetConnection 192.168.1.20 -Port 42002
```

These checks cover TCP only. On A, use `Get-NetUDPEndpoint -LocalPort 42000` to confirm the server is listening. If B can reach TCP but discovery times out, confirm the private firewall rule, same subnet, and absence of wireless client isolation. Check whether a VPN or virtual adapter is selected for broadcast traffic. On B, you can set `CAMPUSSHARE_DISCOVERY_ADDRESS` to the correct subnet broadcast address and restart the agent. For example, `192.168.1.255` applies to `192.168.1.0/24`. If broadcast remains blocked, use **Connect manually** with the Wi-Fi IP covered by the certificate and record the discovery failure. Keep endpoint verification enabled.

