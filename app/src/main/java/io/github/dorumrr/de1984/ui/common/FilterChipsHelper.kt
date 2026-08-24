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
    
    fun setupFilterChips(
        chipGroup: ChipGroup,
        filters: List<String>,
        selectedFilter: String?,
        onFilterSelected: (String) -> Unit
    ) {
        chipGroup.removeAllViews()
        
        filters.forEach { filter ->
            val chip = LayoutInflater.from(chipGroup.context)
                .inflate(R.layout.filter_chip_item, chipGroup, false) as Chip
            
            chip.text = filter
            chip.isChecked = filter == selectedFilter
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    onFilterSelected(filter)
                }
            }
            
            chipGroup.addView(chip)
        }
        
        chipGroup.isSingleSelection = false
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
        chipGroup.removeAllViews()

        typeFilters.forEach { filter ->
            val chip = createFilterChip(chipGroup, filter, filter == selectedTypeFilter)
            chip.tag = "type:$filter"
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
            chipGroup.addView(chip)
        }

        permissionFilters.forEach { filter ->
            val chip = createFilterChip(chipGroup, filter, selectedPermissionFilter)
            chip.tag = "permission:$filter"
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingProgrammatically) {
                    return@setOnCheckedChangeListener
                }

                onPermissionFilterSelected(isChecked)
            }
            chipGroup.addView(chip)
        }

        stateFilters.forEach { filter ->
            val chip = createFilterChip(chipGroup, filter, filter == selectedStateFilter)
            chip.tag = "state:$filter"
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
            chipGroup.addView(chip)
        }

        val defaultProfileFilter = profileFilters.firstOrNull() ?: ""

        profileFilters.forEach { filter ->
            val chip = createFilterChip(chipGroup, filter, filter == selectedProfileFilter)
            chip.tag = "profile:$filter"
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
            chipGroup.addView(chip)
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
    
    private fun createFilterChip(
        chipGroup: ChipGroup,
        label: String,
        isChecked: Boolean
    ): Chip {
        val chip = LayoutInflater.from(chipGroup.context)
            .inflate(R.layout.filter_chip_item, chipGroup, false) as Chip
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

