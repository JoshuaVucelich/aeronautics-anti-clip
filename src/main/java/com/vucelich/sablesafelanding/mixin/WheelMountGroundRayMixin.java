package com.vucelich.sablesafelanding.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.vucelich.sablesafelanding.WheelContactRuntime;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Offroad's wheel mount (bundled with Create Aeronautics 1.3.x) finds the ground with three
 * {@code Level.clip} rays in {@code computeMaxExtensionToTerrain}. That method feeds both the
 * server suspension force and the client wheel position. This wraps the clip so the ray also
 * lands on a moving Create contraption deck. Pseudo and {@code require = 0}: without Offroad
 * the mixin does nothing.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity", remap = false)
public abstract class WheelMountGroundRayMixin {
    @WrapOperation(
            method = "computeMaxExtensionToTerrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"
            ),
            require = 0,
            remap = false
    )
    private BlockHitResult sableSafeLanding$groundRayHitsDecks(Level level, ClipContext context, Operation<BlockHitResult> original) {
        BlockHitResult base = original.call(level, context);
        return WheelContactRuntime.withDecks(level, context, base, (Object) this);
    }
}
