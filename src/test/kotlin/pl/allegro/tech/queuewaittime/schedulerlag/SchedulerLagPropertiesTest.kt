package pl.allegro.tech.queuewaittime.schedulerlag

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

internal class SchedulerLagPropertiesTest {
    @ParameterizedTest
    @ValueSource(longs = [0L, -1L])
    fun rejectsNonPositiveSamplingIntervals(intervalNanos: Long) {
        assertThatIllegalArgumentException()
            .isThrownBy { SchedulerLagProperties(interval = Duration.ofNanos(intervalNanos)) }
            .withMessageContaining("allegro-tech.scheduler-lag.interval")
    }

    @ParameterizedTest
    @ValueSource(longs = [1L, 25_000_000L, 5_000_000_000L])
    fun acceptsPositiveSamplingIntervals(intervalNanos: Long) {
        val interval = Duration.ofNanos(intervalNanos)

        val properties = SchedulerLagProperties(interval = interval)

        assertThat(properties.interval).isEqualTo(interval)
    }
}
