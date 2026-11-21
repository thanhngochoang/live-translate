package io.github.thanhngochoang.voicetrans.core.model

/** One downloadable model file. [dest] is relative to the models directory. */
data class ModelFile(
    val id: String,
    val url: String,
    val dest: String,
    val sha256: String,
    val size: Long,
)

/**
 * The model files the app needs, read from `assets/models/models.json`. That file is the single place for model URLs
 * and checksums; the desktop apps read its `files` array too, so the entries keep the shape they expect.
 */
class ModelCatalog(val release: String, val files: List<ModelFile>) {

    val totalBytes: Long get() = files.sumOf { it.size }

    companion object {
        /** Arrays the Android app downloads: speech models shared with desktop, then the translation models. */
        private val GROUPS = listOf("files", "nmt")

        // Android compiles these with ICU, which rejects an unescaped "}" or "]" that the JVM accepts: escape every
        // brace and bracket. ModelCatalogDeviceTest parses the real catalog on a device to catch this.
        fun parse(json: String): ModelCatalog {
            val release = Regex(""""release"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1).orEmpty()
            val files = GROUPS.flatMap { entries(json, it) }
            require(files.isNotEmpty()) { "models.json lists no model files" }
            require(files.map { it.dest }.toSet().size == files.size) { "models.json has duplicate dest paths" }
            return ModelCatalog(release, files)
        }

        private fun entries(json: String, key: String): List<ModelFile> {
            val section = Regex(""""$key"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(json) ?: return emptyList()
            return Regex("""\{[^\{\}]*\}""").findAll(section.groupValues[1]).map { entry(it.value) }.toList()
        }

        private fun entry(obj: String): ModelFile {
            fun string(name: String) = Regex(""""$name"\s*:\s*"([^"]*)"""").find(obj)?.groupValues?.get(1)
                ?: error("models.json entry has no \"$name\": $obj")
            val size = Regex(""""size"\s*:\s*(\d+)""").find(obj)?.groupValues?.get(1)?.toLong()
                ?: error("models.json entry has no \"size\": $obj")
            val file = ModelFile(string("id"), string("url"), string("dest"), string("sha256").lowercase(), size)
            require(file.url.startsWith("https://")) { "Model URL must be https: ${file.url}" }
            require(Regex("[0-9a-f]{64}").matches(file.sha256)) { "Bad SHA-256 for ${file.dest}" }
            // dest becomes a path under the models directory, so it must not be able to leave it.
            require(Regex("""[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*""").matches(file.dest) && ".." !in file.dest.split('/')) {
                "Bad dest path: ${file.dest}"
            }
            return file
        }
    }
}
