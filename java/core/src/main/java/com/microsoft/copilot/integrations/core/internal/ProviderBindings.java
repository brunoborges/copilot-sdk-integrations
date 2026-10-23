package com.microsoft.copilot.integrations.core.internal;

import com.github.copilot.rpc.ProviderConfig;

final class ProviderBindings {

    private ProviderBindings() {
    }

    /** Resolves a provider name to a property definition or SDK bean; an empty name selects none. */
    static ProviderConfig bind(String name, BindingContext binding) {
        if (name.isEmpty()) {
            return null;
        }
        var beans = binding.beans();
        var configured = binding.providers().apply(name);
        boolean nativeBean = beans.containsBean(name) && beans.isTypeMatch(name, ProviderConfig.class);
        Assert.isTrue(configured == null || !nativeBean,
                "Ambiguous provider definition in properties and SDK bean: " + name);
        Assert.isTrue(configured != null || nativeBean, () -> "Unknown provider '" + name + "': define "
                + binding.configPrefix() + ".providers." + name + " or an SDK ProviderConfig bean named '" + name + "'");
        return configured == null ? beans.getBean(name, ProviderConfig.class) : configured;
    }
}
