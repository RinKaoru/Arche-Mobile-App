package app.arche.nas;

import android.content.Context;
import android.provider.MediaStore;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Planification : toutes les 15 min + dès qu'une nouvelle photo apparaît + « Sauvegarder maintenant ». */
public final class Scheduler {
    public static void schedule(Context c) {
        WorkManager wm = WorkManager.getInstance(c);
        Prefs p = new Prefs(c);
        if (!p.paired()) { wm.cancelAllWork(); return; }
        Constraints net = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        wm.enqueueUniquePeriodicWork("arche-periodic", ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(BackupWorker.class, 15, TimeUnit.MINUTES).setConstraints(net)
                        .setInputData(new Data.Builder().putString("kind", "periodic").build()).build());
        armTrigger(c, true);
    }

    /** Travail déclenché par l'arrivée d'une nouvelle photo ou vidéo. */
    public static void armTrigger(Context c, boolean replace) {
        Prefs p = new Prefs(c);
        if (!p.paired() || !p.enabled()) { WorkManager.getInstance(c).cancelUniqueWork("arche-trigger"); return; }
        Constraints.Builder cb = new Constraints.Builder()
                .setRequiredNetworkType(p.wifiOnly() ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .setRequiresCharging(p.chargingOnly())
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(30, TimeUnit.SECONDS);
        WorkManager.getInstance(c).enqueueUniqueWork("arche-trigger",
                replace ? ExistingWorkPolicy.REPLACE : ExistingWorkPolicy.APPEND_OR_REPLACE,
                new OneTimeWorkRequest.Builder(BackupWorker.class).setConstraints(cb.build())
                        .setInputData(new Data.Builder().putString("kind", "trigger").build()).build());
    }

    public static void now(Context c) {
        WorkManager.getInstance(c).enqueueUniqueWork("arche-now", ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(BackupWorker.class)
                        .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .setInputData(new Data.Builder().putString("kind", "now").build()).build());
    }

    /** Suite d'une sauvegarde interrompue (limite de temps d'Android) : on reprend tout de suite. */
    public static void cont(Context c) {
        WorkManager.getInstance(c).enqueueUniqueWork("arche-cont", ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(BackupWorker.class)
                        .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setInitialDelay(5, TimeUnit.SECONDS)
                        .setInputData(new Data.Builder().putString("kind", "cont").build()).build());
    }
}
