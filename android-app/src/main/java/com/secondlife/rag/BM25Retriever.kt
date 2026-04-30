package com.secondlife.rag

import android.content.Context
import org.json.JSONArray
import java.io.File

data class ProtocolChunk(
    val id: String,
    val text: String,
    val source: String,
    val page: Int
)

class BM25Retriever(private val context: Context, private val jsonFilePath: String? = null) {

    private val chunks: List<ProtocolChunk> by lazy {
        loadChunks()
    }

    private fun loadChunks(): List<ProtocolChunk> {
        val jsonString = try {
            if (jsonFilePath != null && File(jsonFilePath).exists()) {
                File(jsonFilePath).readText()
            } else {
                context.assets.open("protocols.json").bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            null
        }

        return if (jsonString != null) {
            parseJson(jsonString)
        } else {
            MOCK_CHUNKS
        }
    }

    private fun parseJson(jsonString: String): List<ProtocolChunk> {
        val list = mutableListOf<ProtocolChunk>()
        val array = JSONArray(jsonString)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                ProtocolChunk(
                    id = obj.getString("id"),
                    text = obj.getString("text"),
                    source = obj.getString("source"),
                    page = obj.getInt("page")
                )
            )
        }
        return list
    }

    fun retrieve(query: String, topK: Int = 3): List<ProtocolChunk> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        return chunks.map { chunk ->
            val chunkTokens = tokenize(chunk.text)
            val score = queryTokens.intersect(chunkTokens).size
            chunk to score
        }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(topK)
        .map { it.first }
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-zA-Z0-9]"))
            .filter { it.length >= 3 }
            .toSet()
    }

    companion object {
        val MOCK_CHUNKS = listOf(
            ProtocolChunk("cpr", "For CPR: Push hard and fast in the center of the chest. 100-120 compressions per minute. Allow chest to recoil.", "Emergency Guide", 1),
            ProtocolChunk("choking", "For Choking (Heimlich): Wrap arms around waist. Make a fist. Press hard into abdomen with quick upward thrusts.", "Emergency Guide", 2),
            ProtocolChunk("bleeding", "For Severe Bleeding: Apply direct pressure with clean cloth. Do not remove soaked bandages, add more on top. Use tourniquet if life-threatening.", "Emergency Guide", 3),
            ProtocolChunk("stroke", "For Stroke (FAST): Face drooping, Arm weakness, Speech difficulty, Time to call emergency services immediately.", "Emergency Guide", 4),
            ProtocolChunk("seizure", "For Seizure: Clear the area of sharp objects. Do not restrain. Place something soft under head. Roll onto side after it stops.", "Emergency Guide", 5),
            ProtocolChunk("burns", "For Burns: Run cool (not cold) water over the area for 10-20 minutes. Cover loosely with sterile dressing. No ice or butter.", "Emergency Guide", 6),
            ProtocolChunk("anaphylaxis", "For Anaphylaxis: Use Epinephrine auto-injector (EpiPen) in outer thigh. Call emergency services. Keep person lying flat.", "Emergency Guide", 7)
        )
    }
}
