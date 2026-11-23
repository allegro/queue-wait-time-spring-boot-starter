package pl.allegro.tech.queuewaittime.schedulerlag

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

internal class SchedulerLagSampleRecorderTest {
    private val registry = SimpleMeterRegistry()
    private val recorder = SchedulerLagSampleRecorder(
        meterRegistry = registry,
        interval = Duration.ofMillis(10),
        executor = "test-executor",
        type = "test-type",
    )

    @AfterEach
    fun closeRegistry() {
        registry.close()
    }

    @ParameterizedTest(name = "interval=10 ms, diff={0} ms is healthy")
    @ValueSource(doubles = [0.0, -1.0, 9.99, 10.0, 10.01, 19.99, 20.0])
    fun countsSamplesAtOrBelowTwiceTheIntervalAsHealthy(diffMillis: Double) {
        recorder.monitorSample(diffMillis.milliseconds.inWholeNanoseconds)

        assertThat(counterCount("healthy")).isEqualTo(1.0)
        assertThat(counterCount("unhealthy")).isZero()
        assertThat(registry.find("scheduler.sample").tag("status", "uncertain").counters()).isEmpty()
        assertThat(unhealthyTimer().count()).isZero()
        assertThat(unhealthyTimer().totalTime(TimeUnit.MILLISECONDS)).isZero()
    }

    @ParameterizedTest(name = "interval=10 ms, diff={0} ms: weight={1}, excess={2} ms")
    @CsvSource(
        "20.01, 2, 0.01",
        "21, 2, 1",
        "29.99, 2, 9.99",
        "67, 6, 47",
    )
    fun recordsUnhealthySamples(
        diffMillis: Double,
        expectedWeight: Double,
        expectedLagMillis: Double,
    ) {
        recorder.monitorSample(diffMillis.milliseconds.inWholeNanoseconds)

        assertThat(counterCount("unhealthy")).isEqualTo(expectedWeight)
        assertThat(counterCount("healthy")).isZero()
        assertThat(unhealthyTimer().count()).isEqualTo(1L)
        assertThat(unhealthyTimer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(expectedLagMillis)
    }

    @Test
    fun accumulatesMetricsAcrossHealthyAndUnhealthySamples() {
        listOf(9.99, 10.0, 20.0, 21.0, 67.0).forEach { diffMillis ->
            recorder.monitorSample(diffMillis.milliseconds.inWholeNanoseconds)
        }

        assertThat(counterCount("healthy")).isEqualTo(3.0)
        assertThat(counterCount("unhealthy")).isEqualTo(8.0)
        assertThat(unhealthyTimer().count()).isEqualTo(2L)
        assertThat(unhealthyTimer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(48.0)
        assertThat(unhealthyTimer().max(TimeUnit.MILLISECONDS)).isEqualTo(47.0)
    }

    @ParameterizedTest(name = "interval={0} ms, diff={1} ms")
    @CsvSource(
        "25, 24.99, 1, 0, 0",
        "25, 25, 1, 0, 0",
        "25, 50, 1, 0, 0",
        "25, 50.01, 0, 2, 0.01",
    )
    fun classifiesSamplesUsingTheConfiguredInterval(
        intervalMillis: Long,
        diffMillis: Double,
        expectedHealthy: Double,
        expectedUnhealthy: Double,
        expectedLagMillis: Double,
    ) {
        val customRecorder = SchedulerLagSampleRecorder(
            meterRegistry = registry,
            interval = Duration.ofMillis(intervalMillis),
            executor = "test-executor",
            type = "test-type",
        )

        customRecorder.monitorSample(diffMillis.milliseconds.inWholeNanoseconds)

        assertThat(counterCount("healthy")).isEqualTo(expectedHealthy)
        assertThat(counterCount("unhealthy")).isEqualTo(expectedUnhealthy)
        assertThat(unhealthyTimer().count()).isEqualTo(if (expectedUnhealthy > 0) 1L else 0L)
        assertThat(unhealthyTimer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(expectedLagMillis)
    }

    private fun counterCount(status: String) = registry.find("scheduler.sample")
        .tag("executor", "test-executor")
        .tag("type", "test-type")
        .tag("status", status)
        .counter()
        ?.count()

    private fun unhealthyTimer() = registry.get("scheduler.lag")
        .tag("executor", "test-executor")
        .tag("type", "test-type")
        .tag("status", "unhealthy")
        .timer()
}
