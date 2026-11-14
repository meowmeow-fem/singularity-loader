package net.singularitymc.loader.patches;

import net.minecraft.CrashReport;
import net.singularitymc.loader.util.Log;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Logs the cause of a crash report right away (3.5.7 in 64-bit mode, every mode from 4.1). Building the report
 * collects system information, which on some Windows machines (broken performance counters) takes minutes; meanwhile
 * a failed chunk keeps the server waiting with nothing in the log, until the watchdog stops it.
 */
@Mixin(CrashReport.class)
abstract class CrashReportCauseMixin {
    private static final Log LOG = Log.get("Singularity");

    @Inject(method = "forThrowable", at = @At("HEAD"))
    private static void Singularity$logCause(Throwable t, String title, CallbackInfoReturnable<CrashReport> cir) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        StringBuilder where = new StringBuilder();
        StackTraceElement[] st = root.getStackTrace();
        for (int i = 0; i < Math.min(12, st.length); i++) {
            where.append("\n    at ").append(st[i]);
        }
        LOG.error("Crash report \"{}\": {}{}", title, root, where);
    }
}
