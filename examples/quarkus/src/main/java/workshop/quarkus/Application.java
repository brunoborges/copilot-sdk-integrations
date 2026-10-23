package workshop.quarkus;

import jakarta.inject.Inject;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import workshop.common.AccessibilityTools;
import workshop.common.MuseumTools;
import workshop.common.BrowserOptions;
import workshop.common.Workshop;

@QuarkusMain
public class Application implements QuarkusApplication {
    @Inject MuseumAgent curator;
    @Inject AccessibilityAgent reviewer;
    @Inject ResearchAgent researcher;
    @Inject MuseumTools museum;
    @Inject AccessibilityTools accessibility;
    @Inject BrowserOptions browser;

    @Override public int run(String... args) throws Exception {
        Workshop.run(args, museum, accessibility, curator::run, reviewer::run, researcher::run, browser);
        return 0;
    }
}
