package io.github.dorumrr.de1984.ui.common

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object StandardDialog {

    fun show(
        context: Context,
        title: String,
        message: String,
        positiveButtonText: String,
        onPositiveClick: () -> Unit = {},
        negativeButtonText: String? = null,
        onNegativeClick: (() -> Unit)? = null,
        cancelable: Boolean = true,
        onDismiss: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ) {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButtonText) { _, _ -> onPositiveClick() }
            .setCancelable(cancelable)

        if (negativeButtonText != null) {
            builder.setNegativeButton(negativeButtonText) { _, _ ->
                onNegativeClick?.invoke()
            }
        }

        // Dismissing by tapping outside or pressing Back runs onCancel, so callers that revert UI
        // state (switches, dropdowns) are not left showing a state that was never applied.
        //
        // This is NEVER inferred from onNegativeClick. Some callers put a second ACTION in the
        // negative slot rather than a cancel - showRestoreOptions uses it for "Replace All" - and
        // treating that as cancel would run a destructive action when the user tries to escape.
        // Callers must say explicitly what cancelling means.
        //
        // setOnCancelListener fires only for back/outside dismissal. Button presses go through
        // dismiss(), which never calls cancel(), so no button can trigger this.
        if (cancelable && onCancel != null) {
            builder.setOnCancelListener { onCancel() }
        }

        if (onDismiss != null) {
            builder.setOnDismissListener { onDismiss() }
        }

        builder.show()
    }

    fun showError(
        context: Context,
        message: String,
        title: String = context.getString(io.github.dorumrr.de1984.R.string.dialog_error_title),
        onDismiss: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = title,
            message = message,
            positiveButtonText = context.getString(io.github.dorumrr.de1984.R.string.dialog_ok),
            onPositiveClick = { onDismiss?.invoke() },
            cancelable = true,
            onDismiss = onDismiss
        )
    }

    fun showInfo(
        context: Context,
        title: String,
        message: String,
        onDismiss: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = title,
            message = message,
            positiveButtonText = context.getString(io.github.dorumrr.de1984.R.string.dialog_ok),
            onPositiveClick = { onDismiss?.invoke() },
            cancelable = true,
            onDismiss = onDismiss
        )
    }

    fun showConfirmation(
        context: Context,
        title: String,
        message: String,
        confirmButtonText: String = "Confirm",
        onConfirm: () -> Unit,
        cancelButtonText: String = "Cancel",
        onCancel: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = title,
            message = message,
            positiveButtonText = confirmButtonText,
            onPositiveClick = onConfirm,
            negativeButtonText = cancelButtonText,
            onNegativeClick = onCancel,
            cancelable = true,
            // In a confirmation the negative button genuinely IS cancel, so tapping outside or
            // pressing Back should do the same thing as pressing it.
            onCancel = onCancel
        )
    }

    fun showNoAccessDialog(
        context: Context,
        onDismiss: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = context.getString(io.github.dorumrr.de1984.R.string.dialog_privileged_access_title),
            message = context.getString(io.github.dorumrr.de1984.R.string.dialog_no_access_message),
            positiveButtonText = context.getString(io.github.dorumrr.de1984.R.string.dialog_ok),
            onPositiveClick = { onDismiss?.invoke() },
            cancelable = true,
            onDismiss = onDismiss
        )
    }

    fun showRootDeniedDialog(
        context: Context,
        onOkClick: (() -> Unit)? = null
    ) {
        show(
            context = context,
            title = context.getString(io.github.dorumrr.de1984.R.string.dialog_privileged_access_title),
            message = context.getString(io.github.dorumrr.de1984.R.string.dialog_root_denied_message),
            positiveButtonText = context.getString(io.github.dorumrr.de1984.R.string.dialog_ok),
            onPositiveClick = { onOkClick?.invoke() },
            cancelable = true,
            onDismiss = onOkClick
        )
    }

    fun showTypeToConfirm(
        context: Context,
        title: String,
        message: String,
        confirmWord: String = "UNINSTALL",
        confirmButtonText: String = "Confirm",
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val editText = android.widget.EditText(context).apply {
            hint = context.getString(io.github.dorumrr.de1984.R.string.dialog_type_to_confirm_hint, confirmWord)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                       android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(40, 20, 40, 20)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(editText)
            .setPositiveButton(confirmButtonText, null)
            .setNegativeButton(context.getString(io.github.dorumrr.de1984.R.string.dialog_cancel)) { _, _ ->
                onCancel?.invoke()
            }
            .setCancelable(true)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = false

            editText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    positiveButton.isEnabled = s?.toString()?.equals(confirmWord, ignoreCase = true) == true
                }
            })

            positiveButton.setOnClickListener {
                if (editText.text.toString().equals(confirmWord, ignoreCase = true)) {
                    dialog.dismiss()
                    onConfirm()
                }
            }
        }

        dialog.show()
    }
}

