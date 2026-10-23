package com.microsoft.copilot.integrations.camel;

import java.io.InputStream;
import java.io.Reader;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.camel.Consumer;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.StreamCache;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.ResourceHelper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.rpc.ToolInvocation;
import com.github.copilot.rpc.ToolResultObject;

import com.microsoft.copilot.integrations.core.internal.StructuredResponse;

/**
 * {@code copilot:tool:<name>} – implements a Copilot tool as a route. The endpoint registers a {@link ToolDefinition}
 * bean called {@code <name>} that agents list in {@code toolRefs}. Each call routes an exchange whose body is the
 * arguments (a {@code Map}, or {@code parametersClass}); the final body is the tool result. A failed exchange
 * fails the tool call.
 */
public class CopilotToolEndpoint extends DefaultEndpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private String description;
    private String parametersSchema;
    private String parametersClass;
    private boolean skipPermission;

    private Class<?> argumentsType;
    private volatile DefaultConsumer consumer;

    CopilotToolEndpoint(String uri, CopilotComponent component, String name) {
        super(uri, component);
        this.name = name;
    }

    @Override
    protected void doInit() throws Exception {
        super.doInit();
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("copilot:tool:" + name + " requires a description for the model");
        }
        if (parametersSchema != null && parametersClass != null) {
            throw new IllegalArgumentException("copilot:tool:" + name
                    + ": parametersSchema and parametersClass are mutually exclusive");
        }
        Map<String, Object> schema;
        if (parametersClass != null) {
            argumentsType = getCamelContext().getClassResolver().resolveMandatoryClass(parametersClass);
            schema = StructuredResponse.of(argumentsType).schema();
        } else if (parametersSchema != null) {
            try (InputStream input = ResourceHelper.resolveMandatoryResourceAsInputStream(getCamelContext(),
                    parametersSchema)) {
                schema = MAPPER.readValue(input, new TypeReference<Map<String, Object>>() {
                });
            }
        } else {
            schema = Map.of("type", "object");
        }
        var tool = ToolDefinition.create(name, description, schema, this::invoke).skipPermission(skipPermission);
        getCamelContext().getRegistry().bind(name, ToolDefinition.class, tool);
    }

    private CompletableFuture<Object> invoke(ToolInvocation invocation) {
        var current = consumer;
        if (current == null) {
            return CompletableFuture.completedFuture(ToolResultObject.failure("Tool " + name + " is not available.",
                    "copilot:tool:" + name + " route is not running"));
        }
        return RouteDispatch.dispatch(current, exchange -> {
            var message = exchange.getMessage();
            message.setBody(argumentsType == null ? invocation.getArguments() : invocation.getArgumentsAs(argumentsType));
            message.setHeader(CopilotConstants.TOOL_NAME, invocation.getToolName());
            message.setHeader(CopilotConstants.TOOL_CALL_ID, invocation.getToolCallId());
            message.setHeader(CopilotConstants.SESSION_ID, invocation.getSessionId());
        }, CopilotToolEndpoint::result);
    }

    private static Object result(Exchange exchange) {
        var message = exchange.getMessage();
        Object body = message.getBody();
        if (body == null) {
            return "";
        }
        if (body instanceof String || body instanceof Number || body instanceof Boolean || body instanceof Map<?, ?>
                || body instanceof Collection<?> || body instanceof ToolResultObject) {
            return body;
        }
        if (body instanceof CharSequence || body instanceof byte[] || body instanceof InputStream
                || body instanceof Reader || body instanceof StreamCache) {
            return message.getBody(String.class);
        }
        return body;
    }

    @Override
    public Producer createProducer() {
        throw new UnsupportedOperationException("copilot:tool is a consumer; agents call it through toolRefs");
    }

    @Override
    public Consumer createConsumer(Processor processor) throws Exception {
        var created = new DefaultConsumer(this, processor) {
            @Override
            protected void doStart() throws Exception {
                super.doStart();
                if (consumer != null) {
                    throw new IllegalStateException("copilot:tool:" + name + " already has a consumer");
                }
                consumer = this;
            }

            @Override
            protected void doStop() throws Exception {
                if (consumer == this) {
                    consumer = null;
                }
                super.doStop();
            }
        };
        configureConsumer(created);
        return created;
    }

    public String getDescription() {
        return description;
    }

    /** What the tool does, shown to the model. Required. */
    public void setDescription(String description) {
        this.description = description;
    }

    public String getParametersSchema() {
        return parametersSchema;
    }

    /** Resource with the JSON Schema of the arguments, such as {@code classpath:schemas/order.json}. */
    public void setParametersSchema(String parametersSchema) {
        this.parametersSchema = parametersSchema;
    }

    public String getParametersClass() {
        return parametersClass;
    }

    /** Record or POJO describing the arguments; the body is converted to it before routing. */
    public void setParametersClass(String parametersClass) {
        this.parametersClass = parametersClass;
    }

    public boolean isSkipPermission() {
        return skipPermission;
    }

    /** Runs the tool without asking the agent's permission handler. Defaults to {@code false}. */
    public void setSkipPermission(boolean skipPermission) {
        this.skipPermission = skipPermission;
    }
}
