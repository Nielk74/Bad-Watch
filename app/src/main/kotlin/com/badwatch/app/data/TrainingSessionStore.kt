package com.badwatch.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.badwatch.core.model.TrainingSession
import kotlinx.coroutines.CoroutineScope
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val DATA_STORE_FILE = "training_sessions.json"

@Serializable
data class TrainingSessionLog(
    val sessions: List<TrainingSession> = emptyList()
)

object TrainingSessionLogSerializer : Serializer<TrainingSessionLog> {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    override val defaultValue: TrainingSessionLog = TrainingSessionLog()

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun readFrom(input: InputStream): TrainingSessionLog =
        withContext(Dispatchers.IO) {
            runCatching {
                json.decodeFromString(
                    TrainingSessionLog.serializer(),
                    input.readBytes().decodeToString()
                )
            }.getOrElse { throwable ->
                if (throwable is SerializationException) {
                    defaultValue
                } else {
                    throw throwable
                }
            }
        }

    override suspend fun writeTo(t: TrainingSessionLog, output: OutputStream) =
        withContext(Dispatchers.IO) {
            val content = json.encodeToString(TrainingSessionLog.serializer(), t)
            output.write(content.encodeToByteArray())
        }
}

object TrainingSessionStore {
    fun create(
        context: Context,
        scope: CoroutineScope
    ): DataStore<TrainingSessionLog> =
        DataStoreFactory.create(
            serializer = TrainingSessionLogSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler {
                TrainingSessionLog()
            },
            scope = scope,
            produceFile = { context.dataStoreFile(DATA_STORE_FILE) }
        )
}

interface TrainingSessionRepository {
    val history: Flow<List<TrainingSession>>

    suspend fun persistSession(session: TrainingSession)

    suspend fun clear()
}

class SessionRepository(
    private val store: DataStore<TrainingSessionLog>
) : TrainingSessionRepository {
    override val history = store.data.map { it.sessions }

    override suspend fun persistSession(session: TrainingSession) {
        store.updateData { current ->
            val filtered = current.sessions.filterNot { it.id == session.id }
            current.copy(sessions = (listOf(session) + filtered).take(MAX_SESSIONS))
        }
    }

    override suspend fun clear() {
        store.updateData { TrainingSessionLog() }
    }

    companion object {
        private const val MAX_SESSIONS = 40
    }
}
