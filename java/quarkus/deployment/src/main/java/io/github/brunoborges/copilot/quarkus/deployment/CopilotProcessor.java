package io.github.brunoborges.copilot.quarkus.deployment;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import jakarta.inject.Singleton;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import com.github.copilot.tool.CopilotToolMetadataProvider;

import io.github.brunoborges.copilot.agent.ConversationId;
import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.CopilotAgentHooks;
import io.github.brunoborges.copilot.agent.CopilotAgentHooksFor;
import io.github.brunoborges.copilot.agent.CopilotAgentReturnTypeAdapter;
import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.github.brunoborges.copilot.agent.CopilotTokenResolver;
import io.github.brunoborges.copilot.agent.GlobalCopilotAgentHooks;
import io.github.brunoborges.copilot.quarkus.runtime.CopilotAgentRegistry;
import io.github.brunoborges.copilot.quarkus.runtime.CopilotAgentTelemetryDecorator;
import io.github.brunoborges.copilot.quarkus.runtime.CopilotProducers;
import io.github.brunoborges.copilot.quarkus.runtime.CopilotRecorder;
import io.github.brunoborges.copilot.quarkus.runtime.CopilotSessionCleanup;
import io.github.brunoborges.copilot.quarkus.runtime.HookRegistration;
import io.github.brunoborges.copilot.quarkus.runtime.endpoint.CopilotEndpoint;
import io.github.brunoborges.copilot.quarkus.runtime.endpoint.CopilotEndpointRecorder;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SynthesisFinishedBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.vertx.http.deployment.spi.RouteBuildItem;

class CopilotProcessor {

    static final String FEATURE = "copilot";

    static final DotName COPILOT_AGENT = DotName.createSimple(CopilotAgent.class);
    static final DotName CONVERSATION_ID = DotName.createSimple(ConversationId.class);
    static final DotName GLOBAL_HOOKS = DotName.createSimple(GlobalCopilotAgentHooks.class);
    static final DotName HOOKS_FOR = DotName.createSimple(CopilotAgentHooksFor.class);
    static final DotName TOOL_METADATA = DotName.createSimple(CopilotToolMetadataProvider.class);

    private static final String MICROMETER = "io.quarkus.micrometer";
    private static final String INFO = "io.quarkus.info";

    static final class Enabled implements BooleanSupplier {
        CopilotBuildTimeConfig config;

        @Override
        public boolean getAsBoolean() {
            return config.enabled();
        }
    }

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep(onlyIf = Enabled.class)
    void beans(CopilotBuildTimeConfig config, Capabilities capabilities, BuildProducer<AdditionalBeanBuildItem> beans,
            BuildProducer<UnremovableBeanBuildItem> unremovable) {
        beans.produce(AdditionalBeanBuildItem.builder().addBeanClasses(CopilotProducers.class,
                CopilotAgentRegistry.class, CopilotSessionCleanup.class).setUnremovable().build());

        List<String> integrations = new ArrayList<>();
        if (config.health().enabled() && capabilities.isPresent(Capability.SMALLRYE_HEALTH)) {
            integrations.add("io.github.brunoborges.copilot.quarkus.runtime.health.CopilotHealthCheck");
        }
        if (config.metrics().enabled() && capabilities.isPresent(MICROMETER)) {
            integrations.add("io.github.brunoborges.copilot.quarkus.runtime.metrics.MicrometerCopilotAgentTelemetry");
        }
        if (config.info().enabled() && capabilities.isPresent(INFO)) {
            integrations.add("io.github.brunoborges.copilot.quarkus.runtime.info.CopilotInfoContributor");
        }
        if (config.security().identityTokenResolver().enabled()) {
            if (!capabilities.isPresent(Capability.SECURITY)) {
                throw new ConfigurationException("quarkus.copilot.security.identity-token-resolver.enabled requires "
                        + "a Quarkus Security extension");
            }
            integrations.add("io.github.brunoborges.copilot.quarkus.runtime.security.IdentityCopilotTokenResolver");
        }
        if (QuarkusClassLoader.isClassPresentAtRuntime("io.smallrye.mutiny.Multi")) {
            integrations.add("io.github.brunoborges.copilot.quarkus.runtime.mutiny.MutinyCopilotAgentAdapter");
        }
        if (config.endpoint().enabled()) {
            if (!capabilities.isPresent(Capability.VERTX_HTTP)) {
                throw new ConfigurationException("quarkus.copilot.endpoint.enabled requires quarkus-vertx-http");
            }
            integrations.add(CopilotEndpoint.class.getName());
        }
        if (!integrations.isEmpty()) {
            beans.produce(AdditionalBeanBuildItem.builder().addBeanClasses(integrations).setUnremovable().build());
        }

        unremovable.produce(UnremovableBeanBuildItem.beanTypes(CopilotAgentHooks.class,
                CopilotAgentReturnTypeAdapter.class, CopilotTokenResolver.class, CopilotAgentTelemetry.class,
                CopilotAgentTelemetryDecorator.class));
    }

    @BuildStep(onlyIf = Enabled.class)
    void agents(CombinedIndexBuildItem combinedIndex, BuildProducer<CopilotAgentBuildItem> agents,
            BuildProducer<NativeImageProxyDefinitionBuildItem> proxies,
            BuildProducer<ReflectiveClassBuildItem> reflective) {
        IndexView index = combinedIndex.getIndex();
        List<String> errors = new ArrayList<>();
        for (AnnotationInstance annotation : index.getAnnotations(COPILOT_AGENT)) {
            if (annotation.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo type = annotation.target().asClass();
            if (!type.isInterface()) {
                errors.add("@CopilotAgent must annotate an interface: " + type.name());
                continue;
            }
            agents.produce(new CopilotAgentBuildItem(type.name(), requiresBinding(index, type)));
            proxies.produce(new NativeImageProxyDefinitionBuildItem(type.name().toString()));
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(String.join("\n", errors));
        }

        List<String> metadata = new ArrayList<>();
        for (ClassInfo provider : index.getAllKnownImplementations(TOOL_METADATA)) {
            metadata.add(provider.name().toString());
        }
        if (!metadata.isEmpty()) {
            reflective.produce(ReflectiveClassBuildItem.builder(metadata.toArray(String[]::new)).constructors()
                    .methods().build());
        }
    }

    /** Mirrors {@code CopilotAgentFactory.requiresBinding}: some abstract method has no {@code @ConversationId}. */
    static boolean requiresBinding(IndexView index, ClassInfo type) {
        Set<DotName> visited = new HashSet<>();
        List<ClassInfo> pending = new ArrayList<>(List.of(type));
        while (!pending.isEmpty()) {
            ClassInfo current = pending.remove(pending.size() - 1);
            if (!visited.add(current.name())) {
                continue;
            }
            for (MethodInfo method : current.methods()) {
                if (Modifier.isAbstract(method.flags()) && method.annotations(CONVERSATION_ID).stream()
                        .noneMatch(a -> a.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER)) {
                    return true;
                }
            }
            for (DotName parent : current.interfaceNames()) {
                ClassInfo info = index.getClassByName(parent);
                if (info != null) {
                    pending.add(info);
                }
            }
        }
        return false;
    }

    @BuildStep(onlyIf = Enabled.class)
    @Record(ExecutionTime.RUNTIME_INIT)
    void agentBeans(List<CopilotAgentBuildItem> agents, CopilotRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> synthetic) {
        for (CopilotAgentBuildItem agent : agents) {
            if (agent.bound()) {
                continue;
            }
            synthetic.produce(SyntheticBeanBuildItem.configure(agent.type()).addType(agent.type())
                    .scope(Singleton.class).unremovable().setRuntimeInit()
                    .supplier(recorder.agent(agent.type().toString())).done());
        }
    }

    @BuildStep(onlyIf = Enabled.class)
    @Record(ExecutionTime.STATIC_INIT)
    void catalog(List<CopilotAgentBuildItem> agents, SynthesisFinishedBuildItem synthesis, CopilotRecorder recorder) {
        List<HookRegistration> hooks = new ArrayList<>();
        synthesis.beanStream().forEach(bean -> {
            AnnotationTarget target = hookTarget(bean);
            if (target == null) {
                return;
            }
            AnnotationInstance global = annotation(target, GLOBAL_HOOKS);
            AnnotationInstance targeted = annotation(target, HOOKS_FOR);
            if (global == null && targeted == null) {
                return;
            }
            List<String> targets = new ArrayList<>();
            if (targeted != null) {
                for (var value : targeted.value().asClassArray()) {
                    targets.add(value.name().toString());
                }
            }
            hooks.add(new HookRegistration(bean.getIdentifier(), global != null, targets));
        });
        recorder.catalog(agents.stream().map(agent -> agent.type().toString()).sorted().toList(), hooks);
    }

    private static AnnotationTarget hookTarget(BeanInfo bean) {
        if (bean.getTarget().isEmpty()) {
            return null;
        }
        AnnotationTarget target = bean.getTarget().get();
        return switch (target.kind()) {
            case CLASS, METHOD -> target;
            default -> null;
        };
    }

    private static AnnotationInstance annotation(AnnotationTarget target, DotName name) {
        return target.kind() == AnnotationTarget.Kind.CLASS ? target.asClass().declaredAnnotation(name)
                : target.asMethod().annotation(name);
    }

    @BuildStep(onlyIf = Enabled.class)
    @Record(ExecutionTime.RUNTIME_INIT)
    void endpoint(CopilotBuildTimeConfig config, CopilotEndpointRecorder recorder,
            BuildProducer<RouteBuildItem> routes, BuildProducer<ReflectiveClassBuildItem> reflective) {
        if (!config.endpoint().enabled()) {
            return;
        }
        var handler = recorder.handler();
        routes.produce(RouteBuildItem.newFrameworkRoute("copilot").withRequestHandler(handler).asBlockingRoute()
                .displayOnNotFoundPage("Copilot diagnostics").build());
        routes.produce(RouteBuildItem.newFrameworkRoute("copilot/:section").withRequestHandler(handler)
                .asBlockingRoute().build());
        List<String> descriptors = new ArrayList<>();
        for (Class<?> nested : CopilotEndpoint.class.getDeclaredClasses()) {
            if (Modifier.isPublic(nested.getModifiers())) {
                descriptors.add(nested.getName());
            }
        }
        reflective.produce(ReflectiveClassBuildItem.builder(descriptors.toArray(String[]::new)).constructors()
                .methods().fields().build());
    }
}
