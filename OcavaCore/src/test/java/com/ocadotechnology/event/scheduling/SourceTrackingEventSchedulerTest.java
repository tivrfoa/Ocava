/*
 * Copyright © 2017-2020 Ocado (Ocava)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ocadotechnology.event.scheduling;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Runnables;
import com.ocadotechnology.time.AdjustableTimeProvider;
import com.ocadotechnology.wrappers.Pair;

public class SourceTrackingEventSchedulerTest {
    private enum TestSchedulerType implements EventSchedulerType {
        T1, T2;
    }

    private SourceSchedulerTracker tracker = new SourceSchedulerTracker();
    private final SimpleDiscreteEventScheduler backingScheduler = new SimpleDiscreteEventScheduler(new EventExecutor(), Runnables.doNothing(), TestSchedulerType.T1, new AdjustableTimeProvider(0), true);
    private final SourceTrackingEventScheduler threadOneScheduler = new SourceTrackingEventScheduler(tracker, TestSchedulerType.T1, backingScheduler);
    private final SourceTrackingEventScheduler threadTwoScheduler = new SourceTrackingEventScheduler(tracker, TestSchedulerType.T2, backingScheduler);

    @BeforeEach
    public void setup() {
        backingScheduler.pause();
    }

    @Test
    void whenEventExecuted_thenCorrectSchedulerTypeSet() {
        List<EventSchedulerType> observedTypes = new ArrayList<>();
        threadOneScheduler.doAt(50, () -> observedTypes.add(tracker.getActiveSchedulerType()), "");
        threadTwoScheduler.doAt(100, () -> observedTypes.add(tracker.getActiveSchedulerType()), "");
        threadOneScheduler.doAt(150, () -> observedTypes.add(tracker.getActiveSchedulerType()), "");
        backingScheduler.unPause();

        List<TestSchedulerType> expectedTypes = new ArrayList<>();
        expectedTypes.add(TestSchedulerType.T1);
        expectedTypes.add(TestSchedulerType.T2);
        expectedTypes.add(TestSchedulerType.T1);
        Assertions.assertEquals(expectedTypes, observedTypes);
    }

    @Test
    void whenEventCancelled_thenEventNotExecuted() {
        List<Boolean> executed = new ArrayList<>();
        executed.add(false);
        Cancelable cancelable = threadOneScheduler.doAt(50, () -> executed.set(0, true), "");
        threadOneScheduler.doAt(25d, cancelable::cancel, "");
        backingScheduler.unPause();
        Assertions.assertFalse(executed.get(0), "event executed after cancellation");
    }

    /**
     * Basic tests to demonstrate that thread pauses affect both doAt and doNow events on the specific thread being
     * paused
     */
    @Nested
    @DisplayName("Thread pause timing tests")
    class ThreadPauseTimingTests {
        private final List<Pair<String, Double>> eventTimeList = new ArrayList<>();

        @Test
        @DisplayName("On thread event is delayed")
        void whenThreadPaused_thenEventOnThreadIsDelayed() {
            double pauseTime = threadOneScheduler.getTimeProvider().getTime() + 10_000;
            double eventTime = pauseTime + 4321;
            double pauseEndTime = eventTime + 12_345;

            threadOneScheduler.doAt(pauseTime, () -> threadOneScheduler.delayExecutionUntil(pauseEndTime));
            threadOneScheduler.doAt(eventTime, () -> eventTimeList.add(Pair.of("EVENT", threadOneScheduler.getTimeProvider().getTime())));
            backingScheduler.unPause();

            Assertions.assertEquals(ImmutableList.of(Pair.of("EVENT", pauseEndTime)), ImmutableList.copyOf(eventTimeList), "Incorrect events recorded");
        }

        @Test
        @DisplayName("On thread doNow event is delayed")
        void whenThreadPaused_thenDoNowEventOnThreadIsDelayed() {
            double pauseTime = threadOneScheduler.getTimeProvider().getTime() + 10_000;
            double eventTime = pauseTime + 4321;
            double pauseEndTime = eventTime + 12_345;

            threadOneScheduler.doAt(pauseTime, () -> threadOneScheduler.delayExecutionUntil(pauseEndTime));
            threadTwoScheduler.doAt(eventTime, () ->
                    threadOneScheduler.doNow(() -> eventTimeList.add(Pair.of("EVENT", threadOneScheduler.getTimeProvider().getTime())))
            );
            backingScheduler.unPause();

            Assertions.assertEquals(ImmutableList.of(Pair.of("EVENT", pauseEndTime)), ImmutableList.copyOf(eventTimeList), "Incorrect events recorded");
        }

        @Test
        @DisplayName("Off thread event is not delayed")
        void whenThreadPaused_thenEventOnOtherThreadIsExecuted() {
            double pauseTime = threadTwoScheduler.getTimeProvider().getTime() + 10_000;
            double eventTime = pauseTime + 4321;
            double pauseEndTime = eventTime + 12_345;

            threadOneScheduler.doAt(pauseTime, () -> threadOneScheduler.delayExecutionUntil(pauseEndTime));
            threadTwoScheduler.doAt(eventTime, () -> eventTimeList.add(Pair.of("EVENT", threadTwoScheduler.getTimeProvider().getTime())));
            backingScheduler.unPause();

            Assertions.assertEquals(ImmutableList.of(Pair.of("EVENT", eventTime)), ImmutableList.copyOf(eventTimeList), "Incorrect events recorded");
        }
    }

    /**
     * More advanced tests to assert that events will be execute in the expected sequence when a thread pause happens.
     *
     * A scheduler has two event queues - the doNow and doAt queues.  When executing events the doNow queue will be used
     * until it is empty, then the doAt queue will be polled from the earliest event ot the latest.
     *
     * If a thread is experiencing a thread pause (due to GC or similar), the events will still be paused and executed
     * in this sequence, even if some doNow events were from another thread after the doAt event should have executed.
     *
     * Note: all cross-thread communication should ideally be performed using the doNow mechanism.  Using doAt can
     * result in slightly different behaviour between the DES and realtime cases when the thread is paused.
     */
    @Nested
    @DisplayName("Thread pause sequence tests")
    class ThreadPauseSequenceTests {
        private final List<String> eventList = new ArrayList<>();

        @Test
        @DisplayName("Delayed doNow event executes before scheduled doAt event")
        void whenThreadPaused_thenDelayedDoNowEventIsExecutedBeforeDoAt() {
            double pauseTime = threadOneScheduler.getTimeProvider().getTime() + 10_000;
            double eventTime = pauseTime + 4321;
            double pauseEndTime = eventTime + 12_345;

            threadOneScheduler.doAt(pauseTime, () -> threadOneScheduler.delayExecutionUntil(pauseEndTime));
            threadTwoScheduler.doAt(eventTime, () -> scheduleDoNow(threadOneScheduler, "DO_NOW"));
            threadOneScheduler.doAt(pauseEndTime, () -> eventList.add("DO_AT"));
            backingScheduler.unPause();

            ImmutableList<String> expectedEvents = ImmutableList.of("DO_NOW", "DO_AT");
            Assertions.assertEquals(expectedEvents, ImmutableList.copyOf(eventList), "Incorrect events recorded");
        }

        @Test
        @DisplayName("Delayed doNow event executes before delayed doAt event")
        void whenThreadPaused_thenDelayedDoNowEventIsExecutedBeforeDelayedDoAt() {
            double pauseTime = threadOneScheduler.getTimeProvider().getTime() + 10_000;
            double doAtTime = pauseTime + 4321;
            double doNowTime = doAtTime + 4321;
            double pauseEndTime = doNowTime + 12_345;

            threadOneScheduler.doAt(pauseTime, () -> threadOneScheduler.delayExecutionUntil(pauseEndTime));
            threadOneScheduler.doAt(doAtTime, () -> eventList.add("DO_AT"));
            threadTwoScheduler.doAt(doNowTime, () -> scheduleDoNow(threadOneScheduler, "DO_NOW"));
            backingScheduler.unPause();

            ImmutableList<String> expectedEvents = ImmutableList.of("DO_NOW", "DO_AT");
            Assertions.assertEquals(expectedEvents, ImmutableList.copyOf(eventList), "Incorrect events recorded");
        }

        @Test
        @DisplayName("Delayed doAt event executes before scheduled doAt event")
        void whenThreadPaused_thenDelayedDoAtEventIsExecutedBeforeScheduledDoAt() {
            double pauseTime = threadOneScheduler.getTimeProvider().getTime() + 10_000;
            double doAtTime = pauseTime + 4321;
            double pauseEndTime = doAtTime + 12_345;

            threadOneScheduler.doAt(pauseTime, () -> threadOneScheduler.delayExecutionUntil(pauseEndTime));
            threadOneScheduler.doAt(doAtTime, () -> eventList.add("DELAYED_DO_AT"));
            threadOneScheduler.doAt(pauseEndTime, () -> eventList.add("SCHEDULED_DO_AT"));
            backingScheduler.unPause();

            ImmutableList<String> expectedEvents = ImmutableList.of("DELAYED_DO_AT", "SCHEDULED_DO_AT");
            Assertions.assertEquals(expectedEvents, ImmutableList.copyOf(eventList), "Incorrect events recorded");
        }

        @Test
        @DisplayName("Delayed doNow events execute before additional doNow events")
        void whenThreadPaused_thenAdditionalDoNowEventIsExecutedAfterDelayedDoNow() {
            double pauseTime = threadOneScheduler.getTimeProvider().getTime() + 10_000;
            double doNowTime = pauseTime + 4321;
            double pauseEndTime = doNowTime + 12_345;

            threadOneScheduler.doAt(pauseTime, () -> threadOneScheduler.delayExecutionUntil(pauseEndTime));
            threadTwoScheduler.doAt(doNowTime, () -> scheduleDoNow(threadOneScheduler, "DELAYED_DO_NOW_1"));
            threadTwoScheduler.doAt(doNowTime, () -> scheduleRecursiveDoNow(threadOneScheduler, "DELAYED_DO_NOW_2", "TRIGGERED_DO_NOW"));
            backingScheduler.unPause();

            ImmutableList<String> expectedEvents = ImmutableList.of(
                    "DELAYED_DO_NOW_1",
                    "DELAYED_DO_NOW_2",
                    "TRIGGERED_DO_NOW");
            Assertions.assertEquals(expectedEvents, ImmutableList.copyOf(eventList), "Incorrect events recorded");
        }

        @Test
        @DisplayName("Delayed doAt events execute after additional doNow events")
        void whenThreadPaused_thenAdditionalDoNowEventIsExecutedBeforeDelayedDoAt() {
            double pauseTime = threadOneScheduler.getTimeProvider().getTime() + 10_000;
            double doAtTime = pauseTime + 4321;
            double doNowTime = doAtTime + 4321;
            double pauseEndTime = doNowTime + 12_345;

            threadOneScheduler.doAt(pauseTime, () -> threadOneScheduler.delayExecutionUntil(pauseEndTime));
            threadOneScheduler.doAt(doAtTime, () -> eventList.add("DO_AT"));
            threadTwoScheduler.doAt(doNowTime, () -> scheduleRecursiveDoNow(threadOneScheduler, "DELAYED_DO_NOW", "TRIGGERED_DO_NOW"));
            backingScheduler.unPause();

            ImmutableList<String> expectedEvents = ImmutableList.of(
                    "DELAYED_DO_NOW",
                    "TRIGGERED_DO_NOW",
                    "DO_AT"
            );
            Assertions.assertEquals(expectedEvents, ImmutableList.copyOf(eventList), "Incorrect events recorded");
        }

        private void scheduleDoNow(SourceTrackingEventScheduler scheduler, String eventName) {
            scheduler.doNow(() -> eventList.add(eventName));
        }

        private void scheduleRecursiveDoNow(SourceTrackingEventScheduler scheduler, String eventName, String triggeredEventName) {
            scheduler.doNow(() -> {
                eventList.add(eventName);
                scheduler.doNow(() -> eventList.add(triggeredEventName));
            });
        }
    }
}
