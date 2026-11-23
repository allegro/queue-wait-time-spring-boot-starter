package pl.allegro.tech.queuewaittime.virtualthreads

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading
import org.springframework.boot.thread.Threading
import org.springframework.context.annotation.Bean
import pl.allegro.tech.queuewaittime.schedulerlag.SchedulerLagProperties

@AutoConfiguration(
    afterName = ["org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"],
)
@ConditionalOnClass(MeterRegistry::class)
@ConditionalOnBean(MeterRegistry::class)
@ConditionalOnProperty(
    prefix = "allegro-tech.scheduler-lag",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class VirtualThreadsSchedulerLagConfiguration {
    @Bean("virtualThreadSchedulerLagMonitor")
    @ConditionalOnThreading(Threading.VIRTUAL)
    internal fun virtualThreadSchedulerLagMonitor(
        properties: SchedulerLagProperties,
        meterRegistry: ObjectProvider<MeterRegistry>,
    ): VirtualThreadSchedulerLagMonitor = VirtualThreadSchedulerLagMonitor(meterRegistry.getObject(), properties.interval)
}
