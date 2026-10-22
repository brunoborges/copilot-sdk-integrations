package io.github.brunoborges.copilot.autoconfigure;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.brunoborges.copilot.agent.CopilotAgent;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentFactoryBean;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

public final class CopilotAgentsRegistrar implements ImportBeanDefinitionRegistrar, BeanFactoryAware,
        EnvironmentAware, ResourceLoaderAware {

    private BeanFactory beans;
    private Environment environment;
    private ResourceLoader resources;

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        if (!environment.getProperty("copilot.enabled", Boolean.class, true)) {
            return;
        }
        Set<String> packages = new LinkedHashSet<>();
        var enable = metadata.getAnnotationAttributes("io.github.brunoborges.copilot.agent.EnableCopilotAgents");
        if (enable != null) {
            packages.addAll(List.of((String[]) enable.get("basePackages")));
            for (var type : (Class<?>[]) enable.get("basePackageClasses")) {
                packages.add(ClassUtils.getPackageName(type));
            }
            if (packages.isEmpty()) {
                packages.add(ClassUtils.getPackageName(metadata.getClassName()));
            }
        } else if (AutoConfigurationPackages.has(beans)) {
            packages.addAll(AutoConfigurationPackages.get(beans));
        }
        var scanner = new ClassPathScanningCandidateComponentProvider(false, environment) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isIndependent();
            }
        };
        scanner.setResourceLoader(resources);
        scanner.addIncludeFilter(new AnnotationTypeFilter(CopilotAgent.class));
        for (var basePackage : packages) {
            Assert.hasText(basePackage, "Copilot agent scanning requires a non-empty base package");
            for (var candidate : scanner.findCandidateComponents(environment.resolveRequiredPlaceholders(basePackage))) {
                Class<?> type = ClassUtils.resolveClassName(candidate.getBeanClassName(), resources.getClassLoader());
                Assert.isTrue(type.isInterface(), "@CopilotAgent must annotate an interface: " + type.getName());
                String name = CopilotAgentDefinition.name(type, environment);
                if (registry.containsBeanDefinition(name)) {
                    var existing = registry.getBeanDefinition(name);
                    Assert.isTrue(type.equals(existing.getAttribute("copilot.agentType")),
                            "Duplicate Copilot agent bean name: " + name);
                    continue;
                }
                var definition = new RootBeanDefinition(CopilotAgentFactoryBean.class);
                definition.getConstructorArgumentValues().addIndexedArgumentValue(0, type);
                definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
                definition.setAttribute("copilot.agentType", type);
                definition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE,
                        CopilotAgentFactoryBean.requiresBinding(type) ? CopilotAgentDefinition.class : type);
                definition.setRole(BeanDefinition.ROLE_APPLICATION);
                registry.registerBeanDefinition(name, definition);
            }
        }
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        beans = beanFactory;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        resources = resourceLoader;
    }
}
