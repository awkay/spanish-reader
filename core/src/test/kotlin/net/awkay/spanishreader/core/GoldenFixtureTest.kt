package net.awkay.spanishreader.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.awkay.spanishreader.core.gloss.GlossCache
import net.awkay.spanishreader.core.gloss.PreGlossPlanner
import net.awkay.spanishreader.core.text.ImportCleaner
import net.awkay.spanishreader.core.text.Paginator
import net.awkay.spanishreader.core.text.PhraseLocator
import net.awkay.spanishreader.core.text.Tokenizer
import net.awkay.spanishreader.core.vocab.WordStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Writes/verifies `fixtures/text-golden.json`: the expected tokenization, sentence fingerprints, pagination, import
 * cleanup, phrase location and pre-gloss planning for sample texts. The web app's TypeScript port is tested against
 * the same file, so Android and web behave identically. Regenerate after an intended change with
 * `UPDATE_GOLDEN=1 ./gradlew :core:test --tests '*GoldenFixtureTest*'`.
 */
class GoldenFixtureTest {
    private val samples = listOf(
        "El perro come pan. La casa es grande.",
        "—¿Vienes? —preguntó ella.\nNo sé… quizás mañana.",
        "El Sr. Pérez y la Dra. López llegaron a las 3.30 p.m. con 1.500 pesos, etc. Luego se fueron.",
        "«¡Qué bonito!», dijo Ana. J. K. Rowling escribió libros...  Hoy  es lunes.",
        "Dámelo ahora. Ella estaba observándome desde la puerta; no se dio cuenta de nada.",
        "Sí, si quieres. ¿Por qué? Porque Ñandú y ÁRBOL están en mayúsculas.",
        "Línea uno\nLínea dos\n\nPárrafo nuevo con rock'n'roll y bien-estar.",
        "Usa la compu-\ntadora hoy.\r\nEl niño que\nvivía en el campo.",
        "Sin embargo, no se dio cuenta. A lo mejor llueve. Voy a echar de menos a mi familia; me echa mucho de menos.",
        "   \n  Uno.   Dos!   ¿Tres?  ",
    )

    private fun build(): String {
        val root = buildJsonObject {
            putJsonArray("texts") {
                for (s in samples) addJsonObject {
                    put("source", s)
                    val t = Tokenizer.tokenize(s)
                    putJsonArray("tokens") {
                        for (tok in t.tokens) addJsonArray {
                            add(tok.text)
                            add(tok.kind.name)
                            add(tok.normalized)
                            add(tok.sentenceIndex)
                        }
                    }
                    putJsonArray("sentences") {
                        for (i in t.sentenceRanges.indices) addJsonArray {
                            add(t.sentenceText(i))
                            add(GlossCache.sentenceHash(t.sentenceText(i)))
                        }
                    }
                    // Pages as [firstTokenIndex, lastTokenIndex] at a tiny page size so breaks are exercised.
                    putJsonArray("pages6") {
                        for (p in Paginator.paginate(t, 6)) addJsonArray {
                            add(p.tokens.first().index)
                            add(p.tokens.last().index)
                        }
                    }
                    put("cleaned", ImportCleaner.clean(s))
                    put("cleanedNoJoin", ImportCleaner.clean(s, joinWrappedLines = false))
                    put("title", ImportCleaner.suggestTitle(s))
                    // Pre-gloss plan with "el" KNOWN and "la" IGNORED, max 2 sentences per form.
                    putJsonArray("plan") {
                        val plan = PreGlossPlanner.plan(t, mapOf("el" to WordStatus.KNOWN, "la" to WordStatus.IGNORED), 2)
                        for (r in plan) addJsonArray {
                            add(r.form)
                            add(r.sentence)
                        }
                    }
                }
            }
            putJsonArray("formKeys") {
                for (w in listOf("Hablo", "SÍ", "Ñandú", "rock’n’roll", "ÁRBOL", "él")) addJsonArray {
                    add(w)
                    add(GlossCache.formKey(w))
                }
            }
            putJsonArray("phraseLocate") {
                val cases = listOf(
                    "Voy a echar de menos a mi familia." to "echar de menos",
                    "Me echa mucho de menos." to "echa de menos",
                    "Sin embargo, no se dio cuenta." to "se dio cuenta",
                    "Sin embargo, no se dio cuenta." to "a lo mejor",
                    "De repente, de repente llegó." to "de répente",
                )
                for ((sentence, phrase) in cases) addJsonObject {
                    put("sentence", sentence)
                    put("phrase", phrase)
                    val hit = PhraseLocator.locate(Tokenizer.tokenize(sentence).tokens, phrase)
                    put("tokenIndices", hit?.let { JsonArray(it.map { t -> JsonPrimitive(t.index) }) } ?: JsonPrimitive(null as String?))
                }
            }
        }
        return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), root) + "\n"
    }

    /** The AI prompts, so the web app sends exactly what Android sends (single source of truth: GlossPrompt.kt). */
    private fun prompts(): String {
        val previous = net.awkay.spanishreader.core.gloss.Gloss("banco", "banco", "noun", "bank")
        val root = buildJsonObject {
            put("glossSystem", net.awkay.spanishreader.core.gloss.GlossPrompt.SYSTEM)
            put("sentenceSystem", net.awkay.spanishreader.core.gloss.GlossPrompt.SENTENCE_SYSTEM)
            put("improveNote", net.awkay.spanishreader.core.gloss.GlossPrompt.IMPROVE_NOTE)
            put("sampleUser", net.awkay.spanishreader.core.gloss.GlossPrompt.user(listOf(
                net.awkay.spanishreader.core.gloss.GlossRequest("banco", "Me senté en el banco.", "1"),
                net.awkay.spanishreader.core.gloss.GlossRequest("dáselo", "Dáselo \"ya\".", "2"),
            )))
            put("sampleUserImprove", net.awkay.spanishreader.core.gloss.GlossPrompt.user(listOf(
                net.awkay.spanishreader.core.gloss.GlossRequest("banco", "Me senté en el banco.", "1", previous = previous),
            ), isRetry = true))
            put("sampleSentenceUser", net.awkay.spanishreader.core.gloss.GlossPrompt.sentenceUser(listOf("0" to "Hola.", "1" to "¿Qué tal?")))
            val p = net.awkay.spanishreader.core.practice.PracticePrompt
            val spec = net.awkay.spanishreader.core.practice.PracticeSpec(
                net.awkay.spanishreader.core.practice.PracticeVerb("ir", "to go", listOf("vamos")),
                net.awkay.spanishreader.core.practice.VerbTenses.byId("past-near-future")!!,
                net.awkay.spanishreader.core.practice.Subject.NOSOTROS,
                listOf("casa", "aeropuerto", "\"día\""),
            )
            val noMeaning = spec.copy(verb = spec.verb.copy(meaning = null))
            val ex = net.awkay.spanishreader.core.practice.VerbExercise("We were going to go to the airport.", "Íbamos a ir al aeropuerto.")
            put("practiceExerciseSystem", p.EXERCISE_SYSTEM)
            put("practiceCheckSystem", p.CHECK_SYSTEM)
            put("practiceMaxTries", p.MAX_TRIES)
            put("practiceTenses", buildJsonArray {
                net.awkay.spanishreader.core.practice.VerbTenses.ALL.forEach { t ->
                    addJsonObject {
                        put("id", t.id); put("label", t.label); put("example", t.example); put("instruction", t.instruction)
                        put("subjects", buildJsonArray { t.subjects.forEach { add(JsonPrimitive(it.name)) } })
                    }
                }
            })
            put("practiceSubjects", buildJsonArray {
                net.awkay.spanishreader.core.practice.Subject.entries.forEach { s ->
                    addJsonObject { put("id", s.name); put("spanish", s.spanish); put("english", s.english) }
                }
            })
            put("samplePracticeUser", p.exerciseUser(spec))
            put("samplePracticeUserNoMeaningRetry", p.exerciseUser(noMeaning, isRetry = true))
            put("samplePracticeCheck", p.checkUser(spec, ex, listOf("Vamos al aeropuerto", "Ibamos al aeropuerto"), "Íbamos a ir al aeropuerto"))
            val d = net.awkay.spanishreader.core.practice.Drill
            put("drillSystem", d.SYSTEM)
            put("drillReviewSystem", d.REVIEW_SYSTEM)
            put("sampleDrillReview", d.reviewUser(
                net.awkay.spanishreader.core.practice.CommonVerb("ser", "to be"),
                listOf(net.awkay.spanishreader.core.practice.DrillCard("ser#2", "ser", "preterite", "EL", "He was \"there\".", "Él ___ allí.", "fue", listOf("x"))),
                isRetry = true,
            ))
            put("drillSet", buildJsonArray { d.SET.forEach { add(JsonPrimitive(it)) } })
            put("drillExtra", buildJsonArray { d.EXTRA.forEach { add(JsonPrimitive(it)) } })
            put("drillToLearn", d.TO_LEARN)
            put("drillCardsPerVerb", d.CARDS_PER_VERB)
            put("commonVerbs", buildJsonArray {
                net.awkay.spanishreader.core.practice.CommonVerbs.ALL.forEach { v -> addJsonObject { put("infinitive", v.infinitive); put("meaning", v.meaning) } }
            })
            put("sampleDrillUser", d.user(
                net.awkay.spanishreader.core.practice.CommonVerb("ir", "to go"),
                listOf(
                    net.awkay.spanishreader.core.practice.VerbTenses.byId("preterite")!! to net.awkay.spanishreader.core.practice.Subject.NOSOTROS,
                    net.awkay.spanishreader.core.practice.VerbTenses.byId("imperative")!! to net.awkay.spanishreader.core.practice.Subject.TU,
                ),
                isRetry = true,
            ))
            put("samplePracticeCheckFirst", p.checkUser(spec, ex.copy(verbForm = "íbamos a ir"), emptyList(), "x"))
            put("samplePracticeCheckSecond", p.checkUser(spec, ex.copy(verbForm = "íbamos a ir"), listOf("y"), "x"))
            put("samplePracticeCheckSecondNoForm", p.checkUser(spec, ex, listOf("y"), "x"))
        }
        return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), root) + "\n"
    }

    @Test
    fun `prompts fixture is current`() {
        val file = File("../fixtures/prompts.json")
        val expected = prompts()
        if (System.getenv("UPDATE_GOLDEN") == "1" || !file.exists()) file.writeText(expected)
        assertEquals(expected, file.readText(), "fixtures/prompts.json is stale; regenerate with UPDATE_GOLDEN=1")
    }

    @Test
    fun `golden fixture is current`() {
        val file = File("../fixtures/text-golden.json")
        val expected = build()
        if (System.getenv("UPDATE_GOLDEN") == "1" || !file.exists()) file.writeText(expected)
        assertEquals(expected, file.readText(), "fixtures/text-golden.json is stale; regenerate with UPDATE_GOLDEN=1")
    }
}
