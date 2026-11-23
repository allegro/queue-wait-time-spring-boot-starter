package pl.allegro.tech.queuewaittime.micrometer

import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

@AutoConfiguration(
    beforeName = ["org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"],
)
@ConditionalOnClass(MeterFilter::class)
@ConditionalOnProperty(
    prefix = "allegro-tech",
    name = ["rename-executor-idle-to-executor-queue-wait-time"],
    havingValue = "true",
    matchIfMissing = true,
)
class ExecutorIdleRenameAutoConfiguration {
    @Bean
    fun executorIdleRenameMeterFilter(): MeterFilter = ExecutorIdleRenameMeterFilter()
}
