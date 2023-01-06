package org.telegram.telegrambots.webhook;

import io.javalin.Javalin;
import io.javalin.event.EventHandler;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.RequestLogger;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.telegram.telegrambots.common.webhook.TelegramWebhookBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import javax.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Ruben Bermudez
 * @version 1.0
 */
@Slf4j
public class TelegramBotsWebhookApplication implements AutoCloseable {
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private final WebhookOptions webhookOptions;

    private Javalin app;

    public TelegramBotsWebhookApplication(WebhookOptions webhookOptions) throws TelegramApiException {
        webhookOptions.validate();
        this.webhookOptions = webhookOptions;
        synchronized (isRunning) {
            startServerInternal();
            isRunning.set(true);
        }
    }

    public void registerBot(TelegramWebhookBot telegramWebhookBot) {
        if (isRunning.get()) {
            app.post(telegramWebhookBot.getBotPath(), ctx -> {
                Update update = ctx.bodyStreamAsClass(Update.class);
                BotApiMethod<?> response = telegramWebhookBot.onWebhookUpdateReceived(update);
                if (response != null) {
                    response.validate();
                    ctx.json(response);
                }
                ctx.status(200);
            });
        } else {
            throw new RuntimeException("Server is not running");
        }
    }

    public boolean isRunning() {
        synchronized (isRunning) {
            return isRunning.get();
        }
    }

    public void start() {
        if (isRunning.get()) {
            throw new RuntimeException("Server already running");
        }
        synchronized (isRunning) {
            if (isRunning.get()) {
                throw new RuntimeException("Server already running");
            }
            startServerInternal();
            isRunning.set(true);
        }
    }

    public void stop() {
        if (isRunning.get()) {
            synchronized (isRunning) {
                if (isRunning.get()) {
                    app.close();
                    app = null;
                    isRunning.set(false);
                } else {
                    throw new RuntimeException("Server is not running");
                }
            }
        } else {
            throw new RuntimeException("Server is not running");
        }
    }

    private void startServerInternal() {
        app = Javalin
                .create(javalinConfig -> {
                    if (webhookOptions.getUseHttps()) {
                        javalinConfig.jetty.server(this::createHttp2Server);
                    }
                    javalinConfig.http.defaultContentType = MediaType.APPLICATION_JSON;
                    javalinConfig.requestLogger.http(new RequestLogger() {
                        @Override
                        public void handle(@NonNull Context ctx, @NonNull Float executionTimeMs) throws Exception {
                            // TODO Request Logger setup
                        }
                    });

                })
                .events(events -> {
                    // TODO Validate events to listen
                    events.serverStarted(new EventHandler() {
                        @Override
                        public void handleEvent() throws Exception {
                            log.info("Server started");
                        }
                    });
                    events.serverStopped(new EventHandler() {
                        @Override
                        public void handleEvent() throws Exception {
                            log.info("Server Stopped");
                        }
                    });
                })
                .before(new Handler() {
                    // TODO Validate before request logging
                    @Override
                    public void handle(@NonNull Context ctx) throws Exception {
                        log.info("Request Received");
                    }
                })
                .after(new Handler() {
                    // TODO Validate after request logging
                    @Override
                    public void handle(@NonNull Context ctx) throws Exception {
                        log.info("Request Received");
                    }
                })
                .start(webhookOptions.getPort());
    }

    private Server createHttp2Server() {
        Server server = new Server();

        // HTTP Configuration
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSendServerVersion(false);
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(webhookOptions.getPort());

        // SSL Context Factory for HTTPS and HTTP/2
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server.Server();
        sslContextFactory.setKeyStorePath(webhookOptions.getKeyStorePath());
        sslContextFactory.setKeyStorePassword(webhookOptions.getKeyStorePassword());
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
        sslContextFactory.setProvider("Conscrypt");

        // HTTPS Configuration
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        // HTTP/2 Connection Factory
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol("h2");

        // SSL Connection Factory
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());

        // HTTP/2 Connector
        ServerConnector http2Connector = new ServerConnector(server, ssl, alpn, h2, new HttpConnectionFactory(httpsConfig));
        http2Connector.setPort(webhookOptions.getPort());
        server.addConnector(http2Connector);

        return server;
    }

    @Override
    public void close() throws Exception {
        app.close();
    }
}