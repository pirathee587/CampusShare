# Connection security

CampusShare verifies the server using JSSE, a configured PKCS#12 truststore, and endpoint identification. Connect using the exact IP address or DNS name in the certificate's Subject Alternative Name. TLS 1.2 and 1.3 are enabled. The agent explicitly checks leaf certificate validity, including directly trusted development certificates. The React indicator depends on the local agent's successful TLS status exchange.

Display names are self-declared and unverified. Any client that can reach the TLS service can use the shared library. Run on a LAN where that access is appropriate. UDP discovery and the generic NIO event channel are unencrypted. The agent's loopback HTTP API restricts browser origins and accepted Host headers and requires a custom header on every POST.

Uploads are checked against a SHA-256 digest sent over TLS and then stored with verified metadata. Downloads are staged and compared against the stored digest before the browser receives the file. Validation, bounded queues, configurable size caps, socket timeouts and randomized storage paths protect the transfer path.

## Generate development stores

From the workspace root, run these commands and choose a local passphrase when prompted. Replace `192.168.1.20` with the server's actual LAN IP before generating the certificate. For IPv4 loopback and LAN addresses used here, use SAN entries of type `IP`. Include a DNS SAN only if clients will connect using that name.

```text
keytool -genkeypair -alias campusshare -keyalg EC -dname "CN=CampusShare LAN" -ext "SAN=IP:127.0.0.1,IP:192.168.1.20" -validity 90 -storetype PKCS12 -keystore data/KEYSTORE.p12
keytool -exportcert -alias campusshare -keystore data/KEYSTORE.p12 -file data/campusshare.cer
keytool -printcert -file data/campusshare.cer
keytool -importcert -alias campusshare -file data/campusshare.cer -storetype PKCS12 -keystore data/TRUSTSTORE.p12
```

Copy only the public certificate to clients and compare its SHA-256 fingerprint through a trusted channel before importing. Keep the server keystore and its private key on the server. Set the matching store path and passphrase via the server or agent environment. Keep the generated stores, certificates and passphrases out of version control.
