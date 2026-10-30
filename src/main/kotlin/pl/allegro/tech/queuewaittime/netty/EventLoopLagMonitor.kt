package pl.allegro.tech.queuewaittime.netty

import io.micrometer.core.instrument.MeterRegistry
import io.netty.util.concurrent.EventExecutor
import io.netty.util.concurrent.EventExecutorGroup
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class EventLoopLagMonitor(
    private val meterRegistry: MeterRegistry,
    interval: Duration,
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

        val task = executor.scheduleAtFixedRate(
            {
                val now = System.nanoTime()
                val diff = now - last[0]
                last[0] = now

                val lagNanos = diff - intervalNanos
                if (lagNanos > 0) {
                    meterRegistry
                        .timer(
                            "event_loop.lag",
                            "executor", Thread.currentThread().name,
                            "type", type,
                        )
                        .record(lagNanos, TimeUnit.NANOSECONDS)
                }
            },
            0, intervalNanos, TimeUnit.NANOSECONDS
        )
        samplingTasks.add(task)
    }
}
