package io.github.brunoborges.copilot.agent.internal;

import java.util.Map;

import com.github.copilot.rpc.ProviderConfig;
import io.github.brunoborges.copilot.quarkus.runtime.config.CopilotProviderProperties;

final class ProviderBindings {

    private ProviderBindings() {
    }

    /** Resolves a provider name to a property definition or SDK bean; an empty name selects none. */
    static ProviderConfig bind(String name, BeanLookup beans, CopilotProviderProperties properties) {
        if (name.isEmpty()) {
            return null;
        }
        var configured = properties.providers().get(name);
        boolean nativeBean = beans.containsBean(name) && beans.isTypeMatch(name, ProviderConfig.class);
        Assert.isTrue(configured == null || !nativeBean,
                "Ambiguous provider definition in properties and SDK bean: " + name);
        Assert.isTrue(configured != null || nativeBean, () -> "Unknown provider '" + name
                + "': define quarkus.copilot.providers." + name + " or an SDK ProviderConfig bean @Named '" + name + "'");
        return configured == null ? beans.getBean(name, ProviderConfig.class) : configured.toProviderConfig();
    }
}
