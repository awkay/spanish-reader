package net.awkay.spanishreader.share

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.awkay.spanishreader.core.backup.BackupCodec
import net.awkay.spanishreader.core.backup.BackupFile
import net.awkay.spanishreader.core.backup.BackupLesson
import net.awkay.spanishreader.core.backup.BackupVocab
import net.awkay.spanishreader.core.backup.Sync
import net.awkay.spanishreader.core.backup.SyncMeta
import net.awkay.spanishreader.core.backup.SyncState
import net.awkay.spanishreader.core.practice.DrillProgress
import net.awkay.spanishreader.data.AppDatabase
import net.awkay.spanishreader.data.DrillProgressEntity
import net.awkay.spanishreader.data.LessonEntity
import net.awkay.spanishreader.data.SettingsRepository
import net.awkay.spanishreader.data.VocabEntity
import java.io.IOException

sealed interface SyncOutcome {
    data class Done(val message: String) : SyncOutcome

    /** Sync up: someone pushed since this device last synced. */
    data class ServerNewer(val current: SyncMeta) : SyncOutcome

    /** Sync down: this device changed since it last synced ([state] null: never synced). */
    data class LocalChanges(val state: SyncState?) : SyncOutcome
}

data class SyncStatus(val state: SyncState?, val server: SyncMeta?, val serverError: String?, val localChanges: Boolean)

/**
 * Manual sync of lessons (with the current page) and vocabulary with the web app account (see core Sync.kt).
 * Up replaces the server's copy, down replaces this device's; each reports first when it would lose newer work.
 * Word meanings go through the shared sentence cache: pushed after sync up, fetched after sync down.
 */
class SyncService(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val web: WebShareService,
    private val device: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lenient = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun status(): SyncStatus {
        val state = state()
        val local = Sync.hasLocalChanges(state, localState())
        return try {
            SyncStatus(state, meta(), null, local)
        } catch (e: Exception) {
            SyncStatus(state, null, e.message ?: e.toString(), local)
        }
    }

    suspend fun up(force: Boolean = false, progress: (String) -> Unit = {}): SyncOutcome {
        val snap = localState()
        val body = buildJsonObject {
            put("baseRevision", state()?.revision ?: 0)
            put("force", force)
            put("device", device)
            put("snapshot", Json.parseToJsonElement(BackupCodec.encode(snap)))
        }
        val (code, text) = call("PUT", "/api/sync", body.toString())
        if (code == 409) {
            val current = (Json.parseToJsonElement(text) as JsonObject)["current"]
            return SyncOutcome.ServerNewer(current?.let { lenient.decodeFromJsonElement(SyncMeta.serializer(), it) } ?: SyncMeta())
        }
        check(code, text, "Sync up")
        val meta = lenient.decodeFromString(SyncMeta.serializer(), text)
        saveState(meta.revision, "up")
        progress("Sharing word meanings…")
        runCatching { web.pushCache(db.lessons().getAll()) }
        return SyncOutcome.Done("Synced up: ${snap.lessons.size} lessons and ${snap.vocab.size} words (server revision ${meta.revision}).")
    }

    suspend fun down(force: Boolean = false, progress: (String) -> Unit = {}): SyncOutcome {
        val state = state()
        if (!force && Sync.hasLocalChanges(state, localState())) return SyncOutcome.LocalChanges(state)
        val (code, text) = call("GET", "/api/sync")
        check(code, text, "Sync down")
        val o = Json.parseToJsonElement(text) as JsonObject
        val meta = lenient.decodeFromJsonElement(SyncMeta.serializer(), o)
        val snapJson = o["snapshot"]?.takeIf { it is JsonObject }
        if (meta.revision == 0 || snapJson == null) throw IOException("Nothing on the server yet. Sync up from a device first.")
        val snap = BackupCodec.decode(snapJson.toString())
        db.withTransaction {
            val local = db.lessons().getAll()
            val plan = Sync.replacePlan(local.map { it to it.toBackup() }, snap.lessons)
            plan.delete.forEach { db.lessons().delete(it) }
            plan.update.forEach { (l, b) ->
                db.lessons().update(l.copy(title = b.title, currentPage = b.currentPage, videoId = b.videoId, sourceUrl = b.sourceUrl))
            }
            plan.insert.forEach { b ->
                db.lessons().insert(
                    LessonEntity(
                        title = b.title, text = b.text, createdAtMillis = b.createdAtMillis, currentPage = b.currentPage,
                        videoId = b.videoId, sourceUrl = b.sourceUrl,
                    ),
                )
            }
            db.vocab().deleteAll()
            db.vocab().upsertAll(snap.vocab.map { VocabEntity.from(it.toEntry()) })
            db.drill().clearProgress()
            db.drill().upsertProgress(snap.drill.map { DrillProgressEntity(it.cardId, it.correct, it.misses, it.lastMillis, it.hidden) })
        }
        saveState(meta.revision, "down")
        progress("Fetching word meanings…")
        for (l in db.lessons().getAll()) runCatching { web.pullCacheFor(l.text) }
        val from = meta.device.ifBlank { "another device" }
        return SyncOutcome.Done("Synced down: ${snap.lessons.size} lessons and ${snap.vocab.size} words (server revision ${meta.revision}, from $from).")
    }

    private suspend fun meta(): SyncMeta {
        val (code, text) = call("GET", "/api/sync/meta")
        check(code, text, "Sync")
        return lenient.decodeFromString(SyncMeta.serializer(), text)
    }

    /** Needs an account; a session token from before the account was set is replaced once (the server says 403). */
    private suspend fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
        if (settings.current().webUser.isBlank()) throw IOException("Set your account name in Settings → Web app first.")
        val first = web.request(method, path, body)
        if (first.first != 403) return first
        settings.update { it.copy(webToken = "") }
        return web.request(method, path, body)
    }

    private fun check(code: Int, text: String, what: String) {
        if (code !in 200..299) throw IOException(web.errorMessage(text) ?: "$what failed (HTTP $code)")
    }

    private suspend fun localState(): BackupFile = Sync.snapshot(
        db.lessons().getAll().map { it.toBackup() },
        db.vocab().getAllOnce().map { BackupVocab.from(it.toEntry()) },
        clock(),
        db.drill().progress().map { DrillProgress(it.cardId, it.correct, it.misses, it.lastMillis, it.hidden) },
    )

    private suspend fun state(): SyncState? {
        val s = settings.current()
        // Stored as {"user": ..., "state": SyncState}; another account's state doesn't count.
        val o = runCatching { Json.parseToJsonElement(s.syncState) as JsonObject }.getOrNull() ?: return null
        if ((o["user"] as? kotlinx.serialization.json.JsonPrimitive)?.content != s.webUser) return null
        return runCatching { lenient.decodeFromJsonElement(SyncState.serializer(), o["state"]!!) }.getOrNull()
    }

    private suspend fun saveState(revision: Int, direction: String) {
        val state = SyncState(revision, Sync.fingerprint(localState()), clock(), direction)
        val user = settings.current().webUser
        val stored = buildJsonObject {
            put("user", user)
            put("state", Json.encodeToJsonElement(SyncState.serializer(), state))
        }
        settings.update { it.copy(syncState = stored.toString()) }
    }

    private fun LessonEntity.toBackup() = BackupLesson(title, text, createdAtMillis, currentPage, videoId, sourceUrl)
}
