package com.vidacruz.triage_service.application;

public interface AlertStreamMetricsPort {
    void connectionOpened();

    void connectionClosed();
}
