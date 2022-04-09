import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import redis.clients.jedis.Jedis;

/** Class representing a runnable task that receives messages from a queue and processes them. */
public class ConsumerRunnable implements Runnable {

  private final String exchangeName;
  private final Connection conn;
  private static final String QUEUE_NAME = "resorts";

  /**
   * Constructor for ConsumerRunnable class.
   *
   * @param exchangeName The name of the exchange from which messages will be retrieved.
   * @param conn The RabbitMQ connection from which a channel will be created.
   */
  public ConsumerRunnable(String exchangeName, Connection conn) {
    this.exchangeName = exchangeName;
    this.conn = conn;
  }

  /** The runnable task that will be executed in its own thread. */
  @Override
  public void run() {
    try {
      final Channel channel = conn.createChannel();
      final boolean autoAck = false;

      // Connect a channel to the queue. Configuration must be identical to channels created
      // on the server
      channel.exchangeDeclare(exchangeName, "fanout");
      channel.queueDeclare(QUEUE_NAME, false, false, false, null);
      channel.queueBind(QUEUE_NAME, exchangeName, "");

      System.out.println(
          " [*] Thread " + Thread.currentThread().getId() + " waiting for messages.");

      // The callback that will be run when a message is received. This is the "push" model of
      // message consumption, as the broker decides when to call this processing callback
      final DeliverCallback callback =
          (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            String key;
            JsonObject data = new JsonObject();

            try {
              JsonObject body = Consumer.gson.fromJson(message, JsonObject.class);
              key =
                  String.join(
                      ":",
                      new String[] {
                        body.get("seasonID").getAsString(), body.get("day").getAsString()
                      });

              // Extract parameters to be stored in map. No need to validate as validation has
              // already been performed on the server prior to publishing the message
              data.add("liftID", body.get("liftID"));
              data.add("time", body.get("time"));
              data.add("waitTime", body.get("waitTime"));
              data.add("skierID", body.get("skierID"));
              data.add("resortID", body.get("resortID"));
            } catch (JsonSyntaxException jse) {
              System.err.println("Message contained invalid JSON formatting");
              channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
              return;
            } catch (Exception e) {
              e.printStackTrace();
              channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
              return;
            }

            // Add the JSON data to the set for the season:day key
            try (Jedis jedis = Consumer.pool.getResource()) {
              jedis.sadd(key, Consumer.gson.toJson(data));
            }

            // Manual acknowledgments are used in this configuration because we want to ensure
            // every message is processed successfully before telling the broker it can safely
            // discard the message
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
          };

      channel.basicConsume(QUEUE_NAME, autoAck, callback, consumerTag -> {});
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
