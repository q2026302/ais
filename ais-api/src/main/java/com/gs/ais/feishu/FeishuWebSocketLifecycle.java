package com.gs.ais.feishu;

import com.gs.ais.config.FeishuProperties;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Owns Feishu's WebSocket long connection for the lifetime of this application.
 * HTTP callbacks remain available as an explicit compatibility transport.
 */
@Component
public class FeishuWebSocketLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebSocketLifecycle.class);
    private static final long INITIAL_CONNECTION_TIMEOUT_MILLIS = 15_000L;

    private final FeishuProperties properties;
    private final FeishuEventService eventService;
    private final TaskExecutor taskExecutor;
    private final Object lifecycleMonitor = new Object();

    private volatile Client client;
    private volatile boolean running;
    private volatile WebSocketStatus status = WebSocketStatus.NOT_STARTED;

    public FeishuWebSocketLifecycle(FeishuProperties properties,
                                    FeishuEventService eventService,
                                    @Qualifier("feishuTaskExecutor") TaskExecutor taskExecutor) {
        this.properties = properties;
        this.eventService = eventService;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (running) {
                return;
            }
            if (!properties.isEnabled()) {
                setStatus(WebSocketStatus.DISABLED);
                log.info("Feishu WebSocket is disabled by configuration.");
                return;
            }
            if (!properties.hasSupportedTransport()) {
                setStatus(WebSocketStatus.MISCONFIGURED);
                log.warn("Feishu transport '{}' is unsupported. Use 'websocket' or 'http'.", properties.getTransport());
                return;
            }
            if (properties.isHttpMode()) {
                setStatus(WebSocketStatus.HTTP_CALLBACK);
                log.info("Feishu uses HTTP callback transport; WebSocket connection will not be created.");
                return;
            }
            if (!properties.isConfigured()) {
                setStatus(WebSocketStatus.MISCONFIGURED);
                log.warn("Feishu WebSocket is enabled but app-id or app-secret is not configured.");
                return;
            }

            running = true;
            setStatus(WebSocketStatus.CONNECTING);
            log.info("Feishu WebSocket is connecting.");
            try {
                taskExecutor.execute(this::connect);
            } catch (RuntimeException e) {
                running = false;
                setStatus(WebSocketStatus.FAILED);
                log.error("Failed to enqueue the Feishu WebSocket connection task.", e);
            }
        }
    }

    private void connect() {
        Client createdClient = null;
        try {
            EventDispatcher dispatcher = EventDispatcher.newBuilder(
                            emptyIfNull(properties.getVerificationToken()), emptyIfNull(properties.getEncryptKey()))
                    .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                        @Override
                        public void handle(P2MessageReceiveV1 event) {
                            eventService.acceptWebSocketMessage(event);
                        }
                    })
                    .build();

            createdClient = new Client.Builder(properties.getAppId(), properties.getAppSecret())
                    .eventHandler(dispatcher)
                    .autoReconnect(true)
                    .onReconnecting(() -> updateForReconnect())
                    .onReconnected(() -> updateForReconnected())
                    .build();

            synchronized (lifecycleMonitor) {
                if (!running) {
                    createdClient.close();
                    return;
                }
                client = createdClient;
            }

            createdClient.start();
            createdClient.awaitReady(INITIAL_CONNECTION_TIMEOUT_MILLIS);
            synchronized (lifecycleMonitor) {
                if (running && client == createdClient) {
                    setStatus(WebSocketStatus.CONNECTED);
                    log.info("Feishu WebSocket connected and ready.");
                }
            }
        } catch (Exception e) {
            synchronized (lifecycleMonitor) {
                if (!running) return;
                if (createdClient == null) {
                    setStatus(WebSocketStatus.FAILED);
                    log.error("Failed to initialize the Feishu WebSocket client.", e);
                    return;
                }
                // The SDK keeps its built-in reconnect logic active after an initial
                // await timeout. Keep the client alive and expose a diagnosable state.
                setStatus(WebSocketStatus.DISCONNECTED);
                log.warn("Feishu WebSocket did not become ready yet; it will keep reconnecting.", e);
            }
        }
    }

    private void updateForReconnect() {
        synchronized (lifecycleMonitor) {
            if (!running) return;
            setStatus(WebSocketStatus.RECONNECTING);
            log.warn("Feishu WebSocket connection interrupted; reconnecting.");
        }
    }

    private void updateForReconnected() {
        synchronized (lifecycleMonitor) {
            if (!running) return;
            setStatus(WebSocketStatus.CONNECTED);
            log.info("Feishu WebSocket reconnected.");
        }
    }

    @Override
    public void stop() {
        Client clientToClose;
        synchronized (lifecycleMonitor) {
            if (!running && client == null) return;
            running = false;
            clientToClose = client;
            client = null;
            setStatus(WebSocketStatus.STOPPED);
        }
        if (clientToClose != null) {
            try {
                clientToClose.close();
            } catch (Exception e) {
                log.warn("Failed while closing Feishu WebSocket.", e);
            }
        }
        log.info("Feishu WebSocket stopped.");
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    public String getStatus() {
        return status.name();
    }

    private void setStatus(WebSocketStatus status) {
        this.status = status;
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    public enum WebSocketStatus {
        NOT_STARTED,
        DISABLED,
        HTTP_CALLBACK,
        MISCONFIGURED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        DISCONNECTED,
        FAILED,
        STOPPED
    }
}
