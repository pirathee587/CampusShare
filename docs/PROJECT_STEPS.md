# Implementation phases

| Phase | Owner | Depends on | Files and acceptance check |
| --- | --- | --- | --- |
| 1. Foundation | All | — | Build Java with Maven and React with Vite. Check README startup commands. |
| 2. Discovery | 1 | 1 | Exercise `Discovery` and `WelcomePage` with UDP results, timeout and manual entry. |
| 3. File transfer | 2 | 1 | Check `Protocol`, `TransferClient`, `TransferServer`, and `FileStore` for fragmented reads, upload/download and interrupted cleanup. |
| 4. Concurrency | 3 | 3 | Inspect `TransferServer` and `TransferTracker`. Run simultaneous uploads and observe cancellation, capacity, and shutdown. |
| 5. Transport and integrity | 4 | 3 | Run trusted and rejected certificate scenarios in `Tls`, and checksum checks in `Integrity`. |
| 6. Collaboration | 5 | 3 | Check `Notifications` and agent SSE with multiple clients and an upload event. |
| 7. React integration | All | 2–6 | Exercise Welcome / Connect and dashboard routes against `LocalHttp`. Confirm temporary, unverified display names. |
| 8. End-to-end | All | 7 | Run [testing procedures](TESTING.md) on loopback and two laptops. Record actual results. |
| 9. Presentation | All | 8 | Rehearse [the five-member demo](DEMO_PLAN.md) and verify setup on a clean machine. |

Use the checks in order as a completion checklist. Commit contract changes together with their API, protocol, and setup documentation.
