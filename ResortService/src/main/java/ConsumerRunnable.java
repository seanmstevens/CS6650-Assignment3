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

  private final String queueName;
  private final Connection conn;

  /**
   * Constructor for ConsumerRunnable class.
   *
   * @param queueName The name of the queue from which messages will be retrieved.
   * @param conn The RabbitMQ connection from which a channel will be created.
   */
  public ConsumerRunnable(String queueName, Connection conn) {
    this.queueName = queueName;
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
      channel.queueDeclare(queueName, false, false, false, null);
      System.out.println(
          " [*] Thread " + Thread.currentThread().getId() + " waiting for messages.");

      // The callback that will be run when a message is received. This is the "push" model of
      // message consumption, as the broker decides when to call this processing callback
      final DeliverCallback callback =
          (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            JsonObject body;

            try {
              body = Consumer.gson.fromJson(message, JsonObject.class);
            } catch (JsonSyntaxException jse) {
              System.err.println("Message contained invalid JSON formatting");
              channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
              return;
            } catch (Exception e) {
              e.printStackTrace();
              channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
              return;
            }

            // Extract parameters to be stored in map. No need to validate as validation has
            // already been performed on the server prior to publishing the message

            // If collection of lift ID values exists for this skier, append. Otherwise, create a
            // new queue and put it to the map. Add the lift ID in either case
            try (Jedis jedis = Consumer.pool.getResource()) {
              jedis.incrBy(
                  String.join(
                      ":",
                      new String[] {
                        "skierDaysTotalPerSeason",
                        body.get("skierID").getAsString(),
                        body.get("seasonID").getAsString()
                      }),
                  1);

              jedis.incrBy(
                  String.join(
                      ":",
                      new String[] {
                        "skierVerticalTotalPerDay",
                        body.get("skierID").getAsString(),
                        body.get("day").getAsString()
                      }),
                  Long.parseLong(body.get("liftID").getAsString()) * 10);

              jedis.sadd(
                  String.join(
                      ":",
                      new String[] {
                        "skierLiftSetPerDay",
                        body.get("skierID").getAsString(),
                        body.get("day").getAsString()
                      }),
                  body.get("liftID").getAsString());
            }

            // Manual acknowledgments are used in this configuration because we want to ensure
            // every message is processed successfully before telling the broker it can safely
            // discard the message
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
          };

      channel.basicConsume(queueName, autoAck, callback, consumerTag -> {});
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
