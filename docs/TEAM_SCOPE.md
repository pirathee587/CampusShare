# Five-member team scope

| Member | Owned implementation | Independent demonstration |
| --- | --- | --- |
| 1 · Discovery | UDP sockets, broadcast, response parsing, deduplication, timeout, IP fallback, connection status and Welcome / Connect | Discover Laptop A from Laptop B, select a result using an unverified display name, then reconnect manually. |
| 2 · Transfer engine | TCP streams, request IDs, message framing, file validation, bounded chunks, interruption handling, shared file UI | Upload a file on one client and download it on another. Compare bytes. |
| 3 · Concurrency | Bounded thread pool and queue, active records, progress, cancellation and shutdown | Start five transfers, inspect live state, cancel an active transfer and show the service remains responsive. |
| 4 · Secure networking | JSSE server identity and endpoint validation, truststore configuration, TLS failures, security indicator, SHA-256 | Connect with the correct truststore, reject an unrelated certificate and show successful file verification. |
| 5 · Collaboration | Non-blocking SocketChannels, one Selector, partial writes, bounded event queues, connection counts, agent SSE and Collaboration UI | Connect five clients and trigger a file list event. Observe clients receiving it and explain selector readiness. |

Members 2–4 integrate with the same TCP transfer server. Member 5 publishes generic events from the transfer service. Member 1 supplies the endpoint and local display name; the display name grants no privileges.
