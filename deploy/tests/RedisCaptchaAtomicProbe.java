import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import tech.qiantong.qdata.common.constant.CacheConstants;
import tech.qiantong.qdata.common.core.redis.RedisCache;
import tech.qiantong.qdata.redis.config.RedisConfig;

public final class RedisCaptchaAtomicProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: RedisCaptchaAtomicProbe private-runtime.env redis-port");
        String password = null;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (line.startsWith("QDATA_REDIS_PASSWORD=")) {
                password = line.substring(line.indexOf('=') + 1);
                if (password.startsWith("\"") && password.endsWith("\"")) password = password.substring(1, password.length() - 1);
                password = password.replace("\\\"", "\"").replace("\\\\", "\\");
            }
        }
        if (password == null) throw new IllegalArgumentException("The protected runtime file lacks QDATA_REDIS_PASSWORD");
        RedisStandaloneConfiguration connection = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(args[1]));
        connection.setPassword(password);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(connection);
        factory.afterPropertiesSet();
        factory.start();
        try {
            RedisCache cache = new RedisCache();
            cache.redisTemplate = new RedisConfig().redisTemplate(factory);
            String challenge = "captcha-原子-42";
            String key = CacheConstants.CAPTCHA_CODE_KEY + "deployment-atomic-" + UUID.randomUUID();
            cache.setCacheObject(key, challenge, 2, TimeUnit.MINUTES);
            CountDownLatch start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(16);
            var results = new ArrayList<java.util.concurrent.Future<String>>();
            try {
                for (int index = 0; index < 16; index++) results.add(executor.submit(() -> { start.await(); return cache.consumeCaptcha(key); }));
                start.countDown();
                int received = 0;
                for (var result : results) {
                    String value = result.get(30, TimeUnit.SECONDS);
                    if (value != null) {
                        if (!challenge.equals(value)) throw new AssertionError("Redis value serialization changed the captcha answer");
                        received++;
                    }
                }
                if (received != 1 || cache.consumeCaptcha(key) != null) throw new AssertionError("The captcha was not consumed exactly once");
                System.out.println("{\"status\":\"PASS\",\"concurrentConsumers\":16,\"receivedValues\":1,\"fastJsonSerializer\":\"PASS\",\"replay\":\"REJECTED\"}");
            } finally {
                executor.shutdownNow();
            }
        } finally {
            factory.destroy();
        }
    }
}
