package com.md.modesetters

import android.app.Activity
import android.content.Context
import android.widget.Button
import android.widget.ToggleButton
import com.md.CategorySingleton
import com.md.ModeHandler
import com.md.R
import com.md.workers.BackupToUsbManager
import com.md.workers.BackupToUsbManager.REQUEST_CODE_FOR_LOCATION_1
import com.md.workers.BackupToUsbManager.REQUEST_CODE_FOR_LOCATION_2
import com.md.workers.BackupToUsbManager.REQUEST_CODE_FOR_LOCATION_3
import com.md.workers.BackupToUsbManager.REQUEST_CODE_FOR_LOCATION_4
import com.md.workers.BackupToUsbManager.openZipFileDocument

object SettingModeSetter : ModeSetter(), ItemDeletedHandler {
    fun setup(memoryDroid: Activity?, modeHand: ModeHandler?) {
        parentSetup(memoryDroid, modeHand)
    }

    override fun setupModeImpl(context: Activity) {
        commonSetup(context, R.layout.settings)
        refreshSettings(context)
    }

     fun refreshSettings(activity: Activity) {
        val markButton = activity.findViewById<ToggleButton>(R.id.look_ahead) ?: return

         val instance = CategorySingleton.getInstance()
        markButton.isChecked = instance.lookAheadDays != 0
        markButton.setOnClickListener {
            val checked = markButton.isChecked
            instance.lookAheadDays = if (checked) 1 else 0
        }

        val repeatButton = activity.findViewById<ToggleButton>(R.id.repeat)
        repeatButton.isChecked = instance.shouldRepeat()
        repeatButton.setOnClickListener { instance.setRepeat(!repeatButton.isChecked) }

        activity.findViewById<Button>(R.id.external_backup_directory).apply {
            specifyNewBackupLocation("Backup location 1", activity, REQUEST_CODE_FOR_LOCATION_1)
        }

        activity.findViewById<Button>(R.id.external_backup_directory_2).apply {
            specifyNewBackupLocation("Backup location 2", activity, REQUEST_CODE_FOR_LOCATION_2)
        }

        activity.findViewById<Button>(R.id.external_backup_directory_3).apply {
            specifyNewBackupLocation("Backup location 3", activity, REQUEST_CODE_FOR_LOCATION_3)
        }
        activity.findViewById<Button>(R.id.external_backup_directory_4).apply {
            specifyNewBackupLocation("Backup location 4", activity, REQUEST_CODE_FOR_LOCATION_4)
        }
    }

    private fun Button.specifyNewBackupLocation(backupLocationName: String, activity: Activity, requestCode: Int) {
        val backupLocation = context.getSharedPreferences(BackupToUsbManager.BACKUP_LOCATION_FILE, Context.MODE_PRIVATE).getString(BackupToUsbManager.requestCodeToKey[requestCode], null)
        if (backupLocation != null) {
            setText(backupLocationName + ":\n" + backupLocation)
        } else {
            setText("$backupLocationName: Tap to set")
        }
        setOnClickListener { openZipFileDocument(activity, requestCode) }
    }
}