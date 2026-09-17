package org.vivecraft.client_vr.provider.nexavr;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Receives the newest Nexa ARCore/MediaPipe sample from the Android host over loopback only. */
public final class NexaTrackingReceiver implements AutoCloseable {
    public static final int PORT = 47821;
    private static final int MAGIC = 0x4E585231; // NXR1
    private static final int VERSION = 1;
    private static final int MAX_PACKET = 768;

    public static final class Snapshot {
        public final long sequence;
        public final long trackingTimestampMs;
        public final long receivedAtNanos;
        public final boolean headTracked;
        public final int handMask;
        public final float[] head;
        public final float[] joints;
        public final float[] pinch;
        public final float leftX, leftY, rightX, rightY, leftTrigger, rightTrigger;
        public final int buttons;

        private Snapshot(long sequence, long trackingTimestampMs, long receivedAtNanos,
                         boolean headTracked, int handMask, float[] head, float[] joints, float[] pinch,
                         float leftX, float leftY, float rightX, float rightY,
                         float leftTrigger, float rightTrigger, int buttons) {
            this.sequence = sequence;
            this.trackingTimestampMs = trackingTimestampMs;
            this.receivedAtNanos = receivedAtNanos;
            this.headTracked = headTracked;
            this.handMask = handMask;
            this.head = head;
            this.joints = joints;
            this.pinch = pinch;
            this.leftX = leftX;
            this.leftY = leftY;
            this.rightX = rightX;
            this.rightY = rightY;
            this.leftTrigger = leftTrigger;
            this.rightTrigger = rightTrigger;
            this.buttons = buttons;
        }

        public boolean isFresh(long maxAgeMs) {
            return System.nanoTime() - this.receivedAtNanos <= maxAgeMs * 1_000_000L;
        }
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Snapshot latest;
    private volatile long lastSequence = Long.MIN_VALUE;
    private DatagramSocket socket;
    private Thread thread;

    public void start() {
        if (!this.running.compareAndSet(false, true)) return;
        try {
            this.socket = new DatagramSocket(PORT, InetAddress.getByName("127.0.0.1"));
            this.socket.setReceiveBufferSize(4096);
            this.thread = new Thread(this::receiveLoop, "NexaTrackingReceiver");
            this.thread.setDaemon(true);
            this.thread.start();
        } catch (Exception e) {
            this.running.set(false);
            this.socket = null;
        }
    }

    public Snapshot latest() {
        return this.latest;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_PACKET];
        while (this.running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                this.socket.receive(packet);
                Snapshot sample = decode(packet.getData(), packet.getOffset(), packet.getLength());
                if (sample != null && sample.sequence > this.lastSequence) {
                    this.lastSequence = sample.sequence;
                    this.latest = sample;
                }
            } catch (Exception e) {
                if (!this.running.get()) return;
            }
        }
    }

    private static Snapshot decode(byte[] data, int offset, int length) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, offset, length))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            long sequence = in.readLong();
            long trackingTimestampMs = in.readLong();
            boolean headTracked = in.readBoolean();
            int handMask = in.readUnsignedByte();
            float[] head = new float[7];
            float[] joints = new float[126];
            float[] pinch = new float[8];
            for (int i = 0; i < head.length; i++) head[i] = in.readFloat();
            for (int i = 0; i < joints.length; i++) joints[i] = in.readFloat();
            for (int i = 0; i < pinch.length; i++) pinch[i] = in.readFloat();
            float leftX = in.readFloat();
            float leftY = in.readFloat();
            float rightX = in.readFloat();
            float rightY = in.readFloat();
            float leftTrigger = in.readFloat();
            float rightTrigger = in.readFloat();
            int buttons = in.readInt();
            return new Snapshot(sequence, trackingTimestampMs, System.nanoTime(), headTracked, handMask,
                head, joints, pinch, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger, buttons);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void close() {
        this.running.set(false);
        DatagramSocket s = this.socket;
        this.socket = null;
        if (s != null) s.close();
        Thread t = this.thread;
        this.thread = null;
        if (t != null) t.interrupt();
        this.latest = null;
    }
}