package com.microsoft.copilot.integrations.core.internal;


/** Resolves {@code *Ref} attribute values to beans, failing with messages that name the attribute and the expected type. */
final class BeanRefs {

    private BeanRefs() {
    }

    static <T> T resolve(String agent, String attribute, String name, Class<T> type, BeanLookup beans) {
        String owner = agent == null ? attribute : "Copilot agent " + agent + ": " + attribute;
        Assert.hasText(name, () -> owner + " entries must not be blank");
        if (!beans.containsBean(name)) {
            throw new IllegalArgumentException(owner + " references bean '" + name
                    + "', but no bean with that name exists; expected a " + type.getName() + " bean");
        }
        if (!beans.isTypeMatch(name, type)) {
            Class<?> actual = beans.getType(name);
            throw new IllegalArgumentException(owner + " references bean '" + name + "' of type "
                    + (actual == null ? "unknown" : actual.getName()) + "; expected a " + type.getName() + " bean");
        }
        return beans.getBean(name, type);
    }
}
