package net.awkay.spanishreader.core.bakeoff

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.awkay.spanishreader.core.chat.ChatAi
import net.awkay.spanishreader.core.chat.ChatLine
import net.awkay.spanishreader.core.chat.ChatRole
import net.awkay.spanishreader.core.gloss.CliticRole
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.GlossOptions
import net.awkay.spanishreader.core.gloss.GlossProvider
import net.awkay.spanishreader.core.gloss.GlossRequest
import net.awkay.spanishreader.core.gloss.GlossResult
import net.awkay.spanishreader.core.gloss.GlosserConfig
import net.awkay.spanishreader.core.gloss.GlosserFactory
import net.awkay.spanishreader.core.practice.CommonVerb
import net.awkay.spanishreader.core.practice.DrillWriter
import net.awkay.spanishreader.core.practice.PhraseDeck
import net.awkay.spanishreader.core.practice.PhraseWriter
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test

/**
 * Compares AI models on this app's real jobs, through the app's own prompts and parsers: word glosses in context,
 * sentence translation + idioms, lightning-drill cards, say-it sentences, and conversation replies + corrections.
 * Skipped unless BAKEOFF_MODELS is set (comma-separated model ids). Defaults to OpenRouter:
 *   BAKEOFF_MODELS=z-ai/glm-5.3-flash,deepseek/deepseek-v4.1-flash BAKEOFF_OUT=/tmp/bakeoff \
 *   ./gradlew :core:test --tests '*ModelBakeoffTest*'
 * BAKEOFF_BASE_URL (default https://openrouter.ai/api/v1), BAKEOFF_API_KEY or OPENROUTER_API_KEY (a placeholder is
 * sent when neither is set, for environments whose proxy adds the key). Writes one JSON file per model plus
 * summary.json to BAKEOFF_OUT (default build/bakeoff): every answer, automatic checks, time, tokens and cost.
 */
class ModelBakeoffTest {
    private val models = System.getenv("BAKEOFF_MODELS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private val baseUrl = System.getenv("BAKEOFF_BASE_URL")?.takeIf { it.isNotBlank() } ?: "https://openrouter.ai/api/v1"
    private val apiKey = System.getenv("BAKEOFF_API_KEY")?.takeIf { it.isNotBlank() }
        ?: System.getenv("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() } ?: "sk-or-v1-placeholder"
    private val out = File(System.getenv("BAKEOFF_OUT")?.takeIf { it.isNotBlank() } ?: "build/bakeoff")
    private val json = Json { prettyPrint = true; encodeDefaults = false; explicitNulls = false }

    data class Check(val task: String, val name: String, val pass: Boolean, val detail: String)
    data class Call(val millis: Long, val status: Int, val promptTokens: Int, val completionTokens: Int, val cost: Double?, val servedModel: String?)

    /** Times every HTTP call and reads the usage (tokens, and OpenRouter's cost when present) from its answer. */
    private class Meter(private val askCost: Boolean) : Interceptor {
        val calls = ConcurrentLinkedQueue<Call>()
        override fun intercept(chain: Interceptor.Chain): Response {
            var req = chain.request()
            if (askCost) req = withUsage(req)
            val t0 = System.nanoTime()
            val resp = chain.proceed(req)
            val text = runCatching { resp.peekBody(16L shl 20).string() }.getOrDefault("")
            val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val usage = o?.get("usage") as? JsonObject
            fun int(vararg k: String) = k.firstNotNullOfOrNull { (usage?.get(it) as? JsonPrimitive)?.intOrNull } ?: 0
            calls += Call(
                (System.nanoTime() - t0) / 1_000_000, resp.code,
                int("prompt_tokens", "input_tokens"), int("completion_tokens", "output_tokens"),
                (usage?.get("cost") as? JsonPrimitive)?.doubleOrNull, (o?.get("model") as? JsonPrimitive)?.content,
            )
            return resp
        }

        /** Asks OpenRouter to report the cost of each call. */
        private fun withUsage(req: Request): Request {
            val body = req.body ?: return req
            val buf = Buffer().also { body.writeTo(it) }
            val o = runCatching { Json.parseToJsonElement(buf.readUtf8()) as? JsonObject }.getOrNull() ?: return req
            val patched = JsonObject(o + ("usage" to buildJsonObject { put("include", true) }))
            return req.newBuilder().method(req.method, patched.toString().toRequestBody("application/json".toMediaType())).build()
        }
    }

    @Test
    fun bakeoff() = runBlocking {
        assumeTrue(models.isNotEmpty(), "BAKEOFF_MODELS not set")
        out.mkdirs()
        val pricing = runCatching { pricing() }.getOrDefault(emptyMap())
        val gate = Semaphore(4)
        val summaries = models.map { m -> async(Dispatchers.IO) { gate.withPermit { runModel(m, pricing[m]) } } }.awaitAll()
        File(out, "summary.json").writeText(json.encodeToString(JsonElement.serializer(), JsonArray(summaries)))
        println("Bake-off written to ${out.absolutePath}")
        summaries.forEach { println(it) }
    }

    /** Price per token (prompt, completion) by model id, from OpenRouter's public model list. */
    private fun pricing(): Map<String, Pair<Double, Double>> {
        val text = OkHttpClient().newCall(Request.Builder().url("https://openrouter.ai/api/v1/models").build()).execute().use { it.body.string() }
        return (Json.parseToJsonElement(text).jsonObject["data"] as JsonArray).mapNotNull { e ->
            val o = e.jsonObject
            val p = o["pricing"] as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as JsonPrimitive).content
            id to ((p["prompt"] as JsonPrimitive).content.toDouble() to (p["completion"] as JsonPrimitive).content.toDouble())
        }.toMap()
    }

    private suspend fun runModel(model: String, price: Pair<Double, Double>?): JsonObject {
        val meter = Meter(askCost = "openrouter.ai" in baseUrl)
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS).addInterceptor(meter).build()
        val config = GlosserConfig(GlossProvider.OPENAI_COMPATIBLE, baseUrl, apiKey, model)
        val checks = mutableListOf<Check>()
        val answers = buildJsonObject {
            put("model", model)
            put("glosses", task("gloss", checks) { glosses(config, http, checks) })
            put("sentences", task("sentences", checks) { sentences(config, http, checks) })
            put("drill", task("drill", checks) { drill(config, http, checks) })
            put("phrases", task("phrases", checks) { phrases(config, http, checks) })
            put("chat", task("chat", checks) { chat(config, http, checks) })
        }
        val calls = meter.calls.toList()
        val tokensIn = calls.sumOf { it.promptTokens }
        val tokensOut = calls.sumOf { it.completionTokens }
        val reported = calls.mapNotNull { it.cost }
        val cost = if (reported.size == calls.size && calls.isNotEmpty()) reported.sum()
        else price?.let { (pi, po) -> tokensIn * pi + tokensOut * po }
        val summary = buildJsonObject {
            put("model", model)
            put("servedAs", calls.mapNotNull { it.servedModel }.distinct().joinToString(", "))
            put("checksPassed", checks.count { it.pass })
            put("checksTotal", checks.size)
            put("calls", calls.size)
            put("httpErrors", calls.count { it.status !in 200..299 })
            put("totalSeconds", calls.sumOf { it.millis } / 1000.0)
            put("medianCallSeconds", calls.map { it.millis }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] / 1000.0 })
            put("tokensIn", tokensIn)
            put("tokensOut", tokensOut)
            put("costUsd", cost?.let { JsonPrimitive(it) } ?: JsonNull)
            put("failed", buildJsonArray { checks.filter { !it.pass }.forEach { add(JsonPrimitive("${it.task}/${it.name}: ${it.detail}")) } })
        }
        val file = File(out, model.replace('/', '_') + ".json")
        file.writeText(json.encodeToString(JsonElement.serializer(), JsonObject(answers + ("summary" to summary) +
            ("checks" to buildJsonArray { checks.forEach { c -> addJsonObject { put("task", c.task); put("name", c.name); put("pass", c.pass); put("detail", c.detail) } } }))))
        return summary
    }

    /** Runs one job; an exception counts as a failed check and its message is kept. */
    private suspend fun task(name: String, checks: MutableList<Check>, block: suspend () -> JsonElement): JsonElement {
        val t0 = System.nanoTime()
        return try {
            val r = block()
            buildJsonObject { put("seconds", (System.nanoTime() - t0) / 1e9); put("result", r) }
        } catch (e: Throwable) {
            checks += Check(name, "ran", false, "${e::class.simpleName}: ${e.message?.take(300)}")
            buildJsonObject { put("seconds", (System.nanoTime() - t0) / 1e9); put("error", "${e::class.simpleName}: ${e.message?.take(500)}") }
        }
    }

    private fun check(checks: MutableList<Check>, task: String, name: String, pass: Boolean, detail: String) {
        checks += Check(task, name, pass, detail)
    }

    // ---------- the jobs ----------

    private class Case(val form: String, val sentence: String, val name: String, val ok: (Gloss) -> Boolean)

    private val glossCases = listOf(
        Case("banco", "Me senté en un banco del parque.", "banco = bench") { g -> listOf("bench", "seat").any { it in g.meaningInContext.lowercase() } },
        Case("dáselo", "Si Juan quiere el libro, dáselo mañana.", "dáselo: dar + IO + DO") { g ->
            g.verb?.infinitive == "dar" && g.clitics.map { it.kind }.toSet() == setOf(CliticRole.INDIRECT_OBJECT, CliticRole.DIRECT_OBJECT)
        },
        Case("echar", "Voy a echar de menos a mi familia.", "echar de menos = idiom, miss") { g -> g.isIdiomOrPhrase && "miss" in (g.phraseMeaning ?: g.meaningInContext).lowercase() },
        Case("observándome", "Ella estaba observándome desde la puerta.", "observándome: DO clitic") { g -> g.clitics.singleOrNull()?.kind == CliticRole.DIRECT_OBJECT },
        Case("olvidó", "Se me olvidó la llave en casa.", "se me olvidó: me = IO") { g -> g.clitics.any { it.kind == CliticRole.INDIRECT_OBJECT } },
        Case("fui", "Ayer fui al mercado con mi hermana.", "fui here = ir") { g -> g.lemma.trim().lowercase() == "ir" },
        Case("fue", "La fiesta fue increíble.", "fue here = ser") { g -> g.lemma.trim().lowercase() == "ser" },
        Case("vino", "Pedimos una botella de vino tinto.", "vino here = wine (noun)") { g -> "wine" in g.meaningInContext.lowercase() },
        Case("como", "Como pan todos los días.", "como here = comer") { g -> g.lemma.trim().lowercase() == "comer" },
        Case("llevo", "Llevo dos años viviendo en Monterrey.", "llevo = have been (duration)") { g -> listOf("been", "for").any { it in g.meaningInContext.lowercase() } },
        Case("pena", "Me da pena hablar en público.", "dar pena = embarrassed/shy") { g ->
            listOf("embarrass", "shy", "ashamed", "awkward").any { it in (g.meaningInContext + " " + (g.phraseMeaning ?: "")).lowercase() }
        },
        Case("sino", "No es rojo sino azul.", "sino = but (rather)") { g -> "but" in g.meaningInContext.lowercase() || "rather" in g.meaningInContext.lowercase() },
        Case("encaja", "Esa pieza no encaja en el rompecabezas.", "encaja: has roots") { g -> !g.roots.isNullOrBlank() },
        Case("quedé", "Me quedé en casa porque llovía.", "me quedé = stayed") { g -> "stay" in g.meaningInContext.lowercase() },
    )

    private suspend fun glosses(config: GlosserConfig, http: OkHttpClient, checks: MutableList<Check>): JsonElement {
        val glosser = GlosserFactory.create(config, null, http)
        val results = glosser.gloss(glossCases.mapIndexed { i, c -> GlossRequest(c.form, c.sentence, "$i") })
        return buildJsonArray {
            glossCases.forEachIndexed { i, c ->
                val r = results.firstOrNull { it.id == "$i" }
                val g = (r as? GlossResult.Success)?.gloss
                check(checks, "gloss", c.name, g != null && runCatching { c.ok(g) }.getOrDefault(false),
                    g?.let { "${it.lemma} | ${it.meaningInContext} | idiom=${it.isIdiomOrPhrase} ${it.phrase ?: ""} | clitics=${it.clitics.map { c -> c.pronoun + ":" + c.kind }}" }
                        ?: (r as? GlossResult.Failure)?.error ?: "missing")
                addJsonObject {
                    put("form", c.form)
                    put("sentence", c.sentence)
                    if (g != null) put("gloss", Json.encodeToJsonElement(Gloss.serializer(), g)) else put("error", (r as? GlossResult.Failure)?.error ?: "missing")
                }
            }
        }
    }

    private suspend fun sentences(config: GlosserConfig, http: OkHttpClient, checks: MutableList<Check>): JsonElement {
        val input = listOf(
            "Sin embargo, no se dio cuenta de nada.",
            "A lo mejor llueve mañana.",
            "Hace dos años que vivo aquí.",
            "Echo de menos a mi abuela.",
            "El perro come pan.",
        )
        val found = GlosserFactory.createSentenceAnalyzer(config, http).analyze(input)
        fun phrases(i: Int) = found.getOrNull(i)?.phrases.orEmpty().map { it.phrase.lowercase() }
        check(checks, "sentences", "sin embargo + darse cuenta", phrases(0).any { "sin embargo" in it } && phrases(0).any { "cuenta" in it }, phrases(0).toString())
        check(checks, "sentences", "a lo mejor", phrases(1).any { "a lo mejor" in it }, phrases(1).toString())
        check(checks, "sentences", "hace ... que", phrases(2).any { "hace" in it } || "for two years" in found.getOrNull(2)?.translation.orEmpty().lowercase(),
            "${phrases(2)} / ${found.getOrNull(2)?.translation}")
        check(checks, "sentences", "echar de menos", phrases(3).any { "menos" in it }, phrases(3).toString())
        check(checks, "sentences", "plain sentence: dog, no idioms", "dog" in found.getOrNull(4)?.translation.orEmpty().lowercase() && phrases(4).isEmpty(),
            "${found.getOrNull(4)?.translation} ${phrases(4)}")
        return buildJsonArray {
            input.forEachIndexed { i, s ->
                addJsonObject {
                    put("sentence", s)
                    put("translation", found.getOrNull(i)?.translation)
                    putJsonArray("phrases") { found.getOrNull(i)?.phrases.orEmpty().forEach { p -> addJsonObject { put("phrase", p.phrase); put("meaning", p.meaning) } } }
                }
            }
        }
    }

    private suspend fun drill(config: GlosserConfig, http: OkHttpClient, checks: MutableList<Check>): JsonElement {
        val c = GlosserFactory.createCompleter(config, http)
        val cards = DrillWriter(c, c, Random(5)).write(CommonVerb("estar", "to be (state, location)"))
        check(checks, "drill", "at least 8 cards", cards.size >= 8, "${cards.size} cards")
        val notEstar = cards.filter { "est" !in it.answer.lowercase() }
        check(checks, "drill", "answers are forms of estar", notEstar.isEmpty(), notEstar.joinToString { it.answer })
        return buildJsonArray {
            cards.forEach { addJsonObject { put("tense", it.tense); put("subject", it.subject); put("english", it.english); put("spanish", it.spanish); put("answer", it.answer) } }
        }
    }

    private suspend fun phrases(config: GlosserConfig, http: OkHttpClient, checks: MutableList<Check>): JsonElement {
        val c = GlosserFactory.createCompleter(config, http)
        val q = PhraseWriter(c, c, Random(5)).write(PhraseDeck.QUESTIONS, PhraseDeck.QUESTIONS.cards.map { it.english }, 0)
        val v = PhraseWriter(c, c, Random(5)).write(PhraseDeck.VERBS, PhraseDeck.VERBS.cards.map { it.english }, 0)
        check(checks, "phrases", "question batch ≥ 7 usable", q.size >= 7, "${q.size} usable")
        check(checks, "phrases", "verb batch ≥ 7 usable", v.size >= 7, "${v.size} usable")
        return buildJsonArray {
            (q + v).forEach { addJsonObject { put("english", it.english); put("spanish", it.spanish); put("answer", it.answer); it.verb?.let { x -> put("verb", x) }; it.tense?.let { x -> put("tense", x) }; it.note?.let { x -> put("note", x) } } }
        }
    }

    private suspend fun chat(config: GlosserConfig, http: OkHttpClient, checks: MutableList<Check>): JsonElement {
        val ai = ChatAi(GlosserFactory.createCompleter(config, http, GlossOptions(temperature = 0.7)), GlosserFactory.createCompleter(config, http))
        val reply = ai.reply("Un día en la playa", emptyList(), "Ayer fui al playa con mi familia y we ate sándwiches.")
        val reply2 = ai.reply(
            "La comida", listOf(ChatLine(ChatRole.LEARNER, "Me gusta mucho la comida mexicana."), ChatLine(ChatRole.PARTNER, "¡A mí también! ¿Cuál es tu platillo favorito?")),
            "Mi favorito es el pozole, but I don't know how to cook it.",
        )
        val englishWords = Regex("\\b(the|and|you|what|is|are|with)\\b", RegexOption.IGNORE_CASE)
        check(checks, "chat", "replies are short Spanish with English for each sentence",
            listOf(reply, reply2).all { r -> r.isNotEmpty() && r.size <= 4 && r.none { englishWords.containsMatchIn(it.es) } && r.all { it.en.isNotBlank() } },
            (reply + reply2).joinToString(" / ") { it.es })
        val c1 = ai.correct("Ayer fui al playa con mi familia y we ate sándwiches.", null, voice = true)
        val c2 = ai.correct("Ayer comí en un restaurante y el comida es muy bueno.", "¿Qué hiciste ayer?", voice = false)
        val c3 = ai.correct("Me gusta cocinar los fines de semana.", "¿Qué te gusta hacer?", voice = false)
        val c4 = ai.correct("Yo tengo veinte años y vivo en Texas con mi esposa.", null, voice = false)
        check(checks, "chat", "fixes al playa + we ate", "a la playa" in c1.corrected && "comimos" in c1.corrected.lowercase(), c1.corrected)
        check(checks, "chat", "fixes el comida / bueno", "la comida" in c2.corrected && "buena" in c2.corrected, c2.corrected)
        check(checks, "chat", "leaves a correct sentence alone", c3.ok, c3.corrected)
        check(checks, "chat", "doesn't polish a correct simple sentence", c4.ok || c4.corrected.trim() == "Tengo veinte años y vivo en Texas con mi esposa.", c4.corrected)
        return buildJsonObject {
            put("reply1", buildJsonArray { reply.forEach { addJsonObject { put("es", it.es); put("en", it.en) } } })
            put("reply2", buildJsonArray { reply2.forEach { addJsonObject { put("es", it.es); put("en", it.en) } } })
            putJsonArray("corrections") {
                for (c in listOf(c1, c2, c3, c4)) addJsonObject {
                    put("english", c.english); put("corrected", c.corrected); put("ok", c.ok)
                    putJsonArray("changes") { c.changes.forEach { ch -> addJsonObject { put("from", ch.from); put("to", ch.to); put("why", ch.why) } } }
                }
            }
        }
    }
}
