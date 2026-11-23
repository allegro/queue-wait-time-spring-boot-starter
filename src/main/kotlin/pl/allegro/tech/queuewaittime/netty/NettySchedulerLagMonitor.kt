package pl.allegro.tech.queuewaittime.netty

import io.micrometer.core.instrument.MeterRegistry
import io.netty.util.concurrent.EventExecutor
import io.netty.util.concurrent.EventExecutorGroup
import pl.allegro.tech.queuewaittime.schedulerlag.SchedulerLagSampleRecorder
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class NettySchedulerLagMonitor(
    private val meterRegistry: MeterRegistry,
    private val interval: Duration,
    private val type: String,
) : AutoCloseable {
    private val intervalNanos = interval.toNanos()
    private val samplingTasks = mutableListOf<ScheduledFuture<*>>()

    init {
        require(intervalNanos > 0) { "interval must be positive" }
    }

    fun registerGroup(group: EventExecutorGroup) {
        group.forEach(::monitor)
    }

    override fun close() {
        samplingTasks.forEach { it.cancel(false) }
    }

    private fun monitor(executor: EventExecutor) {
        val last = longArrayOf(System.nanoTime())
        val sampleRecorder by lazy {
            SchedulerLagSampleRecorder(
                meterRegistry = meterRegistry,
                interval = interval,
                executor = Thread.currentThread().name,
                type = type,
            )
        }

        val task = executor.scheduleWithFixedDelay(
            {
                val now = System.nanoTime()
                val diff = now - last[0]
                last[0] = now

                sampleRecorder.monitorSample(diff)
            },
            0, intervalNanos, TimeUnit.NANOSECONDS
        )

        samplingTasks.add(task)
    }
}
