package com.vucelich.sablesafelanding;

import com.vucelich.sablesafelanding.sim.Aabb;
import com.vucelich.sablesafelanding.sim.PoseSnapshot;
import com.vucelich.sablesafelanding.sim.Rot;
import com.vucelich.sablesafelanding.sim.Vec3;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Last safe poses for this dimension. A pose is only reused while it is still recent.
 */
public final class SafePoseSavedData extends SavedData {
    private static final String FILE_ID = "sable_safe_landing_poses";
    private final Map<String, PoseSnapshot> poses = new HashMap<>();

    public static SafePoseSavedData get(ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(
                new Factory<>(SafePoseSavedData::new, SafePoseSavedData::load, DataFixTypes.LEVEL),
                FILE_ID
        );
    }

    private static SafePoseSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        SafePoseSavedData data = new SafePoseSavedData();
        ListTag list = tag.getList("poses", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String id = entry.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            data.poses.put(id, new PoseSnapshot(
                    new Vec3(entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z")),
                    new Aabb(
                            entry.getDouble("minX"), entry.getDouble("minY"), entry.getDouble("minZ"),
                            entry.getDouble("maxX"), entry.getDouble("maxY"), entry.getDouble("maxZ")
                    ),
                    new Rot(entry.getDouble("qx"), entry.getDouble("qy"), entry.getDouble("qz"), entry.getDouble("qw")),
                    entry.getLong("time")
            ));
        }
        return data;
    }

    public PoseSnapshot recall(String id) {
        return poses.get(id);
    }

    public void remember(String id, PoseSnapshot snapshot) {
        PoseSnapshot previous = poses.get(id);
        if (previous != null
                && previous.position().equals(snapshot.position())
                && previous.bounds().equals(snapshot.bounds())
                && previous.orientation().equals(snapshot.orientation())
                && snapshot.time() - previous.time() < 100L) {
            return;
        }
        poses.put(id, snapshot);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Map.Entry<String, PoseSnapshot> entry : poses.entrySet()) {
            PoseSnapshot pose = entry.getValue();
            CompoundTag item = new CompoundTag();
            item.putString("id", entry.getKey());
            item.putDouble("x", pose.position().x());
            item.putDouble("y", pose.position().y());
            item.putDouble("z", pose.position().z());
            item.putDouble("minX", pose.bounds().minX());
            item.putDouble("minY", pose.bounds().minY());
            item.putDouble("minZ", pose.bounds().minZ());
            item.putDouble("maxX", pose.bounds().maxX());
            item.putDouble("maxY", pose.bounds().maxY());
            item.putDouble("maxZ", pose.bounds().maxZ());
            item.putDouble("qx", pose.orientation().x());
            item.putDouble("qy", pose.orientation().y());
            item.putDouble("qz", pose.orientation().z());
            item.putDouble("qw", pose.orientation().w());
            item.putLong("time", pose.time());
            list.add(item);
        }
        tag.put("poses", list);
        return tag;
    }
}
