package io.github.dorumrr.de1984.ui.common

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import io.github.dorumrr.de1984.R

object FilterChipsHelper {

    private const val TAG = "FilterChipsHelper"

    private var isUpdatingProgrammatically = false

    /**
     * Detach every chip's listener, THEN empty the group.
     *
     * A checked Chip fires `onCheckedChange(false)` as it is removed, and these listeners report
     * that straight back to the ViewModel as a user action. So rebuilding the row - which happens
     * whenever the work/clone profile flags arrive, i.e. on every fragment creation - silently
     * turned the "Internet" filter OFF.
     *
     * It was survivable while filters lived only in memory. Once they were persisted (issue #71) the
     * bogus value was written to disk, so one activity rebuild - a language change, dark mode -
     * permanently cleared a filter the user had set. Measured on hardware: pref `true` before a
     * rebuild, `false` after.
     */
    /**
     * Build chips without any of it looking like a user tapping something.
     *
     * Adding an already-checked Chip to a ChipGroup makes the group run its own checking logic, and
     * that fires `onCheckedChange` on a listener that is already attached. Those listeners report
     * straight to the ViewModel as user actions, so simply CREATING the row reported
     * "Internet-only filter changed: false" - captured verbatim in logcat.
     *
     * Harmless while filters lived only in memory. Once they were persisted (issue #71) that bogus
     * value went to disk, so one activity rebuild - a language change, dark mode - permanently
     * cleared a filter the user had set. Measured: pref `true` before a rebuild, `false` after.
     *
     * try/finally because a throw mid-build would otherwise leave every later real tap ignored.
     */
    private inline fun buildingChips(block: () -> Unit) {
        isUpdatingProgrammatically = true
        try {
            block()
        } finally {
            isUpdatingProgrammatically = false
        }
    }

    private fun ChipGroup.clearChips() {
        for (i in 0 until childCount) {
            (getChildAt(i) as? Chip)?.setOnCheckedChangeListener(null)
        }
        removeAllViews()
    }

    
    fun setupFilterChips(
        chipGroup: ChipGroup,
        filters: List<String>,
        selectedFilter: String?,
        onFilterSelected: (String) -> Unit
    ) {
        buildingChips {
            chipGroup.clearChips()
        
            filters.forEach { filter ->
                val chip = LayoutInflater.from(chipGroup.context)
                    .inflate(R.layout.filter_chip_item, chipGroup, false) as Chip
                chip.disableViewStateRestore()
            
                chip.text = filter
                chip.isChecked = filter == selectedFilter
                chipGroup.addView(chip)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        onFilterSelected(filter)
                    }
                }
            
            }
        
            chipGroup.isSingleSelection = false
            }
}
    
    fun setupMultiSelectFilterChips(
        chipGroup: ChipGroup,
        typeFilters: List<String>,
        stateFilters: List<String>,
        permissionFilters: List<String>,
        profileFilters: List<String> = emptyList(),
        selectedTypeFilter: String?,
        selectedStateFilter: String?,
        selectedPermissionFilter: Boolean,
        selectedProfileFilter: String? = null,
        onTypeFilterSelected: (String) -> Unit,
        onStateFilterSelected: (String?) -> Unit,
        onPermissionFilterSelected: (Boolean) -> Unit,
        onProfileFilterSelected: (String) -> Unit = {}
    ) {
        buildingChips {
            chipGroup.clearChips()

            typeFilters.forEach { filter ->
                val chip = createFilterChip(chipGroup, filter, filter == selectedTypeFilter)
                chip.tag = "type:$filter"
                chipGroup.addView(chip)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isUpdatingProgrammatically) {
                        return@setOnCheckedChangeListener
                    }

                    if (isChecked) {
                        val clickedFilter = chip.tag.toString().removePrefix("type:")

                        isUpdatingProgrammatically = true

                        for (i in 0 until chipGroup.childCount) {
                            val otherChip = chipGroup.getChildAt(i) as? Chip
                            if (otherChip != null &&
                                otherChip.tag.toString().startsWith("type:") &&
                                otherChip != chip) {
                                otherChip.isChecked = false
                            }
                        }

                        isUpdatingProgrammatically = false

                        onTypeFilterSelected(clickedFilter)
                    } else {
                        isUpdatingProgrammatically = true
                        chip.isChecked = true
                        isUpdatingProgrammatically = false
                    }
                }
            }

            permissionFilters.forEach { filter ->
                val chip = createFilterChip(chipGroup, filter, selectedPermissionFilter)
                chip.tag = "permission:$filter"
                chipGroup.addView(chip)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isUpdatingProgrammatically) {
                        return@setOnCheckedChangeListener
                    }

                    onPermissionFilterSelected(isChecked)
                }
            }

            stateFilters.forEach { filter ->
                val chip = createFilterChip(chipGroup, filter, filter == selectedStateFilter)
                chip.tag = "state:$filter"
                chipGroup.addView(chip)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isUpdatingProgrammatically) {
                        return@setOnCheckedChangeListener
                    }

                    val clickedFilter = chip.tag.toString().removePrefix("state:")
                    if (isChecked) {
                        isUpdatingProgrammatically = true

                        for (i in 0 until chipGroup.childCount) {
                            val otherChip = chipGroup.getChildAt(i) as? Chip
                            if (otherChip != null &&
                                otherChip.tag.toString().startsWith("state:") &&
                                otherChip != chip) {
                                otherChip.isChecked = false
                            }
                        }

                        isUpdatingProgrammatically = false

                        onStateFilterSelected(clickedFilter)
                    } else {
                        onStateFilterSelected(null)
                    }
                }
            }

            val defaultProfileFilter = profileFilters.firstOrNull() ?: ""

            profileFilters.forEach { filter ->
                val chip = createFilterChip(chipGroup, filter, filter == selectedProfileFilter)
                chip.tag = "profile:$filter"
                chipGroup.addView(chip)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isUpdatingProgrammatically) {
                        return@setOnCheckedChangeListener
                    }

                    val clickedFilter = chip.tag.toString().removePrefix("profile:")

                    if (isChecked) {
                        isUpdatingProgrammatically = true

                        for (i in 0 until chipGroup.childCount) {
                            val otherChip = chipGroup.getChildAt(i) as? Chip
                            if (otherChip != null &&
                                otherChip.tag.toString().startsWith("profile:") &&
                                otherChip != chip) {
                                otherChip.isChecked = false
                            }
                        }

                        isUpdatingProgrammatically = false

                        onProfileFilterSelected(clickedFilter)
                    } else {
                        if (clickedFilter == defaultProfileFilter) {
                            isUpdatingProgrammatically = true
                            chip.isChecked = true
                            isUpdatingProgrammatically = false
                        } else {
                            isUpdatingProgrammatically = true

                            for (i in 0 until chipGroup.childCount) {
                                val otherChip = chipGroup.getChildAt(i) as? Chip
                                if (otherChip != null &&
                                    otherChip.tag.toString() == "profile:$defaultProfileFilter") {
                                    otherChip.isChecked = true
                                    break
                                }
                            }

                            isUpdatingProgrammatically = false

                            onProfileFilterSelected(defaultProfileFilter)
                        }
                    }
                }
            }
            }
}



    fun updateMultiSelectFilterChips(
        chipGroup: ChipGroup,
        selectedTypeFilter: String?,
        selectedStateFilter: String?,
        selectedPermissionFilter: Boolean,
        selectedProfileFilter: String? = null
    ) {
        isUpdatingProgrammatically = true

        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            val tag = chip.tag as? String ?: continue

            when {
                tag.startsWith("type:") -> {
                    val filterName = tag.removePrefix("type:")
                    val shouldBeChecked = filterName == selectedTypeFilter
                    chip.isChecked = shouldBeChecked
                }
                tag.startsWith("state:") -> {
                    val filterName = tag.removePrefix("state:")
                    val shouldBeChecked = filterName == selectedStateFilter
                    chip.isChecked = shouldBeChecked
                }
                tag.startsWith("permission:") -> {
                    chip.isChecked = selectedPermissionFilter
                }
                tag.startsWith("profile:") -> {
                    val filterName = tag.removePrefix("profile:")
                    val shouldBeChecked = filterName == selectedProfileFilter
                    chip.isChecked = shouldBeChecked
                }
            }
        }

        isUpdatingProgrammatically = false
    }
    
    /**
     * Stop a chip taking part in view-hierarchy state save/restore.
     *
     * THE root cause of the filter resetting itself, found from a captured stack rather than a
     * guess. On an activity rebuild Android walks the saved view tree:
     *
     *   ViewGroup.dispatchRestoreInstanceState -> CompoundButton.onRestoreInstanceState ->
     *   Chip.setChecked -> onCheckedChanged -> onPermissionFilterSelected(false)
     *
     * The listener cannot tell that apart from a tap, so it reported it to the ViewModel as a user
     * action. Worse, every chip is inflated from the same layout and therefore shares the id
     * `filter_chip`, so the restored state is applied to the wrong chips anyway.
     *
     * These chips are not the source of truth - the ViewModel is, and since issue #71 it persists to
     * SharedPreferences. So the view tree restoring them was not merely wrong on screen, it
     * overwrote what was saved on disk. Measured: pref `true` before a rebuild, `false` after.
     *
     * Three earlier attempts missed this because they all assumed the callback came from building
     * the chips - detaching listeners before removal, guarding construction with a flag, and adding
     * the view before attaching its listener. None of them touch state restore.
     */
    private fun Chip.disableViewStateRestore() {
        isSaveEnabled = false
    }

    private fun createFilterChip(
        chipGroup: ChipGroup,
        label: String,
        isChecked: Boolean
    ): Chip {
        val chip = LayoutInflater.from(chipGroup.context)
            .inflate(R.layout.filter_chip_item, chipGroup, false) as Chip
        chip.disableViewStateRestore()
        chip.text = label
        chip.isChecked = isChecked
        return chip
    }
    
    private fun updateChipSelection(
        chipGroup: ChipGroup,
        typeFilters: List<String>,
        selectedFilter: String
    ) {
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip
            if (chip != null && typeFilters.contains(chip.text.toString())) {
                chip.isChecked = chip.text == selectedFilter
            }
        }
    }
}

