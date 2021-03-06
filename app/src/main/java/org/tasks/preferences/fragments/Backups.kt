package org.tasks.preferences.fragments

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.todoroo.andlib.utility.DateUtilities
import dagger.hilt.android.AndroidEntryPoint
import org.tasks.PermissionUtil
import org.tasks.R
import org.tasks.dialogs.ExportTasksDialog
import org.tasks.dialogs.ImportTasksDialog
import org.tasks.drive.DriveLoginActivity
import org.tasks.files.FileHelper
import org.tasks.injection.InjectingPreferenceFragment
import org.tasks.preferences.FragmentPermissionRequestor
import org.tasks.preferences.PermissionRequestor
import org.tasks.preferences.Preferences
import org.tasks.preferences.PreferencesViewModel
import org.tasks.ui.Toaster
import java.util.*
import javax.inject.Inject

private const val REQUEST_CODE_BACKUP_DIR = 10001
const val REQUEST_DRIVE_BACKUP = 12002
private const val REQUEST_PICKER = 10003
private const val FRAG_TAG_EXPORT_TASKS = "frag_tag_export_tasks"
private const val FRAG_TAG_IMPORT_TASKS = "frag_tag_import_tasks"

@AndroidEntryPoint
class Backups : InjectingPreferenceFragment() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var permissionRequestor: FragmentPermissionRequestor
    @Inject lateinit var toaster: Toaster
    @Inject lateinit var locale: Locale

    private val viewModel: PreferencesViewModel by activityViewModels()

    override fun getPreferenceXml() = R.xml.preferences_backups

    override suspend fun setupPreferences(savedInstanceState: Bundle?) {
        initializeBackupDirectory()

        findPreference(R.string.backup_BAc_import)
            .setOnPreferenceClickListener {
                startActivityForResult(
                    FileHelper.newFilePickerIntent(activity, preferences.backupDirectory),
                    REQUEST_PICKER
                )
                false
            }

        findPreference(R.string.backup_BAc_export)
            .setOnPreferenceClickListener {
                ExportTasksDialog.newExportTasksDialog()
                    .show(parentFragmentManager, FRAG_TAG_EXPORT_TASKS)
                false
            }

        findPreference(R.string.google_drive_backup)
                .setOnPreferenceChangeListener(this@Backups::onGoogleDriveCheckChanged)
        findPreference(R.string.p_google_drive_backup_account)
                .setOnPreferenceClickListener {
                    requestGoogleDriveLogin()
                    false
                }

        viewModel.lastBackup.observe(this, this::updateLastBackup)
        viewModel.lastDriveBackup.observe(this, this::updateDriveBackup)
        viewModel.lastAndroidBackup.observe(this, this::updateAndroidBackup)
    }

    private fun updateLastBackup(timestamp: Long?) {
        findPreference(R.string.backup_BAc_export).summary =
                getString(
                        R.string.last_backup,
                        timestamp
                                ?.let { DateUtilities.getLongDateStringWithTime(it, locale) }
                                ?: getString(R.string.last_backup_never)
                )
    }

    private fun updateDriveBackup(timestamp: Long?) {
        findPreference(R.string.google_drive_backup).summary =
                getString(
                        R.string.last_backup,
                        timestamp
                                ?.let { DateUtilities.getLongDateStringWithTime(it, locale) }
                                ?: getString(R.string.last_backup_never)
                )
    }

    private fun updateAndroidBackup(timestamp: Long?) {
        findPreference(R.string.p_backups_android_backup_enabled).summary =
                getString(
                        R.string.last_backup,
                        timestamp
                                ?.let { DateUtilities.getLongDateStringWithTime(it, locale) }
                                ?: getString(R.string.last_backup_never)
                )
    }

    override fun onResume() {
        super.onResume()

        updateDriveAccount()

        val driveBackup = findPreference(R.string.google_drive_backup) as SwitchPreferenceCompat
        val driveAccount = viewModel.driveAccount
        driveBackup.isChecked = driveAccount != null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PermissionRequestor.REQUEST_GOOGLE_ACCOUNTS) {
            if (PermissionUtil.verifyPermissions(grantResults)) {
                requestGoogleDriveLogin()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_BACKUP_DIR) {
            if (resultCode == RESULT_OK && data != null) {
                val uri = data.data!!
                context?.contentResolver
                    ?.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                preferences.setUri(R.string.p_backup_dir, uri)
                updateBackupDirectory()
            }
        } else if (requestCode == REQUEST_PICKER) {
            if (resultCode == RESULT_OK) {
                val uri = data!!.data
                val extension = FileHelper.getExtension(requireContext(), uri!!)
                if (!("json".equals(extension, ignoreCase = true) || "xml".equals(
                        extension,
                        ignoreCase = true
                    ))
                ) {
                    toaster.longToast(R.string.invalid_backup_file)
                } else {
                    ImportTasksDialog.newImportTasksDialog(uri, extension)
                        .show(parentFragmentManager, FRAG_TAG_IMPORT_TASKS)
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun onGoogleDriveCheckChanged(preference: Preference, newValue: Any?) = when {
        newValue as Boolean -> {
            requestGoogleDriveLogin()
            false
        }
        else -> {
            preference.summary = null
            preferences.setString(R.string.p_google_drive_backup_account, null)
            updateDriveAccount()
            true
        }
    }

    private fun updateDriveAccount() {
        val account = viewModel.driveAccount
        val pref = findPreference(R.string.p_google_drive_backup_account)
        pref.isEnabled = account != null
        pref.summary =
                account
                        ?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.none)
    }

    private fun requestGoogleDriveLogin() {
        if (permissionRequestor.requestAccountPermissions()) {
            startActivityForResult(
                    Intent(context, DriveLoginActivity::class.java),
                    REQUEST_DRIVE_BACKUP
            )
        }
    }

    private fun initializeBackupDirectory() {
        findPreference(R.string.p_backup_dir)
            .setOnPreferenceClickListener {
                FileHelper.newDirectoryPicker(
                    this, REQUEST_CODE_BACKUP_DIR, preferences.backupDirectory
                )
                false
            }
        updateBackupDirectory()
    }

    private fun updateBackupDirectory() {
        findPreference(R.string.p_backup_dir).summary =
            FileHelper.uri2String(preferences.backupDirectory)
    }
}