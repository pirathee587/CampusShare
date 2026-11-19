# CampusShare repository instructions

Read the code and relevant tests before changing behavior. Read [WARP.md](WARP.md), [team scope](docs/TEAM_SCOPE.md), and the API or protocol contract for the affected boundary. Check the working tree and preserve unrelated team changes.

- Member 1 owns discovery and Welcome / Connect.
- Member 2 owns the framed TCP transfer engine, file storage integration, and Shared Files.
- Member 3 owns execution limits, transfer records, progress, cancellation, and Transfers.
- Member 4 owns TLS, server certificate and endpoint verification, SHA-256, and the Security view.
- Member 5 owns the NIO event loop, event client and SSE, and Collaboration.

Use the localhost Java agent for all React networking operations. Preserve the UDP, TCP, concurrency, TLS/SHA-256, and selector demonstrations. Keep display names unverified and connection-scoped. The shared file library has no account or per-user permission model. Keep certificate and endpoint verification enabled and test trusted, untrusted, mismatched and expired certificates. Preserve the allowed-origin and custom POST-header checks. Keep store secrets and private keys out of source control.

Update README and relevant documents when contracts, setup, or behavior change. Run affected JUnit and Vitest tests and builds. For cross-layer changes, run the packaged-service browser integration in [testing](docs/TESTING.md). Report what actually ran and whether any physical LAN validation remains. Use focused changes within module boundaries and coordinate cross-module contract changes. Push or merge only with explicit authorization.
