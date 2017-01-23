//
// Wire
// Copyright (C) 2016 Wire Swiss GmbH
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see http://www.gnu.org/licenses/.
//

package com.wire.bots.sdk;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.health.HealthCheck;
import com.wire.bots.sdk.server.resources.BotsResource;
import com.wire.bots.sdk.server.resources.MessageResource;
import com.wire.bots.sdk.server.resources.ProviderResource;
import com.wire.bots.sdk.server.resources.StatusResource;
import com.wire.bots.sdk.server.tasks.BroadCastTask;
import com.wire.bots.sdk.user.Endpoint;
import com.wire.bots.sdk.user.UserClient;
import io.dropwizard.Application;
import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.servlets.tasks.Task;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

import java.io.File;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for your Application
 * @param <Config>
 */
public abstract class Server<Config extends Configuration> extends Application<Config> {

    /**
     * This method is called once by the sdk in order to create the main message handler
     * @param config Configuration object (yaml)
     * @return Instance of your class that implements {@see @MessageHandlerBase}
     */
    protected abstract MessageHandlerBase createHandler(Config config);

    /**
     * Override this method in case you need to add custom Resource and/or Task {@link #addResource(Object, io.dropwizard.setup.Environment)}
     * and {@link #addTask(io.dropwizard.servlets.tasks.Task, io.dropwizard.setup.Environment)}
     *
     * @param config Configuration object (yaml)
     * @param env    Environment object
     */
    protected void onRun(Config config, Environment env) {
    }

    @Override
    public void initialize(Bootstrap<Config> configBootstrap) {
        configBootstrap.addBundle(new AssetsBundle("/diagnosis/", "/diagnosis/"));
    }

    @Override
    public void run(Config config, Environment env) throws Exception {
        MessageHandlerBase handler = createHandler(config);

        initTelemetry(config, env);

        checkCrypto(config, env);
        checkJCEPolicy(config, env);

        WireClientFactory factory = new WireClientFactory() {
            @Override
            public WireClient createClient(OtrManager otrManager, String botId, String convId, String clientId, String token) {
                return new BotClient(otrManager, botId, convId, clientId, token);
            }
        };

        MessageResource messageResource = new MessageResource(handler, factory, config);

        addResource(new StatusResource(), env);
        addResource(new BotsResource(handler, config), env);
        addResource(messageResource, env);
        addResource(new ProviderResource(config), env);

        addTask(new BroadCastTask(config, factory), env);

        onRun(config, env);

        String email = System.getProperty("email");
        String password = System.getProperty("password");

        if (email != null && password != null) {
            MessageResource msgRes = new MessageResource(handler, new WireClientFactory() {
                @Override
                public WireClient createClient(OtrManager otrManager, String botId, String convId, String clientId, String token) {
                    return new UserClient(otrManager, botId, convId, clientId, token);
                }
            }, config);

            Endpoint ep = new Endpoint(config, msgRes);
            ep.signIn(email, password);
            ep.connectWebSocket();
        }
    }

    protected void addTask(Task task, Environment env) {
        env.admin().addTask(task);
    }

    protected void addResource(Object component, Environment env) {
        env.jersey().register(component);
    }

    private void initTelemetry(final Config conf, Environment env) {
        env.healthChecks().register("ok", new HealthCheck() {
            @Override
            protected Result check() throws Exception {
                return Result.healthy();
            }
        });

        for (final String vol : conf.getDirs()) {
            env.healthChecks().register("volumes." + vol, new HealthCheck() {
                final File f = new File(vol);

                @Override
                protected Result check() throws Exception {
                    if (f.exists()) {
                        return Result.healthy();
                    } else {
                        String log = String.format("Failed reading volume %s", f.getCanonicalPath());
                        Logger.error(log);
                        return Result.unhealthy(log);
                    }
                }
            });
        }

        env.metrics().register("logger.errors", new Gauge<Integer>() {
            @Override
            public Integer getValue() {
                return Logger.getErrorCount();
            }
        });

        JmxReporter jmxReporter = JmxReporter.forRegistry(env.metrics())
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();
        jmxReporter.start();
    }

    private void checkCrypto(final Config config, Environment env) {
        env.healthChecks().register("CryptoBox", new HealthCheck() {
            @Override
            protected Result check() throws Exception {
                try (OtrManager otr = new OtrManager(config.getCryptoDir())) {
                    return Result.healthy();
                }
            }
        });
    }

    private void checkJCEPolicy(final Config config, Environment env) {
        env.healthChecks().register("JCEPolicy", new HealthCheck() {
            @Override
            protected Result check() throws Exception {
                byte[] otrKey = new byte[32];
                byte[] iv = new byte[16];
                byte[] data = new byte[1024];

                Random random = new Random();
                random.nextBytes(otrKey);
                random.nextBytes(iv);
                random.nextBytes(data);
                byte[] encBytes = Util.encrypt(otrKey, data, iv);
                return Result.healthy();
            }
        });
    }
}