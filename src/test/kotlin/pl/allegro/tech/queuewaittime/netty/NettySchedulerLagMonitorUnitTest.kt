package pl.allegro.tech.queuewaittime.netty

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.DefaultThreadFactory
import io.netty.util.concurrent.EventExecutor
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class NettySchedulerLagMonitorUnitTest {
    private val registry = SimpleMeterRegistry()
    private val group = DefaultEventExecutorGroup(2, DefaultThreadFactory("netty-scheduler-lag-test"))
    private lateinit var scheduler: NettySchedulerLagMonitor

    @AfterEach
    fun closeResources() {
        try {
            if (::scheduler.isInitialized) {
                scheduler.close()
            }
        } finally {
            try {
                group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
            } finally {
                registry.close()
            }
        }
    }

    @Test
    fun startsSamplingRepeatedlyOnEveryRegisteredExecutor() {
        // given
        val executorNames = group.map(::executorName)
        scheduler = NettySchedulerLagMonitor(registry, Duration.ofMillis(10), "test-type")

        // when
        scheduler.registerGroup(group)

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            executorNames.forEach { executorName ->
                assertThat(counterSum(executorName)).isPositive()
            }
        }
        val initialCounterSums = executorNames.associateWith(::counterSum)

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            executorNames.forEach { executorName ->
                assertThat(counterSum(executorName)).isGreaterThan(initialCounterSums.getValue(executorName))
            }
        }
    }

    @Test
    fun closeStopsSamplingWithoutClosingTheRegistryOrExecutors() {
        // given
        val executorNames = group.map(::executorName)
        scheduler = NettySchedulerLagMonitor(registry, Duration.ofMillis(10), "test-type")
        scheduler.registerGroup(group)
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            executorNames.forEach { executorName ->
                assertThat(counterSum(executorName)).isPositive()
            }
        }
        val counters = executorNames.flatMap(::counters)

        // when
        scheduler.close()
        group.forEach { it.submit {}.get(5, TimeUnit.SECONDS) }

        // then
        assertThat(registry.isClosed).isFalse()
        assertThat(group.isShuttingDown).isFalse()
        val counterSumAfterClose = counters.sumOf { it.count() }
        await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(counters.sumOf { it.count() }).isEqualTo(counterSumAfterClose)
        }
    }

    @Test
    fun countsSamplesDuringHealthyAndBusyPeriods() {
        // given
        val executor = group.next()
        val executorName = executorName(executor)
        scheduler = NettySchedulerLagMonitor(registry, Duration.ofMillis(10), "test-type")
        scheduler.registerGroup(group)
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(counterSum(executorName)).isPositive()
        }
        val healthyCounter = counter(executorName, "healthy")
        val unhealthyCounter = counter(executorName, "unhealthy")
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(healthyCounter.count()).isPositive()
        }

        // when
        val healthySampleCount = countHealthySamplesFor100Millis(healthyCounter)
        val unhealthySampleCount = countUnhealthySamplesAfterBusyPeriod(executor, healthyCounter, unhealthyCounter)

        // then
        assertThat(healthySampleCount)
            .describedAs("healthy samples during 100 ms with a 10 ms interval")
            .isBetween(8.0, 11.0)
        assertThat(unhealthySampleCount)
            .describedAs("unhealthy samples during 100 ms of blocking with a 10 ms interval")
            .isBetween(8.0, 11.0)
    }

    @Test
    fun usesScheduleWithFixedDelayInsteadOfScheduleAtFixedRate() {
        // given
        val channel = EmbeddedChannel()
        // Keep real-clock samples healthy while advancing only Netty's scheduling clock.
        val interval = Duration.ofMinutes(1)
        channel.freezeTime()
        scheduler = NettySchedulerLagMonitor(registry, interval, "test-type")
        try {
            scheduler.registerGroup(channel.eventLoop())
            channel.runScheduledPendingTasks()
            val healthyCounter = counter(Thread.currentThread().name, "healthy")
            assertThat(healthyCounter.count()).isEqualTo(1.0)

            // when
            channel.advanceTimeBy(interval.toNanos() * 10, TimeUnit.NANOSECONDS)
            val nextSampleDelay = channel.runScheduledPendingTasks()

            // then
            assertThat(healthyCounter.count()).isEqualTo(2.0)
            assertThat(nextSampleDelay).isEqualTo(interval.toNanos())
        } finally {
            scheduler.close()
            channel.finishAndReleaseAll()
        }
    }

    private fun countHealthySamplesFor100Millis(healthyCounter: Counter): Double {
        val initialHealthyCount = healthyCounter.count()
        Thread.sleep(100)
        return healthyCounter.count() - initialHealthyCount
    }

    private fun countUnhealthySamplesAfterBusyPeriod(
        executor: EventExecutor,
        healthyCounter: Counter,
        unhealthyCounter: Counter,
    ): Double {
        val busyLoopStarted = CountDownLatch(1)
        val releaseExecutor = CountDownLatch(1)
        val busyTask = executor.submit {
            busyLoopStarted.countDown()
            releaseExecutor.await(5, TimeUnit.SECONDS)
        }
        try {
            assertThat(busyLoopStarted.await(5, TimeUnit.SECONDS)).isTrue()
            val healthyCountBeforeBusyPeriod = healthyCounter.count()
            val unhealthyCountBeforeBusyPeriod = unhealthyCounter.count()

            Thread.sleep(100)

            assertThat(healthyCounter.count()).isEqualTo(healthyCountBeforeBusyPeriod)
            assertThat(unhealthyCounter.count()).isEqualTo(unhealthyCountBeforeBusyPeriod)

            releaseExecutor.countDown()
            busyTask.get(5, TimeUnit.SECONDS)
            await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(5)).untilAsserted {
                assertThat(unhealthyCounter.count()).isGreaterThan(unhealthyCountBeforeBusyPeriod)
            }
            scheduler.close()
            executor.submit {}.get(5, TimeUnit.SECONDS)

            return unhealthyCounter.count() - unhealthyCountBeforeBusyPeriod
        } finally {
            releaseExecutor.countDown()
            busyTask.get(5, TimeUnit.SECONDS)
        }
    }

    private fun executorName(executor: EventExecutor): String =
        executor.submit(Callable { Thread.currentThread().name }).get(5, TimeUnit.SECONDS)

    private fun counter(executorName: String, status: String) = registry.get("scheduler.sample")
        .tag("executor", executorName)
        .tag("type", "test-type")
        .tag("status", status)
        .counter()

    private fun counterSum(executorName: String) = counters(executorName).sumOf { it.count() }

    private fun counters(executorName: String) = registry.find("scheduler.sample")
        .tag("executor", executorName)
        .tag("type", "test-type")
        .counters()
}
