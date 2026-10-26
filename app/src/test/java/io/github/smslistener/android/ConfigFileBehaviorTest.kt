package io.github.smslistener.android

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.smslistener.R
import io.github.smslistener.alarm.AlarmService
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.ForwardTarget
import io.github.smslistener.core.ForwardType
import io.github.smslistener.core.MatchCondition
import io.github.smslistener.core.MatchMode
import io.github.smslistener.core.MatchRule
import io.github.smslistener.core.RuleAction
import io.github.smslistener.core.SentinelState
import io.github.smslistener.core.TriggerSource
import io.github.smslistener.data.AppStore
import io.github.smslistener.data.ConfigBackupCodec
import io.github.smslistener.data.ConfigFileManager
import io.github.smslistener.data.RuleCodec
import io.github.smslistener.ui.ConfigFileActivity
import io.github.smslistener.ui.MainActivity
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowActivity.IntentForResult
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileNotFoundException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Single-document SAF tests. All files, state, logs and cursor values are synthetic fixtures. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class ConfigFileBehaviorTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var store: AppStore
    private lateinit var manager: ConfigFileManager
    private lateinit var provider: FixtureDocumentsProvider
    private val controllers = mutableListOf<ActivityController<out Activity>>()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs = app.getSharedPreferences("smslistener", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        store = AppStore(app)
        manager = ConfigFileManager(app)
        provider = FixtureDocumentsProvider(temporary.newFolder("documents"))
        provider.attachInfo(app, ProviderInfo().apply {
            authority = AUTHORITY
            name = FixtureDocumentsProvider::class.java.name
            packageName = app.packageName
            applicationInfo = app.applicationInfo
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @After fun tearDown() {
        // A runIo barrier does not drain a future debounce. Unbind before allowing fixture cleanup.
        manager.detach()
        provider.beforeRead = null
        provider.beforeWrite = null
        awaitIo()
        ShadowDialog.getShownDialogs().toList().forEach { if (it.isShowing) it.dismiss() }
        controllers.asReversed().forEach { it.close() }
        controllers.clear()
        assertTrue(prefs.edit().clear().commit())
        provider.shutdown()
    }

    @Test fun createWritesOneChosenDocumentAndNeverCreatesSnapshotSiblings() {
        val current = state("local")
        seedState(current)
        seedRuntimeData()
        val uri = provider.seed("chosen.json") // ACTION_CREATE_DOCUMENT already created this document.
        manager.create(uri)
        assertEquals(current, decode(uri))
        assertEquals(current, store.state())
        assertEquals(uri, manager.status().uri)
        assertEquals("chosen.json", manager.status().name)
        assertFalse(manager.status().pending)
        assertTrue(manager.status().lastSuccess > 0)
        assertEquals(1, provider.documentCount())
        assertEquals(0, provider.managerCreates)
        assertEquals(0, provider.managerDeletes)
        assertTrue(provider.events.lastIndexOf("read:" + id(uri)) > provider.events.indexOf("write:" + id(uri)))
        val raw = provider.bytes(uri).toString(Charsets.UTF_8)
        assertFalse(raw.contains("fixture-runtime-log"))
        assertFalse(raw.contains("fixture-fingerprint"))
        assertFalse(raw.contains("last_sms_id"))
    }

    @Test fun referenceUsesWholeExistingStateAndBindingWithoutImportingRuntimeData() {
        seedState(state("local"))
        seedRuntimeData()
        val logs = logEntries()
        val seen = prefs.getString("seen", null)
        val incoming = state("incoming")
        val uri = provider.seed("existing.json", encoded(incoming))
        assertTrue(prefs.edit().putString("state_v2", "{broken-local-cache").commit())
        val result = manager.reference(uri)
        assertTrue(result.alarmStopped)
        assertEquals(incoming, store.state())
        assertEquals(uri, manager.status().uri)
        assertFalse(manager.status().pending)
        assertEquals(808L, store.lastSmsId())
        assertEquals(seen, prefs.getString("seen", null))
        assertTrue(logEntries().containsAll(logs))
        assertEquals("fixture-other-preference", prefs.getString("unrelated_setting", null))
        assertEquals(AlarmService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        assertEquals(0, provider.writeCount())
    }

    @Test fun cancellingEitherPickerOrReturningNoUriDoesNotWriteOrBind() {
        seedState(state("local"))
        val activity = launch(ConfigFileActivity::class.java).get()
        val before = preferences()
        val unused = provider.seed("unused.json", encoded(state("unused")))
        for (create in listOf(true, false)) {
            for (result in listOf(Activity.RESULT_CANCELED, Activity.RESULT_OK)) {
                dismissDialogs()
                val request = pick(activity, create)
                assertEquals(if (create) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
                assertTrue(request.intent.hasCategory(Intent.CATEGORY_OPENABLE))
                assertTrue(request.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertTrue(request.intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                activity.onActivityResult(request.requestCode, result,
                    if (result == Activity.RESULT_CANCELED) Intent().setData(unused) else Intent())
                awaitIo()
                assertEquals(before, preferences())
                assertEquals(0, provider.writeCount())
                assertNull(manager.status().uri)
            }
        }
    }

    @Test fun unrequestedMismatchedAndDuplicateResultsDoNotApplyConfiguration() {
        seedState(state("local"))
        val activity = launch(ConfigFileActivity::class.java).get()
        val first = provider.seed("first.json", encoded(state("first")))
        val other = provider.seed("other.json", encoded(state("other")))
        val before = preferences()
        activity.onActivityResult(ConfigFileActivity.REQUEST_OPEN, Activity.RESULT_OK, Intent().setData(first))
        awaitIo()
        assertEquals(before, preferences())
        val request = pick(activity, create = false)
        activity.onActivityResult(ConfigFileActivity.REQUEST_CREATE, Activity.RESULT_OK, Intent().setData(other))
        awaitIo()
        assertEquals(before, preferences())
        assertFalse(tagged<View>(activity, "reference_config_file").isEnabled)
        activity.onActivityResult(request.requestCode, Activity.RESULT_OK, Intent().setData(first))
        awaitIo()
        val applied = preferences()
        activity.onActivityResult(request.requestCode, Activity.RESULT_OK, Intent().setData(other))
        awaitIo()
        assertEquals(applied, preferences())
        assertEquals(first, manager.status().uri)
        assertEquals(0, provider.writeCount())
    }

    @Test fun recreationRetainsPendingPickerWithoutOpeningItTwice() {
        seedState(state("local"))
        val original = launch(ConfigFileActivity::class.java)
        val request = pick(original.get(), create = false)
        val before = preferences()
        val saved = Bundle()
        original.pause().saveInstanceState(saved).stop().destroy()
        controllers.remove(original)
        val recreated = launch(ConfigFileActivity::class.java, saved).get()
        assertNull(shadowOf(recreated).nextStartedActivityForResult)
        assertFalse(tagged<View>(recreated, "reference_config_file").isEnabled)
        recreated.onActivityResult(request.requestCode, Activity.RESULT_CANCELED, null)
        awaitIo()
        assertTrue(tagged<View>(recreated, "reference_config_file").isEnabled)
        assertEquals(before, preferences())
    }

    @Test fun choosingExistingFileAppliesImmediatelyWithoutASecondConfirmation() {
        seedState(state("local"))
        val incoming = state("incoming")
        val uri = provider.seed("existing.json", encoded(incoming))
        val activity = launch(ConfigFileActivity::class.java).get()
        val request = pick(activity, create = false)
        activity.onActivityResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        awaitIo()
        assertEquals(incoming, store.state())
        assertEquals(uri, manager.status().uri)
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as AlertDialog
        assertTrue(labels(dialog.window!!.decorView).contains("已引用配置文件"))
        assertFalse(labels(dialog.window!!.decorView).any { it.contains("确认恢复") })
        assertTrue(tagged<TextView>(activity, "config_file_status").text.toString().contains("existing.json"))
        assertEquals(0, provider.writeCount())
    }

    @Test fun createWorkDisablesBothControlsAndCreatesOnlyTheSelectedDocument() {
        val current = state("local")
        seedState(current)
        val uri = provider.seed("created.json")
        val activity = launch(ConfigFileActivity::class.java).get()
        val request = pick(activity, create = true)
        pausedWorker {
            activity.onActivityResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            idle()
            assertFalse(tagged<View>(activity, "create_config_file").isEnabled)
            assertFalse(tagged<View>(activity, "reference_config_file").isEnabled)
            tagged<View>(activity, "create_config_file").performClick()
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertNull(manager.status().uri)
        }
        assertEquals(current, decode(uri))
        assertEquals(uri, manager.status().uri)
        assertTrue(tagged<View>(activity, "create_config_file").isEnabled)
        assertEquals(1, provider.documentCount())
    }

    @Test fun referenceStillStopsOldAlarmAfterActivityWasDestroyed() {
        val local = state("local")
        val incoming = state("incoming")
        seedState(local)
        val uri = provider.seed("existing.json", encoded(incoming))
        val controller = launch(ConfigFileActivity::class.java)
        val request = pick(controller.get(), create = false)
        pausedWorker {
            controller.get().onActivityResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            controller.pause().stop().destroy()
            controllers.remove(controller)
            assertEquals(local, store.state())
            assertNull(shadowOf(app).nextStoppedService)
        }
        assertEquals(incoming, store.state())
        assertEquals(AlarmService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        assertFalse(ShadowDialog.getShownDialogs().any { it.isShowing })
    }

    @Test fun homeRulesAndSettingsExposeConfigurationFileWithoutLegacyManagementLabels() {
        val activity = launch(MainActivity::class.java).get()
        val home = tagged<View>(activity, "home_config_file")
        assertTrue(home.isShown)
        assertTrue(home.performClick())
        assertConfigActivityStarted(activity)
        activity.findViewById<View>(R.id.nav_rules).performClick()
        idle()
        val empty = tagged<View>(activity, "use_config_file")
        assertTrue(empty.isShown)
        assertTrue(empty.performClick())
        assertConfigActivityStarted(activity)
        activity.findViewById<View>(R.id.nav_settings).performClick()
        idle()
        clickText(activity, "配置文件")
        assertConfigActivityStarted(activity)
        val page = launch(ConfigFileActivity::class.java).get()
        val allLabels = labels(activity.window.decorView) + labels(page.window.decorView)
        for (removed in listOf("配置备份与恢复", "立即备份", "停止自动备份")) {
            assertFalse("Legacy UI remains: " + removed, allLabels.any { it.contains(removed) })
        }
    }

    @Test fun invalidUtf8OversizedAndBrokenJsonFilesNeverReplaceStateOrBinding() {
        val old = bind(state("local"))
        val raw = prefs.getString("state_v2", null)
        val invalid = listOf("{partial".toByteArray(), "{}".toByteArray(), byteArrayOf(0xC3.toByte(), 0x28),
            ByteArray(ConfigBackupCodec.MAX_BYTES + 1) { ' '.code.toByte() })
        invalid.forEachIndexed { index, bytes ->
            val uri = provider.seed("invalid-" + index + ".json", bytes)
            assertThrows(Exception::class.java) { manager.reference(uri) }
            assertEquals(raw, prefs.getString("state_v2", null))
            assertEquals(old, manager.status().uri)
        }
        assertEquals(state("local"), decode(old))
    }

    @Test fun permissionRevocationOnChosenFileDoesNotReplaceCurrentConfiguration() {
        val old = bind(state("local"))
        val incoming = provider.seed("denied.json", encoded(state("incoming")))
        provider.denied += id(incoming)
        assertThrows(Exception::class.java) { manager.reference(incoming) }
        assertEquals(state("local"), store.state())
        assertEquals(old, manager.status().uri)
        assertEquals(state("local"), decode(old))
    }

    @Test fun referenceCasConflictDoesNotOverwriteAStateSavedWhileReading() {
        val old = bind(state("original"))
        val incoming = provider.seed("incoming.json", encoded(state("remote")))
        val latest = state("latest-local")
        provider.beforeRead = { document ->
            if (document == id(incoming)) {
                provider.beforeRead = null
                assertTrue(store.saveState(latest))
            }
        }
        pausedWorker {
            assertThrows(Exception::class.java) { manager.reference(incoming) }
            assertEquals(latest, store.state())
            assertEquals(old, manager.status().uri)
            assertEquals(state("remote"), decode(incoming))
            assertTrue(manager.status().pending)
        }
    }

    @Test fun freshUnboundStoreCanReferenceSamePublicFileAfterSyntheticDataReset() {
        val saved = state("saved")
        val uri = bind(saved)
        val bytes = provider.bytes(uri)
        assertTrue(prefs.edit().clear().commit()) // Only this Robolectric app's synthetic preferences.
        app.contentResolver.persistedUriPermissions.toList().forEach {
            app.contentResolver.releasePersistableUriPermission(it.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val freshStore = AppStore(app)
        val freshManager = ConfigFileManager(app)
        assertTrue(freshStore.state().rules.isEmpty())
        assertNull(freshManager.status().uri)
        val writes = provider.writeCount()
        freshManager.reference(uri)
        assertEquals(saved, freshStore.state())
        assertEquals(uri, freshManager.status().uri)
        assertArrayEquals(bytes, provider.bytes(uri))
        assertEquals(writes, provider.writeCount())
    }

    @Test fun realSaveStateAutomaticallyWritesSameUriAndClearsPending() {
        val uri = bind(state("original"))
        val writes = provider.writeCount()
        val verified = CountDownLatch(1)
        val latest = state("latest")
        provider.beforeRead = { document ->
            if (document == id(uri) && provider.writeCount() > writes) {
                provider.beforeRead = null
                verified.countDown()
            }
        }
        try {
            assertTrue(store.saveState(latest))
            assertTrue("Automatic write did not reach read-back", verified.await(10, TimeUnit.SECONDS))
            awaitIo()
            assertEquals(latest, decode(uri))
            assertEquals(uri, manager.status().uri)
            assertFalse(manager.status().pending)
            assertEquals(1, provider.documentCount())
            assertEquals(0, provider.managerCreates)
        } finally { provider.beforeRead = null }
    }

    @Test fun saveDuringWriteKeepsPendingUntilLatestRevisionReachesSameFile() {
        val uri = bind(state("original"))
        val first = state("first")
        val latest = state("latest")
        val writes = provider.writeCount()
        val checkpointReady = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latestVerified = CountDownLatch(1)
        var checkpoint: Future<*>? = null
        var pendingAtCheckpoint = false
        var writtenAtCheckpoint: SentinelState? = null
        provider.beforeWrite = { document ->
            if (document == id(uri)) {
                provider.beforeWrite = null
                assertTrue(store.saveState(latest))
                checkpoint = ConfigFileManager.runIo {
                    pendingAtCheckpoint = manager.status().pending
                    writtenAtCheckpoint = decode(uri)
                    checkpointReady.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Checkpoint not released" }
                }
            }
        }
        provider.beforeRead = { document ->
            if (document == id(uri) && provider.writeCount() >= writes + 2 && decode(uri) == latest) {
                provider.beforeRead = null
                latestVerified.countDown()
            }
        }
        try {
            assertTrue(store.saveState(first))
            assertTrue(checkpointReady.await(10, TimeUnit.SECONDS))
            assertTrue(pendingAtCheckpoint)
            assertEquals(first, writtenAtCheckpoint)
            assertEquals(latest, store.state())
            release.countDown()
            requireNotNull(checkpoint).get(10, TimeUnit.SECONDS)
            assertTrue(latestVerified.await(10, TimeUnit.SECONDS))
            awaitIo()
            assertEquals(latest, decode(uri))
            assertFalse(manager.status().pending)
            assertEquals(uri, manager.status().uri)
        } finally {
            provider.beforeWrite = null; provider.beforeRead = null
            release.countDown()
            checkpoint?.get(10, TimeUnit.SECONDS)
        }
    }

    @Test fun cleanSynchronizationReadsExternalChangeWithoutWritingLocalCacheOverIt() {
        val uri = bind(state("original"))
        val external = state("external")
        provider.replace(uri, encoded(external))
        val writes = provider.writeCount()
        // Both malformed raw and an unexpectedly missing state key are repairable when clean.
        for (raw in listOf("{broken-local-cache", null)) {
            assertTrue(prefs.edit().putString("state_v2", raw).commit())
            manager.synchronize()
            assertEquals(external, store.state())
            assertEquals(external, decode(uri))
            assertEquals(writes, provider.writeCount())
            assertFalse(manager.status().pending)
        }
    }

    @Test fun cleanStartupAndReturnReadExternalChangesInsteadOfWritingAnEmptyCache() {
        val uri = bind(SentinelState())
        val first = state("external-first")
        provider.replace(uri, encoded(first))
        val writes = provider.writeCount()
        val controller = launch(MainActivity::class.java)
        awaitIo()
        assertEquals(first, store.state())
        assertTrue(views(controller.get().window.decorView).filterIsInstance<TextView>()
            .any { it.text.toString() == first.rules.first().name })
        assertEquals(writes, provider.writeCount())
        val second = state("external-second")
        controller.pause().stop()
        provider.replace(uri, encoded(second))
        controller.start().resume().visible()
        awaitIo()
        assertEquals(second, store.state())
        assertTrue(views(controller.get().window.decorView).filterIsInstance<TextView>()
            .any { it.text.toString() == second.rules.first().name })
        assertEquals(second, decode(uri))
        assertEquals(writes, provider.writeCount())
    }

    @Test fun dirtySynchronizationWritesLocalRevisionToTheSameBoundUri() {
        val uri = bind(state("original"))
        val latest = state("local-new")
        seedState(latest)
        assertTrue(manager.status().pending)
        manager.synchronize()
        assertEquals(latest, decode(uri))
        assertEquals(uri, manager.status().uri)
        assertFalse(manager.status().pending)
        assertEquals(1, provider.documentCount())
    }

    @Test fun simultaneousExternalAndLocalChangesPreserveBothSidesAsConflict() {
        val uri = bind(state("original"))
        val local = state("local-new")
        val external = state("external-new")
        seedState(local)
        provider.replace(uri, encoded(external))
        val writes = provider.writeCount()
        runCatching { manager.synchronize() }
        assertEquals(local, store.state())
        assertEquals(external, decode(uri))
        assertEquals(uri, manager.status().uri)
        assertEquals(writes, provider.writeCount())
        assertTrue(manager.status().pending)
        assertTrue(manager.status().error.isNotBlank())
    }

    @Test fun missingOrDamagedPublicDocumentPreservesUsableLocalCache() {
        val local = state("local")
        val uri = bind(local)
        val writes = provider.writeCount()
        provider.missing += id(uri)
        runCatching { manager.synchronize() }
        assertEquals(local, store.state())
        assertEquals(uri, manager.status().uri)
        assertTrue(manager.status().error.isNotBlank())
        provider.missing.clear()
        provider.replace(uri, "{broken".toByteArray())
        runCatching { manager.synchronize() }
        assertEquals(local, store.state())
        assertEquals(uri, manager.status().uri)
        assertEquals(writes, provider.writeCount())
        assertArrayEquals("{broken".toByteArray(), provider.bytes(uri))
    }

    @Test fun descriptorWriteFailureRetainsLocalRevisionAndExistingBinding() {
        val uri = bind(state("original"))
        val latest = state("local-new")
        seedState(latest)
        provider.readOnlyNextWrite += id(uri)
        runCatching { manager.synchronize() }
        assertEquals(latest, store.state())
        assertEquals(uri, manager.status().uri)
        assertTrue(manager.status().pending)
        assertTrue(manager.status().error.isNotBlank())
        assertTrue(provider.events.contains("read-only-write:" + id(uri)))
        // A SAF provider may also fail rollback: assert cache/binding, not universal physical atomicity.
    }

    @Test fun readFailureDoesNotOverwritePublicFileOrDiscardDirtyLocalState() {
        val uri = bind(state("original"))
        val original = provider.bytes(uri)
        val latest = state("local-new")
        seedState(latest)
        provider.failReads += id(uri)
        val writes = provider.writeCount()
        runCatching { manager.synchronize() }
        assertEquals(latest, store.state())
        assertEquals(uri, manager.status().uri)
        assertEquals(writes, provider.writeCount())
        assertArrayEquals(original, provider.bytes(uri))
        assertTrue(manager.status().pending)
    }

    @Test fun failedCreateDoesNotSwitchAwayFromPreviouslyBoundFile() {
        val old = bind(state("original"))
        val original = provider.bytes(old)
        val latest = state("local-new")
        seedState(latest)
        val next = provider.seed("new.json")
        provider.failWrites += id(next)
        assertThrows(Exception::class.java) { manager.create(next) }
        assertEquals(old, manager.status().uri)
        assertEquals(latest, store.state())
        assertArrayEquals(original, provider.bytes(old))
    }

    @Test fun failedReadBackVerificationNeverCommitsTheNewBinding() {
        val old = bind(state("original"))
        val latest = state("local-new")
        seedState(latest)
        val next = provider.seed("new.json")
        provider.corruptAfterNextWrite += id(next)
        assertThrows(Exception::class.java) { manager.create(next) }
        assertEquals(old, manager.status().uri)
        assertEquals(latest, store.state())
        assertEquals(state("original"), decode(old))
    }

    @Test fun cleanUnchangedSynchronizationNeverWritesOrCreatesAnotherDocument() {
        val current = state("local")
        val uri = bind(current)
        val writes = provider.writeCount()
        val revision = prefs.getLong(ConfigFileManager.KEY_REVISION, -1)
        repeat(3) { manager.synchronize() }
        assertEquals(current, store.state())
        assertEquals(current, decode(uri))
        assertEquals(writes, provider.writeCount())
        assertEquals(revision, prefs.getLong(ConfigFileManager.KEY_REVISION, -1))
        assertEquals(1, provider.documentCount())
        assertEquals(0, provider.managerCreates)
    }

    @Test fun failedApplyCommitRollsBackConfigurationKeysWithoutRollingBackLogsOrCursor() {
        val old = bind(state("original"))
        seedRuntimeData()
        val before = preferences()
        val incoming = state("incoming")
        val faulty = FaultPreferences(prefs).apply {
            failNextCommit = true
            onFailedCommit = {
                assertEquals(incoming, store.state())
                store.setLastSmsId(909L)
                store.log("fixture-interleaved-log")
            }
        }
        val expected = prefs.getLong(ConfigFileManager.KEY_REVISION, -1)
        assertFalse(AppStore(FaultContext(app, faulty)).applyConfigurationFile(incoming,
            "content://" + AUTHORITY + "/document/new", "new.json", "fixture-hash", expected))
        assertEquals(before - setOf("logs", "last_sms_id"), preferences() - setOf("logs", "last_sms_id"))
        assertEquals(old, manager.status().uri)
        assertEquals(909L, store.lastSmsId())
        assertTrue(store.logs().contains("fixture-interleaved-log"))
        assertTrue(store.logs().contains("fixture-runtime-log"))
    }

    @Test fun staleApplyRevisionIsANoOpIncludingBindingAndSuccessMetadata() {
        bind(state("original"))
        val before = preferences()
        val revision = prefs.getLong(ConfigFileManager.KEY_REVISION, -1)
        assertFalse(store.applyConfigurationFile(state("incoming"), "content://fixture/new", "new.json", "hash", revision - 1))
        assertEquals(before, preferences())
    }

    @Test fun corruptDirtyInternalStateIsNeverExportedAsAnEmptyConfiguration() {
        val uri = bind(state("original"))
        val bytes = provider.bytes(uri)
        val writes = provider.writeCount()
        for (raw in listOf("{}", null)) {
            assertTrue(prefs.edit().putString("state_v2", raw)
                .putLong(ConfigFileManager.KEY_REVISION, prefs.getLong(ConfigFileManager.KEY_REVISION, 0) + 1).commit())
            runCatching { manager.synchronize() }
            assertEquals(raw, prefs.getString("state_v2", null))
            assertEquals(uri, manager.status().uri)
            assertArrayEquals(bytes, provider.bytes(uri))
            assertEquals(writes, provider.writeCount())
        }
    }

    @Test fun referencingAnotherFileThenSavingNeverUsesPreviousFileHashOrUri() {
        val old = bind(state("original"))
        val oldBytes = provider.bytes(old)
        val next = provider.seed("next.json", encoded(state("next")))
        manager.reference(next)
        val latest = state("latest")
        seedState(latest)
        manager.synchronize()
        assertEquals(next, manager.status().uri)
        assertEquals(latest, decode(next))
        assertArrayEquals(oldBytes, provider.bytes(old))
        assertFalse(manager.status().pending)
    }

    private fun bind(current: SentinelState): Uri {
        seedState(current)
        return provider.seed("config.json").also { manager.create(it) }
    }

    private fun seedState(state: SentinelState) {
        assertTrue(prefs.edit().putString("state_v2", RuleCodec.encode(state))
            .putLong(ConfigFileManager.KEY_REVISION, prefs.getLong(ConfigFileManager.KEY_REVISION, 0) + 1).commit())
    }

    private fun seedRuntimeData() {
        store.log("fixture-runtime-log")
        store.setLastSmsId(808L)
        assertFalse(store.isDuplicate("fixture-fingerprint"))
        assertTrue(prefs.edit().putString("unrelated_setting", "fixture-other-preference").commit())
    }

    private fun state(prefix: String): SentinelState {
        val first = ForwardTarget(id = prefix + "-http", name = prefix + "-HTTP", type = ForwardType.HTTP,
            url = "https://example.test/" + prefix, headers = "X-Fixture: true", body = "fixture-body")
        val second = ForwardTarget(id = prefix + "-feishu", name = prefix + "-飞书", type = ForwardType.FEISHU,
            url = "https://example.test/feishu", secret = "fixture-secret-" + prefix)
        return SentinelState(rules = listOf(
            AlarmRule(id = prefix + "-sms", name = "短信规则 " + prefix, description = "合成配置",
                matchRules = listOf(MatchRule(id = prefix + "-condition",
                    body = MatchCondition(MatchMode.CONTAINS, "fixture-" + prefix))),
                actions = listOf(RuleAction(ActionType.FORWARD, forwardIds = listOf(first.id, second.id)),
                    RuleAction(ActionType.RING, enabled = false, delayMs = 321, durationSeconds = 17,
                        repeatCount = 3, soundUri = "content://fixture-tone/" + prefix))),
            AlarmRule(id = prefix + "-screen", name = "屏幕规则 " + prefix, enabled = false,
                matchRules = listOf(MatchRule(id = prefix + "-screen-condition", source = TriggerSource.LOCK, delayMs = 123)),
                actions = listOf(RuleAction(ActionType.FLASH, durationSeconds = 9))),
        ), forwards = listOf(first, second))
    }

    private fun preferences(): Map<String, *> = prefs.all.toMap()
    private fun logEntries(): List<String> = JSONArray(prefs.getString("logs", "[]")).let { array ->
        (0 until array.length()).map { array.getJSONObject(it).toString() }
    }
    private fun encoded(state: SentinelState) = ConfigBackupCodec.encode(state).toByteArray(Charsets.UTF_8)
    private fun decode(uri: Uri) = ConfigBackupCodec.decode(provider.bytes(uri).toString(Charsets.UTF_8))
    private fun id(uri: Uri) = DocumentsContract.getDocumentId(uri)
    private fun <T : Activity> launch(type: Class<T>, saved: Bundle? = null): ActivityController<T> {
        val controller = Robolectric.buildActivity(type, Intent(app, type))
        controllers += controller
        if (saved == null) controller.setup() else controller.setup(saved)
        idle()
        awaitIo()
        return controller
    }
    private fun pick(activity: ConfigFileActivity, create: Boolean): IntentForResult {
        val button = tagged<View>(activity, if (create) "create_config_file" else "reference_config_file")
        assertTrue(button.isEnabled)
        assertTrue(button.performClick())
        idle()
        return requireNotNull(shadowOf(activity).nextStartedActivityForResult)
    }
    private fun assertConfigActivityStarted(activity: Activity) {
        idle()
        assertEquals(ConfigFileActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
    }
    private fun <T : View> tagged(activity: Activity, tag: String): T =
        requireNotNull(activity.window.decorView.findViewWithTag<T>(tag)) { "Missing tag: " + tag }
    private fun clickText(activity: Activity, text: String) {
        var target: View = views(activity.window.decorView).filterIsInstance<TextView>()
            .first { it.isShown && it.text.toString() == text }
        while (!target.isClickable) target = target.parent as? View ?: error("No clickable ancestor: " + text)
        assertTrue(target.performClick()); idle()
    }
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun labels(view: View) = views(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun dismissDialogs() = ShadowDialog.getShownDialogs().toList().forEach { if (it.isShowing) it.dismiss() }
    private fun awaitIo() { ConfigFileManager.runIo {}.get(10, TimeUnit.SECONDS); idle() }
    private fun pausedWorker(block: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = ConfigFileManager.runIo { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try { assertTrue(entered.await(10, TimeUnit.SECONDS)); block() }
        finally { release.countDown(); gate.get(10, TimeUnit.SECONDS); awaitIo() }
    }

    private class FaultPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var failNextCommit = false
        var onFailedCommit: (() -> Unit)? = null
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value); return this
                }
                override fun commit(): Boolean {
                    val fail = failNextCommit; failNextCommit = false
                    val result = editor.commit()
                    if (fail) { onFailedCommit?.invoke(); return false }
                    return result
                }
            }
        }
    }
    private class FaultContext(base: Context, private val preferences: SharedPreferences) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "smslistener") preferences else super.getSharedPreferences(name, mode)
    }

    /** Same DocumentsProvider/PFD approach as the former regression, now with document rather than tree URIs. */
    private class FixtureDocumentsProvider(private val directory: File) : DocumentsProvider() {
        private data class Node(val id: String, val name: String, val file: File)
        private val nodes = linkedMapOf<String, Node>()
        private var sequence = 0
        private val corruptOnRead = mutableSetOf<String>()
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val denied: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val missing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val failReads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val failWrites: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val corruptAfterNextWrite: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val readOnlyNextWrite: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        @Volatile var beforeRead: ((String) -> Unit)? = null
        @Volatile var beforeWrite: ((String) -> Unit)? = null
        var managerCreates = 0
        var managerDeletes = 0
        override fun onCreate() = true

        @Synchronized fun seed(name: String, bytes: ByteArray = byteArrayOf()): Uri {
            val id = "fixture-" + ++sequence
            nodes[id] = Node(id, name, File(directory, id).apply { writeBytes(bytes) })
            return DocumentsContract.buildDocumentUri(AUTHORITY, id)
        }
        @Synchronized fun bytes(uri: Uri): ByteArray = requireNotNull(nodes[DocumentsContract.getDocumentId(uri)]).file.readBytes()
        @Synchronized fun replace(uri: Uri, bytes: ByteArray) {
            requireNotNull(nodes[DocumentsContract.getDocumentId(uri)]).file.writeBytes(bytes)
        }
        @Synchronized fun documentCount() = nodes.size
        fun writeCount() = events.toList().count { it.startsWith("write:") }

        override fun queryRoots(projection: Array<out String>?): Cursor {
            val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE)
            return MatrixCursor(columns).apply { addRow(columns.map { "fixture-root" }.toTypedArray<Any?>()) }
        }
        @Synchronized override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            checkAccess(documentId)
            return rows(projection, listOf(documentId))
        }
        @Synchronized override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
            rows(projection, nodes.keys.toList())
        @Synchronized override fun isChildDocument(parentDocumentId: String, documentId: String) =
            parentDocumentId == "fixture-root" && documentId in nodes
        @Synchronized override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            managerCreates++
            return DocumentsContract.getDocumentId(seed(displayName))
        }
        @Synchronized override fun deleteDocument(documentId: String) {
            managerDeletes++
            check(requireNotNull(nodes.remove(documentId)).file.delete())
        }
        @Synchronized override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            checkAccess(documentId)
            val node = requireNotNull(nodes[documentId])
            if (mode.contains('w')) {
                if (documentId in failWrites) throw FileNotFoundException("fixture write failed")
                events += "write:" + documentId
                beforeWrite?.invoke(documentId)
                if (corruptAfterNextWrite.remove(documentId)) corruptOnRead += documentId
                if (readOnlyNextWrite.remove(documentId)) {
                    events += "read-only-write:" + documentId
                    return ParcelFileDescriptor.open(node.file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            } else {
                if (documentId in failReads) throw FileNotFoundException("fixture read failed")
                events += "read:" + documentId
                beforeRead?.invoke(documentId)
                if (corruptOnRead.remove(documentId)) node.file.writeText("{}")
            }
            return ParcelFileDescriptor.open(node.file, ParcelFileDescriptor.parseMode(mode))
        }
        private fun checkAccess(id: String) {
            if (id in denied) throw SecurityException("fixture permission revoked")
            if (id in missing || id !in nodes) throw FileNotFoundException("fixture document missing")
        }
        private fun rows(projection: Array<out String>?, ids: List<String>): Cursor {
            val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS)
            return MatrixCursor(columns).apply {
                for (id in ids) {
                    val node = requireNotNull(nodes[id])
                    addRow(columns.map { column -> when (column) {
                        Document.COLUMN_DOCUMENT_ID -> id
                        Document.COLUMN_DISPLAY_NAME -> node.name
                        Document.COLUMN_MIME_TYPE -> "application/json"
                        Document.COLUMN_SIZE -> node.file.length()
                        Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
                        else -> null
                    } }.toTypedArray<Any?>())
                }
            }
        }
    }
    private companion object { const val AUTHORITY = "io.github.smslistener.fixture.documents" }
}
