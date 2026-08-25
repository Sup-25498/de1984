package io.github.dorumrr.de1984.ui.firewall

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.telephony.TelephonyManager
import io.github.dorumrr.de1984.utils.AppLogger
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import android.widget.TextView
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.databinding.BottomSheetFirewallMultiselectBinding
import io.github.dorumrr.de1984.databinding.BottomSheetPackageActionGranularBinding
import io.github.dorumrr.de1984.databinding.BottomSheetPackageActionSimpleBinding
import io.github.dorumrr.de1984.databinding.FragmentFirewallBinding
import io.github.dorumrr.de1984.databinding.NetworkTypeToggleBinding
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageId
import io.github.dorumrr.de1984.presentation.viewmodel.FirewallViewModel
import io.github.dorumrr.de1984.presentation.viewmodel.SettingsViewModel
import io.github.dorumrr.de1984.ui.base.BaseFragment
import io.github.dorumrr.de1984.ui.common.FilterChipsHelper
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.copyToClipboard
import io.github.dorumrr.de1984.utils.openAppSettings
import io.github.dorumrr.de1984.utils.setOnClickListenerDebounced
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FirewallFragmentViews : BaseFragment<FragmentFirewallBinding>() {

    private val TAG = "FirewallFragmentViews"

    private val viewModel: FirewallViewModel by activityViewModels {
        val app = requireActivity().application as De1984Application
        FirewallViewModel.Factory(
            app,
            app.dependencies.provideGetNetworkPackagesUseCase(),
            app.dependencies.provideManageNetworkAccessUseCase(),
            app.dependencies.superuserBannerState,
            app.dependencies.permissionManager,
            app.dependencies.firewallManager,
            app.dependencies.packageDataChanged
        )
    }

    private val settingsViewModel: SettingsViewModel by activityViewModels {
        val app = requireActivity().application as De1984Application
        SettingsViewModel.Factory(
            requireContext(),
            app.dependencies.permissionManager,
            app.dependencies.rootManager,
            app.dependencies.shizukuManager,
            app.dependencies.firewallManager,
            app.dependencies.firewallRepository,
            app.dependencies.captivePortalManager,
            app.dependencies.bootProtectionManager,
            app.dependencies.provideSmartPolicySwitchUseCase(),
            app.dependencies.packageRepository
        )
    }

    private lateinit var adapter: NetworkPackageAdapter
    private var currentTypeFilter: String? = null
    private var currentStateFilter: String? = null
    private var currentPermissionFilter: Boolean = false
    private var currentProfileFilter: String? = null

    private var lastHasWorkProfile: Boolean? = null
    private var lastHasCloneProfile: Boolean? = null

    private var previousObservedPolicy: String? = null

    /**
     * Last `showAppIcons` value the adapter was built for.
     *
     * The settings flow emits on EVERY settings change, and the adapter used to be rebuilt each
     * time - which drops the RecyclerView back to the top and reloads every visible icon from disk.
     * Only this one setting changes what the adapter is, so only this one should rebuild it.
     */
    private var previousObservedShowIcons: Boolean? = null

    /**
     * Last `allowCriticalPackageFirewall` value the rows were bound for.
     *
     * The adapter caches this flag and reads it only at bind time, to decide whether a
     * system-critical or VPN row is dimmed and whether its quick toggles respond. Before the
     * early-return below existed, the unconditional adapter rebuild happened to repaint those rows.
     * Nothing else does - refreshSettings() updates the cache with no notify, and updateUI bails out
     * when the package objects have not changed. So this has to be tracked explicitly.
     */
    private var previousObservedAllowCritical: Boolean? = null
    private var lastSubmittedPackages: List<NetworkPackage> = emptyList()

    private var currentDialog: BottomSheetDialog? = null
    private var dialogOpenTimestamp: Long = 0
    private var pendingDialogPackageId: PackageId? = null

    private var isSelectionMode = false
    private val selectedPackages = mutableSetOf<PackageId>()
    private var backPressedCallback: OnBackPressedCallback? = null

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = FragmentFirewallBinding.inflate(inflater, container, false)

    override fun scrollToTop() {
        _binding?.packagesRecyclerView?.scrollToPosition(0)
    }

    private fun scrollToPackage(packageName: String) {
        _binding?.let { binding ->
            binding.packagesRecyclerView.post {
                val displayedPackages = viewModel.uiState.value.packages
                val index = displayedPackages.indexOfFirst { it.packageName == packageName }

                if (index >= 0) {
                    (binding.packagesRecyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 100)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.loadingState.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.packagesRecyclerView.visibility = View.GONE

        setupRecyclerView()
        setupFilterChips()
        setupSearchBox()
        setupSelectionToolbar()
        setupBackPressHandler()

        // Sync search query with EditText after restoration
        // Fix: EditText state is restored by Android before TextWatcher is attached,
        // so TextWatcher doesn't fire for restored text. Manually sync ViewModel.
        val currentSearchText = binding.searchInput.text?.toString() ?: ""
        if (currentSearchText.isNotEmpty()) {
            viewModel.setSearchQuery(currentSearchText)
            binding.searchLayout.isEndIconVisible = true
        }

        observeUiState()
        observeSettingsState()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            adapter.refreshSettings(requireContext())
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        AppLogger.d(TAG, "onHiddenChanged: hidden=$hidden")

        if (!hidden) {
            AppLogger.d(TAG, "onHiddenChanged: Fragment became visible, checking for policy changes")
            val currentPolicy = settingsViewModel.uiState.value.defaultFirewallPolicy
            AppLogger.d(TAG, "onHiddenChanged: previousObservedPolicy=$previousObservedPolicy, currentPolicy=$currentPolicy")

            if (previousObservedPolicy != null && previousObservedPolicy != currentPolicy) {
                AppLogger.d(TAG, "onHiddenChanged: Policy changed while hidden! Refreshing...")
                previousObservedPolicy = currentPolicy
                viewModel.refreshDefaultPolicy()
            } else {
                AppLogger.d(TAG, "onHiddenChanged: No policy change detected")
            }

            if (::adapter.isInitialized) {
                adapter.refreshSettings(requireContext())
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = NetworkPackageAdapter(
            showIcons = true,
            onPackageClick = { pkg ->
                showPackageActionSheet(pkg)
            },
            onPackageLongClick = { pkg ->
                onPackageLongClick(pkg)
            },
            onQuickToggle = { pkg, networkType ->
                handleQuickToggle(pkg, networkType)
            }
        )

        adapter.initialize(requireContext())

        adapter.setOnSelectionChangedListener { selected ->
            selectedPackages.clear()
            selectedPackages.addAll(selected)
            updateSelectionToolbar()
        }

        adapter.setOnSelectionLimitReachedListener {
            Toast.makeText(
                requireContext(),
                getString(R.string.multiselect_toast_limit_reached, Constants.Packages.MultiSelect.MAX_SELECTION_COUNT),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Reset last submitted packages when creating new adapter
        // This ensures the new adapter gets populated even if the list hasn't changed
        lastSubmittedPackages = emptyList()

        binding.packagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@FirewallFragmentViews.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupFilterChips() {
        currentTypeFilter = getString(io.github.dorumrr.de1984.R.string.packages_filter_all)
        currentStateFilter = null
        currentPermissionFilter = true
        currentProfileFilter = getString(io.github.dorumrr.de1984.R.string.filter_profile_all)

        rebuildFilterChips(hasWorkProfile = false, hasCloneProfile = false)
    }

    private fun rebuildFilterChips(hasWorkProfile: Boolean, hasCloneProfile: Boolean) {
        val packageTypeFilters = listOf(
            getString(io.github.dorumrr.de1984.R.string.packages_filter_all),
            getString(io.github.dorumrr.de1984.R.string.packages_filter_user),
            getString(io.github.dorumrr.de1984.R.string.packages_filter_system)
        )
        val networkStateFilters = listOf(
            getString(io.github.dorumrr.de1984.R.string.firewall_state_allowed),
            getString(io.github.dorumrr.de1984.R.string.firewall_state_blocked)
        )
        val permissionFilters = listOf(
            getString(io.github.dorumrr.de1984.R.string.firewall_state_internet)
        )

        val profileFilters = mutableListOf<String>()
        if (hasWorkProfile || hasCloneProfile) {
            profileFilters.add(getString(io.github.dorumrr.de1984.R.string.filter_profile_all))
            profileFilters.add(getString(io.github.dorumrr.de1984.R.string.filter_profile_personal))
        }
        if (hasWorkProfile) {
            profileFilters.add(getString(io.github.dorumrr.de1984.R.string.filter_profile_work))
        }
        if (hasCloneProfile) {
            profileFilters.add(getString(io.github.dorumrr.de1984.R.string.filter_profile_clone))
        }

        if (currentProfileFilter != null && !profileFilters.contains(currentProfileFilter)) {
            currentProfileFilter = profileFilters.firstOrNull()
            if (currentProfileFilter != null) {
                viewModel.setProfileFilter(mapProfileFilterToInternal(currentProfileFilter!!))
            }
        }

        FilterChipsHelper.setupMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            typeFilters = packageTypeFilters,
            stateFilters = networkStateFilters,
            permissionFilters = permissionFilters,
            profileFilters = profileFilters,
            selectedTypeFilter = currentTypeFilter,
            selectedStateFilter = currentStateFilter,
            selectedPermissionFilter = currentPermissionFilter,
            selectedProfileFilter = currentProfileFilter,
            onTypeFilterSelected = { filter ->
                if (filter != currentTypeFilter) {
                    AppLogger.d(TAG, "🔘 USER ACTION: Package type filter changed: $filter")
                    currentTypeFilter = filter
                    val internalFilter = mapTypeFilterToInternal(filter)
                    viewModel.setPackageTypeFilter(internalFilter)
                }
            },
            onStateFilterSelected = { filter ->
                if (filter != currentStateFilter) {
                    AppLogger.d(TAG, "🔘 USER ACTION: Network state filter changed: ${filter ?: "none"}")

                    if (isSelectionMode) {
                        AppLogger.d(TAG, "🔘 Exiting selection mode due to state filter change")
                        exitSelectionMode()
                    }

                    currentStateFilter = filter
                    val internalFilter = filter?.let { mapStateFilterToInternal(it) }
                    viewModel.setNetworkStateFilter(internalFilter)
                }
            },
            onPermissionFilterSelected = { enabled ->
                if (enabled != currentPermissionFilter) {
                    AppLogger.d(TAG, "🔘 USER ACTION: Internet-only filter changed: $enabled")
                    currentPermissionFilter = enabled
                    viewModel.setInternetOnlyFilter(enabled)
                }
            },
            onProfileFilterSelected = { filter ->
                if (filter != currentProfileFilter) {
                    AppLogger.d(TAG, "🔘 USER ACTION: Profile filter changed: $filter")
                    currentProfileFilter = filter
                    val internalFilter = mapProfileFilterToInternal(filter)
                    viewModel.setProfileFilter(internalFilter)
                }
            }
        )
    }

    private fun setupSearchBox() {
        binding.searchLayout.isEndIconVisible = false

        binding.searchInput.addTextChangedListener { text ->
            val query = text?.toString() ?: ""
            if (query.isNotEmpty()) {
                AppLogger.d(TAG, "🔍 USER ACTION: Search query changed: '$query'")
            }
            viewModel.setSearchQuery(query)

            binding.searchLayout.isEndIconVisible = query.isNotEmpty()
        }

        binding.searchLayout.setEndIconOnClickListener {
            AppLogger.d(TAG, "🔘 USER ACTION: Search cleared")
            binding.searchInput.text?.clear()
            binding.searchLayout.isEndIconVisible = false
        }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboardAndClearFocus()
                true
            } else {
                false
            }
        }

        binding.packagesRecyclerView.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (binding.searchInput.hasFocus()) {
                    hideKeyboardAndClearFocus()
                    view.requestFocus()
                }
            }
            false
        }

        binding.packagesRecyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
                    if (binding.searchInput.hasFocus()) {
                        hideKeyboardAndClearFocus()
                    }
                }
            }
        })

        binding.rootContainer.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val searchLayoutLocation = IntArray(2)
                binding.searchLayout.getLocationOnScreen(searchLayoutLocation)
                val searchLayoutRect = android.graphics.Rect(
                    searchLayoutLocation[0],
                    searchLayoutLocation[1],
                    searchLayoutLocation[0] + binding.searchLayout.width,
                    searchLayoutLocation[1] + binding.searchLayout.height
                )

                val touchX = event.rawX.toInt()
                val touchY = event.rawY.toInt()

                if (!searchLayoutRect.contains(touchX, touchY) && binding.searchInput.hasFocus()) {
                    hideKeyboardAndClearFocus()
                    view.requestFocus()
                }
            }
            false
        }
    }

    private fun hideKeyboardAndClearFocus() {
        binding.searchInput.clearFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun updateFilterChips(
        packageTypeFilter: String,
        networkStateFilter: String?,
        internetOnlyFilter: Boolean,
        profileFilter: String
    ) {
        val translatedTypeFilter = mapInternalToTypeFilter(packageTypeFilter)
        val translatedStateFilter = networkStateFilter?.let { mapInternalToStateFilter(it) }
        val translatedProfileFilter = mapInternalToProfileFilter(profileFilter)

        if (translatedTypeFilter == currentTypeFilter &&
            translatedStateFilter == currentStateFilter &&
            internetOnlyFilter == currentPermissionFilter &&
            translatedProfileFilter == currentProfileFilter) {
            return
        }

        currentTypeFilter = translatedTypeFilter
        currentStateFilter = translatedStateFilter
        currentPermissionFilter = internetOnlyFilter
        currentProfileFilter = translatedProfileFilter

        FilterChipsHelper.updateMultiSelectFilterChips(
            chipGroup = binding.filterChips,
            selectedTypeFilter = translatedTypeFilter,
            selectedStateFilter = translatedStateFilter,
            selectedPermissionFilter = internetOnlyFilter,
            selectedProfileFilter = translatedProfileFilter
        )
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun observeSettingsState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.uiState.collect { settingsState ->
                    AppLogger.d(TAG, "observeSettingsState: settingsState changed - showAppIcons=${settingsState.showAppIcons}, defaultFirewallPolicy=${settingsState.defaultFirewallPolicy}")
                    AppLogger.d(TAG, "observeSettingsState: previousObservedPolicy=$previousObservedPolicy, newPolicy=${settingsState.defaultFirewallPolicy}")

                    val iconsChanged = previousObservedShowIcons != settingsState.showAppIcons
                    val policyChanged = previousObservedPolicy != null &&
                        previousObservedPolicy != settingsState.defaultFirewallPolicy
                    val allowCriticalChanged = previousObservedAllowCritical != null &&
                        previousObservedAllowCritical != settingsState.allowCriticalPackageFirewall

                    if (allowCriticalChanged) {
                        // Changes what each row LOOKS like and whether its quick toggles respond, but
                        // not the package data - so updateUI's diff sees nothing to submit. Refresh
                        // the adapter's cached copy and force a rebind, or critical and VPN rows stay
                        // dimmed with dead toggles until they scroll off screen and back.
                        AppLogger.d(TAG, "observeSettingsState: allowCriticalPackageFirewall changed - rebinding rows")
                        adapter.refreshSettings(requireContext())
                        adapter.notifyDataSetChanged()
                    }
                    previousObservedAllowCritical = settingsState.allowCriticalPackageFirewall

                    if (!iconsChanged && !policyChanged && previousObservedPolicy != null) {
                        // Nothing this screen renders has changed. Rebuilding here reset the scroll
                        // position and re-read every visible icon, on every unrelated settings write.
                        AppLogger.d(TAG, "observeSettingsState: nothing relevant changed - leaving the list alone")
                        return@collect
                    }

                    if (iconsChanged) {
                    if (isSelectionMode) {
                        AppLogger.d(TAG, "observeSettingsState: Exiting selection mode before adapter recreation")
                        exitSelectionMode()
                    }

                    adapter = NetworkPackageAdapter(
                        showIcons = settingsState.showAppIcons,
                        onPackageClick = { pkg ->
                            showPackageActionSheet(pkg)
                        },
                        onPackageLongClick = { pkg ->
                            onPackageLongClick(pkg)
                        },
                        onQuickToggle = { pkg, networkType ->
                            handleQuickToggle(pkg, networkType)
                        }
                    )

                    adapter.initialize(requireContext())

                    adapter.setOnSelectionChangedListener { selected ->
                        selectedPackages.clear()
                        selectedPackages.addAll(selected)
                        updateSelectionToolbar()
                    }

                    adapter.setOnSelectionLimitReachedListener {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.multiselect_toast_limit_reached, Constants.Packages.MultiSelect.MAX_SELECTION_COUNT),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    binding.packagesRecyclerView.adapter = adapter

                    lastSubmittedPackages = emptyList()
                    }
                    previousObservedShowIcons = settingsState.showAppIcons

                    if (previousObservedPolicy != null && previousObservedPolicy != settingsState.defaultFirewallPolicy) {
                        AppLogger.d(TAG, "observeSettingsState: Policy changed! Refreshing packages...")
                        viewModel.refreshDefaultPolicy()
                    } else if (previousObservedPolicy == null) {
                        AppLogger.d(TAG, "observeSettingsState: First observation, skipping refresh")
                    } else {
                        AppLogger.d(TAG, "observeSettingsState: Policy unchanged, skipping refresh")
                    }

                    // Update previous policy for next comparison (persists across lifecycle)
                    previousObservedPolicy = settingsState.defaultFirewallPolicy

                    updateUI(viewModel.uiState.value)
                }
            }
        }
    }

    private fun updateUI(state: io.github.dorumrr.de1984.presentation.viewmodel.FirewallUiState) {
        if (state.isLoadingData && state.packages.isEmpty()) {
            binding.packagesRecyclerView.visibility = View.INVISIBLE
            binding.loadingState.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        } else if (state.packages.isEmpty()) {
            binding.packagesRecyclerView.visibility = View.INVISIBLE
            binding.loadingState.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.packagesRecyclerView.visibility = View.VISIBLE
            binding.loadingState.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
        }

        if (lastHasWorkProfile != state.hasWorkProfile || lastHasCloneProfile != state.hasCloneProfile) {
            lastHasWorkProfile = state.hasWorkProfile
            lastHasCloneProfile = state.hasCloneProfile
            AppLogger.d(TAG, "Profile availability changed: hasWork=${state.hasWorkProfile}, hasClone=${state.hasCloneProfile}")
            rebuildFilterChips(state.hasWorkProfile, state.hasCloneProfile)
        }

        updateFilterChips(
            packageTypeFilter = state.filterState.packageType,
            networkStateFilter = state.filterState.networkState,
            internetOnlyFilter = state.filterState.internetOnly,
            profileFilter = state.filterState.profileFilter
        )

        state.batchBlockResult?.let { result ->
            showBatchResultDialog(result)
            viewModel.clearBatchBlockResult()
        }

        val displayedPackages = if (state.searchQuery.isBlank()) {
            state.packages
        } else {
            val query = state.searchQuery.lowercase()
            state.packages.filter { pkg ->
                pkg.name.lowercase().contains(query, ignoreCase = false)
            }
        }

        val count = displayedPackages.size
        binding.packageCounter.text = if (count == 0 && state.searchQuery.isBlank()) {
            ""
        } else {
            resources.getQuantityString(
                R.plurals.package_count,
                count,
                count
            )
        }

        val listChanged = displayedPackages != lastSubmittedPackages
        if (!listChanged) {
            return
        }

        lastSubmittedPackages = displayedPackages
        adapter.submitList(displayedPackages)
        if (state.isRenderingUI) {
            viewModel.setUIReady()
        }
    }

    // ============================================================================
    // DO NOT REMOVE: This method is called from MainActivity for cross-navigation
    // ============================================================================
    fun openAppDialog(packageName: String, userId: Int = 0) {
        if (currentDialog?.isShowing == true) {
            AppLogger.w(TAG, "[FIREWALL] Dialog already open, dismissing before opening new one")
            currentDialog?.dismiss()
            currentDialog = null
        }

        val pkg = viewModel.uiState.value.packages.find {
            it.packageName == packageName && it.userId == userId
        }

        val targetPackageId = PackageId(packageName, userId)

        if (pkg != null) {
            pendingDialogPackageId = null
            scrollToPackage(packageName)
            showPackageActionSheet(pkg)
        } else {
            pendingDialogPackageId = targetPackageId

            lifecycleScope.launch {
                try {
                    val app = requireActivity().application as De1984Application
                    val networkPackageRepository = app.dependencies.networkPackageRepository
                    val result = networkPackageRepository.getNetworkPackage(packageName, userId)

                    result.onSuccess { foundPkg ->
                        if (pendingDialogPackageId != targetPackageId) {
                            return@onSuccess
                        }

                        val currentFilter = viewModel.uiState.value.filterState.packageType
                        val packageType = foundPkg.type.toString()

                        if (currentFilter.equals(packageType, ignoreCase = true)) {
                            viewModel.uiState.collect { state ->
                                if (pendingDialogPackageId != targetPackageId) {
                                    return@collect
                                }

                                val foundPackage = state.packages.find {
                                    it.packageName == packageName && it.userId == userId
                                }
                                if (foundPackage != null) {
                                    pendingDialogPackageId = null
                                    scrollToPackage(packageName)
                                    showPackageActionSheet(foundPackage)
                                    return@collect
                                }
                            }
                        } else {
                            viewModel.setPackageTypeFilter(packageType)

                            viewModel.uiState.collect { state ->
                                if (pendingDialogPackageId != targetPackageId) {
                                    return@collect
                                }

                                if (state.filterState.packageType.equals(packageType, ignoreCase = true) && !state.isLoading) {
                                    val foundPackage = state.packages.find {
                                        it.packageName == packageName && it.userId == userId
                                    }
                                    if (foundPackage != null) {
                                        pendingDialogPackageId = null
                                        scrollToPackage(packageName)
                                        showPackageActionSheet(foundPackage)
                                        return@collect
                                    }
                                }
                            }
                        }
                    }.onFailure { error ->
                        AppLogger.e(TAG, "Failed to load package for dialog: ${error.message}")
                        if (pendingDialogPackageId == targetPackageId) {
                            pendingDialogPackageId = null
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Exception opening dialog: ${e.message}")
                    if (pendingDialogPackageId == targetPackageId) {
                        pendingDialogPackageId = null
                    }
                }
            }
        }
    }

    private fun showPackageActionSheet(pkg: NetworkPackage) {
        val dialog = BottomSheetDialog(requireContext())
        currentDialog = dialog

        if (supportsGranularControl()) {
            showGranularControlSheet(dialog, pkg)
        } else {
            showSimpleControlSheet(dialog, pkg)
        }
    }

    private fun showGranularControlSheet(dialog: BottomSheetDialog, pkg: NetworkPackage) {
        AppLogger.d(TAG, "showGranularControlSheet: ENTRY - pkg=${pkg.packageName}, dialog=$dialog")
        val binding = BottomSheetPackageActionGranularBinding.inflate(layoutInflater)

        val telephonyManager = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasCellular = telephonyManager?.phoneType != TelephonyManager.PHONE_TYPE_NONE

        binding.actionSheetAppIcon.setImageResource(R.drawable.de1984_icon)
        binding.actionSheetAppName.text = pkg.name
        binding.actionSheetPackageName.text = pkg.packageName

        // Load icon asynchronously to prevent blocking main thread
        // Work profile apps require slow shell commands via HiddenApiHelper
        // IMPORTANT: Capture context BEFORE entering coroutine to avoid IllegalStateException
        val context = requireContext()
        lifecycleScope.launch {
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
            if (dialog.isShowing && isAdded) {
                icon?.let { binding.actionSheetAppIcon.setImageDrawable(it) }
            }
        }

        binding.actionSheetPackageName.setOnClickListenerDebounced {
            requireContext().copyToClipboard(pkg.packageName, getString(R.string.clipboard_label_package_name))
        }

        binding.actionSheetSettingsIcon.setOnClickListener {
            requireContext().openAppSettings(pkg.packageName)
            dialog.dismiss()
        }

        if (hasCellular) {
            binding.roamingDivider.visibility = View.VISIBLE
            binding.roamingToggle.root.visibility = View.VISIBLE
        } else {
            binding.roamingDivider.visibility = View.GONE
            binding.roamingToggle.root.visibility = View.GONE
        }

        var isUpdatingProgrammatically = false

        fun updateTogglesFromPackage(currentPkg: NetworkPackage) {
            AppLogger.d(TAG, "updateTogglesFromPackage: pkg=${currentPkg.packageName}, wifi=${currentPkg.wifiBlocked}, mobile=${currentPkg.mobileBlocked}, roaming=${currentPkg.roamingBlocked}, background=${currentPkg.backgroundBlocked}, isFullyBlocked=${currentPkg.isFullyBlocked}")
            isUpdatingProgrammatically = true

            binding.wifiToggle.toggleSwitch.isChecked = currentPkg.wifiBlocked
            updateSwitchColors(binding.wifiToggle.toggleSwitch, currentPkg.wifiBlocked)

            binding.mobileToggle.toggleSwitch.isChecked = currentPkg.mobileBlocked
            updateSwitchColors(binding.mobileToggle.toggleSwitch, currentPkg.mobileBlocked)

            if (hasCellular) {
                binding.roamingToggle.toggleSwitch.isChecked = currentPkg.roamingBlocked
                updateSwitchColors(binding.roamingToggle.toggleSwitch, currentPkg.roamingBlocked)
            }

            val app = requireActivity().application as De1984Application
            val backendType = app.dependencies.firewallManager.activeBackendType.value
            binding.lanToggle.toggleSwitch.isChecked = currentPkg.lanBlocked
            if (backendType == FirewallBackendType.IPTABLES) {
                updateSwitchColors(binding.lanToggle.toggleSwitch, currentPkg.lanBlocked)
            }

            val allowCriticalForUpdate = settingsViewModel.uiState.value.allowCriticalPackageFirewall
            val shouldShowBackgroundAccess = (!currentPkg.isSystemCritical || allowCriticalForUpdate) && (!currentPkg.isVpnApp || allowCriticalForUpdate) && !currentPkg.isFullyBlocked
            val wasBackgroundToggleVisible = binding.foregroundOnlyToggle.root.visibility == View.VISIBLE
            AppLogger.d(TAG, "updateTogglesFromPackage: shouldShowBackgroundAccess=$shouldShowBackgroundAccess, wasVisible=$wasBackgroundToggleVisible (isSystemCritical=${currentPkg.isSystemCritical}, isVpnApp=${currentPkg.isVpnApp}, isFullyBlocked=${currentPkg.isFullyBlocked})")

            binding.foregroundOnlyDivider.visibility = if (shouldShowBackgroundAccess) View.VISIBLE else View.GONE
            binding.foregroundOnlyToggle.root.visibility = if (shouldShowBackgroundAccess) View.VISIBLE else View.GONE

            if (shouldShowBackgroundAccess) {
                if (!wasBackgroundToggleVisible) {
                    AppLogger.d(TAG, "updateTogglesFromPackage: Background toggle just became visible - setting up listener")
                    setupNetworkToggle(
                        binding = binding.foregroundOnlyToggle,
                        label = getString(R.string.firewall_network_label_background_access),
                        isBlocked = !currentPkg.backgroundBlocked,
                        enabled = true,
                        invertLabels = true,
                        onToggle = { isChecked ->
                            if (isUpdatingProgrammatically) return@setupNetworkToggle
                            AppLogger.d(TAG, "updateTogglesFromPackage: Background toggle clicked - isChecked=$isChecked, setting backgroundBlocked=${!isChecked}")
                            viewModel.setBackgroundBlocking(currentPkg.packageName, currentPkg.userId, !isChecked)
                        }
                    )
                } else {
                    binding.foregroundOnlyToggle.toggleSwitch.isChecked = !currentPkg.backgroundBlocked
                    updateSwitchColors(binding.foregroundOnlyToggle.toggleSwitch, !currentPkg.backgroundBlocked, invertColors = true)
                }
                AppLogger.d(TAG, "updateTogglesFromPackage: Background toggle updated - isChecked=${!currentPkg.backgroundBlocked}")
            }

            isUpdatingProgrammatically = false
        }

        val observerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val updatedPkg = state.packages.find { it.packageName == pkg.packageName }
                AppLogger.d(TAG, "showGranularControlSheet: uiState collected - updatedPkg found=${updatedPkg != null}, isUpdatingProgrammatically=$isUpdatingProgrammatically")
                if (updatedPkg != null && !isUpdatingProgrammatically) {
                    AppLogger.d(TAG, "showGranularControlSheet: Calling updateTogglesFromPackage for ${updatedPkg.packageName}")
                    updateTogglesFromPackage(updatedPkg)
                } else if (updatedPkg != null) {
                    AppLogger.d(TAG, "showGranularControlSheet: Skipping update (isUpdatingProgrammatically=true)")
                }
            }
        }

        dialog.setOnDismissListener {
            AppLogger.d(TAG, "showGranularControlSheet: Dialog dismissed, cancelling observer for ${pkg.packageName}")
            observerJob.cancel()
            if (currentDialog == dialog) {
                currentDialog = null
            }
        }

        val allowCritical = settingsViewModel.uiState.value.allowCriticalPackageFirewall
        val isProtected = (pkg.isSystemCritical || pkg.isVpnApp) && !allowCritical
        if (isProtected) {
            binding.protectionWarningBanner.root.visibility = View.VISIBLE

            val bannerMessage = when {
                pkg.isSystemCritical -> getString(R.string.protection_banner_message_firewall_system)
                pkg.isVpnApp -> getString(R.string.protection_banner_message_firewall_vpn)
                else -> getString(R.string.protection_banner_message_firewall)
            }

            binding.protectionWarningBanner.bannerMessage.text = bannerMessage

            binding.protectionWarningBanner.bannerSettingsButton.setOnClickListener {
                dialog.dismiss()
                (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToSettings()
            }
        } else {
            binding.protectionWarningBanner.root.visibility = View.GONE
        }

        setupNetworkToggle(
            binding = binding.wifiToggle,
            label = getString(R.string.firewall_network_label_wifi),
            isBlocked = pkg.wifiBlocked,
            enabled = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical),
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle
                AppLogger.d(TAG, "🔘 USER ACTION: WiFi toggle changed for ${pkg.packageName} - blocked: $blocked")
                viewModel.setWifiBlocking(pkg.packageName, pkg.userId, blocked)
            }
        )

        setupNetworkToggle(
            binding = binding.mobileToggle,
            label = getString(R.string.firewall_network_label_mobile),
            isBlocked = pkg.mobileBlocked,
            enabled = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical),
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle
                AppLogger.d(TAG, "🔘 USER ACTION: Mobile toggle changed for ${pkg.packageName} - blocked: $blocked")

                viewModel.setMobileBlocking(pkg.packageName, pkg.userId, blocked)
            }
        )

        if (hasCellular) {
            setupNetworkToggle(
                binding = binding.roamingToggle,
                label = getString(R.string.firewall_network_label_roaming),
                isBlocked = pkg.roamingBlocked,
                enabled = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical),
                onToggle = { blocked ->
                    if (isUpdatingProgrammatically) return@setupNetworkToggle
                    AppLogger.d(TAG, "🔘 USER ACTION: Roaming toggle changed for ${pkg.packageName} - blocked: $blocked")

                    viewModel.setRoamingBlocking(pkg.packageName, pkg.userId, blocked)
                }
            )
        }

        // Setup LAN toggle - always visible, disabled when not using iptables backend
        val appForLan = requireActivity().application as De1984Application
        val backendTypeForLan = appForLan.dependencies.firewallManager.activeBackendType.value
        val isIptablesBackend = backendTypeForLan == FirewallBackendType.IPTABLES

        binding.lanDivider.visibility = View.VISIBLE
        binding.lanToggle.root.visibility = View.VISIBLE

        if (!isIptablesBackend) {
            binding.lanToggle.root.alpha = 0.6f
            binding.lanToggle.networkTypeSubtitle.visibility = View.VISIBLE
            binding.lanToggle.networkTypeSubtitle.text = getString(R.string.firewall_lan_requires_root)
        }

        setupNetworkToggle(
            binding = binding.lanToggle,
            label = getString(R.string.firewall_network_label_lan),
            isBlocked = pkg.lanBlocked,
            enabled = isIptablesBackend && (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical),
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle
                viewModel.setLanBlocking(pkg.packageName, pkg.userId, blocked)
            }
        )

        if (isProtected) {
            binding.wifiToggle.root.setOnClickListener {
                if (!binding.wifiToggle.toggleSwitch.isEnabled) {
                    showProtectionSnackbar(dialog)
                }
            }

            binding.mobileToggle.root.setOnClickListener {
                if (!binding.mobileToggle.toggleSwitch.isEnabled) {
                    showProtectionSnackbar(dialog)
                }
            }

            if (hasCellular) {
                binding.roamingToggle.root.setOnClickListener {
                    if (!binding.roamingToggle.toggleSwitch.isEnabled) {
                        showProtectionSnackbar(dialog)
                    }
                }
            }

            binding.lanToggle.root.setOnClickListener {
                if (!binding.lanToggle.toggleSwitch.isEnabled && isIptablesBackend) {
                    showProtectionSnackbar(dialog)
                }
            }
        }

        val defaultPolicy = viewModel.uiState.value.defaultFirewallPolicy
        val isBlockAllMode = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL
        val shouldShowBackgroundAccess = (!pkg.isSystemCritical || allowCritical) && (!pkg.isVpnApp || allowCritical) && !pkg.isFullyBlocked

        AppLogger.d(TAG, "showGranularControlSheet: defaultPolicy=$defaultPolicy, isBlockAllMode=$isBlockAllMode, isFullyBlocked=${pkg.isFullyBlocked}, shouldShowBackgroundAccess=$shouldShowBackgroundAccess")

        if (shouldShowBackgroundAccess) {
            binding.foregroundOnlyDivider.visibility = View.VISIBLE
            binding.foregroundOnlyToggle.root.visibility = View.VISIBLE

            setupNetworkToggle(
                binding = binding.foregroundOnlyToggle,
                label = getString(R.string.firewall_network_label_background_access),
                isBlocked = !pkg.backgroundBlocked, // INVERTED: ON = allowed (not blocked), OFF = blocked
                enabled = true,
                invertLabels = true,
                onToggle = { isChecked ->
                    if (isUpdatingProgrammatically) return@setupNetworkToggle
                    // isChecked=true means switch is ON, which means "allowed" for this toggle
                    // So we need to set backgroundBlocked to the opposite: !isChecked
                    viewModel.setBackgroundBlocking(pkg.packageName, pkg.userId, !isChecked)
                }
            )
        } else {
            binding.foregroundOnlyDivider.visibility = View.GONE
            binding.foregroundOnlyToggle.root.visibility = View.GONE
        }

        val app = requireActivity().application as De1984Application
        val firewallManager = app.dependencies.firewallManager
        val backendType = firewallManager.getActiveBackendType()

        if (!pkg.hasInternetPermission) {
            binding.infoMessage.visibility = View.VISIBLE
            binding.infoMessage.text = getString(R.string.firewall_no_internet_info)
        } else if (pkg.isVpnApp) {
            binding.infoMessage.visibility = View.VISIBLE
            binding.infoMessage.text = getString(R.string.firewall_vpn_app_info)
        } else if (backendType == io.github.dorumrr.de1984.domain.firewall.FirewallBackendType.VPN) {
            binding.infoMessage.visibility = View.VISIBLE
            binding.infoMessage.text = getString(R.string.firewall_vpn_info_message)
        } else {
            binding.infoMessage.visibility = View.GONE
        }

        binding.manageAppAction.setOnClickListener {
            dialog.dismiss()
            (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToPackagesWithApp(pkg.packageName, pkg.userId)
        }

        dialog.setContentView(binding.root)
        AppLogger.d(TAG, "showGranularControlSheet: EXIT - About to show dialog for ${pkg.packageName}")
        dialog.show()

        dialog.behavior.apply {
            isDraggable = true
            // Allow the sheet to be dragged, but nested scrolling will take priority
            // This ensures content scrolls first before the sheet starts dragging
        }
    }

    private fun showSimpleControlSheet(dialog: BottomSheetDialog, pkg: NetworkPackage) {
        val binding = BottomSheetPackageActionSimpleBinding.inflate(layoutInflater)

        binding.actionSheetAppIcon.setImageResource(R.drawable.de1984_icon)
        binding.actionSheetAppName.text = pkg.name
        binding.actionSheetPackageName.text = pkg.packageName

        // Load icon asynchronously to prevent blocking main thread
        // Work profile apps require slow shell commands via HiddenApiHelper
        // IMPORTANT: Capture context BEFORE entering coroutine to avoid IllegalStateException
        val context = requireContext()
        lifecycleScope.launch {
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
            if (dialog.isShowing && isAdded) {
                icon?.let { binding.actionSheetAppIcon.setImageDrawable(it) }
            }
        }

        binding.actionSheetPackageName.setOnClickListenerDebounced {
            requireContext().copyToClipboard(pkg.packageName, getString(R.string.clipboard_label_package_name))
        }

        // ============================================================================
        // IMPORTANT: Click settings icon to open Android system settings
        // User preference: Settings cog icon on the right opens Android app settings
        // DO NOT REMOVE THIS FUNCTIONALITY - it's a core feature!
        // ============================================================================
        binding.actionSheetSettingsIcon.setOnClickListener {
            requireContext().openAppSettings(pkg.packageName)
            dialog.dismiss()
        }

        val app = requireActivity().application as De1984Application
        val firewallManager = app.dependencies.firewallManager
        val backendType = firewallManager.getActiveBackendType()
        val allowCriticalSimple = settingsViewModel.uiState.value.allowCriticalPackageFirewall

        val infoMessage: String? = if (!pkg.hasInternetPermission) {
            getString(R.string.firewall_no_internet_info)
        } else if ((pkg.isSystemCritical || pkg.isVpnApp) && allowCriticalSimple) {
            getString(R.string.firewall_critical_allowed_info)
        } else if (pkg.isSystemCritical) {
            getString(R.string.firewall_system_critical_info)
        } else if (pkg.isVpnApp) {
            getString(R.string.firewall_vpn_app_info)
        } else if (backendType == io.github.dorumrr.de1984.domain.firewall.FirewallBackendType.CONNECTIVITY_MANAGER) {
            getString(R.string.firewall_connectivity_manager_info)
        } else {
            null
        }

        if (infoMessage != null) {
            binding.infoMessage.visibility = View.VISIBLE
            binding.infoMessage.text = infoMessage
        } else {
            binding.infoMessage.visibility = View.GONE
        }

        var isUpdatingProgrammatically = false

        fun updateToggleFromPackage(currentPkg: NetworkPackage) {
            isUpdatingProgrammatically = true

            val isBlocked = currentPkg.wifiBlocked || currentPkg.mobileBlocked || currentPkg.roamingBlocked
            binding.internetToggle.toggleSwitch.isChecked = isBlocked
            updateSwitchColors(binding.internetToggle.toggleSwitch, isBlocked)

            isUpdatingProgrammatically = false
        }

        updateToggleFromPackage(pkg)

        val observerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val updatedPkg = state.packages.find { it.packageName == pkg.packageName }
                if (updatedPkg != null && !isUpdatingProgrammatically) {
                    updateToggleFromPackage(updatedPkg)
                }
            }
        }

        dialog.setOnDismissListener {
            AppLogger.d(TAG, "showSimpleControlSheet: Dialog dismissed, cancelling observer for ${pkg.packageName}")
            observerJob.cancel()
        }

        setupNetworkToggle(
            binding = binding.internetToggle,
            label = getString(R.string.firewall_network_label_internet_access),
            isBlocked = pkg.wifiBlocked || pkg.mobileBlocked || pkg.roamingBlocked,
            enabled = (!pkg.isSystemCritical || allowCriticalSimple) && (!pkg.isVpnApp || allowCriticalSimple),
            onToggle = { blocked ->
                if (isUpdatingProgrammatically) return@setupNetworkToggle

                viewModel.setAllNetworkBlocking(pkg.packageName, pkg.userId, blocked)
            }
        )
        binding.internetToggle.networkTypeSubtitle.visibility = View.VISIBLE
        binding.internetToggle.networkTypeSubtitle.text = getString(R.string.firewall_internet_access_subtitle)

        setupNetworkToggle(
            binding = binding.lanToggle,
            label = getString(R.string.firewall_network_label_lan),
            isBlocked = pkg.lanBlocked,
            enabled = false,
            onToggle = { }
        )
        binding.lanToggle.root.alpha = 0.6f
        binding.lanToggle.networkTypeSubtitle.visibility = View.VISIBLE
        binding.lanToggle.networkTypeSubtitle.text = getString(R.string.firewall_lan_requires_root)

        binding.manageAppAction.setOnClickListener {
            dialog.dismiss()
            (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToPackagesWithApp(pkg.packageName, pkg.userId)
        }

        dialog.setContentView(binding.root)
        dialog.show()

        dialog.behavior.apply {
            isDraggable = true
            // Allow the sheet to be dragged, but nested scrolling will take priority
            // This ensures content scrolls first before the sheet starts dragging
        }
    }

    private fun setupNetworkToggle(
        binding: NetworkTypeToggleBinding,
        label: String,
        isBlocked: Boolean,
        enabled: Boolean,
        invertLabels: Boolean = false,
        onToggle: (Boolean) -> Unit
    ) {
        AppLogger.d(TAG, "setupNetworkToggle: label=$label, isBlocked=$isBlocked, enabled=$enabled, binding=$binding")
        binding.networkTypeLabel.text = label

        if (invertLabels) {
            binding.labelLeft.text = getString(R.string.firewall_state_off)
            binding.labelRight.text = getString(R.string.firewall_state_on)
        } else {
            binding.labelLeft.text = getString(R.string.firewall_state_allowed)
            binding.labelRight.text = getString(R.string.firewall_state_blocked)
        }

        binding.toggleSwitch.isChecked = isBlocked
        binding.toggleSwitch.isEnabled = enabled

        updateSwitchColors(binding.toggleSwitch, isBlocked, invertColors = invertLabels)

        binding.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchColors(binding.toggleSwitch, isChecked, invertColors = invertLabels)
            onToggle(isChecked)
        }
    }

    private fun updateSwitchColors(
        switch: SwitchMaterial,
        @Suppress("UNUSED_PARAMETER") isBlocked: Boolean,
        invertColors: Boolean = false
    ) {
        val context = switch.context

        val (checkedColor, uncheckedColor) = if (invertColors) {
            Pair(
                ContextCompat.getColor(context, R.color.lineage_teal),
                ContextCompat.getColor(context, R.color.error_red)
            )
        } else {
            Pair(
                ContextCompat.getColor(context, R.color.error_red),
                ContextCompat.getColor(context, R.color.lineage_teal)
            )
        }

        val thumbColorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(checkedColor, uncheckedColor)
        )

        val trackColorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                checkedColor and 0x80FFFFFF.toInt(),
                uncheckedColor and 0x80FFFFFF.toInt()
            )
        )

        switch.thumbTintList = thumbColorStateList
        switch.trackTintList = trackColorStateList
    }

    private fun mapTypeFilterToInternal(translatedFilter: String): String {
        return when (translatedFilter) {
            getString(io.github.dorumrr.de1984.R.string.packages_filter_all) -> Constants.Packages.TYPE_ALL
            getString(io.github.dorumrr.de1984.R.string.packages_filter_user) -> Constants.Packages.TYPE_USER
            getString(io.github.dorumrr.de1984.R.string.packages_filter_system) -> Constants.Packages.TYPE_SYSTEM
            else -> Constants.Packages.TYPE_ALL
        }
    }

    private fun mapStateFilterToInternal(translatedFilter: String): String {
        return when (translatedFilter) {
            getString(io.github.dorumrr.de1984.R.string.firewall_state_allowed) -> Constants.Firewall.STATE_ALLOWED
            getString(io.github.dorumrr.de1984.R.string.firewall_state_blocked) -> Constants.Firewall.STATE_BLOCKED
            else -> translatedFilter
        }
    }

    private fun mapInternalToTypeFilter(internalFilter: String): String {
        return when (internalFilter) {
            Constants.Packages.TYPE_ALL -> getString(io.github.dorumrr.de1984.R.string.packages_filter_all)
            Constants.Packages.TYPE_USER -> getString(io.github.dorumrr.de1984.R.string.packages_filter_user)
            Constants.Packages.TYPE_SYSTEM -> getString(io.github.dorumrr.de1984.R.string.packages_filter_system)
            else -> getString(io.github.dorumrr.de1984.R.string.packages_filter_all)
        }
    }

    private fun mapInternalToStateFilter(internalFilter: String): String {
        return when (internalFilter) {
            Constants.Firewall.STATE_ALLOWED -> getString(io.github.dorumrr.de1984.R.string.firewall_state_allowed)
            Constants.Firewall.STATE_BLOCKED -> getString(io.github.dorumrr.de1984.R.string.firewall_state_blocked)
            else -> internalFilter
        }
    }

    private fun mapProfileFilterToInternal(translatedFilter: String): String {
        return when (translatedFilter) {
            getString(io.github.dorumrr.de1984.R.string.filter_profile_all) -> "All"
            getString(io.github.dorumrr.de1984.R.string.filter_profile_personal) -> "Personal"
            getString(io.github.dorumrr.de1984.R.string.filter_profile_work) -> "Work"
            getString(io.github.dorumrr.de1984.R.string.filter_profile_clone) -> "Clone"
            else -> "All"
        }
    }

    private fun mapInternalToProfileFilter(internalFilter: String): String {
        return when (internalFilter) {
            "All" -> getString(io.github.dorumrr.de1984.R.string.filter_profile_all)
            "Personal" -> getString(io.github.dorumrr.de1984.R.string.filter_profile_personal)
            "Work" -> getString(io.github.dorumrr.de1984.R.string.filter_profile_work)
            "Clone" -> getString(io.github.dorumrr.de1984.R.string.filter_profile_clone)
            else -> getString(io.github.dorumrr.de1984.R.string.filter_profile_all)
        }
    }

    private fun showProtectionSnackbar(dialog: BottomSheetDialog) {
        val parentView = dialog.window?.decorView ?: requireView()
        Snackbar.make(
            parentView,
            getString(R.string.snackbar_firewall_protected),
            Snackbar.LENGTH_LONG
        ).setAction(getString(R.string.snackbar_action_settings)) {
            dialog.dismiss()
            (requireActivity() as? io.github.dorumrr.de1984.ui.MainActivity)?.navigateToSettings()
        }.show()
    }


    private fun setupSelectionToolbar() {
        binding.selectionToolbar.setNavigationOnClickListener {
            exitSelectionMode()
        }

        binding.rulesButton.setOnClickListener {
            if (selectedPackages.isNotEmpty()) {
                showMultiSelectRulesSheet()
            }
        }
    }

    private fun setupBackPressHandler() {
        backPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
                    exitSelectionMode()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback!!)
    }

    private fun onPackageLongClick(pkg: NetworkPackage): Boolean {
        AppLogger.d(TAG, "🔘 Long click on package: ${pkg.packageName}")

        if (!adapter.canSelectPackage(pkg, requireContext())) {
            Toast.makeText(
                requireContext(),
                getString(R.string.firewall_multiselect_toast_cannot_select_critical),
                Toast.LENGTH_SHORT
            ).show()
            return true
        }

        if (!isSelectionMode) {
            enterSelectionMode()
        }

        adapter.selectPackage(pkg.id)
        return true
    }

    private fun enterSelectionMode() {
        AppLogger.d(TAG, "🔘 Entering selection mode")
        isSelectionMode = true
        adapter.setSelectionMode(true)
        binding.selectionToolbar.visibility = View.VISIBLE
        backPressedCallback?.isEnabled = true
        updateSelectionToolbar()
    }

    private fun exitSelectionMode() {
        AppLogger.d(TAG, "🔘 Exiting selection mode")
        isSelectionMode = false
        selectedPackages.clear()
        adapter.setSelectionMode(false)
        binding.selectionToolbar.visibility = View.GONE
        backPressedCallback?.isEnabled = false
    }

    private fun updateSelectionToolbar() {
        val count = selectedPackages.size
        binding.selectionCount.text = getString(R.string.multiselect_toolbar_title_format, count)
    }

    private fun showBatchResultDialog(result: io.github.dorumrr.de1984.presentation.viewmodel.BatchBlockResult) {
        val actionName = if (result.wasBlocking) {
            getString(R.string.firewall_multiselect_toolbar_button_block).lowercase()
        } else {
            getString(R.string.firewall_multiselect_toolbar_button_allow).lowercase()
        }

        val message = if (result.failed.isEmpty()) {
            getString(R.string.firewall_multiselect_dialog_message_success_format, result.succeeded.size, actionName)
        } else {
            getString(R.string.firewall_multiselect_dialog_message_failed_format, result.succeeded.size, result.failed.size, actionName)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.firewall_multiselect_dialog_title_results))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }


    private enum class MultiSelectToggleState {
        ALL_BLOCKED,
        ALL_ALLOWED,
        MIXED
    }

    private fun calculateToggleState(
        packages: List<NetworkPackage>,
        getBlockedState: (NetworkPackage) -> Boolean
    ): MultiSelectToggleState {
        if (packages.isEmpty()) return MultiSelectToggleState.ALL_ALLOWED

        val blockedCount = packages.count { getBlockedState(it) }
        return when {
            blockedCount == packages.size -> MultiSelectToggleState.ALL_BLOCKED
            blockedCount == 0 -> MultiSelectToggleState.ALL_ALLOWED
            else -> MultiSelectToggleState.MIXED
        }
    }

    private fun showMultiSelectRulesSheet() {
        val dialog = BottomSheetDialog(requireContext())
        currentDialog = dialog

        val sheetBinding = BottomSheetFirewallMultiselectBinding.inflate(layoutInflater)

        val allPackages = viewModel.uiState.value.packages
        val selectedPkgs = allPackages.filter { selectedPackages.contains(it.id) }

        if (selectedPkgs.isEmpty()) {
            dialog.dismiss()
            return
        }

        sheetBinding.multiselectHeader.text = getString(R.string.firewall_multiselect_sheet_header_format, selectedPkgs.size)

        val telephonyManager = requireContext().getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasCellular = telephonyManager?.phoneType != TelephonyManager.PHONE_TYPE_NONE

        val app = requireActivity().application as De1984Application
        val backendType = app.dependencies.firewallManager.activeBackendType.value
        val isIptablesBackend = backendType == FirewallBackendType.IPTABLES
        val granular = supportsGranularControl()

        // One switch per app, not one per network. The WiFi row is reused as the single "Internet
        // Access" toggle and the rest are hidden, matching the single-app sheet and FIREWALL.md
        // section 3: "the UI should NOT show separate WiFi/Mobile/Roaming switches". See issue #72.
        if (!granular) {
            sheetBinding.mobileDivider.visibility = View.GONE
            sheetBinding.mobileToggle.root.visibility = View.GONE
        }

        val wifiState = if (!granular) {
            calculateToggleState(selectedPkgs) { it.wifiBlocked || it.mobileBlocked || it.roamingBlocked }
        } else {
            calculateToggleState(selectedPkgs) { it.wifiBlocked }
        }
        val mobileState = calculateToggleState(selectedPkgs) { it.mobileBlocked }
        val roamingState = calculateToggleState(selectedPkgs) { it.roamingBlocked }
        val lanState = calculateToggleState(selectedPkgs) { it.lanBlocked }

        setupMultiSelectToggleInitial(
            binding = sheetBinding.wifiToggle,
            label = if (granular) {
                getString(R.string.firewall_network_label_wifi)
            } else {
                getString(R.string.firewall_network_label_internet_access)
            },
            state = wifiState
        )

        if (granular) {
            setupMultiSelectToggleInitial(
                binding = sheetBinding.mobileToggle,
                label = getString(R.string.firewall_network_label_mobile),
                state = mobileState
            )
        }

        if (hasCellular && granular) {
            sheetBinding.roamingDivider.visibility = View.VISIBLE
            sheetBinding.roamingToggle.root.visibility = View.VISIBLE
            setupMultiSelectToggleInitial(
                binding = sheetBinding.roamingToggle,
                label = getString(R.string.firewall_network_label_roaming),
                state = roamingState
            )
        }

        if (isIptablesBackend) {
            sheetBinding.lanDivider.visibility = View.VISIBLE
            sheetBinding.lanToggle.root.visibility = View.VISIBLE
            setupMultiSelectToggleInitial(
                binding = sheetBinding.lanToggle,
                label = getString(R.string.firewall_network_label_lan),
                state = lanState
            )
        }

        sheetBinding.allowAllButton.setOnClickListener {
            viewModel.batchAllowPackages(selectedPackages.toList())
            dialog.dismiss()
            exitSelectionMode()
        }

        sheetBinding.blockAllButton.setOnClickListener {
            viewModel.batchBlockPackages(selectedPackages.toList())
            dialog.dismiss()
            exitSelectionMode()
        }

        var isUpdatingProgrammatically = false

        fun updateTogglesFromPackages(packages: List<NetworkPackage>) {
            if (packages.isEmpty()) return
            isUpdatingProgrammatically = true

            val newWifiState = if (!granular) {
                calculateToggleState(packages) { it.wifiBlocked || it.mobileBlocked || it.roamingBlocked }
            } else {
                calculateToggleState(packages) { it.wifiBlocked }
            }
            val newMobileState = calculateToggleState(packages) { it.mobileBlocked }
            val newRoamingState = calculateToggleState(packages) { it.roamingBlocked }
            val newLanState = calculateToggleState(packages) { it.lanBlocked }

            updateMultiSelectToggleState(sheetBinding.wifiToggle, newWifiState)

            if (granular) {
                updateMultiSelectToggleState(sheetBinding.mobileToggle, newMobileState)
            }

            if (hasCellular && granular) {
                updateMultiSelectToggleState(sheetBinding.roamingToggle, newRoamingState)
            }

            if (isIptablesBackend) {
                updateMultiSelectToggleState(sheetBinding.lanToggle, newLanState)
            }

            isUpdatingProgrammatically = false
        }

        fun getSelectedPackagePairs(): List<Pair<String, Int>> {
            return selectedPackages.map { it.packageName to it.userId }
        }

        sheetBinding.wifiToggle.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
            sheetBinding.wifiToggle.networkTypeSubtitle.visibility = View.GONE
            updateSwitchColors(sheetBinding.wifiToggle.toggleSwitch, isChecked)
            if (granular) {
                viewModel.batchSetWifiBlocking(getSelectedPackagePairs(), isChecked)
            } else {
                viewModel.batchSetAllNetworkBlocking(getSelectedPackagePairs(), isChecked)
            }
        }

        if (granular) {
            sheetBinding.mobileToggle.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
                sheetBinding.mobileToggle.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(sheetBinding.mobileToggle.toggleSwitch, isChecked)
                viewModel.batchSetMobileBlocking(getSelectedPackagePairs(), isChecked)
            }
        }

        if (hasCellular && granular) {
            sheetBinding.roamingToggle.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
                sheetBinding.roamingToggle.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(sheetBinding.roamingToggle.toggleSwitch, isChecked)
                viewModel.batchSetRoamingBlocking(getSelectedPackagePairs(), isChecked)
            }
        }

        if (isIptablesBackend) {
            sheetBinding.lanToggle.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
                sheetBinding.lanToggle.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(sheetBinding.lanToggle.toggleSwitch, isChecked)
                viewModel.batchSetLanBlocking(getSelectedPackagePairs(), isChecked)
            }
        }

        // The sheet's own record of the selection, seeded with everything the user picked.
        //
        // It is MERGED on each emit, never rebuilt by filtering. state.packages is the FILTERED
        // list, so a package that leaves the filter - which is exactly what happens when a toggle
        // in this sheet changes its blocked state under a Blocked/Allowed filter - vanishes from
        // it. Rebuilding by filter then recomputed the toggles from whatever survived, so the
        // switches showed the state of PART of the selection while every tap still applied to ALL
        // of it, via getSelectedPackagePairs().
        val trackedSelection = LinkedHashMap<PackageId, NetworkPackage>().apply {
            selectedPkgs.forEach { put(it.id, it) }
        }

        val observerJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                var changed = false
                val seenThisEmit = mutableSetOf<PackageId>()
                state.packages.forEach { pkg ->
                    if (trackedSelection.containsKey(pkg.id)) {
                        if (trackedSelection[pkg.id] != pkg) changed = true
                        trackedSelection[pkg.id] = pkg
                        seenThisEmit.add(pkg.id)
                    }
                }

                // Aggregate over what we can still SEE. A selected app that has dropped out of the
                // filtered list - which is exactly what a toggle in this sheet does under a
                // Blocked/Allowed filter - would otherwise keep voting with its last-seen values and
                // drag the switch the user just moved back to "Mixed". Writes are unaffected: they go
                // through getSelectedPackagePairs(), which reads the full selection.
                val visible = trackedSelection.filterKeys { it in seenThisEmit }.values.toList()

                if (changed && visible.isNotEmpty() && !isUpdatingProgrammatically) {
                    AppLogger.d(TAG, "showMultiSelectRulesSheet: uiState collected - updating from ${visible.size} of ${trackedSelection.size} selected")
                    updateTogglesFromPackages(visible)
                }
            }
        }

        dialog.setOnDismissListener {
            AppLogger.d(TAG, "showMultiSelectRulesSheet: Dialog dismissed, cancelling observer")
            observerJob.cancel()
            if (currentDialog == dialog) {
                currentDialog = null
            }
        }

        dialog.setContentView(sheetBinding.root)
        dialog.show()
    }

    private fun updateMultiSelectToggleState(
        binding: NetworkTypeToggleBinding,
        state: MultiSelectToggleState
    ) {
        when (state) {
            MultiSelectToggleState.ALL_BLOCKED -> {
                binding.toggleSwitch.isChecked = true
                binding.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(binding.toggleSwitch, true)
            }
            MultiSelectToggleState.ALL_ALLOWED -> {
                binding.toggleSwitch.isChecked = false
                binding.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(binding.toggleSwitch, false)
            }
            MultiSelectToggleState.MIXED -> {
                binding.toggleSwitch.isChecked = false
                binding.networkTypeSubtitle.visibility = View.VISIBLE
                binding.networkTypeSubtitle.text = getString(R.string.firewall_multiselect_sheet_state_mixed)
                updateSwitchColors(binding.toggleSwitch, false)
            }
        }
    }

    /**
     * Setup a network toggle for multi-select mode - initial state only (no listener).
     * Listener is added separately to support the isUpdatingProgrammatically flag.
     */
    private fun setupMultiSelectToggleInitial(
        binding: NetworkTypeToggleBinding,
        label: String,
        state: MultiSelectToggleState
    ) {
        binding.networkTypeLabel.text = label
        binding.labelLeft.text = getString(R.string.firewall_state_allowed)
        binding.labelRight.text = getString(R.string.firewall_state_blocked)
        binding.toggleSwitch.isEnabled = true

        when (state) {
            MultiSelectToggleState.ALL_BLOCKED -> {
                binding.toggleSwitch.isChecked = true
                binding.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(binding.toggleSwitch, true)
            }
            MultiSelectToggleState.ALL_ALLOWED -> {
                binding.toggleSwitch.isChecked = false
                binding.networkTypeSubtitle.visibility = View.GONE
                updateSwitchColors(binding.toggleSwitch, false)
            }
            MultiSelectToggleState.MIXED -> {
                binding.toggleSwitch.isChecked = false
                binding.networkTypeSubtitle.visibility = View.VISIBLE
                binding.networkTypeSubtitle.text = getString(R.string.firewall_multiselect_sheet_state_mixed)
                updateSwitchColors(binding.toggleSwitch, false)
            }
        }
    }

    /**
     * ConnectivityManager and NetworkPolicyManager have one switch per app, not one per network.
     * On those the row's WiFi/Mobile/Roaming icons cannot mean three separate things, so a tap on
     * any of them has to act on all three - the same thing the bottom sheet's single "Internet
     * Access" switch does. See issue #72.
     */
    private fun supportsGranularControl(): Boolean {
        val app = requireActivity().application as De1984Application
        return app.dependencies.firewallManager.supportsGranularControl()
    }

    private fun handleQuickToggle(pkg: NetworkPackage, networkType: NetworkType) {
        val prefs = requireContext().getSharedPreferences(
            Constants.Settings.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val confirmRuleChanges = prefs.getBoolean(
            Constants.Settings.KEY_CONFIRM_RULE_CHANGES,
            Constants.Settings.DEFAULT_CONFIRM_RULE_CHANGES
        )

        // Read once and pass it down. Evaluated again at each step, the confirmation dialog could
        // describe one action and the OK button perform another - the backend can change between
        // showing the dialog and pressing it.
        val granular = supportsGranularControl()

        val isCurrentlyBlocked = if (!granular) {
            pkg.wifiBlocked || pkg.mobileBlocked || pkg.roamingBlocked
        } else when (networkType) {
            NetworkType.WIFI -> pkg.wifiBlocked
            NetworkType.MOBILE -> pkg.mobileBlocked
            NetworkType.ROAMING -> pkg.roamingBlocked
        }
        val willBlock = !isCurrentlyBlocked

        if (confirmRuleChanges) {
            showQuickToggleConfirmationDialog(pkg, networkType, willBlock, granular)
        } else {
            executeQuickToggle(pkg, networkType, willBlock, showSnackbar = true, granular = granular)
        }
    }

    private fun showQuickToggleConfirmationDialog(
        pkg: NetworkPackage,
        networkType: NetworkType,
        willBlock: Boolean,
        granular: Boolean
    ) {
        val networkTypeName = if (!granular) {
            getString(R.string.firewall_network_label_internet_access)
        } else when (networkType) {
            NetworkType.WIFI -> getString(R.string.firewall_network_label_wifi)
            NetworkType.MOBILE -> getString(R.string.firewall_network_label_mobile)
            NetworkType.ROAMING -> getString(R.string.firewall_network_label_roaming)
        }
        val title = if (willBlock) {
            getString(R.string.dialog_quick_toggle_block_title, networkTypeName)
        } else {
            getString(R.string.dialog_quick_toggle_allow_title, networkTypeName)
        }

        val action = if (willBlock) {
            getString(R.string.dialog_quick_toggle_action_block)
        } else {
            getString(R.string.dialog_quick_toggle_action_allow)
        }

        val message = if (!granular) {
            getString(R.string.dialog_quick_toggle_message_internet, action, pkg.name)
        } else when (networkType) {
            NetworkType.WIFI -> getString(R.string.dialog_quick_toggle_message_wifi, action, pkg.name)
            NetworkType.MOBILE -> getString(R.string.dialog_quick_toggle_message_mobile, action, pkg.name)
            NetworkType.ROAMING -> getString(R.string.dialog_quick_toggle_message_roaming, action, pkg.name)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                executeQuickToggle(pkg, networkType, willBlock, showSnackbar = false, granular = granular)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun executeQuickToggle(
        pkg: NetworkPackage,
        networkType: NetworkType,
        willBlock: Boolean,
        showSnackbar: Boolean,
        granular: Boolean
    ) {
        AppLogger.d(TAG, "🔘 QUICK TOGGLE: ${networkType.name} for ${pkg.packageName} - willBlock: $willBlock")

        if (!granular) {
            viewModel.setAllNetworkBlocking(pkg.packageName, pkg.userId, willBlock)
        } else when (networkType) {
            NetworkType.WIFI -> viewModel.setWifiBlocking(pkg.packageName, pkg.userId, willBlock)
            NetworkType.MOBILE -> viewModel.setMobileBlocking(pkg.packageName, pkg.userId, willBlock)
            NetworkType.ROAMING -> viewModel.setRoamingBlocking(pkg.packageName, pkg.userId, willBlock)
        }

        if (showSnackbar) {
            showQuickToggleSnackbar(pkg, networkType, willBlock, granular)
        }
    }

    private fun showQuickToggleSnackbar(
        pkg: NetworkPackage,
        networkType: NetworkType,
        wasBlocked: Boolean,
        granular: Boolean
    ) {
        val message = if (!granular) {
            if (wasBlocked) {
                getString(R.string.snackbar_internet_blocked, pkg.name)
            } else {
                getString(R.string.snackbar_internet_allowed, pkg.name)
            }
        } else when (networkType) {
            NetworkType.WIFI -> if (wasBlocked) {
                getString(R.string.snackbar_wifi_blocked, pkg.name)
            } else {
                getString(R.string.snackbar_wifi_allowed, pkg.name)
            }
            NetworkType.MOBILE -> if (wasBlocked) {
                getString(R.string.snackbar_mobile_blocked, pkg.name)
            } else {
                getString(R.string.snackbar_mobile_allowed, pkg.name)
            }
            NetworkType.ROAMING -> if (wasBlocked) {
                getString(R.string.snackbar_roaming_blocked, pkg.name)
            } else {
                getString(R.string.snackbar_roaming_allowed, pkg.name)
            }
        }

        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(getString(R.string.snackbar_undo)) {
                AppLogger.d(TAG, "🔄 UNDO QUICK TOGGLE: ${networkType.name} for ${pkg.packageName}")
                if (!granular) {
                    viewModel.setAllNetworkBlocking(pkg.packageName, pkg.userId, !wasBlocked)
                } else when (networkType) {
                    NetworkType.WIFI -> viewModel.setWifiBlocking(pkg.packageName, pkg.userId, !wasBlocked)
                    NetworkType.MOBILE -> viewModel.setMobileBlocking(pkg.packageName, pkg.userId, !wasBlocked)
                    NetworkType.ROAMING -> viewModel.setRoamingBlocking(pkg.packageName, pkg.userId, !wasBlocked)
                }
            }
            .show()
    }

    companion object {
        private const val TAG = "FirewallFragmentViews"
    }
}

