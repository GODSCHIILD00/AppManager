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

package io.github.muntashirakon.AppManager.apk.installer;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.apk.splitapk.SplitApkChooser;
import io.github.muntashirakon.AppManager.apk.whatsnew.WhatsNewDialogFragment;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.AppPref;
import io.github.muntashirakon.AppManager.utils.StoragePermission;
import io.github.muntashirakon.AppManager.utils.UIUtils;

import static io.github.muntashirakon.AppManager.utils.UIUtils.getDialogTitle;

public class PackageInstallerActivity extends BaseActivity implements WhatsNewDialogFragment.InstallInterface {
    public static final String EXTRA_APK_FILE_KEY = "EXTRA_APK_FILE_KEY";
    public static final String ACTION_PACKAGE_INSTALLED = BuildConfig.APPLICATION_ID + ".action.PACKAGE_INSTALLED";

    private int actionName;
    private FragmentManager fm;
    private AlertDialog progressDialog;
    private int sessionId = -1;
    private PackageInstallerViewModel model;
    private final StoragePermission storagePermission = StoragePermission.init(this);
    private final ActivityResultLauncher<Intent> confirmIntentLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                // User did some interaction and the installer screen is closed now
                Intent broadcastIntent = new Intent(AMPackageInstaller.ACTION_INSTALL_INTERACTION_END);
                broadcastIntent.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, model.getPackageName());
                broadcastIntent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
                getApplicationContext().sendBroadcast(broadcastIntent);
                triggerCancel();
            });
    private final ActivityResultLauncher<Intent> uninstallIntentLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                try {
                    // No need for user handle since it is only applicable for the current user (no-root)
                    getPackageManager().getPackageInfo(model.getPackageName(), 0);
                    // The package is still installed meaning that the app uninstall wasn't successful
                    UIUtils.displayLongToast(R.string.failed_to_install_package_name, model.getAppLabel());
                } catch (PackageManager.NameNotFoundException e) {
                    install();
                }
            });

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        final Intent intent = getIntent();
        if (intent == null) {
            triggerCancel();
            return;
        }
        Log.d("PIA", "On create, intent: " + intent);
        if (ACTION_PACKAGE_INSTALLED.equals(intent.getAction())) {
            onNewIntent(intent);
            return;
        }
        final Uri apkUri = intent.getData();
        int apkFileKey = intent.getIntExtra(EXTRA_APK_FILE_KEY, -1);
        if (apkUri == null && apkFileKey == -1) {
            triggerCancel();
            return;
        }
        model = new ViewModelProvider(this).get(PackageInstallerViewModel.class);
        progressDialog = UIUtils.getProgressDialog(this, getText(R.string.loading));
        fm = getSupportFragmentManager();
        progressDialog.show();
        model.getPackageInfo(apkFileKey, apkUri, intent.getType()).observe(this, newPackageInfo -> {
            progressDialog.dismiss();
            if (newPackageInfo == null) {
                UIUtils.displayLongToast(R.string.failed_to_fetch_package_info);
                triggerCancel();
                return;
            }
            if (model.getInstalledPackageInfo() == null) {
                // App not installed or data not cleared
                actionName = R.string.install;
                if (model.getApkFile().isSplit() || !AppPref.isRootOrAdbEnabled()) {
                    install();
                } else {
                    new MaterialAlertDialogBuilder(this)
                            .setCancelable(false)
                            .setCustomTitle(getDialogTitle(this, model.getAppLabel(), model.getAppIcon(),
                                    model.getVersionWithTrackers()))
                            .setMessage(R.string.install_app_message)
                            .setPositiveButton(R.string.install, (dialog, which) -> install())
                            .setNegativeButton(R.string.cancel, (dialog, which) -> triggerCancel())
                            .show();
                }
            } else {
                // App is installed or the app is uninstalled without clearing data or the app is uninstalled
                // but it's a system app
                long installedVersionCode = PackageInfoCompat.getLongVersionCode(model.getInstalledPackageInfo());
                long thisVersionCode = PackageInfoCompat.getLongVersionCode(newPackageInfo);
                if (installedVersionCode < thisVersionCode) {
                    // Needs update
                    actionName = R.string.update;
                    displayWhatsNewDialog();
                } else if (installedVersionCode == thisVersionCode) {
                    // Issue reinstall
                    actionName = R.string.reinstall;
                    displayWhatsNewDialog();
                } else {
                    // Downgrade
                    actionName = R.string.downgrade;
                    if (AppPref.isRootOrAdbEnabled()) {
                        displayWhatsNewDialog();
                    } else {
                        UIUtils.displayLongToast(R.string.downgrade_not_possible);
                        triggerCancel();
                    }
                }
            }
        });
    }

    @UiThread
    private void displayWhatsNewDialog() {
        if (!AppPref.getBoolean(AppPref.PrefKey.PREF_INSTALLER_DISPLAY_CHANGES_BOOL)) {
            if (!AppPref.isRootOrAdbEnabled()) {
                triggerInstall();
                return;
            }
            new MaterialAlertDialogBuilder(this)
                    .setCancelable(false)
                    .setCustomTitle(getDialogTitle(this, model.getAppLabel(), model.getAppIcon(),
                            model.getVersionWithTrackers()))
                    .setMessage(R.string.install_app_message)
                    .setPositiveButton(actionName, (dialog, which) -> triggerInstall())
                    .setNegativeButton(R.string.cancel, (dialog, which) -> triggerCancel())
                    .show();
            return;
        }
        Bundle args = new Bundle();
        args.putParcelable(WhatsNewDialogFragment.ARG_NEW_PKG_INFO, model.getNewPackageInfo());
        args.putParcelable(WhatsNewDialogFragment.ARG_OLD_PKG_INFO, model.getInstalledPackageInfo());
        args.putString(WhatsNewDialogFragment.ARG_INSTALL_NAME, getString(actionName));
        WhatsNewDialogFragment dialogFragment = new WhatsNewDialogFragment();
        dialogFragment.setCancelable(false);
        dialogFragment.setArguments(args);
        dialogFragment.setOnTriggerInstall(this);
        dialogFragment.show(getSupportFragmentManager(), WhatsNewDialogFragment.TAG);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.clear();
        super.onSaveInstanceState(outState);
    }

    @UiThread
    private void install() {
        if (model.getApkFile().hasObb() && !AppPref.isRootOrAdbEnabled()) {
            // Need to request permissions if not given
            storagePermission.request(granted -> {
                if (granted) launchInstaller();
            });
        } else launchInstaller();
    }

    @UiThread
    private void launchInstaller() {
        if (model.getApkFile().isSplit()) {
            SplitApkChooser splitApkChooser = new SplitApkChooser();
            Bundle args = new Bundle();
            args.putInt(SplitApkChooser.EXTRA_APK_FILE_KEY, model.getApkFileKey());
            args.putString(SplitApkChooser.EXTRA_ACTION_NAME, getString(actionName));
            args.putParcelable(SplitApkChooser.EXTRA_APP_INFO, model.getNewPackageInfo().applicationInfo);
            args.putString(SplitApkChooser.EXTRA_VERSION_INFO, model.getVersionWithTrackers());
            splitApkChooser.setArguments(args);
            splitApkChooser.setCancelable(false);
            splitApkChooser.setOnTriggerInstall(new SplitApkChooser.InstallInterface() {
                @Override
                public void triggerInstall() {
                    launchInstallService();
                }

                @Override
                public void triggerCancel() {
                    PackageInstallerActivity.this.triggerCancel();
                }
            });
            splitApkChooser.show(fm, SplitApkChooser.TAG);
        } else {
            launchInstallService();
        }
    }

    private void launchInstallService() {
        // Select user
        if (AppPref.isRootOrAdbEnabled() && AppPref.getBoolean(AppPref.PrefKey.PREF_INSTALLER_DISPLAY_USERS_BOOL)) {
            List<UserInfo> users = model.getUsers();
            if (users != null && users.size() > 1) {
                String[] userNames = new String[users.size() + 1];
                int[] userHandles = new int[users.size() + 1];
                userNames[0] = getString(R.string.backup_all_users);
                userHandles[0] = Users.USER_ALL;
                int i = 1;
                for (UserInfo info : users) {
                    userNames[i] = info.name == null ? String.valueOf(info.id) : info.name;
                    userHandles[i] = info.id;
                    ++i;
                }
                AtomicInteger userHandle = new AtomicInteger(Users.USER_ALL);
                new MaterialAlertDialogBuilder(this)
                        .setCancelable(false)
                        .setTitle(R.string.select_user)
                        .setSingleChoiceItems(userNames, 0, (dialog, which) ->
                                userHandle.set(userHandles[which]))
                        .setPositiveButton(R.string.ok, (dialog, which) -> doLaunchInstallerService(userHandle.get()))
                        .setNegativeButton(R.string.cancel, (dialog, which) -> triggerCancel())
                        .show();
                return;
            }
        }
        doLaunchInstallerService(Users.getCurrentUserHandle());
    }

    private void doLaunchInstallerService(int userHandle) {
        Intent intent = new Intent(this, PackageInstallerService.class);
        intent.putExtra(PackageInstallerService.EXTRA_APK_FILE_KEY, model.getApkFileKey());
        intent.putExtra(PackageInstallerService.EXTRA_APP_LABEL, model.getAppLabel());
        intent.putExtra(PackageInstallerService.EXTRA_USER_ID, userHandle);
        intent.putExtra(PackageInstallerService.EXTRA_CLOSE_APK_FILE, model.isCloseApkFile());
        ContextCompat.startForegroundService(this, intent);
        model.setCloseApkFile(false);
        triggerCancel();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d("PIA", "New intent called: " + intent.toString());
        // Check for action first
        if (ACTION_PACKAGE_INSTALLED.equals(intent.getAction())) {
            sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            try {
                Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
                if (confirmIntent == null) throw new Exception("Empty confirmation intent.");
                if (!model.getPackageName().equals(packageName)) {
                    throw new Exception("Current package name doesn't match with the package name sent to confirm intent");
                }
                Log.d("PIA", "Requesting user confirmation for package " + model.getPackageName());
                confirmIntentLauncher.launch(confirmIntent);
            } catch (Exception e) {
                e.printStackTrace();
                AMPackageInstaller.sendCompletedBroadcast(model.getPackageName(), AMPackageInstaller.STATUS_FAILURE_INCOMPATIBLE_ROM, sessionId);
                triggerCancel();
            }
        }
    }

    @UiThread
    @Override
    public void triggerInstall() {
        if (model.isSignatureDifferent()) {
            // Signature is different, offer to uninstall and then install apk
            // only if the app is not a system app
            // TODO(8/10/20): Handle apps uninstalled with DONT_DELETE_DATA flag
            ApplicationInfo info = model.getInstalledPackageInfo().applicationInfo;  // Installed package info is never null here.
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                UIUtils.displayLongToast(R.string.app_signing_signature_mismatch_for_system_apps);
                return;
            }
            SpannableStringBuilder builder = new SpannableStringBuilder()
                    .append(getString(R.string.do_you_want_to_uninstall_and_install)).append(" ")
                    .append(UIUtils.getItalicString(getString(R.string.app_data_will_be_lost)))
                    .append("\n\n");
            int start = builder.length();
            builder.append(getText(R.string.app_signing_install_without_data_loss));
            builder.setSpan(new RelativeSizeSpan(0.8f), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            new MaterialAlertDialogBuilder(PackageInstallerActivity.this)
                    .setCustomTitle(getDialogTitle(PackageInstallerActivity.this, model.getAppLabel(),
                            model.getAppIcon(), model.getVersionWithTrackers()))
                    .setMessage(builder)
                    .setPositiveButton(R.string.yes, (dialog, which) -> {
                        // Uninstall and then install again
                        if (AppPref.isRootOrAdbEnabled()) {
                            // User must be all
                            try {
                                PackageInstallerCompat.uninstall(model.getPackageName(),
                                        Users.USER_ALL, false);
                                install();
                            } catch (Exception e) {
                                e.printStackTrace();
                                UIUtils.displayLongToast(R.string.failed_to_uninstall, model.getAppLabel());
                            }
                        } else {
                            // Uninstall using service, not guaranteed to work
                            // since it only uninstalls for the current user
                            Intent intent = new Intent(Intent.ACTION_DELETE);
                            intent.setData(Uri.parse("package:" + model.getPackageName()));
                            uninstallIntentLauncher.launch(intent);
                        }
                    })
                    .setNegativeButton(R.string.no, (dialog, which) -> triggerCancel())
                    .setNeutralButton(R.string.only_install, (dialog, which) -> install())
                    .show();
        } else {
            // Signature is either matched or the app isn't installed
            install();
        }
    }

    @Override
    public void triggerCancel() {
        finish();
    }
}

