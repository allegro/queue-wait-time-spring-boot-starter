package pl.allegro.tech.queuewaittime.virtualthreads

import io.micrometer.core.instrument.MeterRegistry
import pl.allegro.tech.queuewaittime.schedulerlag.SchedulerLagSampleRecorder
import java.time.Duration

internal class VirtualThreadSchedulerLagMonitor(
    meterRegistry: MeterRegistry,
    private val interval: Duration,

    ) : AutoCloseable {

    private val sampleRecorder = SchedulerLagSampleRecorder(
        meterRegistry = meterRegistry,
        interval = interval,
        executor = "VirtualThreadDefaultScheduler",
        type = "VirtualThreadDefaultScheduler",
    )

    // visible for testing
    val recordingThreadProbe: Thread =
        Thread.ofVirtual()
            .name("vt-scheduler-lag-probe")
            .start {
                try {
                    monitorUntilIsNotInterrupted()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

    private fun monitorUntilIsNotInterrupted() {
        var last = System.nanoTime()
        while (!Thread.currentThread().isInterrupted) {
            Thread.sleep(interval)
            val now = System.nanoTime()
            val diff = now - last
            last = now

            sampleRecorder.monitorSample(diff)
        }
    }


    override fun close() {
        recordingThreadProbe.interrupt()
        recordingThreadProbe.join(Duration.ofSeconds(1))
    }
}
