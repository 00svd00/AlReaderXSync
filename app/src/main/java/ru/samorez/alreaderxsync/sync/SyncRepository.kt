package ru.samorez.alreaderxsync.sync

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.samorez.alreaderxsync.data.DeviceRole
import ru.samorez.alreaderxsync.data.FileEntry
import ru.samorez.alreaderxsync.data.SyncDirection
import ru.samorez.alreaderxsync.data.SyncMode
import ru.samorez.alreaderxsync.data.SyncResult
import ru.samorez.alreaderxsync.data.SyncState
import ru.samorez.alreaderxsync.storage.DocumentTreeWalker
import ru.samorez.alreaderxsync.transport.SyncTransport
import java.io.InputStream

/**
 * Репозиторий, инкапсулирующий полный цикл синхронизации.
 *
 * ДИАГНОСТИКА: добавлено подробное логирование каждого шага
 * (sync → syncAsSource/syncAsTarget → walk → sendMetadata/receiveMetadata →
 * sendRequest/receiveRequest → передача файлов → Complete),
 * чтобы точно определить место зависания на Reader.
 *
 * @param context контекст приложения.
 */
class SyncRepository(private val context: Context) {

    private companion object {
        const val TAG = "SyncRepository"
    }

    /**
     * Выполняет синхронизацию между [sourceRoot] и [targetRoot]
     * через уже подключённый [transport].
     */
    suspend fun sync(
        transport: SyncTransport,
        sourceRoot: DocumentFile,
        targetRoot: DocumentFile,
        mode: SyncMode,
        excludedPaths: List<String>,
        localRole: DeviceRole,
        direction: SyncDirection,
        onProgress: (SyncState) -> Unit
    ): SyncResult = withContext(Dispatchers.IO) {

        Log.d(
            TAG,
            "sync() called, transport=${transport.name}, " +
                    "role=$localRole, direction=$direction"
        )

        // Определяем, является ли текущее устройство источником.
        val iAmSource = when (direction) {
            SyncDirection.PHONE_TO_READER -> localRole == DeviceRole.PHONE
            SyncDirection.READER_TO_PHONE -> localRole == DeviceRole.READER
        }

        Log.d(TAG, "sync(): iAmSource=$iAmSource")

        onProgress(SyncState.Discovering)
        onProgress(SyncState.Connecting(transport.name))

        Log.d(TAG, "sync(): about to call syncAsSource/syncAsTarget")

        try {
            val result = if (iAmSource) {
                syncAsSource(transport, sourceRoot, mode, excludedPaths, onProgress)
            } else {
                syncAsTarget(transport, targetRoot, mode, excludedPaths, onProgress)
            }

            Log.d(TAG, "sync(): completed, transferred=${result.transferred}")
            onProgress(SyncState.Complete(result))
            result
        } finally {
            withContext(NonCancellable) {
                try {
                    transport.close()
                    Log.d(TAG, "Transport closed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing transport: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Сценарий источника. Подробное логирование каждого шага.
     */
    private suspend fun syncAsSource(
        transport: SyncTransport,
        sourceRoot: DocumentFile,
        mode: SyncMode,
        excludedPaths: List<String>,
        onProgress: (SyncState) -> Unit
    ): SyncResult {
        Log.d(TAG, "syncAsSource started")
        val errors = mutableListOf<String>()

        onProgress(SyncState.Comparing)
        val walker = DocumentTreeWalker(
            context = context,
            root = sourceRoot,
            excludedPaths = excludedPaths
        )
        Log.d(TAG, "syncAsSource: walker created, walking...")
        val sourceEntries = walker.walk()
        Log.d(TAG, "syncAsSource: Source entries: ${sourceEntries.size} files")

        val filesOnly = sourceEntries.filter { !it.isDirectory }
        Log.d(TAG, "syncAsSource: filesOnly=${filesOnly.size}")

        Log.d(TAG, "syncAsSource: sending metadata...")
        transport.sendMetadata(filesOnly)
        Log.d(TAG, "syncAsSource: Manifest sent")

        Log.d(TAG, "syncAsSource: waiting for request...")
        val requestedPaths: List<String> = transport.receiveRequest()
        Log.d(TAG, "syncAsSource: Received request: ${requestedPaths.size} paths")

        val total = requestedPaths.size
        var transferred = 0

        for ((index, path) in requestedPaths.withIndex()) {
            currentCoroutineContext().ensureActive()

            try {
                val entry = filesOnly.find { it.relativePath == path }
                if (entry == null) {
                    errors.add("Not found: $path")
                    continue
                }
                if (entry.isDirectory) {
                    errors.add("Skipped directory: $path")
                    continue
                }

                Log.d(
                    TAG,
                    "syncAsSource: Sending [${index + 1}/$total]: " +
                            "${entry.relativePath}, size=${entry.size}"
                )

                val input = context.contentResolver.openInputStream(entry.uri)
                if (input == null) {
                    errors.add("Cannot open: $path")
                    continue
                }
                try {
                    transport.sendFile(entry, input)
                } finally {
                    input.close()
                }
                transferred++
                Log.d(TAG, "syncAsSource: Sent: ${entry.relativePath}")

                onProgress(
                    SyncState.Transferring(
                        currentFile = path,
                        progress = transferred * 100 / total.coerceAtLeast(1),
                        transferred = transferred,
                        total = total
                    )
                )
            } catch (e: CancellationException) {
                Log.d(TAG, "syncAsSource: cancelled at file: $path")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "syncAsSource: sendFile error for $path: ${e.message}")
                errors.add("$path: ${e.message}")
            }
        }

        Log.d(TAG, "syncAsSource: sending Complete...")
        transport.sendComplete(transferred, 0)
        // ← гарантирует, что Phone вычитал все данные
        //   из Bluetooth-буфера, прежде чем мы закроем сокет
        Log.d(TAG, "syncAsSource: sending Ping...")
        try {
            transport.sendPing()
            Log.d(TAG, "syncAsSource: Ping/Pong done")
        } catch (e: Exception) {
            Log.w(TAG, "syncAsSource: sendPing failed: ${e.message}")
        }
        Log.d(TAG, "syncAsSource: completed, transferred=$transferred")
        return SyncResult(transferred, 0, errors, transport.name)
    }

    /**
     * Сценарий цели. Подробное логирование каждого шага —
     * чтобы видеть, где именно зависает Reader.
     */
    private suspend fun syncAsTarget(
        transport: SyncTransport,
        targetRoot: DocumentFile,
        mode: SyncMode,
        excludedPaths: List<String>,
        onProgress: (SyncState) -> Unit
    ): SyncResult {
        Log.d(TAG, "syncAsTarget started")
        val errors = mutableListOf<String>()

        onProgress(SyncState.Comparing)
        val walker = DocumentTreeWalker(
            context = context,
            root = targetRoot,
            excludedPaths = excludedPaths
        )
        Log.d(TAG, "syncAsTarget: walker created, walking...")
        val targetEntries = walker.walk()
        Log.d(TAG, "syncAsTarget: Target entries: ${targetEntries.size} files")

        val filesOnly = targetEntries.filter { !it.isDirectory }
        Log.d(TAG, "syncAsTarget: filesOnly=${filesOnly.size}")

        Log.d(TAG, "syncAsTarget: waiting for manifest from source...")
        val manifest = transport.receiveMetadata()
        Log.d(TAG, "syncAsTarget: Received manifest: ${manifest.size} files")

        Log.d(TAG, "syncAsTarget: building plan...")
        val plan = SyncPlanner().buildPlan(manifest, filesOnly, mode)
        Log.d(
            TAG,
            "syncAsTarget: Plan: transfer=${plan.toTransfer.size}, " +
                    "delete=${plan.toDelete.size}"
        )

        val pathsToRequest = plan.toTransfer.map { it.relativePath }
        Log.d(TAG, "syncAsTarget: sending request for ${pathsToRequest.size} files...")
        transport.sendRequest(pathsToRequest)
        Log.d(TAG, "syncAsTarget: request sent")

        val total = pathsToRequest.size
        var received = 0

        for ((index, path) in pathsToRequest.withIndex()) {
            currentCoroutineContext().ensureActive()

            Log.d(TAG, "syncAsTarget: Receiving [${index + 1}/$total]: $path")

            try {
                val receivedFile: Pair<FileEntry, InputStream> = transport.receiveFile()
                val entry = receivedFile.first
                val input = receivedFile.second

                try {
                    if (saveFileToTarget(targetRoot, entry, input)) {
                        received++
                        Log.d(TAG, "syncAsTarget: Received: ${entry.relativePath}")
                        onProgress(
                            SyncState.Transferring(
                                currentFile = path,
                                progress = received * 100 / total.coerceAtLeast(1),
                                transferred = received,
                                total = total
                            )
                        )
                    } else {
                        errors.add("Failed to save: $path")
                    }
                } finally {
                    input.close()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "syncAsTarget: cancelled at file: $path")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "syncAsTarget: receiveFile error for $path: ${e.message}")
                errors.add("$path: ${e.message}")
            }
        }

        Log.d(TAG, "syncAsTarget: waiting for Complete from source...")
        val (sourceTransferred, sourceDeleted) = transport.receiveComplete()
        Log.d(
            TAG,
            "syncAsTarget: Complete from source: transferred=$sourceTransferred, " +
                    "deleted=$sourceDeleted"
        )
        // ← ждём Ping от источника. Это гарантирует, что мы получили
        //   ВСЕ данные из Bluetooth-буфера, прежде чем отправитель
        //   закроет сокет.
        Log.d(TAG, "syncAsTarget: waiting for Ping...")
        try {
            transport.awaitPing()
        } catch (e: Exception) {
            Log.w(TAG, "syncAsTarget: awaitPing failed: ${e.message}")
            // не критично — не прерываем синхронизацию
        }
        var deleted = 0
        if (mode == SyncMode.FULL_REPLACE) {
            for (entry in plan.toDelete) {
                currentCoroutineContext().ensureActive()
                if (targetRoot.findFile(entry.relativePath)?.delete() == true) {
                    deleted++
                }
            }
        }

        Log.d(TAG, "syncAsTarget: completed, received=$received, deleted=$deleted")
        return SyncResult(received, deleted, errors, transport.name)
    }

    /**
     * Атомарное сохранение принятого файла через временный файл.
     */
    private suspend fun saveFileToTarget(
        targetRoot: DocumentFile,
        entry: FileEntry,
        input: InputStream
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val parts = entry.relativePath.split("/")
            var dir = targetRoot

            for (i in 0 until parts.size - 1) {
                val part = parts[i]
                var subDir = dir.findFile(part)
                if (subDir == null) {
                    subDir = dir.createDirectory(part)
                }
                if (subDir == null || !subDir.isDirectory) {
                    Log.e(TAG, "saveFileToTarget: cannot create dir $part")
                    return@withContext false
                }
                dir = subDir
            }

            val fileName = parts.last()
            val tempName = ".tmp_${System.currentTimeMillis()}_$fileName"

            val tempFile = dir.createFile("application/octet-stream", tempName)
            if (tempFile == null) {
                Log.e(TAG, "saveFileToTarget: cannot create temp $tempName")
                return@withContext false
            }

            try {
                val out = context.contentResolver.openOutputStream(tempFile.uri)
                if (out == null) {
                    withContext(NonCancellable) { tempFile.delete() }
                    return@withContext false
                }
                out.use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }

                if (tempFile.length() != entry.size) {
                    Log.e(
                        TAG,
                        "saveFileToTarget: size mismatch ${entry.relativePath}: " +
                                "expected=${entry.size}, actual=${tempFile.length()}"
                    )
                    withContext(NonCancellable) { tempFile.delete() }
                    return@withContext false
                }

                dir.findFile(fileName)?.delete()
                if (!tempFile.renameTo(fileName)) {
                    withContext(NonCancellable) { tempFile.delete() }
                    return@withContext false
                }
                true
            } catch (e: CancellationException) {
                withContext(NonCancellable) { tempFile.delete() }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "saveFileToTarget error: ${e.message}", e)
                withContext(NonCancellable) { tempFile.delete() }
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "saveFileToTarget error: ${e.message}", e)
            false
        }
    }
}