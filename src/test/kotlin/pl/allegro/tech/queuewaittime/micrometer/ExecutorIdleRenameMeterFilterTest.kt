package pl.allegro.tech.queuewaittime.micrometer

import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ExecutorIdleRenameMeterFilterTest {
    private val registry = SimpleMeterRegistry()
    private val executor = Executors.newFixedThreadPool(1)

    @BeforeEach
    fun configureFilter() {
        registry.config().meterFilter(ExecutorIdleRenameMeterFilter())
    }

    @AfterEach
    fun closeResources() {
        try {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            registry.close()
        }
    }

    @Test
    fun recordsMonitoredExecutorQueueWaitTimeUnderTheRenamedMetric() {
        val executorName = "test-executor"
        val tags = Tags.of("source", "test")
        val monitoredExecutor = ExecutorServiceMetrics.monitor(registry, executor, executorName, tags)

        monitoredExecutor.submit {}.get(5, TimeUnit.SECONDS)

        val timer = registry.find("executor.queue.wait.time")
            .tag("name", executorName)
            .tags(tags)
            .timer()
        assertThat(timer?.count()).isEqualTo(1L)
        assertThat(registry.find("executor.idle").timer()).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["executor.idle.extra", "executor.completed", "scheduler.lag"])
    fun leavesOtherMetricNamesUnchanged(metricName: String) {
        val counter = registry.counter(metricName, "source", "test")

        counter.increment()

        assertThat(counter.id.name).isEqualTo(metricName)
        assertThat(counter.count()).isEqualTo(1.0)
    }
}
