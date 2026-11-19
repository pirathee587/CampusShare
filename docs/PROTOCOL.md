# Wire protocol

## Discovery

Send ASCII `CAMPUSSHARE_DISCOVER_V1` as one UDP datagram. The server replies to the sender with `CAMPUSSHARE_SERVER_V1 <tlsPort>`. The client uses the datagram source IP and advertised TCP port, deduplicates endpoints, and returns after a 1.2-second collection window. Trust is established independently during the TLS handshake.

## TLS file service

Open one TLS socket per request, using TLS 1.2 or 1.3. A metadata frame starts with a four-byte big-endian length followed by UTF-8 JSON bytes. Requests are capped at 4 KiB and responses at 16 MiB. Malformed lengths and incomplete reads terminate the request.

| Frame | Fields |
| --- | --- |
| Request | `action`, `requestId`, optional `fileId`, optional `filename`, `size` for uploads |
| Response | `ok`, matching `requestId`, optional `error`, optional `data` |

- `STATUS` returns the event port, notification count, maximum file size, active workers and queued transfers.
- `LIST` returns the shared file metadata array.
- `UPLOAD` sends a request naming the file and its exact byte length. After server acceptance, send exactly that many raw bytes and the 32-byte binary SHA-256 digest. The server replies with verified file metadata or an error. Temporary files are discarded for failed or incomplete uploads.
- `DOWNLOAD` sends the file UUID. The server replies with metadata including the byte length and stored digest, followed by exactly that many bytes. The client reads the full payload and verifies it before offering it to the browser.

Server filenames are validated display metadata. Storage paths use server-generated UUIDs. The protocol contains no user identity or permissions.

## NIO events

The separate TCP event channel sends only ASCII event identifiers delimited by LF. The types are `FILE_LIST_CHANGED`, `TRANSFER_COMPLETED`, `SERVER_STATUS_CHANGED`, and `ONLINE_COUNT_CHANGED`. Slow consumers with full outgoing queues are disconnected. Use TLS status and file-list requests for the current authoritative state. The event channel is plaintext and carries no file metadata or display names.
