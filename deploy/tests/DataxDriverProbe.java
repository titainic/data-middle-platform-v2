import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Offline probe against a prepared DataX distribution. Never opens a connection. */
public final class DataxDriverProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("DataX home, reader|writer, probe classes directory, expectHive boolean");
        }
        Path home = Paths.get(args[0]);
        System.setProperty("datax.home", home.toString());
        // Initialize SPI before exposing plugin jars, as the core process does.
        DriverManager.getDrivers();
        List<URL> coreJars = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(home.resolve("lib"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".jar"))::iterator) {
                coreJars.add(file.toUri().toURL());
            }
        }
        try (URLClassLoader core = new URLClassLoader(coreJars.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Class<?> jarLoaderType = core.loadClass("com.alibaba.datax.core.util.container.JarLoader");
            String[] roots = {home.resolve("plugin/" + args[1] + "/rdbms" + args[1]).toString(), args[2]};
            try (URLClassLoader plugin = (URLClassLoader) jarLoaderType.getConstructor(String[].class, ClassLoader.class)
                    .newInstance((Object) roots, core)) {
                Thread.currentThread().setContextClassLoader(plugin);
                Class<?> check = Class.forName(DataxDriverProbe.class.getName() + "$PluginCheck", true, plugin);
                check.getMethod("run", String.class, boolean.class).invoke(null, args[1], Boolean.parseBoolean(args[3]));
            }
        }
    }

    public static final class PluginCheck {
        public static void run(String kind, boolean expectHive) throws Exception {
            ClassLoader loader = PluginCheck.class.getClassLoader();
            Class<?> dbUtil = Class.forName("com.alibaba.datax.plugin.rdbms.util.DBUtil", true, loader);
            // Actual DataX registration implementation, not a fixture of its behavior.
            dbUtil.getMethod("loadDriverClass", String.class, String.class).invoke(null, kind, "rdbms");
            boolean hive = hasDriver("jdbc:hive2://example.invalid:10000/default", "org.apache.hive.jdbc.HiveDriver", loader);
            boolean mysql = hasDriver("jdbc:mysql://example.invalid:3306/fixture", "com.mysql.cj.jdbc.Driver", loader);
            if (hive != expectHive || !mysql) {
                throw new AssertionError("Unexpected driver lookup: hive=" + hive + ", mysql=" + mysql);
            }
            if (dbUtil.getClassLoader() != loader) throw new AssertionError("DBUtil escaped plugin isolation");
            System.out.println("plugin=rdbms" + kind + " hive2=" + hive + " mysql=" + mysql
                    + " actualDataxJarLoader=true actualDBUtil=true connections=0");
        }

        private static boolean hasDriver(String url, String expected, ClassLoader loader) throws Exception {
            try {
                Driver driver = DriverManager.getDriver(url);
                if (!driver.getClass().getName().equals(expected) || driver.getClass().getClassLoader() != loader) {
                    throw new AssertionError("Unexpected driver or class loader");
                }
                return driver.acceptsURL(url);
            } catch (SQLException absent) {
                return false;
            }
        }
    }
}
