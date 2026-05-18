package com.handyvoice.v2.voice

/**
 * Placeholder for the Phase 3 TFLite embedding engine.
 *
 * The plan: extract EfficientWord-Net's Resnet50_Arc_loss TFLite model
 * (or a comparable speaker-agnostic embedding) and use cosine similarity
 * against per-label averaged embeddings. Same training UX, same dispatch.
 * Until then, [MfccDtwEngine] is the default.
 */
class TfliteEmbeddingEngine : TemplateEngine {
    override fun labels(): List<String> = emptyList()
    override fun addTake(label: String, audio: ShortArray) =
        throw UnsupportedOperationException("TFLite engine not implemented yet")
    override fun clearLabel(label: String) {}
    override fun clearAll() {}
    override fun takeCount(label: String): Int = 0
    override fun match(audio: ShortArray): TemplateEngine.Match? = null
}
