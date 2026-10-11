package com.vucelich.sablesafelanding.mixin;

import com.vucelich.sablesafelanding.VoidRescueRuntime;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs after the bounding box is refreshed and before the local bounds are tested against
 * {@code sub_level_remove_min/max}. A rescue rewrites the pose and refreshes the box so the
 * following removal check sees the restored range.
 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class ServerSubLevelMixin {
    /**
     * Sable 2.0.3's {@code ServerSubLevel.tick} invokes {@code ServerSubLevel.updateBoundingBox()V}
     * (the method is declared on {@code SubLevel}, but the constant-pool owner is the subclass).
     * The declared-owner inject is a fallback. {@code require = 0} so a mismatch warns instead of
     * crashing startup.
     */
    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/ServerSubLevel;updateBoundingBox()V",
                    shift = At.Shift.AFTER
            ),
            require = 0,
            remap = false
    )
    private void sableSafeLanding$rescueBeforeRemoval(CallbackInfo ci) {
        rescue(ci);
    }

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/SubLevel;updateBoundingBox()V",
                    shift = At.Shift.AFTER
            ),
            require = 0,
            remap = false
    )
    private void sableSafeLanding$rescueBeforeRemovalDeclared(CallbackInfo ci) {
        rescue(ci);
    }

    private void rescue(CallbackInfo ci) {
        VoidRescueRuntime.noteRemovalHook();
        ServerSubLevel subLevel = (ServerSubLevel) (Object) this;
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(subLevel.getLevel());
        var pipeline = system == null ? null : system.getPipeline();
        if (SubLevelContainer.getContainer(subLevel.getLevel()) instanceof ServerSubLevelContainer) {
            VoidRescueRuntime.consider(subLevel, pipeline);
        }
    }
}
