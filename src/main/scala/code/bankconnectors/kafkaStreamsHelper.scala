package code.bankconnectors

import java.util.UUID

import akka.actor.{Actor, ActorRef}
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, KafkaConsumerActor, ProducerSettings, Subscriptions}
import akka.pattern.pipe
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import code.actorsystem.{ObpActorHelper, ObpActorInit}
import code.util.Helper.MdcLoggable
import net.liftweb.json
import net.liftweb.json.{DefaultFormats, Extraction, JsonAST}
import net.liftweb.util.Props
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}

import scala.concurrent.{ExecutionException, Future, TimeoutException}

/**
  * Actor for accessing kafka from North side.
  */
class KafkaStreamsHelperActor extends Actor with ObpActorInit with ObpActorHelper with MdcLoggable with KafkaConfig with AvroSerializer {

  implicit val formats = DefaultFormats

  implicit val materializer = ActorMaterializer()

  import materializer._

  private def makeKeyFuture = Future(scala.util.Random.nextInt(40) + "_" + UUID.randomUUID().toString)

  private val consumerSettings = ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
    .withBootstrapServers(bootstrapServers)
    .withGroupId(groupId)
    .withClientId(clientId)
    .withMaxWakeups(maxWakeups)
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetResetConfig)

  private val consumerActor: ActorRef = system.actorOf(KafkaConsumerActor.props(consumerSettings))

  private val consumer: ((String, Int) => Source[ConsumerRecord[String, String], Consumer.Control]) = { (topic, partition) =>
    val assignment = Subscriptions.assignmentWithOffset(new TopicPartition(topic, partition), 0)
    Consumer.plainSource(consumerSettings, assignment)
      .completionTimeout(completionTimeout)
  }

  private val producerSettings = ProducerSettings(system, new StringSerializer, new StringSerializer)
    .withBootstrapServers(bootstrapServers)
    .withProperty("batch.size", "0")
    .withParallelism(3)
  //.withProperty("auto.create.topics.enable", "true")

  private val producer = producerSettings
    .createKafkaProducer()

  private val flow: ((String, String) => Source[String, Consumer.Control]) = { (topic, key) =>
    consumer(topic, key.split("_")(0).toInt)
      .filter(msg => msg.key() == key)
      .map { msg =>
        logger.debug(s"$topic with $msg")
        msg.value
      }
  }

  private val sendRequest: ((Topic, String, String) => Future[String]) = { (topic, key, value) =>
    producer.send(new ProducerRecord[String, String](topic.request, key.split("_")(0).toInt, key, value))
    flow(topic.response, key)
      // .throttle(1, FiniteDuration(10, MILLISECONDS), 1, Shaping)
      .runWith(Sink.head)
  }

  private val parseF: (String => Future[JsonAST.JValue]) = { r =>
    Future(json.parse(r) \\ "data")
  }

  val extractF: (JsonAST.JValue => Future[Any]) = { r =>
    logger.info("kafka-response:" + r)
    Future(extractResult(r))
  }

  val decomposeF: (Map[String, String] => Future[json.JValue]) = { m =>
    Future(Extraction.decompose(m))
  }

  val serializeF: (json.JValue => Future[String]) = { m =>
    Future(json.compactRender(m))
  }

  //private val RESP: String = "{\"count\": \"\", \"data\": [], \"state\": \"\", \"pager\": \"\", \"target\": \"banks\"}"

  override def preStart(): Unit = {
    super.preStart()
    //    self ? Map()
  }

  def receive = {
    case request: GetBanks =>
      logger.info("kafka_request: " + request)
      val orgSender = sender
      val f = for {
        key <- makeKeyFuture
        s <- serializeFuture(request)
        r <- sendRequest(Topics.caseClassToTopic(GetBanks), key, s)
        d <- deserializeFuture[Banks](r)
      } yield {
        d.get.data
      }

      f recover {
        case e: InterruptedException => json.parse(s"""[{"error":"sending message to kafka interrupted"}, {"error","${e}"}]""")
        case e: ExecutionException => json.parse(s"""[{"error":"could not send message to kafka"}, {"error","${e}"}]""")
        case e: TimeoutException => json.parse(s"""[{"error":"receiving message from kafka timed out"}, {"error","${e}"}]""")
        case e: Throwable => json.parse(s"""[{"error":"unexpected error sending message to kafka"}, {"error","${e}"}]""")
      } pipeTo orgSender

    case request: Map[String, String] =>
      logger.info("kafka_request: " + request)
      val orgSender = sender
      val f = for {
        key <- makeKeyFuture
        d <- decomposeF(request)
        v <- serializeF(d)
        r <- sendRequest(Topics.connectorTopic, key, v)
        jv <- parseF(r)
        any <- extractF(jv)
      } yield {
        any
      }

      f recover {
        case e: InterruptedException => json.parse(s"""[{"error":"sending message to kafka interrupted"}, {"error","${e}"}]""")
        case e: ExecutionException => json.parse(s"""[{"error":"could not send message to kafka"}, {"error","${e}"}]""")
        case e: TimeoutException => json.parse(s"""[{"error":"receiving message from kafka timed out"}, {"error","${e}"}]""")
        case e: Throwable => json.parse(s"""[{"error":"unexpected error sending message to kafka"}, {"error","${e}"}]""")
      } pipeTo orgSender
  }
}

case class Topic(request: String, response: String)

object Topics {
  private val requestTopic = Props.get("kafka.request_topic").openOrThrowException("no kafka.request_topic set")
  private val responseTopic = Props.get("kafka.response_topic").openOrThrowException("no kafka.response_topic set")

  val connectorTopic = Topic(requestTopic, responseTopic)

  def caseClassToTopic(caseClass: Any): Topic =
    Topic("obp.Q." + caseClass.getClass.getSimpleName.replace("$", ""),
      "obp.R." + caseClass.getClass.getSimpleName.replace("$", ""))

}
