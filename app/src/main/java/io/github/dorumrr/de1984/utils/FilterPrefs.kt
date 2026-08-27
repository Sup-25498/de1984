package io.github.dorumrr.de1984.utils

import android.content.Context
import io.github.dorumrr.de1984.domain.model.FirewallFilterState
import io.github.dorumrr.de1984.presentation.viewmodel.PackageFilterState

/**
 * Remembers the last filter selection on the Firewall and Packages screens (issue #71).
 *
 * One place for both screens on purpose. They hold different filter shapes but the same rule, and a
 * second copy of that rule is how the two drift apart.
 *
 * Everything is normalised on the way IN, and that is the whole point of this being a boundary
 * rather than four `putString` calls. Two things get through otherwise, and both are permanent
 * once written:
 *
 *  - A package type that is not one of [Constants.Packages]' three constants. The deep-link path
 *    in both fragments passes `foundPkg.type.toString()`, which is the enum name "USER", not the
 *    "user" the chips map back from. `filterPackages` lowercases, so the LIST is right while the
 *    chip reads "All" - a mismatch that used to die with the session and would otherwise be
 *    restored on every launch from here on.
 *  - The "Uninstalled" package state. Only `GetPackagesUseCase.getFilteredByState` fetches
 *    uninstalled system packages; `PackagesViewModel` collects plain `invoke()`, which never
 *    contains them. Restoring it means an empty Packages screen at every launch with no clue why.
 *
 * A null state filter means "no state chip selected". `putString(key, null)` removes the key and
 * `getString` then returns null, so "never saved" and "explicitly none" collapse - harmless here,
 * because both land on the same default.
 */
object FilterPrefs {

    private fun prefs(context: Context) =
        context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)

    /** Keeps only a value `filterPackages` and the chip mappers both understand. */
    private fun normalisedType(value: String?): String? = when (value?.lowercase()) {
        Constants.Packages.TYPE_ALL -> Constants.Packages.TYPE_ALL
        Constants.Packages.TYPE_USER -> Constants.Packages.TYPE_USER
        Constants.Packages.TYPE_SYSTEM -> Constants.Packages.TYPE_SYSTEM
        else -> null
    }

    /** Drops a state this screen cannot restore. See the note on "Uninstalled" above. */
    private fun restorablePackageState(value: String?): String? =
        if (value?.lowercase() == Constants.Packages.STATE_UNINSTALLED.lowercase()) null else value

    fun loadFirewall(context: Context): FirewallFilterState {
        val p = prefs(context)
        val defaults = FirewallFilterState()
        return FirewallFilterState(
            packageType = normalisedType(p.getString(Constants.Settings.KEY_FIREWALL_FILTER_TYPE, null))
                ?: defaults.packageType,
            networkState = p.getString(Constants.Settings.KEY_FIREWALL_FILTER_STATE, null),
            internetOnly = p.getBoolean(
                Constants.Settings.KEY_FIREWALL_FILTER_INTERNET_ONLY,
                defaults.internetOnly
            ),
            profileFilter = p.getString(Constants.Settings.KEY_FIREWALL_FILTER_PROFILE, null)
                ?: defaults.profileFilter
        )
    }

    fun saveFirewall(context: Context, state: FirewallFilterState) {
        prefs(context).edit()
            .putString(Constants.Settings.KEY_FIREWALL_FILTER_TYPE, normalisedType(state.packageType))
            .putString(Constants.Settings.KEY_FIREWALL_FILTER_STATE, state.networkState)
            .putBoolean(Constants.Settings.KEY_FIREWALL_FILTER_INTERNET_ONLY, state.internetOnly)
            .putString(Constants.Settings.KEY_FIREWALL_FILTER_PROFILE, state.profileFilter)
            .apply()
    }

    fun loadPackages(context: Context): PackageFilterState {
        val p = prefs(context)
        val defaults = PackageFilterState()
        return PackageFilterState(
            packageType = normalisedType(p.getString(Constants.Settings.KEY_PACKAGES_FILTER_TYPE, null))
                ?: defaults.packageType,
            packageState = restorablePackageState(p.getString(Constants.Settings.KEY_PACKAGES_FILTER_STATE, null)),
            profileFilter = p.getString(Constants.Settings.KEY_PACKAGES_FILTER_PROFILE, null)
                ?: defaults.profileFilter
        )
    }

    fun savePackages(context: Context, state: PackageFilterState) {
        prefs(context).edit()
            .putString(Constants.Settings.KEY_PACKAGES_FILTER_TYPE, normalisedType(state.packageType))
            .putString(Constants.Settings.KEY_PACKAGES_FILTER_STATE, restorablePackageState(state.packageState))
            .putString(Constants.Settings.KEY_PACKAGES_FILTER_PROFILE, state.profileFilter)
            .apply()
    }
}
