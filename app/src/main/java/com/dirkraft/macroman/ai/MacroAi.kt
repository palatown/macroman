package com.dirkraft.macroman.ai

import com.dirkraft.macroman.data.Settings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class FoodItem(
    val name: String,
    val quantity: String,
    val calories: Double,
    @SerialName("protein_g") val protein: Double,
    @SerialName("carbs_g") val carbs: Double,
    @SerialName("fat_g") val fat: Double,
    @SerialName("fiber_g") val fiber: Double,
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
@Serializable private data class HandResult(val measurements: String)
@Serializable private data class AmountResult(val amount: String)

/** Turns free text and/or a photo into structured macros (or workout sets) with one model call. */
class MacroAi(private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true }
    private var backend: LlmBackend? = null
    private var backendConfig: LlmConfig? = null

    /** Rebuilt whenever the provider, address, key or models change in Settings. */
    private fun backend(): LlmBackend {
        val config = settings.llmConfig()
        if (backend == null || config != backendConfig) {
            backend = if (config.provider == Provider.ANTHROPIC) AnthropicBackend(config) else OpenAiCompatBackend(config)
            backendConfig = config
        }
        return backend!!
    }

    suspend fun check(): String = backend().check()

    suspend fun models(): List<String> = backend().models()

    suspend fun parseFood(text: String, jpeg: ByteArray?): List<FoodItem> {
        val hand = settings.handReference.trim()
        val system = if (hand.isEmpty()) FOOD_SYSTEM else "$FOOD_SYSTEM\n\n$HAND_SCALE\n$hand"
        // Judging portion size from a photo is where extra thinking pays off; text stays fast.
        val effort = if (jpeg != null) Effort.HIGH else Effort.LOW
        val req = LlmRequest(system, text.ifBlank { "Log what is in this photo." }, jpeg, FOOD_SCHEMA, effort)
        return ask<FoodResult>(req).items
    }

    /** Rewrites an amount like "1 cup, ~240 g" for [factor] times as much. A tiny job, so it goes to the quick model. */
    suspend fun rescaleAmount(amount: String, factor: Double): String {
        val prompt = "Amount: $amount\nFactor:${"%.2f".format(java.util.Locale.ROOT, factor).trimEnd('0').trimEnd('.')}"
        val result = ask<AmountResult>(LlmRequest(AMOUNT_SYSTEM, prompt, null, AMOUNT_SCHEMA, Effort.LOW, quick = true))
        return result.amount.trim().ifEmpty { throw IllegalStateException("Empty amount") }
    }

    suspend fun parseWorkout(text: String): List<WorkoutItem> =
        ask<WorkoutResult>(LlmRequest(WORKOUT_SYSTEM, text, null, WORKOUT_SCHEMA, Effort.LOW)).items

    /** Reads hand dimensions off a photo of the user's hand next to a tape measure or ruler. */
    suspend fun measureHand(jpeg: ByteArray): String {
        val req = LlmRequest(HAND_SYSTEM, "Measure my hand against the tape measure in this photo.", jpeg, HAND_SCHEMA, Effort.HIGH)
        val result = ask<HandResult>(req)
        if (result.measurements.isBlank()) throw IllegalStateException("Couldn't measure a hand against a ruler in that photo.")
        return result.measurements.trim()
    }

    /**
     * Anthropic enforces the schema, but other providers may only approximate it, so an answer that doesn't
     * parse gets one more try before giving up.
     */
    private suspend inline fun <reified T> ask(req: LlmRequest): T {
        var last: SerializationException? = null
        repeat(2) {
            try {
                return json.decodeFromString<T>(backend().complete(req))
            } catch (e: SerializationException) {
                last = e
            } catch (e: IllegalArgumentException) {
                last = SerializationException(e.message)
            }
        }
        throw IllegalStateException("The model's answer wasn't in the expected format. ${last?.message?.take(80).orEmpty()}")
    }

    private companion object {
        val FOOD_SYSTEM = """
            You are the logging engine of a macro tracker used by someone building lean muscle.
            Convert the user's description and/or photo into a list of food items with estimated macros.
            - One item per distinct food. Combine trivial components only when they are normally eaten together (e.g. "ham sandwich").
            - Use the stated quantity; if none is given, assume a typical single serving and state it in "quantity" (e.g. "1 cup, ~240 g").
            - If a nutrition label is visible, use its exact values scaled to the amount eaten.
            - For branded or restaurant items, use the known published nutrition when you know it.
            - calories in kcal, macros in grams, rounded to whole numbers. Keep calories consistent with 4/4/9 kcal per g of protein/carbs/fat, plus 7 kcal per g of alcohol.
            - Sanity-check calories against the item's weight: nothing exceeds 9 kcal/g (pure fat), chips and fried snacks are about 5 kcal/g, and bread about 2.5-3 kcal/g. If a label's serving differs from the amount eaten, rescale before reporting.
            - carbs_g is total carbohydrate including fiber, and fiber_g is the dietary fiber within it, so fiber_g never exceeds carbs_g. If a label lists carbohydrates excluding fiber, as EU labels do, add the fiber to carbs_g.
            - Keep "name" short (2-5 words), title case.
            - If the input does not describe food, return an empty list.
            - For photos, estimate portions from the size of plates, bowls, cutlery, packaging and other objects of known size.
        """.trimIndent()

        val HAND_SCALE = """
            The user sometimes puts their own hand in the photo for scale. If a hand is visible,
            use these measurements of it to size the food:
        """.trimIndent()

        val HAND_SYSTEM = """
            The user wants a reference of their hand's dimensions, so that later food photos with
            their hand in them can be used to judge portion sizes.
            Measure every hand dimension you can read reliably against the tape measure or ruler in the photo,
            e.g. fingertip-to-fingertip spans, finger lengths and widths, palm width and length.
            - Read the scale carefully: note which side of the tape is inches and which is centimetres.
            - Give each measurement in cm with inches in brackets, one per line, e.g. "Index fingertip to thumb tip spread: 15 cm (5.9 in)".
            - Only include dimensions the ruler actually lets you measure; skip anything you would have to guess.
            - If there is no hand or no readable ruler, return an empty string.
        """.trimIndent()

        val AMOUNT_SYSTEM = """
            You rewrite the amount of a food log entry after the user scaled the portion.
            Multiply every quantity in the amount by the factor and keep everything else the same: units, wording and format.
            - Fix plurals and grammar for the new amount, e.g. "1 cup, ~240 g" at 1.5 is "1.5 cups, ~360 g"; "2 large eggs" at 0.5 is "1 large egg".
            - Use simple fractions or at most one decimal for counts and volumes; round gram and ml figures to whole numbers.
            - If the amount has no number, add one that expresses the new amount, e.g. "a bowl" at 2 is "2 bowls".
            - Return only the rewritten amount.
        """.trimIndent()

        val AMOUNT_SCHEMA = mapOf(
            "type" to "object",
            "properties" to mapOf("amount" to mapOf("type" to "string")),
            "required" to listOf("amount"),
            "additionalProperties" to false,
        )

        val HAND_SCHEMA = mapOf(
            "type" to "object",
            "properties" to mapOf("measurements" to mapOf("type" to "string")),
            "required" to listOf("measurements"),
            "additionalProperties" to false,
        )

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
                "protein_g" to "number", "carbs_g" to "number", "fat_g" to "number", "fiber_g" to "number",
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
