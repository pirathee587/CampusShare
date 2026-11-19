# Local agent API

The Java HTTP listener binds to `127.0.0.1`. Vite proxies same-origin `/api` traffic. Direct browser requests must use the configured origin. All POST requests require `X-CampusShare-Request: 1`. JSON errors use `{"error":"message"}`.

| Method and path | Input | Success |
| --- | --- | --- |
| GET /api/health | — | 200 with `{"ready":true}` |
| GET /api/status | — | 200 with connection status |
| GET /api/discovery | — | 200 with an array of `{host,port}` UDP results |
| POST /api/connect | JSON `{displayName,host,port}` | 200 with verified connection status |
| POST /api/disconnect | — | 200 with disconnected status |
| GET /api/files | — | 200 with an array of shared file objects |
| POST /api/files/upload | Raw file body with `Content-Length`, URL-encoded `X-Filename`, optional UUID `X-Transfer-Id` | 201 with shared file object |
| GET /api/files/{id}/download | Optional UUID query parameter `transferId` | 200 with verified attachment |
| GET /api/transfers | — | 200 with an array of local transfer records |
| POST /api/transfers/{id}/cancel | — | 200 with `{"cancelled":true}` |
| GET /api/events | — | 200 SSE stream with one generic event type per `data:` message |

Status fields are `connected`, `tlsVerified`, `displayName`, `host`, `port`, `eventsConnected`, `onlineCount`, `activeWorkers`, and `queuedRequests`. When disconnected, display name and host are omitted. A shared file contains `id`, `filename`, `size` in bytes, and hexadecimal `sha256`. A transfer contains `id`, `direction`, `filename`, `size`, `bytes`, and `state`. SSE sends keepalive comments during idle periods.

Errors include 400 invalid input, 403 unexpected origin, host, or missing POST request header, 404 unknown endpoint or active transfer, 409 when a connection is needed, 411 missing file length, 413 over size limit, 422 server rejection, 502 TLS or network failure, and 503 local capacity reached.
