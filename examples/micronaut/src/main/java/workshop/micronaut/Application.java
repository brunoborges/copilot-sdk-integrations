package workshop.micronaut;

import io.micronaut.context.ApplicationContext;
import workshop.common.AccessibilityTools;
import workshop.common.MuseumTools;
import workshop.common.BrowserOptions;
import workshop.common.Workshop;

public class Application {
    public static void main(String[] args) throws Exception {
        try (var context = ApplicationContext.run()) {
            Workshop.run(args, context.getBean(MuseumTools.class), context.getBean(AccessibilityTools.class),
                    context.getBean(MuseumAgent.class)::run, context.getBean(AccessibilityAgent.class)::run,
                    context.getBean(ResearchAgent.class)::run, context.getBean(BrowserOptions.class));
        }
    }
}
