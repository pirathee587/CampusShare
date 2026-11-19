# CampusShare working guide

CampusShare demonstrates Java networking across a LAN. The React UI calls the localhost Java agent through a loopback Vite proxy. Only the agent opens discovery, file transfer and notification sockets.

| Member | Module boundary |
| --- | --- |
| 1 | UDP discovery, manual server entry, Welcome / Connect |
| 2 | TCP protocol framing, bounded file streaming, shared storage |
| 3 | Bounded executor, queue, transfer tracking, cancellation and shutdown |
| 4 | JSSE TLS 1.2/1.3, truststore and endpoint checks, SHA-256, Security view |
| 5 | Selector, non-blocking channels, bounded event queues and SSE |

Use Java 21 and Maven with standard networking libraries. Keep the React, TypeScript, Vite, Tailwind and Router frontend local. Store shared files and their metadata on disk. Keep display names in local agent memory for the current connection and label them unverified. The file library is shared by all clients that can reach the service. Accounts, user passwords, identity tokens and per-user access rules are outside the design.

Keep TLS and SHA-256 verification in the transfer path. Authenticate the **server endpoint** using the configured truststore and the connected host or IP. Keep discovery as a hint and NIO event payloads limited to non-sensitive event types. Keep the agent bound to 127.0.0.1, restrict browser origins, and preserve the custom request header on mutating calls.

Coordinate changes to API or wire contracts and update their docs in the same change. Use bounded buffers, explicit length framing, validated names and sizes, and clean socket closure. Done means the feature works via the normal app entry point, tests exercise relevant real sockets or frontend behavior, and documentation matches those results. Report whether a demo used loopback or two laptops.

Run `mvn -f backend/pom.xml package`, `npm --prefix frontend test`, and `npm --prefix frontend run build`. Run the browser integration described in [testing](docs/TESTING.md) for changes spanning Vite, the agent, or the server. The same document defines the separate two-laptop Windows procedure.
