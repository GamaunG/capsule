// ICapsuleService.aidl
package capsule.profile.services;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;

import capsule.profile.services.IAppInstallCallback;
import capsule.profile.services.IGetAppsCallback;
import capsule.profile.services.ILoadIconCallback;
import capsule.profile.services.IStartActivityProxy;
import capsule.profile.util.ApplicationInfoWrapper;
import capsule.profile.util.UriForwardProxy;

interface ICapsuleService {
    void ping();
    void stopCapsuleService(boolean kill);
    void getApps(IGetAppsCallback callback, boolean showAll);
    void loadIcon(in ApplicationInfoWrapper info, ILoadIconCallback callback);
    void installApp(in ApplicationInfoWrapper app, IAppInstallCallback callback);
    void installApk(in UriForwardProxy uri, IAppInstallCallback callback);
    void uninstallApp(in ApplicationInfoWrapper app, IAppInstallCallback callback);
    void freezeApp(in ApplicationInfoWrapper app);
    void unfreezeApp(in ApplicationInfoWrapper app);
    boolean hasUsageStatsPermission();
    boolean hasSystemAlertPermission();
    boolean hasAllFileAccessPermission();
    List<String> getCrossProfileWidgetProviders();
    boolean hasWidgetProviders(String packageName);
    boolean setCrossProfileWidgetProviderEnabled(String pkgName, boolean enabled);
    void setStartActivityProxy(in IStartActivityProxy proxy);
    List<String> getCrossProfilePackages();
    void setCrossProfilePackages(in List<String> packages);
    Bundle getManagedApplicationRestrictions(String packageName);
    void setManagedApplicationRestrictions(String packageName, in Bundle restrictions);
    boolean installCaCert(in byte[] cert);
    List<Bundle> getInstalledCaCerts();
    boolean uninstallCaCert(in byte[] cert);
}
