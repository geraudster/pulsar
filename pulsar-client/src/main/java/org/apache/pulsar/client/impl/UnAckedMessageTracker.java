/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.impl;

import com.google.common.base.Preconditions;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.common.util.collections.ConcurrentOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class UnAckedMessageTracker implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(UnAckedMessageTracker.class);

    protected final ConcurrentHashMap<MessageId, ConcurrentOpenHashSet<MessageId>> messageIdPartitionMap;
    protected final LinkedList<ConcurrentOpenHashSet<MessageId>> timePartitions;

    protected final Lock readLock;
    protected final Lock writeLock;

    public static final UnAckedMessageTrackerDisabled UNACKED_MESSAGE_TRACKER_DISABLED = new UnAckedMessageTrackerDisabled();
    private final long ackTimeoutMillis;
    private final long tickDurationInMs;

    private static class UnAckedMessageTrackerDisabled extends UnAckedMessageTracker {
        @Override
        public void clear() {
        }

        @Override
        public boolean add(MessageId m) {
            return true;
        }

        @Override
        public boolean remove(MessageId m) {
            return true;
        }

        @Override
        public int removeMessagesTill(MessageId msgId) {
            return 0;
        }

        @Override
        public void close() {
        }
    }

    private Timeout timeout;

    public UnAckedMessageTracker() {
        readLock = null;
        writeLock = null;
        timePartitions = null;
        messageIdPartitionMap = null;
        this.ackTimeoutMillis = 0;
        this.tickDurationInMs = 0;
    }

    public UnAckedMessageTracker(PulsarClientImpl client, ConsumerBase<?> consumerBase, long ackTimeoutMillis) {
        this(client, consumerBase, ackTimeoutMillis, ackTimeoutMillis);
    }

    public UnAckedMessageTracker(PulsarClientImpl client, ConsumerBase<?> consumerBase, long ackTimeoutMillis, long tickDurationInMs) {
        Preconditions.checkArgument(tickDurationInMs > 0 && ackTimeoutMillis >= tickDurationInMs);
        this.ackTimeoutMillis = ackTimeoutMillis;
        this.tickDurationInMs = tickDurationInMs;
        ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
        this.readLock = readWriteLock.readLock();
        this.writeLock = readWriteLock.writeLock();
        this.messageIdPartitionMap = new ConcurrentHashMap<>();
        this.timePartitions = new LinkedList<>();

        int blankPartitions = (int)Math.ceil((double)this.ackTimeoutMillis / this.tickDurationInMs);
        for (int i = 0; i < blankPartitions + 1; i++) {
            timePartitions.add(new ConcurrentOpenHashSet<>());
        }

        timeout = client.timer().newTimeout(new TimerTask() {
            @Override
            public void run(Timeout t) throws Exception {
                Set<MessageId> messageIds = new HashSet<>();
                writeLock.lock();
                try {
                    timePartitions.addLast(new ConcurrentOpenHashSet<>());
                    ConcurrentOpenHashSet<MessageId> headPartition = timePartitions.removeFirst();
                    if (!headPartition.isEmpty()) {
                        log.warn("[{}] {} messages have timed-out", consumerBase, timePartitions.size());
                        headPartition.forEach(messageId -> {
                            messageIds.add(messageId);
                            messageIdPartitionMap.remove(messageId);
                        });
                    }
                } finally {
                    writeLock.unlock();
                }
                if (messageIds.size() > 0) {
                    consumerBase.onAckTimeoutSend(messageIds);
                    consumerBase.redeliverUnacknowledgedMessages(messageIds);
                }
                timeout = client.timer().newTimeout(this, tickDurationInMs, TimeUnit.MILLISECONDS);
            }
        }, this.tickDurationInMs, TimeUnit.MILLISECONDS);
    }

    public void clear() {
        writeLock.lock();
        try {
            messageIdPartitionMap.clear();
            timePartitions.clear();
            int blankPartitions = (int)Math.ceil((double)ackTimeoutMillis / tickDurationInMs);
            for (int i = 0; i < blankPartitions + 1; i++) {
                timePartitions.add(new ConcurrentOpenHashSet<>());
            }
        } finally {
            writeLock.unlock();
        }
    }

    public boolean add(MessageId messageId) {
        writeLock.lock();
        try {
            ConcurrentOpenHashSet<MessageId> partition = timePartitions.peekLast();
            messageIdPartitionMap.put(messageId, partition);
            return partition.add(messageId);
        } finally {
            writeLock.unlock();
        }
    }

    boolean isEmpty() {
        readLock.lock();
        try {
            return messageIdPartitionMap.isEmpty();
        } finally {
            readLock.unlock();
        }
    }

    public boolean remove(MessageId messageId) {
        writeLock.lock();
        try {
            boolean removed = false;
            ConcurrentOpenHashSet<MessageId> exist = messageIdPartitionMap.remove(messageId);
            if (exist != null) {
                removed = exist.remove(messageId);
            }
            return removed;
        } finally {
            writeLock.unlock();
        }
    }

    long size() {
        readLock.lock();
        try {
            return messageIdPartitionMap.size();
        } finally {
            readLock.unlock();
        }
    }

    public int removeMessagesTill(MessageId msgId) {
        writeLock.lock();
        try {
            int removed = 0;
            Iterator<MessageId> iterator = messageIdPartitionMap.keySet().iterator();
            while (iterator.hasNext()) {
                MessageId messageId = iterator.next();
                if (messageId.compareTo(msgId) <= 0) {
                    ConcurrentOpenHashSet<MessageId> exist = messageIdPartitionMap.get(messageId);
                    if (exist != null) {
                        exist.remove(messageId);
                    }
                    iterator.remove();
                    removed ++;
                }
            }
            return removed;
        } finally {
            writeLock.unlock();
        }
    }

    private void stop() {
        writeLock.lock();
        try {
            if (timeout != null && !timeout.isCancelled()) {
                timeout.cancel();
            }
            this.clear();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close() {
        stop();
    }
}
