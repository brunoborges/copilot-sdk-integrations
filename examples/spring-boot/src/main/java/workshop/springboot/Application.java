package workshop.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import workshop.common.AccessibilityTools;
import workshop.common.MuseumTools;
import workshop.common.BrowserOptions;
import workshop.common.Workshop;

@SpringBootApplication
public class Application {
    public static void main(String[] args) throws Exception {
        var app = new SpringApplication(Application.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run()) {
            Workshop.run(args, context.getBean(MuseumTools.class), context.getBean(AccessibilityTools.class),
                    context.getBean(MuseumAgent.class)::run, context.getBean(AccessibilityAgent.class)::run,
                    context.getBean(ResearchAgent.class)::run, context.getBean(BrowserOptions.class));
        }
    }
}
