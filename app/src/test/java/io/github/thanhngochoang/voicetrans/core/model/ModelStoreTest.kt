package io.github.thanhngochoang.voicetrans.core.model

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val a = ByteArray(300_000) { (it % 251).toByte() }
    private val b = ByteArray(70_000) { (it % 13).toByte() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun file(dest: String, bytes: ByteArray, sha256: String = sha(bytes)) =
        ModelFile(dest, "https://example.test/$dest", dest, sha256, bytes.size.toLong())

    /** Serves [content] by URL and records every (url, offset) it was asked for. */
    private class FakeDownloader(private val content: Map<String, ByteArray>, private val honourRange: Boolean = true) : ModelDownloader {
        val calls = mutableListOf<Pair<String, Long>>()
        var failAfterBytes: Int? = null

        override fun open(url: String, offset: Long): ModelDownloader.Opened {
            calls += url to offset
            val bytes = content[url] ?: throw IOException("HTTP 404 for $url")
            val from = if (honourRange) offset.toInt() else 0
            val body = ByteArrayInputStream(bytes, from, bytes.size - from)
            val limit = failAfterBytes
            val stream: InputStream = if (limit == null) body else object : InputStream() {
                var served = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                    if (served >= limit) throw IOException("connection reset")
                    val n = body.read(buffer, off, minOf(len, limit - served))
                    if (n > 0) served += n
                    return n
                }
            }
            return ModelDownloader.Opened(stream, resumed = honourRange && offset > 0)
        }
    }

    private fun store(files: List<ModelFile>, downloader: ModelDownloader, free: Long = Long.MAX_VALUE) = ModelStore(
        root = File(tmp.root, "models"), catalog = { ModelCatalog("test", files) }, downloader = downloader,
        io = Dispatchers.Unconfined, freeBytes = { free },
    )

    @Test fun missingFiles_areReportedThenDownloadedAndVerified() = runBlocking {
        val files = listOf(file("asr/a.onnx", a), file("nmt/m/b.onnx", b))
        val downloader = FakeDownloader(files.associate { it.url to if (it.dest.endsWith("a.onnx")) a else b })
        val store = store(files, downloader)

        store.refresh()
        assertEquals(ModelState.Missing((a.size + b.size).toLong()), store.state.value)
        assertTrue("refresh must not touch the network", downloader.calls.isEmpty())

        store.download()
        assertEquals(ModelState.Ready, store.state.value)
        assertArrayEquals(a, store.file("asr/a.onnx").readBytes())
        assertArrayEquals(b, store.file("nmt/m/b.onnx").readBytes())
        assertFalse(File(store.root, "asr/a.onnx.part").exists())
    }

    @Test fun multipleFiles_downloadInParallel_verifiesAllFiles() = runBlocking {
        val c = ByteArray(50_000) { (it % 7).toByte() }
        val files = listOf(file("f1.onnx", a), file("f2.onnx", b), file("f3.onnx", c))
        val downloader = FakeDownloader(mapOf(files[0].url to a, files[1].url to b, files[2].url to c))
        val store = store(files, downloader)

        store.download()
        assertEquals(ModelState.Ready, store.state.value)
        assertArrayEquals(a, store.file("f1.onnx").readBytes())
        assertArrayEquals(b, store.file("f2.onnx").readBytes())
        assertArrayEquals(c, store.file("f3.onnx").readBytes())
    }

    @Test fun presentFiles_areReadyWithoutNetwork() = runBlocking {
        val files = listOf(file("a.onnx", a))
        val downloader = FakeDownloader(emptyMap())
        val store = store(files, downloader)
        store.root.mkdirs()
        store.file("a.onnx").writeBytes(a)

        store.refresh()
        assertEquals(ModelState.Ready, store.state.value)
        store.download()
        assertEquals(ModelState.Ready, store.state.value)
        assertTrue(downloader.calls.isEmpty())
    }

    @Test fun wrongChecksum_failsAndLeavesNoFile() = runBlocking {
        val files = listOf(file("a.onnx", a, sha256 = "0".repeat(64)))
        val store = store(files, FakeDownloader(mapOf(files[0].url to a)))

        store.download()
        assertEquals(ModelState.Failed(ModelState.Reason.CORRUPT, a.size.toLong()), store.state.value)
        assertFalse(store.file("a.onnx").exists())
        assertFalse(File(store.root, "a.onnx.part").exists())
    }

    @Test fun oversizedDownload_isRejectedAsCorrupt() = runBlocking {
        val files = listOf(file("b.onnx", b))
        val store = store(files, FakeDownloader(mapOf(files[0].url to a))) // server sends more than the catalog says

        store.download()
        assertEquals(ModelState.Failed(ModelState.Reason.CORRUPT, b.size.toLong()), store.state.value)
        assertFalse(store.file("b.onnx").exists())
    }

    @Test fun droppedConnection_failsThenResumesFromThePartFile() = runBlocking {
        val files = listOf(file("a.onnx", a))
        val downloader = FakeDownloader(mapOf(files[0].url to a)).apply { failAfterBytes = 100_000 }
        val store = store(files, downloader)

        store.download()
        assertEquals(ModelState.Failed(ModelState.Reason.NETWORK, (a.size - 100_000).toLong()), store.state.value)
        assertFalse(store.file("a.onnx").exists())

        downloader.failAfterBytes = null
        store.download()
        assertEquals(ModelState.Ready, store.state.value)
        assertEquals(listOf(files[0].url to 0L, files[0].url to 100_000L), downloader.calls)
        assertArrayEquals(a, store.file("a.onnx").readBytes())
    }

    @Test fun serverIgnoringRange_restartsTheFile() = runBlocking {
        val files = listOf(file("a.onnx", a))
        val store = store(files, FakeDownloader(mapOf(files[0].url to a), honourRange = false))
        store.root.mkdirs()
        File(store.root, "a.onnx.part").writeBytes(ByteArray(1234) { 9 }) // stale partial content

        store.download()
        assertEquals(ModelState.Ready, store.state.value)
        assertArrayEquals(a, store.file("a.onnx").readBytes())
    }

    @Test fun notEnoughStorage_failsBeforeDownloading() = runBlocking {
        val files = listOf(file("a.onnx", a))
        val downloader = FakeDownloader(mapOf(files[0].url to a))
        val store = store(files, downloader, free = 1000)

        store.download()
        assertEquals(ModelState.Failed(ModelState.Reason.STORAGE, a.size.toLong()), store.state.value)
        assertTrue(downloader.calls.isEmpty())
    }

    @Test fun unreachableServer_isANetworkFailure() = runBlocking {
        val files = listOf(file("a.onnx", a))
        val store = store(files, FakeDownloader(emptyMap()))

        store.download()
        assertEquals(ModelState.Failed(ModelState.Reason.NETWORK, a.size.toLong()), store.state.value)
    }
}
