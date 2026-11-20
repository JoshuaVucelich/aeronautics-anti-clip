package com.vucelich.sablesafelanding.mixin;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.vucelich.sablesafelanding.ColliderFeedRuntime;
import com.vucelich.sablesafelanding.SableSafeLanding;
import com.vucelich.sablesafelanding.SafeLandingConfig;
import com.vucelich.sablesafelanding.sim.FailureGuards;
import com.vucelich.sablesafelanding.sim.RateLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * World elevator, bearing, piston, and gantry contraptions are uploaded by this mod.
 * Sable still owns contraptions that already live inside a sub-level.
 * <p>
 * Sable 2.0.3's contraption mixin has the default priority of 1000. Mixin 0.8.7 applies a
 * higher priority later. Sable's initializer is an inject on {@code tick} at {@code Map.entrySet},
 * and Mixin strips its unused callback argument while that mixin is still being applied, so a
 * later mixin cannot target {@code sable$contraptionInitialize}. This hook runs at the same
 * invoke, after it returns, which is after Sable has added its kinematic. Ownership is taken
 * only when this hook runs. {@code require = 0} keeps a missing target from crashing startup.
 * <p>
 * Create 6.0.10 {@code AbstractContraptionEntity.tick} has one {@code Map.entrySet()} call,
 * {@code collidingEntities.entrySet()}, and that is the invoke Sable 2.0.3 injects. Ordinal 0
 * pins it. The seat map's {@code entrySet} is in {@code handlePlayerInteraction}, not {@code tick}.
 */
@Mixin(value = AbstractContraptionEntity.class, priority = 1100, remap = false)
public abstract class ContraptionInitializeMixin {
    private static final RateLimiter HOOK_LOGS = new RateLimiter(5_000_000_000L);

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;entrySet()Ljava/util/Set;",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            ),
            require = 0,
            remap = false
    )
    private void sableSafeLanding$afterSableInit(CallbackInfo ci) {
        if (!FailureGuards.run(this::afterSable)) {
            logHookFailure();
        }
    }

    private void afterSable() {
        AbstractContraptionEntity entity = (AbstractContraptionEntity) (Object) this;
        // The client has no collider feed: one field read and out.
        if (entity.level().isClientSide()) {
            return;
        }
        if (!SafeLandingConfig.running()) {
            return;
        }
        ColliderFeedRuntime.noteHook(entity);
        if (ColliderFeedRuntime.isClaimed(entity.getUUID().toString())) {
            return;
        }
        if (ColliderFeedRuntime.shouldOwn(entity)) {
            // Not taken yet: the feed takes it over in its own tick, inside the budget. Until then
            // Sable's own collider stays in place, so the deck is never without a collider.
            ColliderFeedRuntime.offer(entity);
        }
    }

    @Inject(method = "sable$shouldCollide", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void sableSafeLanding$suppressSableCollider(CallbackInfoReturnable<Boolean> ci) {
        if (!FailureGuards.run(() -> suppress(ci))) {
            logHookFailure();
        }
    }

    private void suppress(CallbackInfoReturnable<Boolean> ci) {
        AbstractContraptionEntity entity = (AbstractContraptionEntity) (Object) this;
        if (entity.level().isClientSide() || !SafeLandingConfig.running()) {
            return;
        }
        if (ColliderFeedRuntime.isClaimed(entity.getUUID().toString())) {
            ci.setReturnValue(false);
        }
    }

    private static void logHookFailure() {
        if (!HOOK_LOGS.tryAcquire(System.nanoTime())) {
            return;
        }
        SableSafeLanding.LOGGER.warn("Contraption hook failed; leaving Sable's collider path alone");
    }
}
