package com.vucelich.sablesafelanding.mixin;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.vucelich.sablesafelanding.ColliderFeedRuntime;
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
 * higher priority later, so 1100 runs after {@code sable$contraptionInitialize} exists.
 * {@code require = 0} keeps a missing target from crashing startup. Ownership is taken only
 * after this hook actually runs.
 */
@Mixin(value = AbstractContraptionEntity.class, priority = 1100, remap = false)
public abstract class ContraptionInitializeMixin {
    /**
     * Sable 2.0.3's handler never reads its {@code CallbackInfo}, so Mixin strips that argument
     * and the method on the class is {@code sable$contraptionInitialize()V}. The descriptor with
     * {@code CallbackInfo} is kept as a second optional target for a build that still has it.
     */
    @Inject(method = "sable$contraptionInitialize()V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void sableSafeLanding$skipOwnedInit(CallbackInfo ci) {
        AbstractContraptionEntity entity = (AbstractContraptionEntity) (Object) this;
        ColliderFeedRuntime.noteHook(entity);
        if (ColliderFeedRuntime.shouldOwn(entity)) {
            ColliderFeedRuntime.claim(entity);
            ci.cancel();
        }
    }

    @Inject(method = "sable$contraptionInitialize(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void sableSafeLanding$skipOwnedInitWithCallback(CallbackInfo ci) {
        sableSafeLanding$skipOwnedInit(ci);
    }

    @Inject(method = "sable$shouldCollide", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void sableSafeLanding$suppressSableCollider(CallbackInfoReturnable<Boolean> ci) {
        if (ColliderFeedRuntime.shouldOwn((AbstractContraptionEntity) (Object) this)) {
            ci.setReturnValue(false);
        }
    }
}
