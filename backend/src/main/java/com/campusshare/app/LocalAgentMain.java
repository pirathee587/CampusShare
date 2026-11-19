package com.campusshare.app;

import com.campusshare.clientagent.LocalHttp;
import com.campusshare.config.Config;

public final class LocalAgentMain {
    public static void main(String[] args) throws Exception {
        var agent = new LocalHttp(Config.agentPort());
        Runtime.getRuntime().addShutdownHook(new Thread(agent::close));
        agent.start();
        System.out.println("CampusShare local agent listening on http://127.0.0.1:" + agent.port());
    }
}
