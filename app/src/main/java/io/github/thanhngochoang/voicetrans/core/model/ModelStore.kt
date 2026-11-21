package io.github.thanhngochoang.voicetrans.core.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface ModelState {
    /** Not checked yet (app start). */
    data object Checking : ModelState

    /** Some files are not on the device; [bytes] is what is left to download. */
    data class Missing(val bytes: Long) : ModelState

    data class Downloading(val doneBytes: Long, val totalBytes: Long) : ModelState

    data object Ready : ModelState

    data class Failed(val reason: Reason, val bytes: Long) : ModelState

    enum class Reason { NETWORK, STORAGE, CORRUPT }
}

/**
 * The model files live in [root] (app-private storage), not in the APK. They are downloaded once, on the user's
 * request, from the URLs in the [ModelCatalog] and checked against its SHA-256 before they are put in place.
 * An interrupted download keeps its `.part` file and continues from there next time.
 */
class ModelStore(
    val root: File,
    catalog: () -> ModelCatalog,
    private val downloader: ModelDownloader,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    /** Outlives any screen, so leaving the download screen does not cancel the download. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + io),
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {
    private val catalog by lazy(catalog)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ModelState>(ModelState.Checking)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** Absolute path of a model file. Only meaningful once [state] is [ModelState.Ready]. */
    fun file(dest: String): File = File(root, dest)

    /** [refresh] in the store's own scope (app start). */
    fun startRefresh(): Job = scope.launch { refresh() }

    /** [download] in the store's own scope (the Download and Retry buttons). */
    fun startDownload(): Job = scope.launch { download() }

    /** Looks at what is on the device. Does not touch the network. */
    suspend fun refresh() = withContext(io) {
        mutex.withLock {
            val left = remaining(missing())
            _state.value = if (left == null) ModelState.Ready else ModelState.Missing(left)
        }
    }

    /** Downloads every missing file in parallel. Ends in [ModelState.Ready] or [ModelState.Failed]; safe to call again. */
    suspend fun download() = withContext(io) {
        mutex.withLock {
            val todo = missing()
            val left = remaining(todo)
            if (left == null) {
                _state.value = ModelState.Ready
                return@withLock
            }
            root.mkdirs()
            if (freeBytes(root) < left + STORAGE_MARGIN) {
                _state.value = ModelState.Failed(ModelState.Reason.STORAGE, left)
                return@withLock
            }
            val total = todo.sumOf { it.size }
            val doneBytes = AtomicLong(0L)
            val reportedBytes = AtomicLong(0L)
            _state.value = ModelState.Downloading(0, total)
            try {
                val semaphore = Semaphore(parallelism)
                // Sort by file size so small files (configs, spm, vad, tokens) finish quickly first
                val sortedTodo = todo.sortedBy { it.size }
                coroutineScope {
                    val deferreds = sortedTodo.map { f ->
                        async(io) {
                            semaphore.withPermit {
                                fetch(f) { delta ->
                                    val currentDone = doneBytes.addAndGet(delta)
                                    val prev = reportedBytes.get()
                                    if (currentDone - prev >= PROGRESS_STEP && reportedBytes.compareAndSet(prev, currentDone)) {
                                        _state.value = ModelState.Downloading(currentDone.coerceIn(0, total), total)
                                    }
                                }
                            }
                        }
                    }
                    deferreds.awaitAll()
                }
                _state.value = ModelState.Ready
            } catch (e: CancellationException) {
                _state.value = ModelState.Missing(remaining(missing()) ?: 0)
                throw e
            } catch (e: Exception) {
                val cause = generateSequence<Throwable>(e) { it.cause }
                    .firstOrNull { it is CorruptModelException || it is IOException } ?: e
                when (cause) {
                    is CorruptModelException -> _state.value = ModelState.Failed(ModelState.Reason.CORRUPT, remaining(missing()) ?: 0)
                    is IOException -> _state.value = ModelState.Failed(ModelState.Reason.NETWORK, remaining(missing()) ?: 0)
                    else -> throw e
                }
            }
        }
    }

    private fun missing(): List<ModelFile> = catalog.files.filterNot { f ->
        file(f.dest).let { it.isFile && it.length() == f.size }
    }

    /** Bytes still to download for [todo], counting what the `.part` files already hold. Null when nothing is missing. */
    private fun remaining(todo: List<ModelFile>): Long? =
        if (todo.isEmpty()) null else todo.sumOf { it.size - part(it).length().coerceIn(0, it.size) }

    private fun part(f: ModelFile) = File(root, f.dest + ".part")

    private suspend fun fetch(f: ModelFile, onBytes: (Long) -> Unit) {
        val target = file(f.dest)
        target.parentFile?.mkdirs()
        val part = part(f)
        if (part.length() > f.size) part.delete()
        var have = part.length()
        if (have < f.size) {
            val opened = downloader.open(f.url, have)
            if (!opened.resumed) have = 0
            onBytes(have)
            opened.stream.use { input ->
                FileOutputStream(part, have > 0).use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        have += n
                        if (have > f.size) {
                            part.delete()
                            throw CorruptModelException(f.dest)
                        }
                        out.write(buffer, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
            if (have < f.size) throw IOException("Download of ${f.dest} ended early at $have of ${f.size} bytes")
        } else {
            onBytes(have)
        }
        if (sha256(part) != f.sha256) {
            part.delete()
            throw CorruptModelException(f.dest)
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not move ${part.name} into place")
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class CorruptModelException(dest: String) : Exception("Checksum or size mismatch for $dest")

    private companion object {
        const val BUFFER = 1 shl 16
        const val PROGRESS_STEP = 1L shl 20
        const val STORAGE_MARGIN = 64L shl 20
        const val DEFAULT_PARALLELISM = 3
    }
}
