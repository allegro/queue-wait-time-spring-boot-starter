package pl.allegro.tech.queuewaittime.schedulerlag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

internal class SchedulerLagPropertiesConfigurationTest {
    @Test
    fun registersAndBindsSchedulerLagProperties() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SchedulerLagPropertiesConfiguration::class.java))
            .withPropertyValues(
                "allegro-tech.scheduler-lag.enabled=false",
                "allegro-tech.scheduler-lag.interval=25ms",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(SchedulerLagProperties::class.java)
                val properties = context.getBean<SchedulerLagProperties>()
                assertThat(properties.enabled).isFalse()
                assertThat(properties.interval).isEqualTo(Duration.ofMillis(25))
            }
    }
}
