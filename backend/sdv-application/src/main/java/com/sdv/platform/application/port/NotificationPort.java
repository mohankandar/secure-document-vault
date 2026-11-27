package com.sdv.platform.application.port;

import com.sdv.platform.application.notifications.VeroEvent;
import com.sdv.platform.application.notifications.VeroNotice;

import java.util.UUID;

/**
 * Task 0081: tells people about what happened in Vero (e-mail, Teams, Slack). Implemented in the web layer over the
 * platform's notifications. Every method returns at once and never throws: a notification never stops or undoes the
 * work it reports. Inside a transaction, it goes only once the transaction has committed.
 */
public interface NotificationPort {

    /** A one-off event. */
    void publish(VeroNotice notice);

    /** A condition is failing (call on every check that fails; it alerts once, after a while). */
    void failing(VeroNotice notice);

    /** The condition is gone; Recovered follows only if it had alerted. */
    void cleared(VeroEvent event, UUID entityId);

    /** Sends nothing (tests, and installs with notifications off). */
    NotificationPort NONE = new NotificationPort() {
        @Override public void publish(VeroNotice notice) { }
        @Override public void failing(VeroNotice notice) { }
        @Override public void cleared(VeroEvent event, UUID entityId) { }
    };
}
