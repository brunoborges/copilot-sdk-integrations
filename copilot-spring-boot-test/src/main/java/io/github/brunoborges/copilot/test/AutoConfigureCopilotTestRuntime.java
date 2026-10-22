package io.github.brunoborges.copilot.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;

/**
 * Replaces the {@code CopilotClient} of a Spring Boot test with the scripted, in-memory {@link CopilotTestRuntime}, so
 * no Copilot CLI is started.
 *
 * <p>The runtime is available for injection, records every interaction and is {@link CopilotTestRuntime#reset()
 * reset} after each test method.
 *
 * <pre>{@code
 * @SpringBootTest
 * @AutoConfigureCopilotTestRuntime
 * class AdvisorTests {
 *
 *     @Autowired CopilotTestRuntime copilot;
 *     @Autowired SpringAdvisor advisor;
 *
 *     @Test
 *     void answers() {
 *         copilot.agent("spring-advisor").replies("Use constructor injection.");
 *         assertThat(advisor.ask("1", "How?").join()).isEqualTo("Use constructor injection.");
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ImportAutoConfiguration
public @interface AutoConfigureCopilotTestRuntime {
}
