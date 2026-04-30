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
        // Require at least 2 meaningful keyword matches — prevents spurious single-word hits
        // (e.g. "for", "the", "with" matching unrelated protocols)
        .filter { it.second >= 2 }
        .sortedByDescending { it.second }
        .take(topK)
        .map { it.first }
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-zA-Z0-9]"))
            .filter { it.length >= 4 }       // 4+ chars drops "for","the","and","his","her","who"
            .filter { it !in STOP_WORDS }
            .toSet()
    }

    companion object {
        private val STOP_WORDS = setOf(
            "this", "that", "them", "they", "then", "than",
            "what", "when", "where", "which", "will", "with",
            "have", "from", "your", "their", "there", "here",
            "just", "also", "call", "more", "some", "over",
            "into", "onto", "upon", "been", "does", "were",
            "each", "much", "such", "very", "even", "back",
            "most", "both", "down", "only", "after", "place",
            "area", "side", "time", "help", "person", "around",
            "make", "keep", "wrap", "push", "hard", "fast",
            "apply", "allow", "chest", "quick", "clean"
        )

        val MOCK_CHUNKS = listOf(
            ProtocolChunk("cpr",         "For CPR: Push hard and fast in the center of the chest. 100-120 compressions per minute. Allow chest to recoil.",                                                       "Emergency Guide", 1),
            ProtocolChunk("choking",     "For Choking (Heimlich): Wrap arms around waist. Make a fist. Press hard into abdomen with quick upward thrusts.",                                                       "Emergency Guide", 2),
            ProtocolChunk("bleeding",    "For Severe Bleeding: Apply direct pressure with clean cloth. Do not remove soaked bandages, add more on top. Use tourniquet if life-threatening.",                      "Emergency Guide", 3),
            ProtocolChunk("stroke",      "For Stroke (FAST): Face drooping, Arm weakness, Speech difficulty, Time to call emergency services immediately.",                                                        "Emergency Guide", 4),
            ProtocolChunk("seizure",     "For Seizure: Clear the area of sharp objects. Do not restrain. Place something soft under head. Roll onto side after it stops.",                                         "Emergency Guide", 5),
            ProtocolChunk("burns",       "For Burns: Run cool (not cold) water over the area for 10-20 minutes. Cover loosely with sterile dressing. No ice or butter.",                                          "Emergency Guide", 6),
            ProtocolChunk("anaphylaxis", "For Anaphylaxis: Use Epinephrine auto-injector (EpiPen) in outer thigh. Call emergency services. Keep person lying flat.",                                              "Emergency Guide", 7),
            ProtocolChunk("fracture",    "For Fracture/Broken Bone: Immobilize the limb. Do not try to straighten it. Apply ice wrapped in cloth. Elevate if possible. Seek medical care.",                      "Emergency Guide", 8),
            ProtocolChunk("sprain",      "For Sprain/Strain (RICE): Rest the injured limb. Ice for 20 minutes. Compress with bandage. Elevate above heart level.",                                               "Emergency Guide", 9),
            ProtocolChunk("knee",        "For Knee Injury: Stop movement immediately. Apply ice wrapped in cloth for 20 minutes. Compress with elastic bandage. Elevate the leg. Do not bear weight.",           "Emergency Guide", 10),
            ProtocolChunk("head",        "For Head Injury: Keep still. Monitor consciousness. Do not remove helmet if worn. Watch for confusion, vomiting, unequal pupils — these need immediate emergency care.", "Emergency Guide", 11),
            ProtocolChunk("diabetic",    "For Diabetic Emergency (conscious): Give sugar — juice, candy, glucose tablets. If unconscious do NOT give anything by mouth, call emergency services.",                "Emergency Guide", 12),
            ProtocolChunk("poisoning",   "For Poisoning: Call poison control. Do not induce vomiting unless directed. Note the substance and time of exposure.",                                                  "Emergency Guide", 13),
            ProtocolChunk("drowning",    "For Drowning: Remove from water safely. Check breathing. Begin CPR if not breathing. Keep warm, treat for shock.",                                                      "Emergency Guide", 14),
            ProtocolChunk("shock",       "For Shock: Lay person flat, elevate legs 12 inches unless head/neck injury suspected. Keep warm. Do not give food or water. Call emergency services.",                  "Emergency Guide", 15),
        )
    }
}
