package eu.kanade.presentation.dictionary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.TermMetaMode
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority

/**
 * Parsed frequency data from a dictionary term meta entry
 */
data class FrequencyData(
    val reading: String,
    val frequency: String, // Changed to String to support "very common" etc.
    val numericFrequency: Int?, // For sorting purposes
    val dictionaryId: Long,
)

object FrequencyFormatter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parse frequency data from term meta entries.
     */
    fun parseFrequencies(termMetaList: List<DictionaryTermMeta>): List<FrequencyData> {
        val frequencies = termMetaList
            .filter { it.mode == TermMetaMode.FREQUENCY }
            .mapNotNull { parseFrequency(it) }

        // Return all frequencies sorted by numeric value (lowest = most common)
        return frequencies.sortedBy { it.numericFrequency ?: Int.MAX_VALUE }
    }

    /**
     * Parse a single frequency entry.
     * Handles multiple formats:
     * - Simple number: 23500
     * - Simple string: "very common"
     * - Object with value/displayValue: {"value": 18000, "displayValue": "Common Word"}
     * - Object with reading: {"reading": "せい", "frequency": 3500}
     * - Object with reading and nested frequency: {"reading": "なま", "frequency": {"value": 12000, "displayValue": "Frequent"}}
     */
    private fun parseFrequency(termMeta: DictionaryTermMeta): FrequencyData? {
        return try {
            val element = json.parseToJsonElement(termMeta.data)

            when {
                // Simple number
                element is JsonPrimitive && element.isString.not() -> {
                    val freq = element.intOrNull ?: return null
                    FrequencyData(
                        reading = "",
                        frequency = freq.toString(),
                        numericFrequency = freq,
                        dictionaryId = termMeta.dictionaryId,
                    )
                }

                // Simple string
                element is JsonPrimitive && element.isString -> {
                    val freqStr = element.content
                    FrequencyData(
                        reading = "",
                        frequency = freqStr,
                        numericFrequency = null,
                        dictionaryId = termMeta.dictionaryId,
                    )
                }

                // Object - need to determine which structure
                element is JsonObject -> {
                    parseFrequencyObject(element, termMeta.dictionaryId)
                }

                else -> null
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Failed to parse frequency data: ${termMeta.data}" }
            null
        }
    }

    private fun parseFrequencyObject(obj: JsonObject, dictionaryId: Long): FrequencyData? {
        // Check if this has a "reading" field
        val reading = obj["reading"]?.jsonPrimitive?.content ?: ""

        // Check if this has a "frequency" field
        val frequencyElement = obj["frequency"]

        return when {
            // Structure with a nested frequency object
            frequencyElement is JsonObject -> {
                val (displayValue, numericValue) = extractFrequencyFromObject(frequencyElement)
                FrequencyData(
                    reading = reading,
                    frequency = displayValue,
                    numericFrequency = numericValue,
                    dictionaryId = dictionaryId,
                )
            }

            // Structure with a simple frequency number
            frequencyElement is JsonPrimitive -> {
                val freq = frequencyElement.intOrNull ?: frequencyElement.content.toIntOrNull()
                FrequencyData(
                    reading = reading,
                    frequency = freq?.toString() ?: frequencyElement.content,
                    numericFrequency = freq,
                    dictionaryId = dictionaryId,
                )
            }

            // Structure with a value/displayValue
            obj.containsKey("value") || obj.containsKey("displayValue") -> {
                val (displayValue, numericValue) = extractFrequencyFromObject(obj)
                FrequencyData(
                    reading = reading,
                    frequency = displayValue,
                    numericFrequency = numericValue,
                    dictionaryId = dictionaryId,
                )
            }

            else -> null
        }
    }

    /**
     * Extract frequency display and numeric values from an object with "value" and/or "displayValue"
     */
    private fun extractFrequencyFromObject(obj: JsonObject): Pair<String, Int?> {
        val displayValue = obj["displayValue"]?.jsonPrimitive?.content
        val numericValue = obj["value"]?.jsonPrimitive?.intOrNull

        // Prefer displayValue if it exists, otherwise use numeric value
        val display = displayValue ?: numericValue?.toString() ?: "Unknown"

        return Pair(display, numericValue)
    }
}
