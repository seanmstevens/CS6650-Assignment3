import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

public class Consumer {

  protected static final String EXCHANGE_NAME = "liftride";
  protected static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  protected static final JedisPool pool =
      new JedisPool(getPoolConfig(), Protocol.DEFAULT_HOST, 6379);
  private static final Integer NUM_THREADS = 128;

  public static void main(String[] args) throws IOException, TimeoutException {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost("54.148.205.171");
    factory.setUsername("consumer");
    factory.setPassword("admin");

    Connection connection = factory.newConnection();

    // Create a fixed size thread pool. This allows the JVM to schedule threads in the most
    // efficient way possible
    ExecutorService executorService = Executors.newFixedThreadPool(NUM_THREADS);

    for (int i = 0; i < NUM_THREADS; i++) {
      executorService.execute(new ConsumerRunnable(EXCHANGE_NAME, connection));
    }
  }

  private static JedisPoolConfig getPoolConfig() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(128);
    config.setMaxWait(Duration.ofMillis(2000));
    config.setBlockWhenExhausted(true);
    config.setTestOnBorrow(true);

    return config;
  }
}
