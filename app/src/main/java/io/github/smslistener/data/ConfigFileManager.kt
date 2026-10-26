package io.github.smslistener.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.smslistener.alarm.AlarmController
import io.github.smslistener.core.SentinelState
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One user-selected document. Blocking operations belong on runIo, never the activity thread. */
class ConfigFileManager(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("smslistener", Context.MODE_PRIVATE)
    private val store = AppStore(app)

    data class Status(val uri: Uri?, val name: String, val error: String, val pending: Boolean, val lastSuccess: Long)
    data class UseResult(val alarmStopped: Boolean)
    private data class Contents(val bytes: ByteArray, val state: SentinelState, val hash: String)
    private data class WriteIntent(val uri: String, val oldHash: String, val newHash: String, val revision: Long)
    private class FileFailure(message: String) : IllegalArgumentException(message)

    fun status(): Status {
        val values = prefs.all
        val uri = (values[KEY_URI] as? String)?.let(Uri::parse)
        val revision = values[KEY_REVISION] as? Long ?: 0L
        val saved = values[KEY_SAVED_REVISION] as? Long ?: -1L
        return Status(uri, values[KEY_NAME] as? String ?: "", values[KEY_ERROR] as? String ?: "",
            (uri != null && revision != saved) || values[KEY_WRITE_URI] != null,
            values[KEY_SUCCESS] as? Long ?: 0L)
    }

    /** The picker already created this document. No binding changes until verified bytes exist. */
    fun create(uri: Uri): Unit = operation {
        requireDocument(uri)
        val (state, revision) = store.configurationSnapshot()
        val bytes = encode(state)
        val before = readBytes(uri)
        if (before.isNotEmpty()) decode(before) // Never silently overwrite a malformed existing document.
        val current = status()
        if (current.uri == uri && hash(before) != prefs.getString(KEY_HASH, "") && !before.contentEquals(bytes)) {
            conflict()
        }
        persistGrant(uri)
        writeAndBind(uri, displayName(uri), before, bytes, revision)
    }

    /** Validate before obtaining a durable grant; applying the state and binding is one CAS. */
    fun reference(uri: Uri): UseResult = operation {
        requireDocument(uri)
        val revision = prefs.getLong(KEY_REVISION, 0)
        val local = runCatching { store.configurationSnapshot() }.getOrNull()
            ?.takeIf { it.second == revision }?.first
        val contents = readContents(uri)
        val name = displayName(uri)
        persistGrant(uri)
        adopt(uri, name, contents, local, revision)
    }

    /** Clean local state follows the document; local edits are written only against a known hash. */
    fun synchronize(): Unit = operation {
        val uri = status().uri ?: return@operation
        requireDocument(uri)
        val revision = prefs.getLong(KEY_REVISION, 0)
        val local = runCatching { store.configurationSnapshot() }.getOrNull()
            ?.takeIf { it.second == revision }?.first
        val contents = readContents(uri) // Missing, corrupt, or unreadable files never trigger a write.
        val name = displayName(uri)
        recoverWriteIntent(uri, name, contents)
        val dirty = revision != prefs.getLong(KEY_SAVED_REVISION, -1)
        if (!dirty) {
            // A valid authoritative document repairs a damaged clean cache even if its hash did
            // not change. A concurrent local save is still protected by adopt's revision CAS.
            if (contents.hash == prefs.getString(KEY_HASH, "") && contents.state == local &&
                prefs.getString(KEY_ERROR, "").isNullOrEmpty()) return@operation
            adopt(uri, name, contents, local, revision)
        } else {
            if (local == null) {
                if (prefs.getLong(KEY_REVISION, 0) != revision) {
                    configurationSaved(app)
                    throw FileFailure("读取配置文件期间本机配置已更新，请稍后同步")
                }
                throw FileFailure("本机配置未通过校验，配置文件保持不变，请重新引用有效配置文件")
            }
            if (contents.state == local) {
                // Independent edits which converge to the same state need no rewrite or alarm stop.
                saveMetadata(uri, name, contents.hash, revision)
                rescheduleIfDirty()
            } else {
                if (contents.hash != prefs.getString(KEY_HASH, "")) conflict()
                writeAndBind(uri, name, contents.bytes, encode(local), revision)
            }
        }
    }

    /** Internal lifecycle/test hook: detach the reference, never delete the file or local rules. */
    internal fun detach(): Unit = synchronized(ioLock) {
        synchronized(queueLock) { pending?.cancel(false); pending = null }
        val previous = status().uri
        checkFile(store.commitPreferences(METADATA_KEYS + WRITE_KEYS) {
            METADATA_KEYS.forEach { remove(it) }
            WRITE_KEYS.forEach { remove(it) }
        }, "配置文件引用设置保存失败")
        previous?.let { runCatching { resolver.releasePersistableUriPermission(it, READ_WRITE) } }
    }

    private fun adopt(uri: Uri, name: String, contents: Contents, local: SentinelState?, revision: Long): UseResult {
        if (contents.state == local) {
            saveMetadata(uri, name, contents.hash, revision, expectedRevision = revision)
            return UseResult(true)
        }
        if (!store.applyConfigurationFile(contents.state, uri.toString(), name, contents.hash, revision)) {
            if (prefs.getLong(KEY_REVISION, 0) != revision) {
                configurationSaved(app)
                throw FileFailure("读取配置文件期间本机配置已更新，请重新选择或同步")
            }
            throw FileFailure("配置文件引用保存失败，本机配置和原引用保持不变")
        }
        clearWriteIntent()
        val stopped = runCatching { AlarmController.stop(app) }.isSuccess
        if (!stopped) recordError("配置文件已采用，但停止现有报警未完成")
        runCatching { store.log("已采用配置文件：" + contents.state.rules.size + " 条规则，" + contents.state.forwards.size + " 个转发目标") }
        return UseResult(stopped)
    }

    private fun saveMetadata(uri: Uri, name: String, hash: String, revision: Long, expectedRevision: Long? = null) {
        val saved = store.commitPreferences(METADATA_KEYS + WRITE_KEYS) {
            if (expectedRevision != null && prefs.getLong(KEY_REVISION, 0) != expectedRevision) {
                throw FileFailure("读取配置文件期间本机配置已更新")
            }
            putString(KEY_URI, uri.toString())
            putString(KEY_NAME, name)
            putString(KEY_HASH, hash)
            putLong(KEY_SAVED_REVISION, revision)
            putLong(KEY_SUCCESS, System.currentTimeMillis())
            remove(KEY_ERROR)
            WRITE_KEYS.forEach { remove(it) }
        }
        if (!saved && expectedRevision != null && prefs.getLong(KEY_REVISION, 0) != expectedRevision) {
            configurationSaved(app)
            throw FileFailure("读取配置文件期间本机配置已更新，请重新选择或同步")
        }
        checkFile(saved, "配置文件状态保存失败")
    }

    /**
     * No generic DocumentsProvider offers a cross-file/preferences transaction. Keep a durable
     * write intent, verify before committing metadata, and attempt to put the original bytes back
     * on failure. Recovery recognizes only our exact old/new hashes; other bytes remain untouched.
     */
    private fun writeAndBind(uri: Uri, name: String, before: ByteArray, after: ByteArray, revision: Long) {
        val oldHash = hash(before)
        val newHash = hash(after)
        if (before.contentEquals(after)) {
            saveMetadata(uri, name, newHash, revision)
            rescheduleIfDirty()
            return
        }
        checkFile(store.commitPreferences(WRITE_KEYS) {
            putString(KEY_WRITE_URI, uri.toString())
            putString(KEY_WRITE_OLD_HASH, oldHash)
            putString(KEY_WRITE_NEW_HASH, newHash)
            putLong(KEY_WRITE_REVISION, revision)
        }, "配置文件写入准备失败，文件未修改")
        var touched = false
        try {
            replaceBytes(uri, before, after) { touched = true }
            checkFile(readBytes(uri).contentEquals(after), "配置文件写入后校验失败")
            saveMetadata(uri, name, newHash, revision)
            rescheduleIfDirty()
        } catch (error: Exception) {
            if (!touched) {
                clearWriteIntent()
                throw error
            }
            val restored = rollbackBytes(uri, before, after)
            val message = if (restored) {
                messageFor(error) + "；已回写原内容，本机配置未变"
            } else {
                "配置文件写入未完成，回写原内容也未完成；本机配置已保留，请检查文件"
            }
            recordError(message, clearIntent = restored)
            throw FileFailure(message)
        }
    }

    private fun recoverWriteIntent(uri: Uri, name: String, contents: Contents) {
        val values = prefs.all
        val intent = (values[KEY_WRITE_URI] as? String)?.let {
            WriteIntent(it, values[KEY_WRITE_OLD_HASH] as? String ?: "",
                values[KEY_WRITE_NEW_HASH] as? String ?: "", values[KEY_WRITE_REVISION] as? Long ?: -1L)
        } ?: return
        if (intent.uri != uri.toString()) {
            checkFile(clearWriteIntent(), "旧配置文件写入状态清理失败，请稍后重试")
            return
        }
        // An explicit successful reference may already have committed a newer trusted baseline.
        if (contents.hash == prefs.getString(KEY_HASH, "")) {
            checkFile(clearWriteIntent(), "配置文件写入状态清理失败，请稍后重试")
            return
        }
        when (contents.hash) {
            intent.oldHash -> checkFile(clearWriteIntent(), "配置文件写入状态清理失败，请稍后重试")
            intent.newHash -> saveMetadata(uri, name, contents.hash, intent.revision)
            else -> conflict()
        }
    }

    private fun rollbackBytes(uri: Uri, original: ByteArray, attempted: ByteArray): Boolean {
        val current = runCatching { readBytes(uri) }.getOrNull() ?: return false
        if (current.contentEquals(original)) return true
        // Unknown partial bytes may also belong to an external writer. Only roll back our exact
        // complete output; never reconstruct or overwrite content whose ownership is uncertain.
        if (!current.contentEquals(attempted)) return false
        runCatching { replaceBytes(uri, current, original) {} }
        return runCatching { readBytes(uri).contentEquals(original) }.getOrDefault(false)
    }

    /** Open without truncation. Confirm seek support and unchanged bytes before the first write. */
    private fun replaceBytes(uri: Uri, expected: ByteArray?, replacement: ByteArray, starting: () -> Unit) {
        val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("配置文件打开失败")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            val channel = output.channel
            try {
                val position = channel.position()
                channel.position(position)
                channel.size()
            } catch (_: Exception) {
                throw FileFailure("配置文件提供程序不支持定位写入，文件未修改")
            }
            channel.position(0)
            val actual = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use(::readLimited)
            if (expected != null && !actual.contentEquals(expected)) conflict()
            channel.position(0)
            starting()
            val buffer = ByteBuffer.wrap(replacement)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.truncate(replacement.size.toLong())
            channel.force(true)
        }
    }

    private fun readContents(uri: Uri): Contents {
        val bytes = readBytes(uri)
        return Contents(bytes, decode(bytes), hash(bytes))
    }

    private fun readBytes(uri: Uri): ByteArray {
        requireDocument(uri)
        return (resolver.openInputStream(uri) ?: throw IOException("配置文件读取失败")).use(::readLimited)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            checkFile(bytes.size() + count <= ConfigBackupCodec.MAX_BYTES, "配置文件超过2MiB")
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): SentinelState {
        val raw = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw FileFailure("配置文件不是有效UTF-8文本")
        }
        return try { ConfigBackupCodec.decode(raw) } catch (error: IllegalArgumentException) {
            throw FileFailure("配置文件校验失败：" + error.message.orEmpty().replace("备份", "配置文件"))
        }
    }

    private fun encode(state: SentinelState): ByteArray = try {
        ConfigBackupCodec.encode(state).toByteArray(Charsets.UTF_8)
    } catch (error: IllegalArgumentException) {
        throw FileFailure("配置文件校验失败：" + error.message.orEmpty().replace("备份", "配置文件"))
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun displayName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), Bundle.EMPTY, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }?.takeIf { it.isNotBlank() } ?: "配置文件"
    }.getOrDefault("配置文件")

    private fun persistGrant(uri: Uri) { resolver.takePersistableUriPermission(uri, READ_WRITE) }

    private fun requireDocument(uri: Uri) {
        checkFile(uri.scheme == "content", "请选择系统文件管理器中的配置文件")
    }

    private fun checkFile(condition: Boolean, message: String) {
        if (!condition) throw FileFailure(message)
    }

    private fun conflict(): Nothing = throw FileFailure("配置文件与本机配置均已变化，存在冲突；两边内容均保留，请重新选择采用的文件")

    private fun clearWriteIntent(): Boolean = store.commitPreferences(WRITE_KEYS) { WRITE_KEYS.forEach { remove(it) } }

    private fun recordError(message: String, clearIntent: Boolean = false) {
        runCatching {
            store.commitPreferences(if (clearIntent) WRITE_KEYS + KEY_ERROR else setOf(KEY_ERROR)) {
                putString(KEY_ERROR, message)
                if (clearIntent) WRITE_KEYS.forEach { remove(it) }
            }
        }
    }

    private fun rescheduleIfDirty() {
        if (prefs.contains(KEY_URI) && prefs.getLong(KEY_REVISION, 0) != prefs.getLong(KEY_SAVED_REVISION, -1)) {
            configurationSaved(app)
        }
    }

    private fun <T> operation(block: () -> T): T = synchronized(ioLock) {
        try { block() } catch (error: Exception) {
            recordError(messageFor(error))
            throw error
        }
    }

    companion object {
        const val KEY_URI = "config_file_uri"
        const val KEY_NAME = "config_file_name"
        const val KEY_HASH = "config_file_hash"
        const val KEY_ERROR = "config_file_error"
        const val KEY_SUCCESS = "config_file_success"
        const val KEY_SAVED_REVISION = "config_file_saved_revision"
        const val KEY_REVISION = "config_revision"
        private const val KEY_WRITE_URI = "config_file_write_uri"
        private const val KEY_WRITE_OLD_HASH = "config_file_write_old_hash"
        private const val KEY_WRITE_NEW_HASH = "config_file_write_new_hash"
        private const val KEY_WRITE_REVISION = "config_file_write_revision"
        private val WRITE_KEYS = setOf(KEY_WRITE_URI, KEY_WRITE_OLD_HASH, KEY_WRITE_NEW_HASH, KEY_WRITE_REVISION)
        private val METADATA_KEYS = setOf(KEY_URI, KEY_NAME, KEY_HASH, KEY_ERROR, KEY_SUCCESS, KEY_SAVED_REVISION)
        private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private val ioLock = Any()
        private val queueLock = Any()
        private val worker = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "sentinel-config-file").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private var pending: ScheduledFuture<*>? = null

        fun runIo(block: () -> Unit): Future<*> = worker.submit(block)

        fun configurationSaved(context: Context) = schedule(context, 500)
        fun opened(context: Context) = schedule(context, 0)

        private fun schedule(context: Context, delayMs: Long) {
            val app = context.applicationContext
            if (!app.getSharedPreferences("smslistener", Context.MODE_PRIVATE).contains(KEY_URI)) return
            synchronized(queueLock) {
                pending?.cancel(false)
                pending = worker.schedule({ runCatching { ConfigFileManager(app).synchronize() } }, delayMs, TimeUnit.MILLISECONDS)
            }
        }

        fun messageFor(error: Throwable): String = when (error) {
            is FileFailure -> error.message ?: "配置文件操作未完成"
            is SecurityException -> "配置文件访问权限已失效，请重新选择文件"
            is FileNotFoundException -> "配置文件已删除或暂不可用，请重新选择文件"
            is IllegalArgumentException -> "配置文件校验失败"
            else -> "配置文件读写未完成，请检查文件、存储空间和访问权限"
        }
    }
}
