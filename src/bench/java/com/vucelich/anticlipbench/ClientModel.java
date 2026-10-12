package com.vucelich.anticlipbench;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.util.Mth;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Same-tick model of Sable 2.0.3 {@code SubLevelSnapshotInterpolator} and
 * {@code ClientSableInterpolationState}. The delay is the client default, 1.5 ticks.
 * Dead reckoning runs for at most one tick, and only while the server has not sent STOP.
 * The snapshot is the pose at tracking time. Desync is against the pose after the server tick.
 */
final class ClientModel {
    static final double DELAY = 1.5;
    /** Sable 2.0.3 movement tolerance: 0.015/16 blocks and 0.015 degrees. */
    private static final double POSITION_TOLERANCE = 0.015 / 16.0;
    private static final double ANGLE_TOLERANCE = Math.toRadians(0.015);

    private final List<Snap> buffer = new ArrayList<>();
    private final List<Vector3d> serverPositions = new ArrayList<>();
    private final List<Vector3d> clientPositions = new ArrayList<>();
    private final List<Double> desync = new ArrayList<>();
    private final Pose3d running = new Pose3d();
    private Pose3d lastSent;
    private boolean stopped = true;
    private boolean hasRunning;
    private int movePackets;
    private int stopPackets;
    private int netTick;

    void reset() {
        buffer.clear();
        serverPositions.clear();
        clientPositions.clear();
        desync.clear();
        lastSent = null;
        stopped = true;
        hasRunning = false;
        movePackets = 0;
        stopPackets = 0;
        netTick = 0;
    }

    /**
     * @param networked pose that tracking would have sent, before a post-tick teleport
     * @param server    pose after the server tick
     */
    void observe(Pose3dc networked, Pose3dc server) {
        int tick = netTick++;
        Pose3d sent = new Pose3d(networked);
        if (lastSent == null || !sent.withinTolerance(lastSent, POSITION_TOLERANCE, ANGLE_TOLERANCE)) {
            buffer.add(new Snap(tick, sent));
            lastSent = new Pose3d(sent);
            stopped = false;
            movePackets++;
        } else if (!stopped) {
            stopped = true;
            stopPackets++;
        }
        double pointer = tick - DELAY;
        trim(pointer);
        // Sable keeps the running pose when the buffer has been trimmed empty after STOP.
        // A fresh Pose3d would sit at the origin and look like a 50-block rubber band.
        if (!buffer.isEmpty()) {
            sample(pointer, running);
            hasRunning = true;
        }
        Vector3d serverPos = new Vector3d(server.position());
        Vector3d clientPos = new Vector3d(hasRunning ? running.position() : serverPos);
        serverPositions.add(serverPos);
        clientPositions.add(clientPos);
        desync.add(serverPos.distance(clientPos));
    }

    int movePackets() {
        return movePackets;
    }

    int stopPackets() {
        return stopPackets;
    }

    List<Double> desync() {
        return desync;
    }

    double clientJitterRms() {
        return jitter(clientPositions);
    }

    double serverJitterRms() {
        return jitter(serverPositions);
    }

    double errorJitterRms() {
        List<Vector3d> error = new ArrayList<>(serverPositions.size());
        for (int i = 0; i < serverPositions.size(); i++) {
            error.add(new Vector3d(clientPositions.get(i)).sub(serverPositions.get(i)));
        }
        return jitter(error);
    }

    private void trim(double pointer) {
        int start = (int) (pointer - 6);
        while (!buffer.isEmpty() && buffer.get(0).tick < start) {
            buffer.remove(0);
        }
    }

    private void sample(double pointer, Pose3d dest) {
        int beforeIndex = -1;
        Snap before = null;
        Snap after = null;
        for (int i = 0; i < buffer.size(); i++) {
            Snap snapshot = buffer.get(i);
            if (snapshot.tick == pointer) {
                dest.set(snapshot.pose);
                return;
            }
            if (snapshot.tick < pointer) {
                beforeIndex = i;
                before = snapshot;
            } else if (snapshot.tick > pointer) {
                after = snapshot;
                break;
            }
        }
        if (before == null || after == null) {
            if (before != null) {
                dest.set(before.pose);
                int earlier = beforeIndex - 1;
                if (earlier >= 0 && !stopped) {
                    Snap beforeBefore = buffer.get(earlier);
                    double deadReckoningTicks = Mth.clamp(pointer - before.tick, 0.0, 1.0);
                    double span = before.tick - beforeBefore.tick;
                    if (span != 0.0) {
                        dest.set(beforeBefore.pose).lerp(before.pose, 1.0 + deadReckoningTicks / span);
                    }
                }
            } else if (after != null) {
                dest.set(after.pose);
            }
        } else {
            double factor = (pointer - before.tick) / (double) (after.tick - before.tick);
            before.pose.lerp(after.pose, factor, dest);
        }
    }

    private static double jitter(List<Vector3d> points) {
        if (points.size() < 3) {
            return 0.0;
        }
        double sum = 0.0;
        int count = 0;
        for (int i = 2; i < points.size(); i++) {
            Vector3d second = new Vector3d(points.get(i))
                    .sub(new Vector3d(points.get(i - 1)).mul(2.0))
                    .add(points.get(i - 2));
            sum += second.lengthSquared();
            count++;
        }
        return Math.sqrt(sum / count);
    }

    private record Snap(int tick, Pose3d pose) {
    }
}
