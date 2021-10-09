package com.md.workers

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import com.md.MemPrimeManager
import com.md.NotesProvider
import com.md.SpacedRepeaterActivity
import com.md.modesetters.TtsSpeaker
import com.md.utils.ToastSingleton
import com.md.workers.BackupToUsbManager.UPDATE_TIME_FILE_NAME
import com.md.workers.BackupToUsbManager.markAudioDirectoryWithUpdateTime
import kotlinx.coroutines.*
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import com.md.AudioRecorder
import java.lang.IllegalStateException


object IncrementalBackupManager {
    fun openBackupDir(activity: Activity, requestCode: Int) {
        TtsSpeaker.speak(
            "Create a new directory where you would like backup files to be" +
                    " written to. Then click allow to grant this app access."
        )
        // Choose a directory using the system's file picker.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Provide write access to files and sub-directories in the user-selected
            // directory.
            flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        ActivityCompat.startActivityForResult(activity, intent, requestCode, null)
    }

    fun createAndWriteZipBackToPreviousLocation(
            context: SpacedRepeaterActivity,
            contentResolver: ContentResolver,
            shouldSpeak: Boolean,
            runExtraValidation: Boolean = false,
            onFinished: ((Boolean) -> Unit)? = null
    ) {
        val backupLocations = IncrementalBackupPreferences.getBackupLocations(context)

        if (backupLocations.isNotEmpty()) {
            backupToUris(context, contentResolver, backupLocations, shouldSpeak, runExtraValidation, onFinished)
        } else {
            TtsSpeaker.speak("No backup needed")
            onFinished?.invoke(true)
        }
    }

    fun createAndWriteZipBackToNewLocation(
        context: SpacedRepeaterActivity,
        data: Intent,
        requestCode: Int,
        contentResolver: ContentResolver
    ): Boolean {
        val locationKey: String = IncrementalBackupPreferences.requestCodeToKey.get(requestCode)
            ?: return false
        val sourceTreeUri: Uri = data.data ?: return false

        contentResolver.takePersistableUriPermission(
            sourceTreeUri,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val sharedPref = context.getSharedPreferences(
            IncrementalBackupPreferences.BACKUP_LOCATION_FILE, Context.MODE_PRIVATE
        )

        sharedPref.edit().putString(locationKey, sourceTreeUri.toString()).apply()

        return true
    }

    private fun backupToUris(
            context: Context,
            contentResolver: ContentResolver,
            backupUris: MutableMap<String, Uri>,
            shouldSpeak: Boolean = false,
            runExtraValidation: Boolean,
            onFinished: ((Boolean) -> Unit)?
    ) {
        GlobalScope.launch(Dispatchers.Main) {
            if (shouldSpeak) TtsSpeaker.speak("starting backup")

            val deferred = async(Dispatchers.IO) {
                backupOnBackground(
                    contentResolver,
                    backupUris,
                    context.filesDir,
                    shouldSpeak,
                    context,
                    runExtraValidation
                )
            }

            deferred.await()

            onFinished?.invoke(true)
            // We don't really need this due to the toasts.
            // if (shouldSpeak) TtsSpeaker.speak("backup finished: " + deferred.await())
        }
    }

    private suspend fun backupOnBackground(
            contentResolver: ContentResolver,
            backupUris: MutableMap<String, Uri>,
            appStorageRoot: File,
            shouldSpeak: Boolean,
            context: Context,
            runExtraValidation: Boolean
    ) {

        val validBackupUris = backupUris.filter { uri ->
            try {
                if (DocumentFile.fromTreeUri(context, uri.value)?.isDirectory == true) {
                    return@filter true
                }
            } catch (e: FileNotFoundException) {
                if (shouldSpeak) TtsSpeaker.speak("missing exception for $uri")
            } catch (e: SecurityException) {
                if (shouldSpeak) TtsSpeaker.speak("security exception for $uri")
            }
            false
        }
        val backupsNeeded = validBackupUris.size
        if (shouldSpeak) TtsSpeaker.speak("backups needed $backupsNeeded")
        if (backupsNeeded == 0) {
            return
        }

        val allFiles = appStorageRoot.listFiles()
        if (allFiles == null || allFiles.isEmpty()) {
            TtsSpeaker.error("All files empty")
            return
        }

        val openHelper = NotesProvider.mOpenHelper
        openHelper?.close()

        val audioDirectoryToAudioFiles = mutableMapOf<String, List<File>>()
        var databaseLastModTime: Long? = 0
        val audioDirectoryToModificationTime = mutableMapOf<String, Long>()
        allFiles.forEach { it ->
            if (it.isDirectory && it.name == "com.md.MemoryPrime") {
                val dirsToZip = mutableListOf<File>()
                var databaseFileOrNull: File? = null
                val databaseOrAudioDirectoryList = it.listFiles()
                if (databaseOrAudioDirectoryList == null || databaseOrAudioDirectoryList.isEmpty()) {
                    TtsSpeaker.error("no data base or audio directory")
                    return
                }

                databaseOrAudioDirectoryList.forEach { databaseOrAudioDirectory ->
                    if (databaseOrAudioDirectory.isDirectory) {
                        // com.md.MemoryPrime/AudioMemo/
                        val audioDirectoryList = databaseOrAudioDirectory.listFiles()
                        // com.md.MemoryPrime/AudioMemo/1
                        // com.md.MemoryPrime/AudioMemo/2
                        if (audioDirectoryList == null || audioDirectoryList.isEmpty()) {
                            TtsSpeaker.error("numbered audio directory empty")
                            return
                        }
                        audioDirectoryList.forEach { numberedAudioDir ->
                            if (numberedAudioDir.isDirectory) {
                                val updateTimeFile = File(numberedAudioDir, UPDATE_TIME_FILE_NAME)
                                if (updateTimeFile.exists()) {
                                    audioDirectoryToModificationTime.put(
                                        numberedAudioDir.name,
                                        updateTimeFile.lastModified()
                                    )
                                } else {
                                    markAudioDirectoryWithUpdateTime(numberedAudioDir)
                                }
                                audioDirectoryToAudioFiles.put(
                                    numberedAudioDir.name,
                                    numberedAudioDir.listFiles().filter { it.isFile })
                            } else {
                                TtsSpeaker.error("Audio directory contained unknown file")
                            }
                        }
                    } else { // Else it's the database.
                        // Files only. No directories
                        if (databaseOrAudioDirectory.name.equals("memory_droid.db")) {
                            // This if adds memory_droid.db, but not memory_droid.db-journal
                            databaseLastModTime = databaseOrAudioDirectory.lastModified()
                            databaseFileOrNull = databaseOrAudioDirectory
                        } else {
                            println("backup ignoring non-db file: >$databaseOrAudioDirectory<")
                        }
                    }
                }


                val databaseFile = databaseFileOrNull
                if (databaseFile == null) {
                    TtsSpeaker.error("No database file! ")
                    return
                }

                zipBackup(validBackupUris, context, contentResolver, mutableListOf(databaseFile), dirsToZip, audioDirectoryToAudioFiles, runExtraValidation, audioDirectoryToModificationTime, shouldSpeak)

                // Deleted this:
                // fileBackup(validBackupUris, context, contentResolver, databaseFileOrNull, dirsToZip, audioDirectoryToAudioFiles, runExtraValidation, audioDirectoryToModificationTime, shouldSpeak)


            }
        }
    }

    private suspend fun fileBackup(validBackupUris: Map<String, Uri>, context: Context, contentResolver: ContentResolver, databaseFileOrNull: File?, dirsToZip: MutableList<File>, audioDirectoryToAudioFiles: MutableMap<String, List<File>>, runExtraValidation: Boolean, audioDirectoryToModificationTime: MutableMap<String, Long>, shouldSpeak: Boolean) {

        if (databaseFileOrNull == null) {
            TtsSpeaker.error("No database file! ")
            return
        }

        val databaseFile = databaseFileOrNull

        for (uri: Map.Entry<String, Uri> in validBackupUris) {



            try {
                val backupRoot = DocumentFile.fromTreeUri(context, uri.value)
                if (backupRoot == null) {
                    TtsSpeaker.error("Couldn't open backup dir")
                    continue
                }

                val fileBackupRootOrEmpty = backupRoot.findFile("filebasedBackup")
                val fileBackupRoot = if (fileBackupRootOrEmpty?.exists() == true) {
                    fileBackupRootOrEmpty
                } else {
                    backupRoot.createDirectory("filebasedBackup")
                }
                if (fileBackupRoot == null) {
                    TtsSpeaker.error("Couldn't open fileBackupRoot")
                    continue
                }

                val oldOldDatabaseFile = fileBackupRoot.findFile("memory_droid.db.last")
                if (oldOldDatabaseFile?.exists() == true) {
                    oldOldDatabaseFile.delete()
                }

                val oldDatabaseFile = fileBackupRoot.findFile("memory_droid.db")
                if (oldDatabaseFile?.exists() == true) {
                    oldDatabaseFile.renameTo("memory_droid.db.last")
                }

                val backUpDb = fileBackupRoot.createFile("application/octet-stream", "memory_droid.db") ?: throw IllegalStateException("Coudln't create audio backup file")
                writeFileToDocumentFile(contentResolver, backUpDb, databaseFile)

                val taskList = mutableListOf<Deferred<Boolean>>()
                // TODOJ remove filter
//                audioDirectoryToAudioFiles.filterKeys {   it == AudioRecorder.sessionSuffixTwoDigitNumber  }.forEach { (dirName, appAudiofileList) ->

                audioDirectoryToAudioFiles.forEach { (dirName, appAudiofileList) ->
                    taskList.add(GlobalScope.async(Dispatchers.IO) {
                        val audioNumberRootOrEmpty =  fileBackupRoot.findFile(dirName)
                        val audioNumberBackDir = if (audioNumberRootOrEmpty?.exists() == true) {
                            audioNumberRootOrEmpty
                        } else {
                            fileBackupRoot.createDirectory(dirName)
                        }

                        if (audioNumberBackDir == null) {
                            TtsSpeaker.error("Couldn't open audioNumberRoot $dirName")
                            return@async false
                        }

                        val backupAudioFiles = audioNumberBackDir.listFiles()


                        val backupDirUpdateCompleteMarker = audioNumberBackDir.findFile(UPDATE_TIME_FILE_NAME)
                        val backDirLastCompleteBackupTime = if (backupDirUpdateCompleteMarker?.exists() == true) {
                            backupDirUpdateCompleteMarker.lastModified()
                        } else {
                            0L
                        }

                        val filesToBackup = if (backupAudioFiles.isEmpty()) {
                            // Backup everything.
                            appAudiofileList
                        } else {
                            val appAudioNumDirModTime = audioDirectoryToModificationTime[dirName]
                            if (appAudioNumDirModTime != null && backDirLastCompleteBackupTime > appAudioNumDirModTime) {
                                // Backup is never than audio dir mod time, so nothing to backup.
                                listOf<File>()
                            }

                            appAudiofileList.filter {
                                if (backDirLastCompleteBackupTime > it.lastModified()) {
                                    // don't backup backup dir more recent than audio file.
                                    return@filter false
                                    // TODOJ maybe double check this.
                                }

                                val backUpAudioFile = audioNumberBackDir.findFile(it.name) ?: return@filter true
                                if (!backUpAudioFile.exists()) {
                                    // Needs a backup since no backup exists.
                                    return@filter true
                                }

                                return@filter backUpAudioFile.lastModified() < it.lastModified()
                            }
                        }

                        filesToBackup.forEach { appAudioFile ->
                            val backupOrEmpty = audioNumberBackDir.findFile(appAudioFile.name)
                            if (backupOrEmpty?.exists() == true) {
                                backupOrEmpty.delete()
                            }
                            val backUp = audioNumberBackDir.createFile("application/octet-stream", appAudioFile.name)
                            if (backUp == null) {
                                TtsSpeaker.speak("could not create audio backup finished for" + appAudioFile.name)
                                throw IllegalStateException("Coudln't create audio backup file")
                            }
                            writeFileToDocumentFile(contentResolver, backUp, appAudioFile)
                        }

                        if (backupDirUpdateCompleteMarker?.exists() == true) {
                            backupDirUpdateCompleteMarker.delete()
                        }
                        // Update the time marker.
                        audioNumberBackDir.createFile("application/octet-stream", UPDATE_TIME_FILE_NAME)

                        return@async false
                    })
                }
                taskList.forEach { it.await() }
                if (shouldSpeak) TtsSpeaker.speak("Backup finished for" + uri.key)
            } catch (e: FileNotFoundException) {
                if (shouldSpeak) TtsSpeaker.speak("FileNotFoundException for " + uri.key)
                System.err.println("Missing file during backup: $uri")
            } catch (e: SecurityException) {
                if (shouldSpeak) TtsSpeaker.speak("security exception for $uri" + uri.key)
            }
        }
    }

    private fun writeFileToDocumentFile(contentResolver: ContentResolver, backUp: DocumentFile, appAudioFile: File) {
        val data = ByteArray(MemPrimeManager.BUFFER)
        contentResolver.openFileDescriptor(backUp.uri, "w")?.use {
            val output = FileOutputStream(it.fileDescriptor)
            val fi = FileInputStream(appAudioFile)
            val origin = BufferedInputStream(fi, MemPrimeManager.BUFFER)

            while (true) {
                val count = origin.read(data, 0, MemPrimeManager.BUFFER)
                if (count == -1) {
                    break
                }
                output.write(data, 0, count)
            }
            origin.close()
            output.close()
        }
    }

    private suspend fun zipBackup(validBackupUris: Map<String, Uri>, context: Context, contentResolver: ContentResolver, databaseFilesToZip: MutableList<File>, dirsToZip: MutableList<File>, audioDirectoryToAudioFiles: MutableMap<String, List<File>>, runExtraValidation: Boolean, audioDirectoryToModificationTime: MutableMap<String, Long>, shouldSpeak: Boolean) {
        for (uri: Map.Entry<String, Uri> in validBackupUris) {
            try {
                val backupRoot = DocumentFile.fromTreeUri(context, uri.value)
                if (backupRoot == null) {
                    TtsSpeaker.error("Couldn't open backup dir")
                    continue
                }


                val oldOldDatabaseFile = backupRoot.findFile("database.zip.last")
                if (oldOldDatabaseFile?.exists() == true) {
                    oldOldDatabaseFile.delete()
                }

                val oldDatabaseFile = backupRoot.findFile("database.zip")
                if (oldDatabaseFile?.exists() == true) {
                    oldDatabaseFile.renameTo("database.zip.last")
                }

                var databaseZip: DocumentFile? = null
                for (attempt in 1..3) {
                    databaseZip = backupRoot.createFile("application/zip", "database.zip")
                    if (databaseZip == null) {
                        TtsSpeaker.error("Database backup create failed. Try $attempt")
                    } else {
                        break
                    }
                }

                if (databaseZip == null) {
                    TtsSpeaker.error("Database backup failed repeatedly for " + uri.key)
                    continue
                }

                val fileDescriptor =
                        contentResolver.openFileDescriptor(databaseZip.uri, "w")
                val descriptor = fileDescriptor?.fileDescriptor
                if (descriptor == null) {
                    TtsSpeaker.error("Database zipping failed for missing file " + uri.key)
                    continue
                }
                val output = FileOutputStream(descriptor)
                println("zipping database $databaseFilesToZip")
                if (MemPrimeManager.zip(databaseFilesToZip, dirsToZip, output)) {
                    println("Backed up database successful")
                } else {
                    TtsSpeaker.error("Database zipping failed for " + uri.key)
                    continue
                }
                fileDescriptor.close()

                // Now delete
                if (oldDatabaseFile?.exists() == true) {
                    oldDatabaseFile.delete()
                }

                val taskList = mutableListOf<Deferred<Boolean>>()
                audioDirectoryToAudioFiles.forEach { (dirName, fileList) ->
                    taskList.add(GlobalScope.async(Dispatchers.IO) {
                        val previousBackup = backupRoot.findFile("$dirName.zip")
                        if (previousBackup != null && previousBackup.exists()) {
                            if (previousBackup.length() == 0L) {
                                // If there is an empty backup zip. Write the file again.
                                TtsSpeaker.speak("Empty $dirName")
                                previousBackup.delete()
                            } else if (runExtraValidation && !isValidZip(previousBackup, contentResolver, fileList)) {
                                // If there is an empty backup zip. Write the file again.
                                TtsSpeaker.speak("Extra validation $dirName")
                                previousBackup.delete()
                            } else {
                                val lastDirMod = audioDirectoryToModificationTime[dirName]
                                if (lastDirMod == null) {
                                    if (fileList.indexOfFirst { it.lastModified() >= previousBackup.lastModified() } == -1) {
                                        println("Done Search times stamps for $dirName none")
                                        return@async false
                                    }
                                } else {
                                    if (lastDirMod < previousBackup.lastModified()) {
                                        println("Done No Search needed for $dirName")
                                        return@async false
                                    }
                                }
                            } // else out of date. Recreate backup.

                            previousBackup.delete()
                        }

                        val dirZip =
                                backupRoot.createFile("application/zip", "$dirName.zip")
                        if (dirZip == null) {
                            TtsSpeaker.error("Couldn't create audio backup file $dirName")
                        } else {
                            contentResolver.openFileDescriptor(dirZip.uri, "w")?.use {
                                val output = FileOutputStream(it.fileDescriptor)
                                if (MemPrimeManager.zip(fileList, dirsToZip, output)) {
                                    GlobalScope.launch(Dispatchers.Main) {
                                        ToastSingleton.getInstance()
                                                .msg("Memprime backed up $dirName")
                                    }
                                    return@async true
                                } else {
                                    GlobalScope.launch(Dispatchers.Main) {
                                        TtsSpeaker.error("zip write failed audio backup file $dirName")
                                    }
                                }
                            }
                        }
                        return@async false
                    })
                }
                taskList.forEach { it.await() }
                if (shouldSpeak) TtsSpeaker.speak("Backup finished for" + uri.key)
            } catch (e: FileNotFoundException) {
                if (shouldSpeak) TtsSpeaker.speak("FileNotFoundException for " + uri.key)
                System.err.println("Missing file during backup: $uri")
            } catch (e: SecurityException) {
                if (shouldSpeak) TtsSpeaker.speak("security exception for $uri" + uri.key)
            }
        }
    }

    private fun isValidZip(
        zipToValidate: DocumentFile,
        contentResolver: ContentResolver,
        expectedFileList: List<File>
    ): Boolean {
        var zis: ZipInputStream? = null
        val expectedCount = expectedFileList.size
        var count = 0
        val openFileDescriptor = contentResolver.openFileDescriptor(zipToValidate.uri, "r") ?: return false
        return try {
            zis = ZipInputStream(FileInputStream(openFileDescriptor.fileDescriptor))
            var ze: ZipEntry? = zis.nextEntry
            while (ze != null) {
                count++
                // if it throws an exception fetching any of the following then we know the file is corrupted.
                ze.crc
                ze.compressedSize
                ze.name
                ze = zis.nextEntry
            }
            println("expectedCount $expectedCount actualCount $count")
            // Validate that current number of files in the directory matches the number in the zip.
            expectedCount == count
        } catch (e: ZipException) {
            println("extra validation error " + e)
            false
        } catch (e: IOException) {
            println("expectedCount $expectedCount actualCount $count")
            println("extra IO exception validation error " + e)
            false
        } finally {
            try {
                zis?.close()
            } catch (e: IOException) {
                println("extra close validation error " + e)
                return false
            }
        }
    }
}