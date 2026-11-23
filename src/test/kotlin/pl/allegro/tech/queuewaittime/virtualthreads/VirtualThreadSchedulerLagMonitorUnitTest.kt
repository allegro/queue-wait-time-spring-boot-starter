package pl.allegro.tech.queuewaittime.virtualthreads

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class VirtualThreadSchedulerLagMonitorUnitTest {
    private val registry = SimpleMeterRegistry()
    private lateinit var scheduler: VirtualThreadSchedulerLagMonitor

    @AfterEach
    fun closeResources() {
        try {
            if (::scheduler.isInitialized) {
                scheduler.recordingThreadProbe.interrupt()
                scheduler.recordingThreadProbe.join(Duration.ofSeconds(5))
            }
        } finally {
            registry.close()
        }
    }

    @Test
    fun startsSamplingRepeatedlyOnAVirtualThread() {
        // given
        val interval = Duration.ofMillis(10)

        // when
        scheduler = VirtualThreadSchedulerLagMonitor(registry, interval)

        // then
        assertThat(scheduler.recordingThreadProbe.isVirtual).isTrue()
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(counterSum()).isPositive()
        }
        val initialCounterSum = counterSum()

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(counterSum()).isGreaterThan(initialCounterSum)
        }
    }

    @Test
    fun closeStopsTheProbeWithoutClosingTheRegistry() {
        // given
        scheduler = VirtualThreadSchedulerLagMonitor(registry, Duration.ofMillis(10))
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(counterSum()).isPositive()
        }

        // when
        scheduler.close()

        // then
        assertThat(scheduler.recordingThreadProbe.isAlive).isFalse()
        assertThat(registry.isClosed).isFalse()
        val counterSumAfterClose = counterSum()
        await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(counterSum()).isEqualTo(counterSumAfterClose)
        }
    }

    @Test
    fun countsSamplesDuringHealthyAndBusyPeriods() {
        // given
        scheduler = VirtualThreadSchedulerLagMonitor(registry, Duration.ofMillis(10))
        val healthyCounter = registry.get("scheduler.sample").tag("status", "healthy").counter()
        val unhealthyCounter = registry.get("scheduler.sample").tag("status", "unhealthy").counter()
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(healthyCounter.count()).isPositive()
        }

        // when
        val healthySampleCount = countHealthySamplesFor100Millis(healthyCounter)
        val unhealthySampleCount = countUnhealthySamplesAfterBusyPeriod(healthyCounter, unhealthyCounter)

        // then
        assertThat(healthySampleCount)
            .describedAs("healthy samples during 100 ms with a 10 ms interval")
            .isBetween(8.0, 11.0)
        assertThat(unhealthySampleCount)
            .describedAs("unhealthy samples during 100 ms of busy waiting with a 10 ms interval")
            .isBetween(8.0, 11.0)
    }

    private fun countHealthySamplesFor100Millis(healthyCounter: Counter): Double {
        val initialHealthyCount = healthyCounter.count()
        Thread.sleep(100)
        return healthyCounter.count() - initialHealthyCount
    }

    private fun countUnhealthySamplesAfterBusyPeriod(healthyCounter: Counter, unhealthyCounter: Counter): Double {
        val keepCarrierBusy = AtomicBoolean(true)
        val busyLoopStarted = CountDownLatch(1)
        val busyThread = Thread.ofVirtual().start {
            busyLoopStarted.countDown()
            while (keepCarrierBusy.get()) {
                Thread.onSpinWait()
            }
        }
        try {
            assertThat(busyLoopStarted.await(5, TimeUnit.SECONDS)).isTrue()
            val healthyCountBeforeBusyPeriod = healthyCounter.count()
            val unhealthyCountBeforeBusyPeriod = unhealthyCounter.count()

            Thread.sleep(100)

            assertThat(healthyCounter.count()).isEqualTo(healthyCountBeforeBusyPeriod)
            assertThat(unhealthyCounter.count()).isEqualTo(unhealthyCountBeforeBusyPeriod)

            keepCarrierBusy.set(false)
            busyThread.join(Duration.ofSeconds(5))
            await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(5)).untilAsserted {
                assertThat(unhealthyCounter.count()).isGreaterThan(unhealthyCountBeforeBusyPeriod)
            }
            scheduler.close()

            return unhealthyCounter.count() - unhealthyCountBeforeBusyPeriod
        } finally {
            keepCarrierBusy.set(false)
            busyThread.join(Duration.ofSeconds(5))
        }
    }

    private fun counterSum() = registry.find("scheduler.sample")
        .tag("executor", "VirtualThreadDefaultScheduler")
        .tag("type", "VirtualThreadDefaultScheduler")
        .counters()
        .sumOf { it.count() }
}
