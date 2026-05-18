package com.handyvoice.v2.voice

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * On-disk persistence for per-label MFCC takes.
 *
 * Layout under [rootDir]:
 *   templates/
 *     <label>/
 *       take_1.json
 *       take_2.json
 *       ...
 *
 * Each file is a [SerializedTake]: format version + a 2D matrix of doubles
 * (frames x coefficients). Plain JSON keeps the format inspectable and
 * easy to copy between devices for debugging.
 */
class TemplateStore(private val rootDir: File) {

    companion object {
        private const val TEMPLATES_SUBDIR = "templates"
        private const val CURRENT_FORMAT = 1
    }

    private val gson = Gson()
    private val type = object : TypeToken<SerializedTake>() {}.type

    data class SerializedTake(
        val format: Int = CURRENT_FORMAT,
        val label: String,
        val mfcc: Array<DoubleArray>,
    )

    fun baseDir(): File = File(rootDir, TEMPLATES_SUBDIR).apply { mkdirs() }

    fun labelDir(label: String): File = File(baseDir(), sanitize(label)).apply { mkdirs() }

    fun hasAnyTemplates(): Boolean {
        val base = File(rootDir, TEMPLATES_SUBDIR)
        if (!base.isDirectory) return false
        return base.listFiles()?.any { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) } == true
    }

    fun labels(): List<String> {
        val base = File(rootDir, TEMPLATES_SUBDIR)
        if (!base.isDirectory) return emptyList()
        return base.listFiles()
            ?.filter { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    fun takeCount(label: String): Int {
        val dir = File(rootDir, "$TEMPLATES_SUBDIR/${sanitize(label)}")
        if (!dir.isDirectory) return 0
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.size ?: 0
    }

    fun saveTake(label: String, mfcc: Array<DoubleArray>): File {
        val dir = labelDir(label)
        val next = (dir.listFiles { f -> f.name.startsWith("take_") }?.size ?: 0) + 1
        val file = File(dir, "take_$next.json")
        file.writeText(gson.toJson(SerializedTake(label = label, mfcc = mfcc)))
        return file
    }

    fun loadAll(): Map<String, List<Array<DoubleArray>>> {
        val out = mutableMapOf<String, MutableList<Array<DoubleArray>>>()
        for (label in labels()) {
            val dir = File(rootDir, "$TEMPLATES_SUBDIR/$label")
            val takes = mutableListOf<Array<DoubleArray>>()
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?.forEach { f ->
                    try {
                        val parsed: SerializedTake = gson.fromJson(f.readText(), type)
                        takes.add(parsed.mfcc)
                    } catch (_: Exception) {
                        // skip corrupt take
                    }
                }
            if (takes.isNotEmpty()) out[label] = takes
        }
        return out
    }

    fun clearLabel(label: String) {
        val dir = File(rootDir, "$TEMPLATES_SUBDIR/${sanitize(label)}")
        if (dir.isDirectory) dir.deleteRecursively()
    }

    fun clearAll() {
        val base = File(rootDir, TEMPLATES_SUBDIR)
        if (base.isDirectory) base.deleteRecursively()
    }

    /** Strip path separators and anything that would confuse a filesystem. */
    private fun sanitize(label: String): String =
        label.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
}
