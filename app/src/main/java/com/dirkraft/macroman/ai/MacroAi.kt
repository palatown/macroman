package com.dirkraft.macroman.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.dirkraft.macroman.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
data class FoodItem(
    val name: String,
    val quantity: String,
    val calories: Double,
    @SerialName("protein_g") val protein: Double,
    @SerialName("carbs_g") val carbs: Double,
    @SerialName("fat_g") val fat: Double,
)

@Serializable
data class WorkoutItem(
    val exercise: String,
    val sets: Int,
    val reps: Int,
    val weight: Double,
    val unit: String,
    @SerialName("duration_min") val durationMin: Double,
    val notes: String,
)

@Serializable private data class FoodResult(val items: List<FoodItem>)
@Serializable private data class WorkoutResult(val items: List<WorkoutItem>)

/** Turns free text and/or a photo into structured macros (or workout sets) with one Claude call. */
class MacroAi(private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true }
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    private fun client(): AnthropicClient {
        val key = settings.apiKey.trim()
        if (key.isEmpty()) throw IllegalStateException("Add your Anthropic API key in Settings first.")
        if (client == null || key != clientKey) {
            client = AnthropicOkHttpClient.builder().apiKey(key).build()
            clientKey = key
        }
        return client!!
    }

    /**
     * Checks the key and model with a Models API lookup, which costs no tokens.
     * Returns the model's display name on success, or throws with a message fit to show the user.
     */
    suspend fun check(): String = withContext(Dispatchers.IO) {
        val model = settings.model.ifBlank { Settings.DEFAULT_MODEL }
        try {
            client().models().retrieve(model).displayName()
        } catch (e: UnauthorizedException) {
            throw IllegalStateException("Key not recognised")
        } catch (e: PermissionDeniedException) {
            throw IllegalStateException("Key doesn't have access")
        } catch (e: NotFoundException) {
            throw IllegalStateException("Key works, but model \"$model\" wasn't found")
        } catch (e: AnthropicServiceException) {
            throw IllegalStateException("Anthropic returned an error (${e.statusCode()})")
        } catch (e: AnthropicIoException) {
            throw IllegalStateException("Couldn't reach Anthropic. Check your connection.")
        }
    }

    suspend fun parseFood(text: String, jpeg: ByteArray?): List<FoodItem> {
        val blocks = buildList {
            if (jpeg != null) {
                add(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .data(Base64.getEncoder().encodeToString(jpeg))
                                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                    .build()
                            )
                            .build()
                    )
                )
            }
            add(text(text.ifBlank { "Log what is in this photo." }))
        }
        return json.decodeFromString<FoodResult>(ask(FOOD_SYSTEM, FOOD_SCHEMA, blocks)).items
    }

    suspend fun parseWorkout(text: String): List<WorkoutItem> =
        json.decodeFromString<WorkoutResult>(ask(WORKOUT_SYSTEM, WORKOUT_SCHEMA, listOf(text(text)))).items

    private fun text(s: String) = ContentBlockParam.ofText(TextBlockParam.builder().text(s).build())

    private suspend fun ask(system: String, schema: Map<String, Any>, blocks: List<ContentBlockParam>): String =
        withContext(Dispatchers.IO) {
            val schemaParam = JsonOutputFormat.Schema.builder().apply {
                schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
            }.build()
            val params = MessageCreateParams.builder()
                .model(settings.model.ifBlank { Settings.DEFAULT_MODEL })
                .maxTokens(16000L)
                .system(system)
                // Estimating macros is a simple task: low effort keeps the round trip short.
                .outputConfig(
                    OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(JsonOutputFormat.builder().schema(schemaParam).build())
                        .build()
                )
                .addUserMessageOfBlockParams(blocks)
                // Server-side fallback: a safety-classifier refusal is retried on a suitable model instead of failing.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()

            val message = client().messages().create(params)
            if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
                throw IllegalStateException("Claude declined that request. Try rewording it.")
            }
            message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        }

    private companion object {
        val FOOD_SYSTEM = """
            You are the logging engine of a macro tracker used by someone building lean muscle.
            Convert the user's description and/or photo into a list of food items with estimated macros.
            - One item per distinct food. Combine trivial components only when they are normally eaten together (e.g. "ham sandwich").
            - Use the stated quantity; if none is given, assume a typical single serving and state it in "quantity" (e.g. "1 cup, ~240 g").
            - If a nutrition label is visible, use its exact values scaled to the amount eaten.
            - For branded or restaurant items, use the known published nutrition when you know it.
            - calories in kcal, macros in grams, rounded to whole numbers. Keep calories consistent with 4/4/9 kcal per g of protein/carbs/fat.
            - Keep "name" short (2-5 words), title case.
            - If the input does not describe food, return an empty list.
        """.trimIndent()

        val WORKOUT_SYSTEM = """
            You are the logging engine of a strength-training log.
            Convert the user's description into a list of exercise entries.
            - One entry per exercise. "bench 3x8 185" means 3 sets of 8 reps at 185.
            - If different weights or reps were used across sets, create one entry per distinct sets x reps x weight group.
            - unit is "lb" or "kg"; default to "lb" when the user gives none. Use 0 for weight on bodyweight exercises.
            - For cardio or timed work, set duration_min and use 0 for sets/reps/weight.
            - Use 0 or "" for fields that do not apply. Put anything else useful (RPE, tempo, distance) in notes.
            - Use common exercise names in title case, e.g. "Bench Press", "Romanian Deadlift", "Incline Walk".
        """.trimIndent()

        private fun obj(vararg props: Pair<String, String>) = mapOf(
            "type" to "object",
            "properties" to props.associate { (k, t) -> k to mapOf("type" to t) },
            "required" to props.map { it.first },
            "additionalProperties" to false,
        )

        private fun list(item: Map<String, Any>) = mapOf(
            "type" to "object",
            "properties" to mapOf("items" to mapOf("type" to "array", "items" to item)),
            "required" to listOf("items"),
            "additionalProperties" to false,
        )

        val FOOD_SCHEMA = list(
            obj(
                "name" to "string", "quantity" to "string", "calories" to "number",
                "protein_g" to "number", "carbs_g" to "number", "fat_g" to "number",
            )
        )

        val WORKOUT_SCHEMA = list(
            obj(
                "exercise" to "string", "sets" to "integer", "reps" to "integer", "weight" to "number",
                "unit" to "string", "duration_min" to "number", "notes" to "string",
            )
        )
    }
}
