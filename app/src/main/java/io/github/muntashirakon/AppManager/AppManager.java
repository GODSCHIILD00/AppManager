/*
 * Copyright (C) 2020 Muntashir Al-Islam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.muntashirakon.AppManager;

import android.app.Application;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.sun.security.provider.JavaKeyStoreProvider;

import androidx.annotation.NonNull;
import androidx.room.Room;

import com.topjohnwu.superuser.Shell;
import com.yariksoffice.lingver.Lingver;

import java.security.Security;

import io.github.muntashirakon.AppManager.db.AMDatabase;
import io.github.muntashirakon.AppManager.ipc.ProxyBinder;
import io.github.muntashirakon.AppManager.utils.LangUtils;

public class AppManager extends Application {
    private static AppManager instance;
    private static AMDatabase db;
    private static boolean isAuthenticated = false;

    static {
        Shell.enableVerboseLogging = BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10));
    }

    @NonNull
    public static AppManager getInstance() {
        return instance;
    }

    @NonNull
    public static Context getContext() {
        return instance.getBaseContext();
    }

    public static IPackageManager getIPackageManager() {
        return IPackageManager.Stub.asInterface(ProxyBinder.getService("package"));
    }

    @NonNull
    public static synchronized AMDatabase getDb() {
        if (db == null) {
            db = Room.databaseBuilder(getContext(), AMDatabase.class, "am")
                    .addMigrations(AMDatabase.MIGRATION_1_2, AMDatabase.MIGRATION_2_3, AMDatabase.MIGRATION_3_4)
                    .build();
        }
        return db;
    }

    public static boolean isAuthenticated() {
        return isAuthenticated;
    }

    public static void setIsAuthenticated(boolean isAuthenticated) {
        AppManager.isAuthenticated = isAuthenticated;
    }

    @Override
    public void onCreate() {
        instance = this;
        super.onCreate();
        Lingver.init(instance, LangUtils.getLocaleByLanguage(instance));
        Security.addProvider(new JavaKeyStoreProvider());
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }
}
