# Software requirements

- **Entry and connection.** Welcome / Connect accepts a trimmed display name of 1–40 characters and a server selected from UDP results or entered manually as an IP or hostname and port. Report discovery timeout, TLS failures, and disconnects.
- **Identity.** A name is a self-chosen, unverified label. Names may be reused by multiple clients. Keep the label in the local agent's memory and clear it on disconnect or agent restart. The server stores shared files and file metadata. Account creation, login, credentials, and permissions keyed to a person or name are out of scope.
- **Shared files.** Every connected client can list, upload and download common files. Validate sizes and names, stream byte counts through bounded buffers, and publish complete files only after SHA-256 verification. Verify stored digests before releasing downloads to the browser.
- **Secure transport.** Establish connections using TLS 1.2 or 1.3. Validate the server certificate and the connected IP or hostname using a configured truststore. Mark the UI verified only after a successful TLS application exchange.
- **Concurrency.** Bound file workers and queue capacity. Keep connection status responsive while files transfer. Display progress, local outcomes and server worker/queue counts. Support cancellation by closing the transfer socket.
- **Collaboration.** Use non-blocking Java NIO channels and a selector to broadcast bounded, non-sensitive events. The local agent sends events to React over SSE and reports the notification client count and channel availability.
- **Environment.** Run offline on a LAN after dependencies are installed. Keep the browser API on loopback and expose configurable ports.
