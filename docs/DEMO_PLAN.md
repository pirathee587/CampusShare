# Ten-minute demonstration

Before the demo, start Laptop A's server and the agents and frontends on the clients. Prepare a sample PDF, several larger files, an unrelated truststore, and a valid certificate containing Laptop A's LAN IP.

| Time | Member | Show |
| --- | --- | --- |
| 0:00–2:00 | 1 | Enter an unverified display name, discover Laptop A using UDP, connect and show the manual IP fallback. |
| 2:00–4:00 | 2 | Upload the PDF, download on another client, and explain explicit framing and chunked streaming. |
| 4:00–6:00 | 3 | Start five transfers, view the worker and queue counts, and cancel one active transfer. |
| 6:00–8:00 | 4 | Show the verified server indicator and a failed handshake using the unrelated truststore. Compare original and downloaded SHA-256 digests. |
| 8:00–10:00 | 5 | Show connected notification clients, trigger a file update and explain how the selector handles readiness and partial writes. |

Use the [test checklist](TESTING.md) during rehearsal. The shared library is available to each client and display names are unverified.
