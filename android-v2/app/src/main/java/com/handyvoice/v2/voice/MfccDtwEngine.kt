package com.handyvoice.v2.voice

/**
 * Template-matching engine using MFCC features + path-normalized DTW.
 *
 * Stores N takes per label in memory. On match, we extract MFCCs from the
 * input audio, compare against every stored take via DTW, take the best
 * score per label, then return the label with the lowest score (plus the
 * runner-up so the caller can reason about confidence margin).
 *
 * Persistence is handled by [TemplateStore]; this class is pure logic so
 * it can be unit-tested without Android.
 */
class MfccDtwEngine(
    private val mfcc: Mfcc = Mfcc(),
    private val bandRatio: Double = 0.15,
) : TemplateEngine {

    private val takes: MutableMap<String, MutableList<Array<DoubleArray>>> = mutableMapOf()

    /** Load already-extracted MFCCs (e.g. from [TemplateStore.loadAll]). */
    fun loadInto(allTakes: Map<String, List<Array<DoubleArray>>>) {
        takes.clear()
        for ((label, list) in allTakes) {
            takes[label] = list.toMutableList()
        }
    }

    /** For callers that prefer to add already-extracted features. */
    fun addMfccTake(label: String, features: Array<DoubleArray>) {
        if (features.isEmpty()) return
        takes.getOrPut(label) { mutableListOf() }.add(features)
    }

    override fun labels(): List<String> = takes.keys.sorted()

    override fun addTake(label: String, audio: ShortArray) {
        val features = mfcc.extract(audio)
        if (features.isEmpty()) return
        takes.getOrPut(label) { mutableListOf() }.add(features)
    }

    override fun clearLabel(label: String) {
        takes.remove(label)
    }

    override fun clearAll() {
        takes.clear()
    }

    override fun takeCount(label: String): Int = takes[label]?.size ?: 0

    override fun match(audio: ShortArray): TemplateEngine.Match? {
        if (takes.isEmpty()) return null
        val query = mfcc.extract(audio)
        if (query.isEmpty()) return null
        return matchFeatures(query)
    }

    /** Visible to tests so we can match without going through audio capture. */
    fun matchFeatures(query: Array<DoubleArray>): TemplateEngine.Match? {
        if (takes.isEmpty() || query.isEmpty()) return null
        var bestLabel: String? = null
        var bestScore = Double.POSITIVE_INFINITY
        var secondScore = Double.POSITIVE_INFINITY
        for ((label, list) in takes) {
            var labelBest = Double.POSITIVE_INFINITY
            for (template in list) {
                val d = Dtw.distance(query, template, bandRatio)
                if (d < labelBest) labelBest = d
            }
            if (labelBest < bestScore) {
                secondScore = bestScore
                bestScore = labelBest
                bestLabel = label
            } else if (labelBest < secondScore) {
                secondScore = labelBest
            }
        }
        val label = bestLabel ?: return null
        return TemplateEngine.Match(label = label, score = bestScore, runnerUp = secondScore)
    }
}
