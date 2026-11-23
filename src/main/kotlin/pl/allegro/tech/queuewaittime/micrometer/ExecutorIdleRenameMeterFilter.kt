package pl.allegro.tech.queuewaittime.micrometer

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter

internal class ExecutorIdleRenameMeterFilter : MeterFilter {
    override fun map(id: Meter.Id): Meter.Id =
        if (id.name == "executor.idle") {
            id.withName("executor.queue.wait.time")
        } else {
            id
        }
}
