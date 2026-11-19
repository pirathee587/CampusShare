package com.campusshare.security;

import com.campusshare.config.Config;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;

public final class Tls {
    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private Tls() {}

    private static KeyStore load(Path path, char[] password) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }

    public static SSLServerSocket listen(int port) throws Exception {
        return listen(port, Path.of(Config.value("KEYSTORE", "KEYSTORE.p12")),
                Config.value("KEYSTORE_PASSWORD", "").toCharArray());
    }

    public static SSLServerSocket listen(int port, Path path, char[] password) throws Exception {
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(load(path, password), password);
        var context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        var socket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(port);
        socket.setEnabledProtocols(PROTOCOLS);
        return socket;
    }

    public static SSLContext clientContext() throws Exception {
        return clientContext(Path.of(Config.value("TRUSTSTORE", "TRUSTSTORE.p12")),
                Config.value("TRUSTSTORE_PASSWORD", "").toCharArray());
    }

    public static SSLContext clientContext(Path path, char[] password) throws Exception {
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(load(path, password));
        var context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    public static SSLSocket connect(SSLContext context, String host, int port) throws IOException {
        var socket = (SSLSocket) context.getSocketFactory().createSocket();
        try {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(15000);
            socket.setEnabledProtocols(PROTOCOLS);
            var parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            // JSSE may treat a directly trusted self-signed leaf as a trust anchor.
            try {
                ((X509Certificate) socket.getSession().getPeerCertificates()[0]).checkValidity();
            } catch (CertificateException invalid) {
                var failure = new SSLHandshakeException("Server certificate is outside its validity period");
                failure.initCause(invalid);
                throw failure;
            }
            return socket;
        } catch (IOException | RuntimeException error) {
            socket.close();
            throw error;
        }
    }
}
