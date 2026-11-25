package app.arche.nas;

import android.app.Application;

public class ArcheApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Notifs.channels(this);
        Scheduler.schedule(this);
    }
}
