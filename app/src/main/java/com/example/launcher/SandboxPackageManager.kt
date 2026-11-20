package com.example.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ChangedPackages
import android.content.pm.FeatureInfo
import android.content.pm.InstallSourceInfo
import android.content.pm.InstrumentationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.SharedLibraryInfo
import android.content.pm.VersionedPackage
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import androidx.annotation.RequiresApi
import com.example.model.InstalledMinecraftPackage

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class SandboxPackageManager(
    private val basePm: PackageManager,
    private val targetPkg: InstalledMinecraftPackage,
    private val patchedAppInfo: ApplicationInfo,
    private val hostResources: Resources,
    private val activityMetaData: Bundle,
    private val hostedActivityClassName: String
) : PackageManager() {

    private fun parseArchivePackageInfo(flags: Int): PackageInfo? {
        if (targetPkg.sourceDir.isBlank()) return null
        // Always include signatures/permissions/activities/meta-data alongside caller flags
        val combinedFlags = (flags or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_META_DATA or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_GIDS or
            PackageManager.GET_SIGNATURES).let { f ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                f or PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                f
            }
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                basePm.getPackageArchiveInfo(
                    targetPkg.sourceDir,
                    PackageManager.PackageInfoFlags.of(combinedFlags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                basePm.getPackageArchiveInfo(targetPkg.sourceDir, combinedFlags)
            }
        }.getOrNull()
    }

    private fun buildSyntheticPackageInfo(flags: Int = 0): PackageInfo {
        val archive = parseArchivePackageInfo(flags)
        val info = archive ?: PackageInfo()
        info.packageName = targetPkg.packageName
        if (info.versionName.isNullOrBlank()) {
            info.versionName = targetPkg.versionName
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info.longVersionCode <= 0L) {
                info.longVersionCode = targetPkg.versionCode
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.versionCode <= 0) {
                info.versionCode = targetPkg.versionCode.toInt()
            }
        }
        info.applicationInfo = patchedAppInfo

        if (targetPkg.splitNames.isNotEmpty() && info.splitNames.isNullOrEmpty()) {
            info.splitNames = targetPkg.splitNames.toTypedArray()
        }

        val archiveActivities = info.activities?.map { act ->
            ActivityInfo(act).apply {
                packageName = targetPkg.packageName
                applicationInfo = patchedAppInfo
                val mergedMeta = Bundle(act.metaData ?: Bundle())
                mergedMeta.putAll(activityMetaData)
                metaData = mergedMeta
            }
        }?.toTypedArray()

        info.activities = if (!archiveActivities.isNullOrEmpty()) {
            archiveActivities
        } else {
            arrayOf(buildSyntheticActivityInfo(ComponentName(targetPkg.packageName, hostedActivityClassName)))
        }

        val perms = info.requestedPermissions
        if (!perms.isNullOrEmpty() && (info.requestedPermissionsFlags == null || info.requestedPermissionsFlags!!.size != perms.size)) {
            info.requestedPermissionsFlags = IntArray(perms.size) { PackageInfo.REQUESTED_PERMISSION_GRANTED }
        }
        if (info.gids == null || info.gids!!.isEmpty()) {
            info.gids = intArrayOf(3003)
        }
        return info
    }

    private fun buildSyntheticActivityInfo(component: ComponentName): ActivityInfo {
        return ActivityInfo().apply {
            packageName = component.packageName
            name = component.className
            applicationInfo = patchedAppInfo
            metaData = Bundle(activityMetaData)
            exported = true
            theme = patchedAppInfo.theme
        }
    }

    private fun matchesTarget(pkgName: String?): Boolean {
        return pkgName == targetPkg.packageName
    }

    override fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
        if (matchesTarget(packageName)) {
            if (targetPkg.isVaultApk) {
                return buildSyntheticPackageInfo(flags)
            }
            return runCatching { basePm.getPackageInfo(packageName, flags) }
                .getOrElse { buildSyntheticPackageInfo(flags) }
        }
        return basePm.getPackageInfo(packageName, flags)
    }

    override fun getPackageInfo(versionedPackage: VersionedPackage, flags: Int): PackageInfo {
        if (matchesTarget(versionedPackage.packageName)) {
            if (targetPkg.isVaultApk) {
                return buildSyntheticPackageInfo(flags)
            }
            return runCatching { basePm.getPackageInfo(versionedPackage, flags) }
                .getOrElse { buildSyntheticPackageInfo(flags) }
        }
        return basePm.getPackageInfo(versionedPackage, flags)
    }

    override fun currentToCanonicalPackageNames(packageNames: Array<out String>): Array<String> =
        basePm.currentToCanonicalPackageNames(packageNames)

    override fun canonicalToCurrentPackageNames(packageNames: Array<out String>): Array<String> =
        basePm.canonicalToCurrentPackageNames(packageNames)

    override fun getLaunchIntentForPackage(packageName: String): Intent? =
        basePm.getLaunchIntentForPackage(packageName)

    override fun getLeanbackLaunchIntentForPackage(packageName: String): Intent? =
        basePm.getLeanbackLaunchIntentForPackage(packageName)

    override fun getPackageGids(packageName: String): IntArray {
        if (matchesTarget(packageName) && targetPkg.isVaultApk) {
            return buildSyntheticPackageInfo(PackageManager.GET_GIDS).gids ?: intArrayOf(3003)
        }
        return basePm.getPackageGids(packageName)
    }

    override fun getPackageGids(packageName: String, flags: Int): IntArray {
        if (matchesTarget(packageName) && targetPkg.isVaultApk) {
            return buildSyntheticPackageInfo(flags or PackageManager.GET_GIDS).gids ?: intArrayOf(3003)
        }
        return basePm.getPackageGids(packageName, flags)
    }

    override fun getPackageUid(packageName: String, flags: Int): Int {
        if (matchesTarget(packageName)) {
            if (targetPkg.isVaultApk) return patchedAppInfo.uid
            return runCatching { basePm.getPackageUid(packageName, flags) }
                .getOrDefault(patchedAppInfo.uid)
        }
        return basePm.getPackageUid(packageName, flags)
    }

    override fun getPermissionInfo(permName: String, flags: Int): PermissionInfo =
        basePm.getPermissionInfo(permName, flags)

    override fun queryPermissionsByGroup(permissionGroup: String?, flags: Int): MutableList<PermissionInfo> =
        basePm.queryPermissionsByGroup(permissionGroup, flags)

    override fun getPermissionGroupInfo(groupName: String, flags: Int): PermissionGroupInfo =
        basePm.getPermissionGroupInfo(groupName, flags)

    override fun getAllPermissionGroups(flags: Int): MutableList<PermissionGroupInfo> =
        basePm.getAllPermissionGroups(flags)

    override fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo {
        if (matchesTarget(packageName)) {
            return patchedAppInfo
        }
        return basePm.getApplicationInfo(packageName, flags)
    }

    override fun getActivityInfo(component: ComponentName, flags: Int): ActivityInfo {
        if (matchesTarget(component.packageName) || component.className == hostedActivityClassName) {
            if (targetPkg.isVaultApk) {
                val archiveAct = buildSyntheticPackageInfo(flags).activities
                    ?.firstOrNull { it.name == component.className || it.name.endsWith(".${component.className.substringAfterLast('.')}") }
                return archiveAct ?: buildSyntheticActivityInfo(component)
            }
            val baseInfo = runCatching { basePm.getActivityInfo(component, flags) }.getOrNull()
            return if (baseInfo != null) {
                ActivityInfo(baseInfo).apply {
                    applicationInfo = patchedAppInfo
                    val mergedMeta = Bundle(baseInfo.metaData ?: Bundle())
                    mergedMeta.putAll(activityMetaData)
                    metaData = mergedMeta
                }
            } else {
                buildSyntheticActivityInfo(component)
            }
        }
        return basePm.getActivityInfo(component, flags)
    }

    override fun getReceiverInfo(component: ComponentName, flags: Int): ActivityInfo =
        basePm.getReceiverInfo(component, flags)

    override fun getServiceInfo(component: ComponentName, flags: Int): ServiceInfo =
        basePm.getServiceInfo(component, flags)

    override fun getProviderInfo(component: ComponentName, flags: Int): ProviderInfo =
        basePm.getProviderInfo(component, flags)

    override fun getInstalledPackages(flags: Int): MutableList<PackageInfo> =
        basePm.getInstalledPackages(flags)

    override fun getPackagesHoldingPermissions(permissions: Array<out String>, flags: Int): MutableList<PackageInfo> =
        basePm.getPackagesHoldingPermissions(permissions, flags)

    override fun checkPermission(permName: String, packageName: String): Int {
        if (matchesTarget(packageName)) {
            return PERMISSION_GRANTED
        }
        return basePm.checkPermission(permName, packageName)
    }

    override fun isPermissionRevokedByPolicy(permName: String, packageName: String): Boolean =
        if (matchesTarget(packageName) && targetPkg.isVaultApk) false else basePm.isPermissionRevokedByPolicy(permName, packageName)

    override fun addPermission(info: PermissionInfo): Boolean =
        basePm.addPermission(info)

    override fun addPermissionAsync(info: PermissionInfo): Boolean =
        basePm.addPermissionAsync(info)

    override fun removePermission(permName: String) {
        basePm.removePermission(permName)
    }

    override fun checkSignatures(packageName1: String, packageName2: String): Int {
        if (matchesTarget(packageName1) && matchesTarget(packageName2)) {
            return SIGNATURE_MATCH
        }
        return basePm.checkSignatures(packageName1, packageName2)
    }

    override fun checkSignatures(uid1: Int, uid2: Int): Int {
        if (uid1 == patchedAppInfo.uid && uid2 == patchedAppInfo.uid) {
            return SIGNATURE_MATCH
        }
        return basePm.checkSignatures(uid1, uid2)
    }

    override fun getPackagesForUid(uid: Int): Array<String>? {
        val base = basePm.getPackagesForUid(uid)
        if (uid == patchedAppInfo.uid) {
            return ((base?.toList().orEmpty()) + targetPkg.packageName).distinct().toTypedArray()
        }
        return base
    }

    override fun getNameForUid(uid: Int): String? =
        basePm.getNameForUid(uid)

    override fun getInstalledApplications(flags: Int): MutableList<ApplicationInfo> =
        basePm.getInstalledApplications(flags)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun isInstantApp(): Boolean =
        basePm.isInstantApp

    @RequiresApi(Build.VERSION_CODES.O)
    override fun isInstantApp(packageName: String): Boolean =
        if (matchesTarget(packageName)) false else basePm.isInstantApp(packageName)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getInstantAppCookieMaxBytes(): Int =
        basePm.instantAppCookieMaxBytes

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getInstantAppCookie(): ByteArray =
        basePm.instantAppCookie

    @RequiresApi(Build.VERSION_CODES.O)
    override fun clearInstantAppCookie() {
        basePm.clearInstantAppCookie()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun updateInstantAppCookie(cookie: ByteArray?) {
        basePm.updateInstantAppCookie(cookie)
    }

    override fun getSystemSharedLibraryNames(): Array<String>? =
        basePm.systemSharedLibraryNames

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getSharedLibraries(flags: Int): MutableList<SharedLibraryInfo> =
        basePm.getSharedLibraries(flags)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getChangedPackages(sequenceNumber: Int): ChangedPackages? =
        basePm.getChangedPackages(sequenceNumber)

    override fun getSystemAvailableFeatures(): Array<FeatureInfo> =
        basePm.systemAvailableFeatures

    override fun hasSystemFeature(featureName: String): Boolean =
        basePm.hasSystemFeature(featureName)

    override fun hasSystemFeature(featureName: String, version: Int): Boolean =
        basePm.hasSystemFeature(featureName, version)

    override fun resolveActivity(intent: Intent, flags: Int): ResolveInfo? =
        basePm.resolveActivity(intent, flags)

    override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> =
        basePm.queryIntentActivities(intent, flags)

    override fun queryIntentActivityOptions(
        caller: ComponentName?,
        specifics: Array<out Intent>?,
        intent: Intent,
        flags: Int
    ): MutableList<ResolveInfo> =
        basePm.queryIntentActivityOptions(caller, specifics, intent, flags)

    override fun queryBroadcastReceivers(intent: Intent, flags: Int): MutableList<ResolveInfo> =
        basePm.queryBroadcastReceivers(intent, flags)

    override fun resolveService(intent: Intent, flags: Int): ResolveInfo? =
        basePm.resolveService(intent, flags)

    override fun queryIntentServices(intent: Intent, flags: Int): MutableList<ResolveInfo> =
        basePm.queryIntentServices(intent, flags)

    override fun queryIntentContentProviders(intent: Intent, flags: Int): MutableList<ResolveInfo> =
        basePm.queryIntentContentProviders(intent, flags)

    override fun resolveContentProvider(authority: String, flags: Int): ProviderInfo? =
        basePm.resolveContentProvider(authority, flags)

    override fun queryContentProviders(processName: String?, uid: Int, flags: Int): MutableList<ProviderInfo> =
        basePm.queryContentProviders(processName, uid, flags)

    override fun getInstrumentationInfo(className: ComponentName, flags: Int): InstrumentationInfo =
        basePm.getInstrumentationInfo(className, flags)

    override fun queryInstrumentation(targetPackage: String, flags: Int): MutableList<InstrumentationInfo> =
        basePm.queryInstrumentation(targetPackage, flags)

    override fun getDrawable(packageName: String, resid: Int, appInfo: ApplicationInfo?): Drawable? =
        if (matchesTarget(packageName)) {
            runCatching { hostResources.getDrawable(resid, null) }.getOrNull()
        } else {
            basePm.getDrawable(packageName, resid, appInfo)
        }

    override fun getActivityIcon(activityName: ComponentName): Drawable =
        if (matchesTarget(activityName.packageName)) basePm.defaultActivityIcon else basePm.getActivityIcon(activityName)

    override fun getActivityIcon(intent: Intent): Drawable =
        basePm.getActivityIcon(intent)

    override fun getActivityBanner(activityName: ComponentName): Drawable? =
        if (matchesTarget(activityName.packageName)) null else basePm.getActivityBanner(activityName)

    override fun getActivityBanner(intent: Intent): Drawable? =
        basePm.getActivityBanner(intent)

    override fun getDefaultActivityIcon(): Drawable =
        basePm.defaultActivityIcon

    override fun getApplicationIcon(info: ApplicationInfo): Drawable =
        runCatching { basePm.getApplicationIcon(info) }.getOrElse { basePm.defaultActivityIcon }

    override fun getApplicationIcon(packageName: String): Drawable =
        if (matchesTarget(packageName)) {
            runCatching { basePm.getApplicationIcon(packageName) }.getOrElse { basePm.defaultActivityIcon }
        } else {
            basePm.getApplicationIcon(packageName)
        }

    override fun getApplicationBanner(info: ApplicationInfo): Drawable? =
        basePm.getApplicationBanner(info)

    override fun getApplicationBanner(packageName: String): Drawable? =
        if (matchesTarget(packageName)) null else basePm.getApplicationBanner(packageName)

    override fun getActivityLogo(activityName: ComponentName): Drawable? =
        if (matchesTarget(activityName.packageName)) null else basePm.getActivityLogo(activityName)

    override fun getActivityLogo(intent: Intent): Drawable? =
        basePm.getActivityLogo(intent)

    override fun getApplicationLogo(info: ApplicationInfo): Drawable? =
        basePm.getApplicationLogo(info)

    override fun getApplicationLogo(packageName: String): Drawable? =
        if (matchesTarget(packageName)) null else basePm.getApplicationLogo(packageName)

    override fun getUserBadgedIcon(drawable: Drawable, user: UserHandle): Drawable =
        basePm.getUserBadgedIcon(drawable, user)

    override fun getUserBadgedDrawableForDensity(
        drawable: Drawable,
        user: UserHandle,
        badgeLocation: Rect?,
        badgeDensity: Int
    ): Drawable = basePm.getUserBadgedDrawableForDensity(drawable, user, badgeLocation, badgeDensity)

    override fun getUserBadgedLabel(label: CharSequence, user: UserHandle): CharSequence =
        basePm.getUserBadgedLabel(label, user)

    override fun getText(packageName: String, resid: Int, appInfo: ApplicationInfo?): CharSequence? =
        if (matchesTarget(packageName)) {
            runCatching { hostResources.getText(resid) }.getOrNull()
        } else {
            basePm.getText(packageName, resid, appInfo)
        }

    override fun getXml(packageName: String, resid: Int, appInfo: ApplicationInfo?): XmlResourceParser? =
        if (matchesTarget(packageName)) {
            runCatching { hostResources.getXml(resid) }.getOrNull()
        } else {
            basePm.getXml(packageName, resid, appInfo)
        }

    override fun getApplicationLabel(info: ApplicationInfo): CharSequence =
        if (info.packageName == targetPkg.packageName) targetPkg.appLabel else basePm.getApplicationLabel(info)

    override fun getResourcesForActivity(activityName: ComponentName): Resources =
        if (matchesTarget(activityName.packageName)) hostResources else basePm.getResourcesForActivity(activityName)

    override fun getResourcesForApplication(app: ApplicationInfo): Resources =
        if (app.packageName == targetPkg.packageName) hostResources else basePm.getResourcesForApplication(app)

    override fun getResourcesForApplication(packageName: String): Resources =
        if (matchesTarget(packageName)) hostResources else basePm.getResourcesForApplication(packageName)

    override fun verifyPendingInstall(id: Int, verificationCode: Int) {
        basePm.verifyPendingInstall(id, verificationCode)
    }

    override fun extendVerificationTimeout(
        id: Int,
        verificationCodeAtTimeout: Int,
        millisecondsToDelay: Long
    ) {
        basePm.extendVerificationTimeout(id, verificationCodeAtTimeout, millisecondsToDelay)
    }

    override fun setInstallerPackageName(targetPackage: String, installerPackageName: String?) {
        basePm.setInstallerPackageName(targetPackage, installerPackageName)
    }

    override fun getInstallerPackageName(packageName: String): String? =
        if (matchesTarget(packageName)) {
            if (targetPkg.isVaultApk) {
                "com.android.vending"
            } else {
                runCatching { basePm.getInstallerPackageName(packageName) }.getOrNull() ?: "com.android.vending"
            }
        } else {
            basePm.getInstallerPackageName(packageName)
        }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun getInstallSourceInfo(packageName: String): InstallSourceInfo =
        basePm.getInstallSourceInfo(packageName)

    override fun addPackageToPreferred(packageName: String) {
        basePm.addPackageToPreferred(packageName)
    }

    override fun removePackageFromPreferred(packageName: String) {
        basePm.removePackageFromPreferred(packageName)
    }

    override fun getPreferredPackages(flags: Int): MutableList<PackageInfo> =
        basePm.getPreferredPackages(flags)

    override fun addPreferredActivity(
        filter: IntentFilter,
        match: Int,
        set: Array<out ComponentName>?,
        activity: ComponentName
    ) {
        basePm.addPreferredActivity(filter, match, set, activity)
    }

    override fun clearPackagePreferredActivities(packageName: String) {
        basePm.clearPackagePreferredActivities(packageName)
    }

    override fun getPreferredActivities(
        outFilters: MutableList<IntentFilter>,
        outActivities: MutableList<ComponentName>,
        packageName: String?
    ): Int = basePm.getPreferredActivities(outFilters, outActivities, packageName)

    override fun setComponentEnabledSetting(componentName: ComponentName, newState: Int, flags: Int) {
        basePm.setComponentEnabledSetting(componentName, newState, flags)
    }

    override fun getComponentEnabledSetting(componentName: ComponentName): Int =
        basePm.getComponentEnabledSetting(componentName)

    override fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int) {
        basePm.setApplicationEnabledSetting(packageName, newState, flags)
    }

    override fun getApplicationEnabledSetting(packageName: String): Int =
        basePm.getApplicationEnabledSetting(packageName)

    override fun isSafeMode(): Boolean = basePm.isSafeMode

    @RequiresApi(Build.VERSION_CODES.O)
    override fun setApplicationCategoryHint(packageName: String, categoryHint: Int) {
        basePm.setApplicationCategoryHint(packageName, categoryHint)
    }

    override fun getPackageInstaller(): PackageInstaller = basePm.packageInstaller

    @RequiresApi(Build.VERSION_CODES.O)
    override fun canRequestPackageInstalls(): Boolean = basePm.canRequestPackageInstalls()
}
