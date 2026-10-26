package io.github.smslistener.monitor

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import io.github.smslistener.alarm.AlarmService
import io.github.smslistener.core.*
import io.github.smslistener.data.AppStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class MonitorServiceLifecycleTest {
    private lateinit var app: Application
    private var controller: ServiceController<MonitorService>? = null

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("smslistener", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        controller?.destroy()
        controller = null
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun rule(vararg groups: MatchRule) = AlarmRule(
        id = "r", name = "屏幕测试", matchRules = groups.toList(),
        actions = listOf(RuleAction(ActionType.VIBRATE)),
    )

    private fun start(rule: AlarmRule? = null): MonitorService {
        AppStore(app).saveState(SentinelState(rules = listOfNotNull(rule)))
        controller = Robolectric.buildService(MonitorService::class.java).create()
        return controller!!.get()
    }

    private fun worker(service: MonitorService): ScheduledThreadPoolExecutor =
        MonitorService::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(service) as ScheduledThreadPoolExecutor

    private fun drain(service: MonitorService) {
        shadowOf(Looper.getMainLooper()).idle()
        // The first event may enqueue a zero-delay task behind the first barrier.
        repeat(2) { worker(service).submit {}.get(5, TimeUnit.SECONDS) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun dynamicScreenOffAndUnlockDeliverOnceAndScreenOnDoesNotTriggerRules() {
        val service = start(rule(
            MatchRule(id = "u", source = TriggerSource.UNLOCK),
            MatchRule(id = "l", source = TriggerSource.LOCK),
            MatchRule(id = "l2", source = TriggerSource.LOCK),
        ))
        // Repeated start commands must not register additional receivers.
        service.onStartCommand(null, 0, 1)
        service.onStartCommand(null, 0, 2)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        drain(service)
        assertNull(shadowOf(app).nextStartedService)
        for (action in listOf(Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT)) {
            app.sendBroadcast(Intent(action))
            drain(service)
            val started = shadowOf(app).nextStartedService
            assertNotNull(started)
            assertEquals(AlarmService::class.java.name, started.component?.className)
            assertNull(shadowOf(app).nextStartedService)
        }
    }

    @Test fun staticReceiverIsInertAndDynamicAdapterFiltersUnknownEvents() {
        start(rule(MatchRule(source = TriggerSource.LOCK)))
        WakeReceiver().onReceive(app, Intent(Intent.ACTION_SCREEN_OFF))
        assertNull(shadowOf(app).nextStartedService)
        val seen = mutableListOf<String>()
        val receiver = WakeReceiver { seen += it }
        receiver.onReceive(app, Intent("unrelated"))
        receiver.onReceive(app, Intent())
        receiver.onReceive(app, Intent(Intent.ACTION_SCREEN_ON))
        receiver.onReceive(app, Intent(Intent.ACTION_USER_PRESENT))
        receiver.onReceive(app, Intent(Intent.ACTION_SCREEN_OFF))
        assertEquals(listOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_OFF), seen)
        assertEquals(TriggerSource.LOCK, WakeReceiver.sourceFor(Intent.ACTION_SCREEN_OFF))
        assertEquals(TriggerSource.UNLOCK, WakeReceiver.sourceFor(Intent.ACTION_USER_PRESENT))
        assertNull(WakeReceiver.sourceFor(Intent.ACTION_SCREEN_ON))
    }

    @Test fun destroyUnregistersReceiverAndCancelsServiceOwnedDelays() {
        val service = start(rule(MatchRule(source = TriggerSource.LOCK, delayMs = 600_000)))
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        drain(service)
        val worker = worker(service)
        assertEquals(1, worker.queue.size)
        val receiver = MonitorService::class.java.getDeclaredField("wakeReceiver").apply { isAccessible = true }
            .get(service) as WakeReceiver
        controller!!.destroy()
        controller = null
        assertTrue(worker.isShutdown)
        assertTrue(worker.queue.isEmpty())
        try {
            service.unregisterReceiver(receiver)
            fail("The receiver should already have been unregistered")
        } catch (_: IllegalArgumentException) { }
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun stateChangeCancelsPendingWorkWithoutWaitingForTheDeadline() {
        val original = rule(MatchRule(source = TriggerSource.UNLOCK, delayMs = 600_000))
        val service = start(original)
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        drain(service)
        assertEquals(1, worker(service).queue.size)
        AppStore(app).saveRule(original.copy(enabled = false))
        drain(service)
        assertTrue(worker(service).queue.isEmpty())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun screenOnAndUnlockPerformOneSmsQueryEachAndNeverStartAPollingLoop() {
        val provider = CountingSmsProvider()
        ShadowContentResolver.registerProviderInternal("sms", provider)
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        val service = start()
        drain(service)
        assertEquals(0, provider.queries.get())
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        drain(service)
        assertEquals(1, provider.queries.get())
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        drain(service)
        assertEquals(2, provider.queries.get())
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        drain(service)
        assertEquals(2, provider.queries.get())
        assertFalse(provider.queriedOnMain)
        assertTrue(worker(service).queue.isEmpty())
    }

    @Test fun smsObserverIsEventDrivenAndReleasedOnDestroy() {
        val provider = CountingSmsProvider()
        ShadowContentResolver.registerProviderInternal("sms", provider)
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        val service = start()
        app.contentResolver.notifyChange(Uri.parse("content://sms/inbox"), null)
        drain(service)
        assertEquals(1, provider.queries.get())
        controller!!.destroy()
        controller = null
        app.contentResolver.notifyChange(Uri.parse("content://sms/inbox"), null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, provider.queries.get())
    }

    private class CountingSmsProvider : ContentProvider() {
        val queries = AtomicInteger()
        @Volatile var queriedOnMain = false
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries.incrementAndGet()
            if (Looper.myLooper() == Looper.getMainLooper()) queriedOnMain = true
            return MatrixCursor(arrayOf("_id"))
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
