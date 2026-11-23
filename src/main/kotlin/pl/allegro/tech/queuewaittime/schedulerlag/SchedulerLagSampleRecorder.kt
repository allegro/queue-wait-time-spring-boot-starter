package pl.allegro.tech.queuewaittime.schedulerlag

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.TimeUnit

class SchedulerLagSampleRecorder(
    private val meterRegistry: MeterRegistry,
    private val interval: Duration,
    executor: String,
    type: String,
) {

    private val unhealthyCounter = Counter.builder("scheduler.sample")
        .description("Number of sampling windows lost due to unhealthy scheduler lag")
        .tag("executor", executor)
        .tag("type", type)
        .tag("status", "unhealthy")
        .register(meterRegistry)

    private val healthyCounter = Counter.builder("scheduler.sample")
        .description("Number of samples with healthy scheduler lag")
        .tag("executor", executor)
        .tag("type", type)
        .tag("status", "healthy")
        .register(meterRegistry)

    private val unhealthyTimer = Timer.builder("scheduler.lag")
        .description("Scheduling delay distribution for unhealthy scheduler samples")
        .tag("executor", executor)
        .tag("type", type)
        .tag("status", "unhealthy")
        .register(meterRegistry)

    fun monitorSample(diffNanos: Long) {
        val lagNanos = maxOf(0L, diffNanos - interval.toNanos() - interval.toNanos())

        if (lagNanos > 0) {
            val missedSamples = diffNanos / interval.toNanos()
            unhealthyCounter.increment(missedSamples.toDouble())

            unhealthyTimer.record(lagNanos, TimeUnit.NANOSECONDS)
        } else {
            healthyCounter.increment()
        }
    }
}
