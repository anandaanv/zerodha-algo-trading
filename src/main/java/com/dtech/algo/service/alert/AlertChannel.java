package com.dtech.algo.service.alert;

import com.dtech.algo.service.AlertQueueService;

/**
 * Interface for alert dispatch channels (WhatsApp, WebSocket, email, etc.).
 * AlertQueueService uses all available channels to dispatch alerts.
 */
public interface AlertChannel {

    /**
     * Send an alert through this channel.
     */
    void send(AlertQueueService.AlertEntry alert);

    /**
     * Whether this channel supports the given alert type.
     */
    boolean supports(AlertQueueService.AlertType alertType);

    /**
     * Human-readable channel name for logging.
     */
    String getChannelName();
}
