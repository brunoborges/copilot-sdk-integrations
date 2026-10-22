package io.github.brunoborges.copilot.quarkus.runtime;

import java.util.ArrayList;
import java.util.List;

/** Build-time record of a hook bean annotated with {@code @GlobalCopilotAgentHooks} or {@code @CopilotAgentHooksFor}. */
public class HookRegistration {

    private String beanId;
    private boolean global;
    private List<String> targets = new ArrayList<>();

    public HookRegistration() {
    }

    public HookRegistration(String beanId, boolean global, List<String> targets) {
        this.beanId = beanId;
        this.global = global;
        this.targets = new ArrayList<>(targets);
    }

    public String getBeanId() {
        return beanId;
    }

    public void setBeanId(String beanId) {
        this.beanId = beanId;
    }

    public boolean isGlobal() {
        return global;
    }

    public void setGlobal(boolean global) {
        this.global = global;
    }

    public List<String> getTargets() {
        return targets;
    }

    public void setTargets(List<String> targets) {
        this.targets = targets;
    }
}
