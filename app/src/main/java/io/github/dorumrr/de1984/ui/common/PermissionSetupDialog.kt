package io.github.dorumrr.de1984.ui.common

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.PermissionInfo
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.databinding.DialogPermissionSetupBinding
import io.github.dorumrr.de1984.databinding.PermissionTierSectionBinding
import io.github.dorumrr.de1984.utils.Constants

object PermissionSetupDialog {

    fun show(
        context: Context,
        title: String = context.getString(R.string.privileged_access_dialog_title),
        tierTitle: String,
        description: String,
        status: String,
        isComplete: Boolean = false,
        buttonText: String,
        onButtonClick: () -> Unit,
        onDismiss: () -> Unit = {}
    ) {
        val dialogBinding = DialogPermissionSetupBinding.inflate(LayoutInflater.from(context))
        val binding = dialogBinding.permissionTierSection

        dialogBinding.dialogTitle.text = title

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogBinding.root)
            .setOnDismissListener { onDismiss() }
            .setCancelable(true)
            .create()

        setupPermissionTier(
            binding = binding,
            title = tierTitle,
            description = description,
            status = status,
            isComplete = isComplete,
            setupButtonText = buttonText,
            onSetupClick = {
                dialog.dismiss()
                onButtonClick()
            }
        )

        dialog.show()
    }

    fun showPackageManagementDialog(
        context: Context,
        rootStatus: RootStatus,
        shizukuStatus: ShizukuStatus,
        onGrantClick: () -> Unit,
        onSettingsClick: () -> Unit,
        onDismiss: () -> Unit = {}
    ) {
        when {
            shizukuStatus == ShizukuStatus.NOT_INSTALLED && rootStatus == RootStatus.NOT_ROOTED -> {
                show(
                    context = context,
                    tierTitle = context.getString(R.string.privileged_access_tier_title),
                    description = context.getString(R.string.privileged_access_banner_no_access),
                    status = context.getString(R.string.privileged_access_status_setup_required),
                    buttonText = context.getString(R.string.privileged_access_banner_button_settings),
                    onButtonClick = onSettingsClick,
                    onDismiss = onDismiss
                )
            }
            shizukuStatus == ShizukuStatus.INSTALLED_NOT_RUNNING && rootStatus == RootStatus.NOT_ROOTED -> {
                show(
                    context = context,
                    tierTitle = context.getString(R.string.privileged_access_tier_title),
                    description = context.getString(R.string.privileged_access_banner_shizuku_not_running),
                    status = context.getString(R.string.privileged_access_status_setup_required),
                    buttonText = context.getString(R.string.privileged_access_banner_button_settings),
                    onButtonClick = onSettingsClick,
                    onDismiss = onDismiss
                )
            }
            shizukuStatus == ShizukuStatus.RUNNING_NO_PERMISSION ||
            rootStatus == RootStatus.ROOTED_NO_PERMISSION -> {
                show(
                    context = context,
                    tierTitle = context.getString(R.string.privileged_access_tier_title),
                    description = context.getString(R.string.privileged_access_banner_permission_required),
                    status = context.getString(R.string.privileged_access_status_permission_required),
                    buttonText = context.getString(R.string.privileged_access_banner_button_grant),
                    onButtonClick = onGrantClick,
                    onDismiss = onDismiss
                )
            }
            else -> {
                show(
                    context = context,
                    tierTitle = context.getString(R.string.privileged_access_tier_title),
                    description = context.getString(R.string.privileged_access_banner_permission_required),
                    status = context.getString(R.string.privileged_access_status_setup_required),
                    buttonText = context.getString(R.string.privileged_access_banner_button_settings),
                    onButtonClick = onSettingsClick,
                    onDismiss = onDismiss
                )
            }
        }
    }

    private fun setupPermissionTier(
        binding: PermissionTierSectionBinding,
        title: String,
        description: String,
        status: String,
        isComplete: Boolean,
        setupButtonText: String,
        onSetupClick: () -> Unit
    ) {
        binding.tierTitle.text = title
        binding.tierDescription.text = description

        binding.tierStatusBadge.text = status
        binding.tierStatusBadge.setBackgroundResource(
            if (isComplete) R.drawable.status_badge_complete
            else R.drawable.status_badge_background
        )

        binding.permissionsListContainer.removeAllViews()
        binding.permissionsListContainer.visibility = android.view.View.GONE

        binding.setupButtonContainer.visibility = android.view.View.VISIBLE
        binding.setupButton.text = setupButtonText
        binding.setupButton.setOnClickListener { onSetupClick() }

        binding.rootStatusContainer.visibility = android.view.View.GONE
        binding.rootingToolsContainer.visibility = android.view.View.GONE
    }
}
