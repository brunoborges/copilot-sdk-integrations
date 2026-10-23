package io.github.brunoborges.copilot.micronaut.processor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * Registers every {@code @CopilotAgent} interface with Micronaut at compile time by generating a {@code @Factory}
 * next to it. The factory always contributes a {@code CopilotAgentRegistration}, so the runtime knows the agent
 * catalog without classpath scanning, and, for agents whose methods all take a {@code @ConversationId}, an
 * injectable singleton proxy. Agents with ID-less methods are obtained through {@code CopilotAgents} instead.
 * {@code @Requires} conditions declared on the interface are copied onto the factory, so an agent can be made
 * conditional like any other bean.
 */
public final class CopilotAgentVisitor implements TypeElementVisitor<Object, Object> {

    static final String COPILOT_AGENT = "io.github.brunoborges.copilot.agent.CopilotAgent";
    static final String CONVERSATION_ID = "io.github.brunoborges.copilot.agent.ConversationId";
    static final String SUFFIX = "$CopilotAgentBeans";

    private static final Set<String> CLASS_MEMBERS = Set.of("condition", "classes", "entities", "beans", "missing",
            "missingBeans", "bean");
    private static final Set<String> ENUM_MEMBERS = Set.of("sdk", "os", "notOs");
    private static final Set<String> SCALAR_MEMBERS = Set.of("property", "notEquals", "condition", "sdk",
            "configuration", "value", "defaultValue", "pattern", "version", "missingProperty", "bean",
            "beanProperty");

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of(COPILOT_AGENT);
    }

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (!element.hasDeclaredAnnotation(COPILOT_AGENT)) {
            return;
        }
        if (!element.isInterface()) {
            context.fail("@CopilotAgent must annotate an interface: " + element.getName(), element);
            return;
        }
        String packageName = element.getPackageName();
        String simpleName = binarySimpleName(element) + SUFFIX;
        String source = source(packageName, simpleName, element.getName().replace('$', '.'), requiresBinding(element),
                requirements(element.getDeclaredAnnotationValuesByType(Requires.class)));
        context.visitGeneratedSourceFile(packageName, simpleName, element).ifPresent(file -> {
            try {
                file.write(writer -> writer.write(source));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    /** Mirrors {@code CopilotAgentFactory.requiresBinding}: some abstract method has no {@code @ConversationId}. */
    static boolean requiresBinding(ClassElement element) {
        return element.getEnclosedElements(ElementQuery.ALL_METHODS).stream()
                .filter(MethodElement::isAbstract)
                .anyMatch(method -> Arrays.stream(method.getParameters())
                        .noneMatch(parameter -> parameter.hasAnnotation(CONVERSATION_ID)));
    }

    /** Renders {@code @Requires} annotations as source, one per line. */
    static String requirements(List<AnnotationValue<Requires>> requirements) {
        var source = new StringBuilder();
        for (AnnotationValue<Requires> requirement : requirements) {
            String members = requirement.getValues().keySet().stream()
                    .map(CharSequence::toString)
                    .sorted()
                    .map(member -> member + " = " + render(requirement, member))
                    .collect(Collectors.joining(", "));
            source.append("@io.micronaut.context.annotation.Requires(").append(members).append(")\n");
        }
        return source.toString();
    }

    private static String render(AnnotationValue<Requires> requirement, String member) {
        List<String> values;
        if (CLASS_MEMBERS.contains(member)) {
            values = Arrays.stream(requirement.annotationClassValues(member))
                    .map(AnnotationClassValue::getName)
                    .map(name -> name.replace('$', '.') + ".class")
                    .toList();
        } else if (ENUM_MEMBERS.contains(member)) {
            String type = member.equals("sdk") ? "Sdk" : "Family";
            values = Arrays.stream(requirement.stringValues(member))
                    .map(name -> "io.micronaut.context.annotation.Requires." + type + "." + name)
                    .toList();
        } else {
            values = Arrays.stream(requirement.stringValues(member)).map(CopilotAgentVisitor::literal).toList();
        }
        if (SCALAR_MEMBERS.contains(member) && values.size() == 1) {
            return values.get(0);
        }
        return values.stream().collect(Collectors.joining(", ", "{", "}"));
    }

    private static String literal(String value) {
        var literal = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> literal.append("\\\"");
                case '\\' -> literal.append("\\\\");
                case '\n' -> literal.append("\\n");
                case '\r' -> literal.append("\\r");
                case '\t' -> literal.append("\\t");
                default -> literal.append(c);
            }
        }
        return literal.append('"').toString();
    }

    private static String binarySimpleName(ClassElement element) {
        String name = element.getName();
        String packageName = element.getPackageName();
        return packageName.isEmpty() ? name : name.substring(packageName.length() + 1);
    }

    private static String source(String packageName, String simpleName, String agentType, boolean bound,
            String requirements) {
        var source = new StringBuilder();
        if (!packageName.isEmpty()) {
            source.append("package ").append(packageName).append(";\n\n");
        }
        source.append(requirements);
        source.append("""
                @io.micronaut.context.annotation.Factory
                @io.micronaut.context.annotation.Requires(property = "micronaut.copilot.enabled", notEquals = "false")
                final class %1$s {

                    @jakarta.inject.Singleton
                    @jakarta.inject.Named("%2$s")
                    io.github.brunoborges.copilot.micronaut.CopilotAgentRegistration copilotAgentRegistration() {
                        return new io.github.brunoborges.copilot.micronaut.CopilotAgentRegistration(%2$s.class);
                    }
                """.formatted(simpleName, agentType));
        if (!bound) {
            source.append("""

                        @jakarta.inject.Singleton
                        %1$s copilotAgent(io.github.brunoborges.copilot.micronaut.CopilotAgentRegistry registry) {
                            return registry.proxy(%1$s.class);
                        }
                    """.formatted(agentType));
        }
        source.append("}\n");
        return source.toString();
    }
}
