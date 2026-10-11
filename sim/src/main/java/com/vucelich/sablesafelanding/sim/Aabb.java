package com.vucelich.sablesafelanding.sim;

import java.util.List;

/**
 * Axis-aligned box in block coordinates. Max edges are exclusive in the same sense as a
 * Minecraft bounding box: a full block at the origin is {@code [0,1)} on each axis.
 */
public record Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public Aabb {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("inverted aabb");
        }
    }

    public Vec3 center() {
        return new Vec3((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5);
    }

    public Aabb translate(Vec3 delta) {
        return new Aabb(minX + delta.x(), minY + delta.y(), minZ + delta.z(),
                maxX + delta.x(), maxY + delta.y(), maxZ + delta.z());
    }

    public boolean intersects(Aabb other) {
        return minX < other.maxX && maxX > other.minX
                && minY < other.maxY && maxY > other.minY
                && minZ < other.maxZ && maxZ > other.minZ;
    }

    public boolean overlapsHorizontally(Aabb other) {
        return minX < other.maxX && maxX > other.minX
                && minZ < other.maxZ && maxZ > other.minZ;
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
    }

    public boolean finite() {
        return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ);
    }

    public static Aabb enclosing(List<Vec3> points) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("no points");
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (Vec3 point : points) {
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
            maxZ = Math.max(maxZ, point.z());
        }
        return new Aabb(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public Aabb expand(double amount) {
        return new Aabb(minX - amount, minY - amount, minZ - amount,
                maxX + amount, maxY + amount, maxZ + amount);
    }
}
