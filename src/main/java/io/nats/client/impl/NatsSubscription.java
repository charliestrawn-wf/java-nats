// Copyright 2015-2018 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client.impl;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Subscription;

class NatsSubscription extends NatsConsumer implements Subscription {

    private String subject;
    private String queueName;
    private String sid;

    private NatsConnection connection;
    private NatsDispatcher dispatcher;
    private MessageQueue incoming;

    private AtomicLong unSubMessageLimit;

    NatsSubscription(String sid, String subject, String queueName, NatsConnection connection,
            NatsDispatcher dispatcher) {
        this.subject = subject;
        this.queueName = queueName;
        this.sid = sid;
        this.dispatcher = dispatcher;
        this.connection = connection;
        this.unSubMessageLimit = new AtomicLong(-1);

        if (this.dispatcher == null) {
            this.incoming = new MessageQueue(false);
        }
    }

    public boolean isActive() {
        return (this.dispatcher != null || this.incoming != null);
    }

    void invalidate() {
        if (this.incoming != null) {
            this.incoming.pause();
        }
        this.dispatcher = null;
        this.incoming = null;
    }

    void setUnsubLimit(long cd) {
        this.unSubMessageLimit.set(cd);
    }

    boolean reachedUnsubLimit() {
        long max = this.unSubMessageLimit.get();
        long recv = this.getDeliveredCount();
        return (max > 0) && (max <= recv);
    }

    String getSID() {
        return this.sid;
    }

    NatsDispatcher getNatsDispatcher() {
        return this.dispatcher;
    }

    MessageQueue getMessageQueue() {
        return this.incoming;
    }

    public Dispatcher getDispatcher() {
        return this.dispatcher;
    }

    public String getSubject() {
        return this.subject;
    }

    public String getQueueName() {
        return this.queueName;
    }

    public Message nextMessage(Duration timeout) throws InterruptedException, IllegalStateException {
        if (this.dispatcher != null) {
            throw new IllegalStateException(
                    "Subscriptions that belong to a dispatcher cannot respond to nextMessage directly.");
        } else if (this.incoming == null) {
            throw new IllegalStateException("This subscription is inactive.");
        }

        NatsMessage msg = incoming.pop(timeout);

        if (this.incoming == null || !this.incoming.isRunning()) { // We were unsubscribed while waiting
            throw new IllegalStateException("This subscription is inactive.");
        }

        this.incrementDeliveredCount();

        if (this.reachedUnsubLimit()) {
            this.connection.invalidate(this);
        }

        return msg;
    }

    /**
     * Unsubscribe this subscription and stop listening for messages.
     * 
     * <p>Messages are stopped locally and the server is notified.</p>
     */
    public void unsubscribe() {
        if (this.dispatcher != null) {
            throw new IllegalStateException(
                    "Subscriptions that belong to a dispatcher cannot respond to unsubscribe directly.");
        } else if (this.incoming == null) {
            throw new IllegalStateException("This subscription is inactive.");
        }

        this.connection.unsubscribe(this, -1);
    }

    /**
     * Unsubscribe this subscription and stop listening for messages, after the
     * specified number of messages.
     * 
     * <p>If the subscription has already received <code>after</code> messages, it will not receive
     * more. The provided limit is a lifetime total for the subscription, with the caveat
     * that if the subscription already received more than <code>after</code> when unsubscribe is called
     * the client will not travel back in time to stop them.</p>
     * 
     * <p>For example, to get a single asynchronous message, you might do:
     * <blockquote><pre>
     * nc = Nats.connect()
     * m = nc.subscribe("hello").unsubscribe(1).nextMessage(Duration.ZERO);
     * </pre></blockquote></p>
     * 
     * @param after The number of messages to accept before unsubscribing
     * @return The subscription so that calls can be chained
     */
    public Subscription unsubscribe(int after) {
        if (this.dispatcher != null) {
            throw new IllegalStateException(
                    "Subscriptions that belong to a dispatcher cannot respond to unsubscribe directly.");
        } else if (this.incoming == null) {
            throw new IllegalStateException("This subscription is inactive.");
        }

        this.connection.unsubscribe(this, after);
        return this;
    }
}