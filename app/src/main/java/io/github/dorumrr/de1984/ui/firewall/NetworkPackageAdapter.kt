package io.github.dorumrr.de1984.ui.firewall

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.telephony.TelephonyManager
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageId
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.PackageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NetworkType {
    WIFI, MOBILE, ROAMING
}

class NetworkPackageAdapter(
    private val showIcons: Boolean,
    private val onPackageClick: (NetworkPackage) -> Unit,
    private val onPackageLongClick: (NetworkPackage) -> Boolean = { false },
    private val onQuickToggle: ((NetworkPackage, NetworkType) -> Unit)? = null
) : ListAdapter<NetworkPackage, NetworkPackageAdapter.NetworkPackageViewHolder>(NetworkPackageDiffCallback()) {

    companion object {
        private const val TAG = "NetworkPackageAdapter"
        private const val ICON_CACHE_SIZE = 100
    }

    private var isSelectionMode = false
    private val selectedPackages = mutableSetOf<PackageId>()
    private var onSelectionChanged: ((Set<PackageId>) -> Unit)? = null
    private var onSelectionLimitReached: (() -> Unit)? = null

    private val iconCache = LruCache<String, Drawable>(ICON_CACHE_SIZE)

    private var cachedAllowCritical: Boolean = Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL

    private var hasCellular: Boolean = true

    fun initialize(context: Context) {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        hasCellular = telephonyManager?.phoneType != TelephonyManager.PHONE_TYPE_NONE

        refreshSettings(context)
    }

    fun refreshSettings(context: Context) {
        val prefs = context.getSharedPreferences(
            Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        cachedAllowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )
    }

    fun clearIconCache() {
        iconCache.evictAll()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkPackageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_package, parent, false)
        return NetworkPackageViewHolder(
            view,
            showIcons,
            onPackageClick,
            onPackageLongClick,
            ::isPackageSelected,
            ::canSelectPackageCached,
            ::togglePackageSelection,
            onQuickToggle,
            iconCache,
            { hasCellular },
            { cachedAllowCritical }
        )
    }

    override fun onBindViewHolder(holder: NetworkPackageViewHolder, position: Int) {
        holder.bind(getItem(position), isSelectionMode)
    }

    fun setOnSelectionLimitReachedListener(listener: () -> Unit) {
        onSelectionLimitReached = listener
    }

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode != enabled) {
            isSelectionMode = enabled
            if (!enabled) {
                selectedPackages.clear()
            }
            notifyDataSetChanged()
        }
    }

    fun setOnSelectionChangedListener(listener: (Set<PackageId>) -> Unit) {
        onSelectionChanged = listener
    }


    /**
     * Put a saved selection back in one go.
     *
     * selectPackage() would work but fires notifyDataSetChanged() per item - 40 full refreshes for a
     * restore. Capped the same way selectPackage caps, so a tampered or stale bundle cannot exceed
     * the multi-select limit.
     */
    fun restoreSelection(ids: Set<PackageId>) {
        selectedPackages.clear()
        selectedPackages.addAll(ids.take(Constants.Packages.MultiSelect.MAX_SELECTION_COUNT))
        onSelectionChanged?.invoke(selectedPackages)
        // No notify: this is called at the end of onViewCreated, before the first submitList, so
        // there is nothing bound yet. Every row reads selectedPackages when it binds, so the list
        // arrives already showing the restored selection.
    }

    fun getSelectedPackages(): Set<PackageId> = selectedPackages.toSet()

    fun clearSelection() {
        selectedPackages.clear()
        onSelectionChanged?.invoke(selectedPackages)
        notifyDataSetChanged()
    }

    fun selectPackage(packageId: PackageId) {
        if (!selectedPackages.contains(packageId) &&
            selectedPackages.size < Constants.Packages.MultiSelect.MAX_SELECTION_COUNT) {
            selectedPackages.add(packageId)
            onSelectionChanged?.invoke(selectedPackages)
            notifyDataSetChanged()
        }
    }

    fun canSelectPackage(pkg: NetworkPackage, context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )
        if ((pkg.isSystemCritical || pkg.isVpnApp) && !allowCritical) return false
        return true
    }

    private fun canSelectPackageCached(pkg: NetworkPackage): Boolean {
        if ((pkg.isSystemCritical || pkg.isVpnApp) && !cachedAllowCritical) return false
        return true
    }

    private fun isPackageSelected(packageId: PackageId): Boolean {
        return selectedPackages.contains(packageId)
    }

    private fun togglePackageSelection(pkg: NetworkPackage, context: Context) {
        if (!canSelectPackage(pkg, context)) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.firewall_multiselect_toast_cannot_select_critical),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val packageId = pkg.id
        if (selectedPackages.contains(packageId)) {
            selectedPackages.remove(packageId)
        } else {
            if (selectedPackages.size >= Constants.Packages.MultiSelect.MAX_SELECTION_COUNT) {
                onSelectionLimitReached?.invoke()
                return
            }
            selectedPackages.add(packageId)
        }
        onSelectionChanged?.invoke(selectedPackages)
        notifyDataSetChanged()
    }

    class NetworkPackageViewHolder(
        itemView: View,
        private val showIcons: Boolean,
        private val onPackageClick: (NetworkPackage) -> Unit,
        private val onPackageLongClick: (NetworkPackage) -> Boolean,
        private val isPackageSelected: (PackageId) -> Boolean,
        private val canSelectPackage: (NetworkPackage) -> Boolean,
        private val togglePackageSelection: (NetworkPackage, Context) -> Unit,
        private val onQuickToggle: ((NetworkPackage, NetworkType) -> Unit)?,
        private val iconCache: LruCache<String, Drawable>,
        private val getHasCellular: () -> Boolean,
        private val getAllowCritical: () -> Boolean
    ) : RecyclerView.ViewHolder(itemView) {

        private val selectionCheckbox: CheckBox = itemView.findViewById(R.id.selection_checkbox)
        private val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
        private val appName: TextView = itemView.findViewById(R.id.app_name)
        private val packageName: TextView = itemView.findViewById(R.id.package_name)
        private val systemCriticalBadge: TextView = itemView.findViewById(R.id.system_critical_badge)
        private val vpnAppBadge: TextView = itemView.findViewById(R.id.vpn_app_badge)
        private val noInternetBadge: TextView = itemView.findViewById(R.id.no_internet_badge)
        private val profileBadge: TextView = itemView.findViewById(R.id.profile_badge)
        private val wifiContainer: View = itemView.findViewById(R.id.wifi_container)
        private val wifiIcon: ImageView = itemView.findViewById(R.id.wifi_icon)
        private val wifiBlockedOverlay: ImageView = itemView.findViewById(R.id.wifi_blocked_overlay)
        private val mobileContainer: View = itemView.findViewById(R.id.mobile_container)
        private val mobileIcon: ImageView = itemView.findViewById(R.id.mobile_icon)
        private val mobileBlockedOverlay: ImageView = itemView.findViewById(R.id.mobile_blocked_overlay)
        private val roamingContainer: View = itemView.findViewById(R.id.roaming_container)
        private val roamingIcon: ImageView = itemView.findViewById(R.id.roaming_icon)
        private val roamingBlockedOverlay: ImageView = itemView.findViewById(R.id.roaming_blocked_overlay)

        private val scope = CoroutineScope(Dispatchers.Main)

        private var currentPackage: NetworkPackage? = null
        private var currentIsSelectionMode: Boolean = false
        // Track which package the icon was loaded for (to avoid race conditions)
        private var iconLoadedForPackage: String? = null

        fun bind(pkg: NetworkPackage, isSelectionMode: Boolean) {
            currentPackage = pkg
            currentIsSelectionMode = isSelectionMode

            appName.text = pkg.name
            packageName.text = pkg.packageName

            systemCriticalBadge.visibility = if (pkg.isSystemCritical) View.VISIBLE else View.GONE

            vpnAppBadge.visibility = if (pkg.isVpnApp) View.VISIBLE else View.GONE

            noInternetBadge.visibility = if (!pkg.hasInternetPermission) View.VISIBLE else View.GONE

            when {
                pkg.userId >= 10 && pkg.userId < 100 -> {
                    profileBadge.text = itemView.context.getString(R.string.badge_work_profile)
                    profileBadge.visibility = View.VISIBLE
                }
                pkg.userId >= 100 -> {
                    profileBadge.text = itemView.context.getString(R.string.badge_clone_profile)
                    profileBadge.visibility = View.VISIBLE
                }
                else -> {
                    profileBadge.visibility = View.GONE
                }
            }

            // Dim the entire item if system critical or VPN app (unless setting is enabled)
            // Use cached setting value for performance (no SharedPreferences read per bind)
            val allowCritical = getAllowCritical()
            val shouldDim = !allowCritical && (pkg.isSystemCritical || pkg.isVpnApp)
            itemView.alpha = if (shouldDim) 0.6f else 1.0f

            if (isSelectionMode) {
                selectionCheckbox.visibility = View.VISIBLE
                val isSelected = isPackageSelected(pkg.id)
                selectionCheckbox.isChecked = isSelected

                val canSelect = !shouldDim
                selectionCheckbox.alpha = if (canSelect) 1.0f else 0.5f
            } else {
                selectionCheckbox.visibility = View.GONE
            }

            if (showIcons) {
                appIcon.visibility = View.VISIBLE
                val iconCacheKey = "${pkg.packageName}_${pkg.userId}"

                val cachedIcon = iconCache.get(iconCacheKey)
                if (cachedIcon != null) {
                    appIcon.setImageDrawable(cachedIcon)
                    iconLoadedForPackage = iconCacheKey
                } else {
                    appIcon.setImageResource(R.drawable.de1984_icon)
                    iconLoadedForPackage = null

                    val context = itemView.context
                    scope.launch {
                        val icon = withContext(Dispatchers.IO) {
                            try {
                                val pm = context.packageManager
                                val appInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getApplicationInfoAsUser(
                                    context, pkg.packageName, 0, pkg.userId
                                )
                                if (appInfo != null) {
                                    pm.getApplicationIcon(appInfo)
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }

                        if (currentPackage?.packageName == pkg.packageName && currentPackage?.userId == pkg.userId) {
                            if (icon != null) {
                                iconCache.put(iconCacheKey, icon)
                                appIcon.setImageDrawable(icon)
                                iconLoadedForPackage = iconCacheKey
                            }
                        }
                    }
                }
            } else {
                appIcon.visibility = View.GONE
            }

            val allowedColor = ContextCompat.getColor(itemView.context, R.color.lineage_teal)
            val blockedColor = ContextCompat.getColor(itemView.context, R.color.error_red)

            wifiIcon.setColorFilter(
                if (pkg.wifiBlocked) blockedColor else allowedColor,
                PorterDuff.Mode.SRC_IN
            )
            wifiBlockedOverlay.visibility = if (pkg.wifiBlocked) View.VISIBLE else View.GONE
            wifiBlockedOverlay.setColorFilter(blockedColor, PorterDuff.Mode.SRC_IN)

            mobileIcon.setColorFilter(
                if (pkg.mobileBlocked) blockedColor else allowedColor,
                PorterDuff.Mode.SRC_IN
            )
            mobileBlockedOverlay.visibility = if (pkg.mobileBlocked) View.VISIBLE else View.GONE
            mobileBlockedOverlay.setColorFilter(blockedColor, PorterDuff.Mode.SRC_IN)

            val hasCellular = getHasCellular()
            if (hasCellular) {
                roamingContainer.visibility = View.VISIBLE
                roamingIcon.setColorFilter(
                    if (pkg.roamingBlocked) blockedColor else allowedColor,
                    PorterDuff.Mode.SRC_IN
                )
                roamingBlockedOverlay.visibility = if (pkg.roamingBlocked) View.VISIBLE else View.GONE
                roamingBlockedOverlay.setColorFilter(blockedColor, PorterDuff.Mode.SRC_IN)
            } else {
                roamingContainer.visibility = View.GONE
            }

            val canQuickToggle = onQuickToggle != null && !shouldDim && !isSelectionMode

            wifiContainer.setOnClickListener {
                if (canQuickToggle) {
                    currentPackage?.let { pkg -> onQuickToggle?.invoke(pkg, NetworkType.WIFI) }
                }
            }
            wifiContainer.isClickable = canQuickToggle

            mobileContainer.setOnClickListener {
                if (canQuickToggle) {
                    currentPackage?.let { pkg -> onQuickToggle?.invoke(pkg, NetworkType.MOBILE) }
                }
            }
            mobileContainer.isClickable = canQuickToggle

            roamingContainer.setOnClickListener {
                if (canQuickToggle && hasCellular) {
                    currentPackage?.let { pkg -> onQuickToggle?.invoke(pkg, NetworkType.ROAMING) }
                }
            }
            if (hasCellular) {
                roamingContainer.isClickable = canQuickToggle
            }

            itemView.setOnClickListener {
                currentPackage?.let { pkg ->
                    if (currentIsSelectionMode) {
                        togglePackageSelection(pkg, itemView.context)
                    } else {
                        onPackageClick(pkg)
                    }
                }
            }

            itemView.setOnLongClickListener {
                currentPackage?.let { pkg ->
                    onPackageLongClick(pkg)
                } ?: false
            }
        }
    }

    class NetworkPackageDiffCallback : DiffUtil.ItemCallback<NetworkPackage>() {
        override fun areItemsTheSame(oldItem: NetworkPackage, newItem: NetworkPackage): Boolean {
            return oldItem.packageName == newItem.packageName && oldItem.userId == newItem.userId
        }

        override fun areContentsTheSame(oldItem: NetworkPackage, newItem: NetworkPackage): Boolean {
            return oldItem == newItem
        }
    }
}

