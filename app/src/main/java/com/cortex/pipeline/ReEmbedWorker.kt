package com.cortex.pipeline

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cortex.data.AppDatabase
import com.cortex.data.EmbeddingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot upgrade: when the real MiniLM model is present, re-embed any nodes/items
 * whose vectors were written under the old `fallback-hash-v1` model. Until this
 * finishes, retrieval/resolution already ignore stale-version vectors, so there is
 * never cross-model cosine noise — this just restores semantic recall for old data.
 */
class ReEmbedWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val embeddings = EmbeddingService.get(applicationContext)
        if (embeddings.modelVersion == FALLBACK) return@withContext Result.success() // nothing to upgrade to

        val dao = AppDatabase.getDatabase(applicationContext).cortexDao()
        try {
            val staleNodes = dao.getEmbeddingsByModelVersionNot("node", embeddings.modelVersion)
            for (e in staleNodes) {
                val node = dao.getNodeById(e.ownerId) ?: continue
                val vec = embeddings.embed("${node.name}. ${node.summary}")
                dao.insertEmbedding(EmbeddingEntity("node", node.id, Vectors.toBlob(vec), embeddings.modelVersion))
            }
            val staleItems = dao.getEmbeddingsByModelVersionNot("item", embeddings.modelVersion)
            for (e in staleItems) {
                val item = dao.getItemById(e.ownerId) ?: continue
                val vec = embeddings.embed(item.content)
                dao.insertEmbedding(EmbeddingEntity("item", item.id, Vectors.toBlob(vec), embeddings.modelVersion))
            }
            Log.i(TAG, "Re-embedded ${staleNodes.size} nodes, ${staleItems.size} items to ${embeddings.modelVersion}")
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Re-embed failed: ${t.message}", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ReEmbedWorker"
        private const val FALLBACK = "fallback-hash-v1"
    }
}
