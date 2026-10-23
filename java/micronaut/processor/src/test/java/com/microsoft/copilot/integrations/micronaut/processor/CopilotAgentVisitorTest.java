package io.github.brunoborges.copilot.micronaut.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopilotAgentVisitorTest {

    @TempDir
    Path output;

    @Test
    void generatesARegistrationAndAProxyForConversationalAgents() throws IOException {
        var result = compile("demo.Helper", """
                package demo;
                @io.github.brunoborges.copilot.agent.CopilotAgent(name = "helper")
                public interface Helper {
                    String ask(@io.github.brunoborges.copilot.agent.ConversationId String id,
                            @io.github.brunoborges.copilot.agent.UserMessage String question);
                }
                """);

        assertTrue(result.success(), result.diagnostics());
        String factory = generated("demo/Helper$CopilotAgentBeans.java");
        assertTrue(factory.contains("new io.github.brunoborges.copilot.micronaut.CopilotAgentRegistration(demo.Helper.class)"));
        assertTrue(factory.contains("demo.Helper copilotAgent("));
        assertTrue(Files.exists(output.resolve("classes/demo/$Helper$CopilotAgentBeans$Definition.class")),
                "the factory is processed into a bean definition");
    }

    @Test
    void registersBoundAgentsWithoutAProxy() throws IOException {
        var result = compile("demo.OneShot", """
                package demo;
                @io.github.brunoborges.copilot.agent.CopilotAgent(name = "one-shot")
                public interface OneShot {
                    String ask(@io.github.brunoborges.copilot.agent.UserMessage String question);
                }
                """);

        assertTrue(result.success(), result.diagnostics());
        String factory = generated("demo/OneShot$CopilotAgentBeans.java");
        assertTrue(factory.contains("CopilotAgentRegistration(demo.OneShot.class)"));
        assertFalse(factory.contains("copilotAgent("));
    }

    @Test
    void copiesRequirementsOntoTheFactory() throws IOException {
        var result = compile("demo.Conditional", """
                package demo;
                @io.micronaut.context.annotation.Requires(property = "spec.name", value = "say \\"hi\\"")
                @io.micronaut.context.annotation.Requires(classes = java.util.List.class,
                        os = io.micronaut.context.annotation.Requires.Family.LINUX)
                @io.github.brunoborges.copilot.agent.CopilotAgent(name = "conditional")
                public interface Conditional {
                    String ask(@io.github.brunoborges.copilot.agent.ConversationId String id,
                            @io.github.brunoborges.copilot.agent.UserMessage String question);
                }
                """);

        assertTrue(result.success(), result.diagnostics());
        String factory = generated("demo/Conditional$CopilotAgentBeans.java");
        assertTrue(factory.contains(
                "@io.micronaut.context.annotation.Requires(property = \"spec.name\", value = \"say \\\"hi\\\"\")"),
                factory);
        assertTrue(factory.contains("@io.micronaut.context.annotation.Requires(classes = {java.util.List.class}, "
                + "os = {io.micronaut.context.annotation.Requires.Family.LINUX})"), factory);
    }

    @Test
    void rejectsNonInterfaces() throws IOException {
        var result = compile("demo.Broken", """
                package demo;
                @io.github.brunoborges.copilot.agent.CopilotAgent(name = "broken")
                public class Broken {
                }
                """);

        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("@CopilotAgent must annotate an interface: demo.Broken"),
                result.diagnostics());
    }

    private String generated(String path) throws IOException {
        return Files.readString(output.resolve("sources").resolve(path));
    }

    private Result compile(String className, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path classes = Files.createDirectories(output.resolve("classes"));
        Path sources = Files.createDirectories(output.resolve("sources"));
        String classPath = System.getProperty("java.class.path");
        var file = new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + ".java"),
                javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        var diagnostics = new StringWriter();
        List<String> options = List.of("-classpath", classPath, "-processorpath", classPath,
                "-d", classes.toString(), "-s", sources.toString(), "-proc:full");
        boolean success = compiler.getTask(diagnostics, null, null, options, null, List.of(file)).call();
        return new Result(success, diagnostics.toString());
    }

    private record Result(boolean success, String diagnostics) {
    }

}
