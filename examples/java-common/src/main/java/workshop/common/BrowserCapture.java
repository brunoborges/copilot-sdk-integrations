package workshop.common;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.ServiceWorkerPolicy;

public final class BrowserCapture {
    private BrowserCapture() {}

    public static String validateTarget(String target) {
        URI uri = URI.create(target);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !uri.toASCIIString().equals(target)) {
            throw new IllegalArgumentException("Use an absolute ASCII HTTP(S) URL without credentials or fragment");
        }
        return target;
    }

    public static boolean allowedRequest(String target, String url, String method, boolean navigation) {
        if (!"GET".equals(method)) return false;
        if (navigation) return target.equals(url);
        try {
            URI uri = URI.create(url);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static void capture(String target, SnapshotStore output, BrowserOptions options) throws Exception {
        validateTarget(target);
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(options.headless()).setTimeout(options.timeoutMillis()))) {
            try (var context = browser.newContext(new Browser.NewContextOptions()
                    .setAcceptDownloads(false).setServiceWorkers(ServiceWorkerPolicy.BLOCK))) {
                AtomicBoolean blockedNavigation = new AtomicBoolean();
                context.route("**/*", route -> {
                    var request = route.request();
                    if (allowedRequest(target, request.url(), request.method(), request.isNavigationRequest())) {
                        if (request.isNavigationRequest()) {
                            // Intercept the response before Chromium can follow a redirect. A post-load
                            // URL check alone would detect an out-of-scope visit only after it happened.
                            var response = route.fetch(new Route.FetchOptions().setMaxRedirects(0).setTimeout(options.timeoutMillis()));
                            try {
                                if (response.status() >= 300 && response.status() < 400) {
                                    blockedNavigation.set(true);
                                    route.abort();
                                } else {
                                    route.fulfill(new Route.FulfillOptions().setResponse(response));
                                }
                            } finally {
                                response.dispose();
                            }
                        } else {
                            route.resume();
                        }
                    } else {
                        if (request.isNavigationRequest()) blockedNavigation.set(true);
                        route.abort();
                    }
                });
                var page = context.newPage();
                page.setDefaultTimeout(options.timeoutMillis());
                page.setDefaultNavigationTimeout(options.timeoutMillis());
                page.navigate(target);
                if (blockedNavigation.get() || !target.equals(page.url())) {
                    throw new IllegalStateException("Redirect or navigation away from exact target rejected");
                }
                String tree = page.locator("body").ariaSnapshot();
                if (blockedNavigation.get() || !target.equals(page.url())) {
                    throw new IllegalStateException("Page navigated during snapshot capture");
                }
                output.write(tree);
            }
        }
    }
}
