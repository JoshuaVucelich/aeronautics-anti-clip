package com.vucelich.sablesafelanding.sim;

import java.util.List;

/**
 * Minecraft-free world used to reproduce elevator clips and void losses.
 * M0 only integrates gravity. Later milestones run {@code AntiClipStep} on the same bodies.
 */
public final class HeadlessLandingHarness {
    public static final double TICK_SECONDS = 1.0 / 20.0;

    private HeadlessLandingHarness() {
    }

    public record DropReport(BodyState start, BodyState end, int ticks, boolean minecraftLoaded) {
    }

    /**
     * Releases a car-sized body at y=80 with protection off.
     */
    public static DropReport dropWithoutProtection(AntiClipConfig config, double seconds) {
        if (seconds < 0.0) {
            throw new IllegalArgumentException("seconds must be non-negative");
        }
        BodyState body = new BodyState(
                "dropped-car",
                new Vec3(0.0, 80.0, 0.0),
                Vec3.ZERO,
                new Aabb(-0.8, 80.0, -1.2, 0.8, 81.4, 1.2),
                false
        );
        BodyState start = body;
        int ticks = (int) Math.round(seconds / TICK_SECONDS);
        for (int i = 0; i < ticks; i++) {
            body = integrateGravity(body, config.gravity(), TICK_SECONDS);
        }
        return new DropReport(start, body, ticks, minecraftPresent());
    }

    /**
     * Drops the same car from y=80 with void rescue on, until it is restored or time runs out.
     * The first ticks are still inside the world, so the harness can remember a safe pose.
     */
    public static DropReport rescueDroppedBody(AntiClipConfig config, RescueBounds world, double maxSeconds) {
        if (maxSeconds < 0.0) {
            throw new IllegalArgumentException("seconds must be non-negative");
        }
        AntiClipStep step = new AntiClipStep();
        BodyState body = new BodyState(
                "dropped-car",
                new Vec3(0.0, 80.0, 0.0),
                Vec3.ZERO,
                new Aabb(-0.8, 80.0, -1.2, 0.8, 81.4, 1.2),
                false
        );
        BodyState start = body;
        int maxTicks = (int) Math.round(maxSeconds / TICK_SECONDS);
        int ticks = 0;
        while (ticks < maxTicks) {
            body = step.step(List.of(body), List.of(), world, config, TICK_SECONDS).get(0);
            ticks++;
            if (body.rescued()) {
                break;
            }
            body = integrateGravity(body, config.gravity(), TICK_SECONDS);
        }
        return new DropReport(start, body, ticks, minecraftPresent());
    }

    public static BodyState integrateGravity(BodyState body, double gravity, double dt) {
        Vec3 velocity = body.velocity().add(new Vec3(0.0, gravity * dt, 0.0));
        Vec3 delta = velocity.mul(dt);
        return body.moved(delta, velocity);
    }

    private static boolean minecraftPresent() {
        try {
            Class.forName("net.minecraft.server.MinecraftServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
