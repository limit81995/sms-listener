package io.github.smslistener.alarm

import io.github.smslistener.core.ActionOperation
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.StopScope
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM tests: every timestamp is supplied explicitly; no Android, sleeps or wall-clock reads. */
class AlarmSessionEngineTest {
    private val engine = AlarmSessionEngine()

    @Test fun finiteRuleNeverTruncatesAnotherRulesUnlimitedContribution() {
        engine.submit(listOf(start("finite", duration = 1), start("unlimited")), 0)
        assertEquals(listOf("finite", "unlimited"), rules())
        assertEquals(1000L, engine.nextWakeAt())
        engine.advance(1000)
        assertEquals(listOf("unlimited"), rules())
        assertTrue(engine.hasWork())
        assertNull(engine.nextWakeAt())
        engine.advance(1_000_000)
        assertEquals(listOf("unlimited"), rules())
    }

    @Test fun sameRuleDifferentSlotsRetainIndependentDeadlines() {
        engine.submit(listOf(start(slot = "short", duration = 1), start(slot = "long", duration = 3)), 100)
        engine.advance(1100)
        assertEquals(listOf("long"), engine.activeOutputs().map { it.slotId })
        assertEquals(3100L, engine.nextWakeAt())
        engine.advance(3100)
        assertEmpty()
    }

    @Test fun thisRuleStopRemovesOnlyItsChosenChannelIncludingQueuedStarts() {
        engine.submit(listOf(start("a", "ring-one"), start("a", "ring-later", delay = 500),
            start("a", "vibrate", ActionType.VIBRATE), start("b", "ring")), 0)
        engine.submit(listOf(stop("a")), 100)
        assertEquals(listOf("a" to ActionType.VIBRATE, "b" to ActionType.RING), outputs())
        engine.advance(1000)
        assertEquals(listOf("a" to ActionType.VIBRATE, "b" to ActionType.RING), outputs())
        assertNull(engine.nextWakeAt())
    }

    @Test fun specificRuleStopTargetsAnotherRuleNotItsOwnerOrOtherChannels() {
        engine.submit(listOf(start("a"), start("b"), start("b", "later", delay = 500),
            start("b", "flash", ActionType.FLASH), start("c")), 0)
        engine.submit(listOf(stop("a", scope = StopScope.SPECIFIC_RULE, target = "b")), 100)
        assertEquals(listOf("a" to ActionType.RING, "b" to ActionType.FLASH, "c" to ActionType.RING), outputs())
        engine.advance(500)
        assertEquals(listOf("a", "b", "c"), rules())
    }

    @Test fun allRulesStopIsStillRestrictedToTheSelectedChannel() {
        engine.submit(listOf(start("a"), start("b"), start("c", delay = 100),
            start("a", "vibrate", ActionType.VIBRATE), start("b", "flash", ActionType.FLASH)), 0)
        engine.submit(listOf(stop("unrelated", scope = StopScope.ALL_RULES)), 10)
        assertEquals(listOf("a" to ActionType.VIBRATE, "b" to ActionType.FLASH), outputs())
        engine.advance(1000)
        assertEquals(listOf("a" to ActionType.VIBRATE, "b" to ActionType.FLASH), outputs())
    }

    @Test fun blankSpecificTargetDoesNotAccidentallyActAsAllRulesOrThisRule() {
        engine.submit(listOf(start("a"), start("b")), 0)
        engine.submit(listOf(stop("a", scope = StopScope.SPECIFIC_RULE)), 1)
        assertEquals(listOf("a", "b"), rules())
    }

    @Test fun immediateStopOnlyBatchProducesNoOutputOrRemainingWork() {
        engine.submit(listOf(stop("a"), stop("b", type = ActionType.FLASH, scope = StopScope.ALL_RULES)), 10)
        assertEmpty()
    }

    @Test fun delayedStopOnlyHasWorkAndWakeTimeButNeverCreatesAnOutput() {
        engine.submit(listOf(stop(delay = 500)), 100)
        assertTrue(engine.activeOutputs().isEmpty())
        assertTrue(engine.hasWork())
        assertEquals(600L, engine.nextWakeAt())
        engine.advance(599)
        assertTrue(engine.hasWork())
        engine.advance(600)
        assertEmpty()
    }

    @Test fun stoppingIsOneShotAndAFutureStartOfSameSlotCanRunAgain() {
        engine.submit(listOf(start(), stop()), 0)
        assertEmpty()
        engine.submit(listOf(start()), 100)
        assertEquals(100L, engine.activeOutputs().single().startedAt)
        engine.submit(listOf(stop(scope = StopScope.ALL_RULES)), 200)
        engine.submit(listOf(start()), 300)
        assertEquals(300L, engine.activeOutputs().single().startedAt)
    }

    @Test fun delayedStartUsesPlannedTimeAndHasNoOutputBeforeItsDueTime() {
        engine.submit(listOf(start(delay = 500, duration = 2)), 100)
        assertTrue(engine.activeOutputs().isEmpty())
        assertEquals(600L, engine.nextWakeAt())
        engine.advance(599)
        assertTrue(engine.activeOutputs().isEmpty())
        engine.advance(900)
        assertEquals(600L, engine.activeOutputs().single().startedAt)
        assertEquals(2600L, engine.nextWakeAt())
        engine.advance(2600)
        assertEmpty()
    }

    @Test fun delayedStopResolvesContributionsPresentAtExecutionNotAtSubmission() {
        engine.submit(listOf(stop("owner", scope = StopScope.ALL_RULES, delay = 500)), 0)
        engine.submit(listOf(start("new-rule"), start("queued", delay = 700),
            start("new-rule", "vibrate", ActionType.VIBRATE)), 100)
        engine.advance(500)
        assertEquals(listOf("new-rule" to ActionType.VIBRATE), outputs())
        engine.advance(1000)
        assertEquals(listOf("new-rule" to ActionType.VIBRATE), outputs())
    }

    @Test fun immediateStopCancelsQueuedStartSoLateAdvanceCannotReviveIt() {
        engine.submit(listOf(start(delay = 1000)), 0)
        engine.submit(listOf(stop()), 100)
        assertEmpty()
        engine.advance(100_000)
        assertEmpty()
    }

    @Test fun immediateBatchOrderingIsStartThenStopOffAndStopThenStartOn() {
        engine.submit(listOf(start(), stop()), 0)
        assertEmpty()
        engine.submit(listOf(stop(), start()), 10)
        assertEquals(listOf("a"), rules())
        assertEquals(10L, engine.activeOutputs().single().startedAt)
    }

    @Test fun equalDelayedDueTimesHonorStopThenStartAndStartThenStopOrder() {
        engine.submit(listOf(stop(delay = 100), start(delay = 100)), 0)
        engine.advance(100)
        assertEquals(listOf("a"), rules())
        engine.clear()
        engine.submit(listOf(start(delay = 100), stop(delay = 100)), 0)
        engine.advance(100)
        assertEmpty()
    }

    @Test fun stopCancelsStrictlyLaterPendingStartEvenWhenStartWasSubmittedAfterStop() {
        engine.submit(listOf(stop(delay = 100), start(delay = 101)), 0)
        engine.advance(100)
        assertEmpty()
        engine.advance(101)
        assertEmpty()
        engine.submit(listOf(start(delay = 101), stop(delay = 100)), 200)
        engine.advance(1000)
        assertEmpty()
    }

    @Test fun eventDueTimeTakesPriorityOverSubmissionOrderAcrossALargeAdvance() {
        engine.submit(listOf(stop(delay = 200), start(delay = 100)), 0)
        engine.advance(100)
        assertEquals(listOf("a"), rules())
        assertEquals(200L, engine.nextWakeAt())
        engine.advance(100_000)
        assertEmpty()
    }

    @Test fun lateWakeSkipsAnEntireFiniteRunInsteadOfRestartingItsDuration() {
        engine.submit(listOf(start(delay = 100, duration = 1)), 0)
        engine.advance(5000)
        assertEmpty()
    }

    @Test fun activeDuplicateCannotResetStartDeadlineOrOriginalParameters() {
        val original = start(duration = 2, sound = "tone-one").copy(reason = "first", fromSms = true)
        engine.submit(listOf(original), 100)
        val output = engine.activeOutputs().single()
        engine.submit(listOf(original.copy(durationSeconds = 300, soundUri = "tone-two", reason = "later")), 900)
        assertEquals(output, engine.activeOutputs().single())
        assertEquals(2100L, engine.nextWakeAt())
        assertEquals("first", output.reason)
        assertTrue(output.fromSms)
        engine.advance(2100)
        assertEmpty()
    }

    @Test fun pendingDuplicateCannotPostponeStartOrExpiration() {
        engine.submit(listOf(start(delay = 1000, duration = 1)), 0)
        engine.submit(listOf(start(delay = 5000, duration = 60)), 100)
        assertEquals(1000L, engine.nextWakeAt())
        engine.advance(1000)
        assertEquals(1000L, engine.activeOutputs().single().startedAt)
        assertEquals(2000L, engine.nextWakeAt())
        engine.advance(2000)
        assertEmpty()
    }

    @Test fun triggerAtExactExpiryIsANewRunRatherThanAnOldDuplicate() {
        engine.submit(listOf(start(duration = 1)), 0)
        engine.submit(listOf(start(duration = 1)), 1000)
        assertEquals(1000L, engine.activeOutputs().single().startedAt)
        assertEquals(2000L, engine.nextWakeAt())
    }

    @Test fun repeatedTriggerFloodDoesNotAccumulateContributionsOrExtendAFiniteRun() {
        repeat(2000) { engine.submit(listOf(start(duration = 3)), it.toLong()) }
        assertEquals(1, engine.activeOutputs().size)
        assertEquals(0L, engine.activeOutputs().single().startedAt)
        assertEquals(3000L, engine.nextWakeAt())
        engine.advance(3000)
        assertEmpty()
    }

    @Test fun outputOrderFollowsFirstAcceptanceRatherThanActivationOrder() {
        engine.submit(listOf(start("first-accepted", delay = 100), start("second-accepted")), 0)
        assertEquals(listOf("second-accepted"), rules())
        engine.advance(100)
        assertEquals(listOf("first-accepted", "second-accepted"), rules())
        engine.submit(listOf(start("first-accepted")), 101)
        assertEquals(listOf("first-accepted", "second-accepted"), rules())
    }

    @Test fun contributionKeysDistinguishChannelsAndDelimiterContainingRuleAndSlotIds() {
        engine.submit(listOf(start("a:b", "c"), start("a", "b:c"),
            start("a:b", "c", ActionType.VIBRATE)), 0)
        assertEquals(3, engine.activeOutputs().map { it.key }.toSet().size)
        engine.submit(listOf(stop("a:b")), 1)
        assertEquals(listOf("a" to ActionType.RING, "a:b" to ActionType.VIBRATE), outputs())
    }

    @Test fun vibrationAndFlashUseTheirOwnFullPatternPeriods() {
        engine.submit(listOf(start(slot = "v", type = ActionType.VIBRATE, repeats = 3),
            start(slot = "f", type = ActionType.FLASH, repeats = 2)), 0)
        assertEquals(2000L, engine.nextWakeAt())
        engine.advance(2000)
        assertEquals(listOf(ActionType.VIBRATE), engine.activeOutputs().map { it.type })
        assertEquals(3600L, engine.nextWakeAt())
        engine.advance(3600)
        assertEmpty()
    }

    @Test fun durationAndPositiveRepeatCountChooseTheEarlierDeadline() {
        engine.submit(listOf(start("duration-wins", type = ActionType.VIBRATE, duration = 2, repeats = 5),
            start("repeat-wins", type = ActionType.FLASH, duration = 10, repeats = 3)), 100)
        assertEquals(2100L, engine.nextWakeAt())
        engine.advance(2100)
        assertEquals(listOf("repeat-wins"), rules())
        assertEquals(3100L, engine.nextWakeAt())
        engine.advance(3100)
        assertEmpty()
    }

    @Test fun limitedSameChannelContributionCannotStopAnUnlimitedPeer() {
        engine.submit(listOf(start("finite", type = ActionType.VIBRATE, repeats = 1),
            start("unlimited", type = ActionType.VIBRATE)), 0)
        engine.advance(1200)
        assertEquals(listOf("unlimited"), rules())
        assertNull(engine.nextWakeAt())
    }

    @Test fun unknownSoundPeriodUsesOnlyDurationUntilRealPeriodArrives() {
        engine.submit(listOf(start(repeats = 4, sound = "tone")), 0)
        assertNull(engine.nextWakeAt())
        engine.setSoundPeriod("tone", 250, 100)
        assertEquals(1000L, engine.nextWakeAt())
        engine.advance(999)
        assertEquals(1, engine.activeOutputs().size)
        engine.advance(1000)
        assertEmpty()
    }

    @Test fun periodArrivingAfterRepeatDeadlineImmediatelyRemovesInsteadOfRevivingSound() {
        engine.submit(listOf(start(repeats = 2, sound = "tone")), 100)
        engine.setSoundPeriod("tone", 300, 1000)
        assertEmpty()
        engine.setSoundPeriod("tone", 900, 2000)
        assertEmpty()
    }

    @Test fun knownPeriodAppliesToNewAndDelayedContributionsWithoutAnotherCallback() {
        engine.setSoundPeriod("tone", 400, 0)
        engine.submit(listOf(start(repeats = 2, sound = "tone", delay = 100)), 0)
        assertEquals(100L, engine.nextWakeAt())
        engine.advance(100)
        assertEquals(900L, engine.nextWakeAt())
        engine.advance(900)
        assertEmpty()
    }

    @Test fun periodUpdateConstrictsOnlyMatchingUriPositiveRepeatContributions() {
        engine.submit(listOf(start("short", repeats = 1, sound = "one"),
            start("long", repeats = 2, sound = "one"), start("infinite", sound = "one"),
            start("other-uri", repeats = 1, sound = "two"),
            start("pending", repeats = 1, sound = "one", delay = 1000)), 0)
        engine.setSoundPeriod("one", 500, 100)
        engine.advance(500)
        assertEquals(listOf("long", "infinite", "other-uri"), rules())
        engine.advance(1000)
        assertEquals(listOf("infinite", "other-uri", "pending"), rules())
        assertEquals(1500L, engine.nextWakeAt())
        engine.advance(1500)
        assertEquals(listOf("infinite", "other-uri"), rules())
        assertNull(engine.nextWakeAt())
    }

    @Test fun ringDurationRemainsAnUpperBoundEvenWhenPreparedPeriodIsLonger() {
        engine.submit(listOf(start(duration = 2, repeats = 10, sound = "tone")), 0)
        engine.setSoundPeriod("tone", 1000, 500)
        assertEquals(2000L, engine.nextWakeAt())
        engine.advance(2000)
        assertEmpty()
    }

    @Test fun laterLongerSoundPeriodNeverExtendsExistingDeadline() {
        engine.submit(listOf(start(repeats = 2, sound = "tone")), 0)
        engine.setSoundPeriod("tone", 300, 0)
        engine.setSoundPeriod("tone", 900, 100)
        assertEquals(600L, engine.nextWakeAt())
        engine.advance(600)
        assertEmpty()
        // A later, genuinely new contribution uses the newly reported period.
        engine.submit(listOf(start(repeats = 2, sound = "tone")), 700)
        assertEquals(2500L, engine.nextWakeAt())
    }

    @Test fun nonPositiveSoundPeriodIsIgnoredButStillAdvancesDurationExpiry() {
        engine.submit(listOf(start(duration = 1, repeats = 2, sound = "tone")), 0)
        engine.setSoundPeriod("tone", 0, 500)
        assertEquals(1000L, engine.nextWakeAt())
        engine.setSoundPeriod("tone", -10, 1000)
        assertEmpty()
    }

    @Test fun duplicatePendingStopRetainsEarliestDueTimeAndDoesNotQueueExtraStops() {
        engine.submit(listOf(start(), stop(delay = 100)), 0)
        engine.submit(listOf(stop(delay = 500)), 10)
        assertEquals(100L, engine.nextWakeAt())
        engine.submit(listOf(stop(delay = 20)), 20)
        assertEquals(40L, engine.nextWakeAt())
        engine.advance(40)
        assertEmpty()
        engine.submit(listOf(start()), 50)
        engine.advance(1000)
        assertEquals(listOf("a"), rules())
    }

    @Test fun pendingStopAndStartUsingSameSlotAreIndependentOperations() {
        engine.submit(listOf(stop(slot = "same", delay = 100), start(slot = "same")), 0)
        assertEquals(listOf("a"), rules())
        engine.advance(100)
        assertEmpty()
    }

    @Test fun removingFailedOutputPreservesOtherKeysAndAllowsLaterRetry() {
        engine.submit(listOf(start("a"), start("b"), stop("b", delay = 100)), 0)
        val key = engine.activeOutputs().first().key
        engine.removeOutput(key)
        engine.removeOutput("unknown")
        assertEquals(listOf("b"), rules())
        assertEquals(100L, engine.nextWakeAt())
        engine.submit(listOf(start("a")), 10)
        assertEquals(listOf("b", "a"), rules())
        assertEquals(10L, engine.activeOutputs().last().startedAt)
        engine.advance(100)
        assertEquals(listOf("a"), rules())
    }

    @Test fun clearDropsOutputsPendingEventsAndSoundPeriodCache() {
        engine.setSoundPeriod("tone", 300, 0)
        engine.submit(listOf(start(), start("later", delay = 100), stop(delay = 200)), 0)
        engine.clear()
        assertEmpty()
        engine.advance(1000)
        assertEmpty()
        engine.submit(listOf(start(sound = "tone", repeats = 1)), 1000)
        assertNull(engine.nextWakeAt())
    }

    @Test fun backwardClockDoesNotResurrectExpiredWorkOrScheduleBeforeLastObservedTime() {
        engine.submit(listOf(start(duration = 1)), 0)
        engine.advance(500)
        engine.submit(listOf(start("second", delay = 300)), 100)
        assertEquals(800L, engine.nextWakeAt())
        engine.advance(0)
        assertEquals(listOf("a"), rules())
        engine.advance(800)
        assertEquals(800L, engine.activeOutputs().last().startedAt)
        engine.advance(1000)
        assertEquals(listOf("second"), rules())
        engine.advance(50)
        assertEquals(listOf("second"), rules())
    }

    @Test fun saturatedTimingNeverWrapsIntoNegativeDeadlinesOrLoopsAtMaximumClock() {
        engine.submit(listOf(start(delay = 100, duration = 1)), Long.MAX_VALUE - 10)
        assertEquals(Long.MAX_VALUE, engine.nextWakeAt())
        engine.advance(Long.MAX_VALUE)
        assertEmpty()
        engine.clear()
        engine.setSoundPeriod("tone", Long.MAX_VALUE, 0)
        engine.submit(listOf(start(sound = "tone", repeats = 2)), 0)
        assertEquals(Long.MAX_VALUE, engine.nextWakeAt())
        engine.advance(Long.MAX_VALUE)
        assertEmpty()
    }

    @Test fun invalidTimingRejectsWholeBatchBeforeAnyOfItsCommandsAreApplied() {
        engine.submit(listOf(start("existing")), 0)
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(listOf(stop("existing"), start("invalid", delay = -1)), 100)
        }
        assertEquals(listOf("existing"), rules())
        assertThrows(IllegalArgumentException::class.java) { engine.advance(-1) }
        assertEquals(listOf("existing"), rules())
    }

    @Test fun forwardCommandsNeverCreateLocalOutputsOrScheduledWork() {
        engine.submit(listOf(start(type = ActionType.FORWARD, delay = 100),
            stop(type = ActionType.FORWARD, delay = 100)), 0)
        assertEmpty()
    }

    @Test fun previouslyReturnedOutputListIsASnapshotNotALiveView() {
        engine.submit(listOf(start()), 0)
        val snapshot = engine.activeOutputs()
        engine.submit(listOf(stop()), 1)
        assertEmpty()
        assertEquals(listOf("a"), snapshot.map { it.sourceRuleId })
    }

    @Test fun stopIgnoresNegativeUnusedDurationAndRepeatFieldsWithoutAffectingOtherChannels() {
        engine.submit(listOf(start("a"), start("b"), start("a", "v", ActionType.VIBRATE)), 0)
        engine.submit(listOf(stop("a").copy(durationSeconds = -1, repeatCount = -99)), 1)
        assertEquals(listOf("b" to ActionType.RING, "a" to ActionType.VIBRATE), outputs())
        assertThrows(IllegalArgumentException::class.java) {
            engine.submit(listOf(stop("b", delay = -1).copy(durationSeconds = -1)), 2)
        }
        assertEquals(listOf("b" to ActionType.RING, "a" to ActionType.VIBRATE), outputs())
    }

    @Test fun stopCallbackRunsOnlyWhenDueExactlyOnceIncludingNoLocalMatches() {
        val applied = mutableListOf<AlarmCommand>()
        val subject = AlarmSessionEngine { applied += it }
        val command = stop("no-local-output", delay = 200)
        subject.submit(listOf(command), 100)
        assertTrue(applied.isEmpty())
        subject.advance(299)
        assertTrue(applied.isEmpty())
        subject.advance(300)
        assertEquals(listOf(command), applied)
        subject.advance(1000)
        assertEquals(listOf(command), applied)
        assertFalse(subject.hasWork())
    }

    @Test fun immediateStopCallbackObservesRemovalBeforeFollowingSameBatchStart() {
        val observed = mutableListOf<List<AlarmOutput>>()
        lateinit var subject: AlarmSessionEngine
        subject = AlarmSessionEngine { observed += subject.activeOutputs() }
        subject.submit(listOf(start()), 0)
        subject.submit(listOf(stop(), start()), 100)
        assertEquals(1, observed.size)
        assertTrue(observed.single().isEmpty())
        assertEquals(100L, subject.activeOutputs().single().startedAt)
    }

    @Test fun duplicateDelayedStopCallbackUsesEarliestAcceptedEventAndDoesNotRepeat() {
        val applied = mutableListOf<AlarmCommand>()
        val subject = AlarmSessionEngine { applied += it }
        subject.submit(listOf(stop(delay = 100)), 0)
        subject.submit(listOf(stop(delay = 500)), 10)
        val earlier = stop(delay = 20)
        subject.submit(listOf(earlier), 20)
        subject.advance(40)
        assertEquals(listOf(earlier), applied)
        subject.advance(1000)
        assertEquals(listOf(earlier), applied)
        val next = stop()
        subject.submit(listOf(next), 1001)
        assertEquals(listOf(earlier, next), applied)
    }

    @Test fun callbackFailurePreservesOtherRulesChannelsAndLaterEvents() {
        var callbacks = 0
        val subject = AlarmSessionEngine { callbacks++; error("fixture callback failure") }
        subject.submit(listOf(start("a"), start("b"), start("a", "v", ActionType.VIBRATE),
            stop("a", delay = 100), stop("b", delay = 200)), 0)
        subject.advance(100)
        assertEquals(listOf("b" to ActionType.RING, "a" to ActionType.VIBRATE),
            subject.activeOutputs().map { it.sourceRuleId to it.type })
        assertEquals(1, callbacks)
        assertEquals(200L, subject.nextWakeAt())
        subject.advance(200)
        assertEquals(listOf(ActionType.VIBRATE), subject.activeOutputs().map { it.type })
        assertEquals(2, callbacks)
        assertTrue(subject.hasWork())
    }

    @Test fun clearingQueuedStopNeverCallsItsCallbackLater() {
        val applied = mutableListOf<AlarmCommand>()
        val subject = AlarmSessionEngine { applied += it }
        subject.submit(listOf(stop(delay = 100)), 0)
        subject.clear()
        subject.advance(1000)
        assertTrue(applied.isEmpty())
        assertFalse(subject.hasWork())
    }

    private fun start(rule: String = "a", slot: String = "ring", type: ActionType = ActionType.RING,
        delay: Long = 0, duration: Int = 0, repeats: Int = 0, sound: String = "") =
        AlarmCommand(rule, slot, type, delayMs = delay, durationSeconds = duration,
            repeatCount = repeats, soundUri = sound)

    private fun stop(rule: String = "a", type: ActionType = ActionType.RING, scope: StopScope = StopScope.THIS_RULE,
        target: String = "", delay: Long = 0, slot: String = "stop") =
        AlarmCommand(rule, slot, type, operation = ActionOperation.STOP, stopScope = scope,
            targetRuleId = target, delayMs = delay)

    private fun rules() = engine.activeOutputs().map { it.sourceRuleId }
    private fun outputs() = engine.activeOutputs().map { it.sourceRuleId to it.type }
    private fun assertEmpty() {
        assertTrue(engine.activeOutputs().isEmpty())
        assertFalse(engine.hasWork())
        assertNull(engine.nextWakeAt())
    }
}
