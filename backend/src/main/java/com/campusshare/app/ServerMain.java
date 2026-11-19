package com.campusshare.app;

import com.campusshare.collaboration.Notifications;
import com.campusshare.config.Config;
import com.campusshare.discovery.Discovery;
import com.campusshare.security.Tls;
import com.campusshare.storage.FileStore;
import com.campusshare.transfer.TransferServer;
import java.nio.file.Path;

public final class ServerMain {
    public static void main(String[] args) throws Exception {
        var storage = new FileStore(Path.of(Config.value("STORAGE", "data/shared")));
        try (var discovery = new Discovery(Config.discoveryPort());
             var notifications = new Notifications(Config.eventPort());
             var listener = Tls.listen(Config.tlsPort());
             var transfers = new TransferServer(listener, storage, notifications, Config.eventPort())) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (var service : new AutoCloseable[]{discovery, transfers, notifications}) {
                    try { service.close(); } catch (Exception error) { System.err.println(error.getMessage()); }
                }
            }));
            notifications.start();
            discovery.start(Config.tlsPort());
            System.out.printf("CampusShare server ready (UDP %d, TLS %d, NIO %d)%n",
                    Config.discoveryPort(), Config.tlsPort(), Config.eventPort());
            transfers.run();
        }
    }
}
