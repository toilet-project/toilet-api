import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Fixture suites only; all-mode also runs Spring test contexts, never a production deployment. */
public class IsolatedRegressionRunner {
    public static void main(String[] args) throws Exception {
        boolean all = args.length == 1 && args[0].equals("--all-fixtures");
        if (args.length != 0 && !all) throw new IllegalArgumentException();
        var suites = new String[]{
            "com.example.toiletapi.cache.CacheInvalidationMySqlTest",
            "com.example.toiletapi.cache.CacheInvalidationPipelineMySqlTest",
            "com.example.toiletapi.toilet.repository.ToiletRegionMySqlTest",
            "com.example.toiletapi.toilet.service.ToiletSitemapMySqlTest"};
        for (var suite : suites) {
            var field = Class.forName(suite).getDeclaredField("mysql");
            if (!field.trySetAccessible()) throw new IllegalStateException();
            var mysql = (org.testcontainers.mysql.MySQLContainer) field.get(null);
            mysql.withLabel("geupddong.isolated-regression", "maintenance-20260910");
            mysql.withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                .withMemory(768L * 1024 * 1024).withNanoCPUs(1_000_000_000L)
                .withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                    com.github.dockerjava.api.model.Ports.Binding.bindIp("127.0.0.1"),
                    new com.github.dockerjava.api.model.ExposedPort(3306))));
        }
        var builder = LauncherDiscoveryRequestBuilder.request();
        if (all) builder.selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage("com.example"),
                org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage("com.geupddong"));
        else builder.selectors(
            selectClass("com.example.toiletapi.cache.CacheInvalidationMySqlTest"),
            selectClass("com.example.toiletapi.cache.CacheInvalidationPipelineMySqlTest"),
            selectClass("com.example.toiletapi.toilet.repository.ToiletRegionMySqlTest"),
            selectClass("com.example.toiletapi.toilet.service.ToiletSitemapMySqlTest")
        );
        var request = builder.build();
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, listener);
        var summary = listener.getSummary();
        System.out.printf("ISOLATED_MYSQL tests=%d passed=%d failed=%d skipped=%d containersFailed=%d%n",
            summary.getTestsFoundCount(), summary.getTestsSucceededCount(), summary.getTestsFailedCount(),
            summary.getTestsSkippedCount(), summary.getContainersFailedCount());
        if (summary.getTestsFoundCount() == 0 || summary.getTestsFailedCount() != 0
                || summary.getContainersFailedCount() != 0 || (!all && summary.getTestsSkippedCount() != 0))
            System.exit(1);
        // Spring test contexts may retain non-daemon workers; run shutdown hooks after the summary.
        System.exit(0);
    }
}
