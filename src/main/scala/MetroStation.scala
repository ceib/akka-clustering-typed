import akka.NotUsed
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import com.typesafe.config.ConfigFactory
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.EntityContext
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}

import java.util.{Date, UUID}
import scala.util.Random




object Key {
  val TypeKey: EntityTypeKey[ValidatorTyped.Command] = EntityTypeKey[ValidatorTyped.Command]("TroykaCovidPassValidator")
}

object TurnstileTyped {
  sealed trait Command
  case class TroykaCard(id: String, isAllowed: Boolean) extends Command
  case object EntryAccepted extends Command
  case class EntryRejected(reason: String) extends Command

  def apply(sharding: ClusterSharding, entityId: String): Behavior[Command] = Behaviors.setup { ctx: ActorContext[Command] =>
    Behaviors.receiveMessage {
      case o: TroykaCard =>
        ctx.log.info(s"!!!!!!!!!! Received message $o")
//        println(s"!!!!!!!!!! Received message $o")
        val entityRef = sharding.entityRefFor(Key.TypeKey, entityId)
        entityRef ! ValidatorTyped.EntryAttempt(o, new Date, ctx.self)
        Behaviors.same
      case  EntryAccepted =>
        ctx.log.info("GREEN: пропуск оформлен, проходите пожалуйста")
        Behaviors.same

      case EntryRejected(reason) =>
        ctx.log.info(s"RED: $reason")
        Behaviors.same
    }
  }
}


object ValidatorTyped {
  trait Command
  case class EntryAttempt(troykaCard: TurnstileTyped.TroykaCard, date: Date, sender: ActorRef[TurnstileTyped.Command]) extends Command


  def apply(id: String): Behavior[Command] = Behaviors.setup { ctx: ActorContext[Command] =>
    Behaviors.receiveMessage {
      case EntryAttempt(card @ TurnstileTyped.TroykaCard(id, isAllowed), _, sender) =>
        ctx.log.info(s"!!!!!!!!!! Received message in ValidatorTyped")
        if(isAllowed) sender !  TurnstileTyped.EntryAccepted
        else sender ! TurnstileTyped.EntryRejected(s"Не оформлен пропуск на mos.ru или карта не привязана")
        Behaviors.same
    }
  }
}


class MetroStationTyped(port: Int, amountOfTurnstiles: Int) extends App {
  val config = ConfigFactory
    .parseString(
      s"""
         |akka.remote.artery.canonical.port = $port
     """.stripMargin)
    .withFallback(ConfigFactory.load("clusterConfig.conf"))


  val system: ActorSystem[NotUsed] = ActorSystem[NotUsed](apply(), "ClusterSystem", config)


  def apply(): Behavior[NotUsed] = Behaviors.setup { ctx: ActorContext[NotUsed] =>
    val sharding: ClusterSharding = ClusterSharding(ctx.system)

    val shardValidatorRegion: ActorRef[ShardingEnvelope[ValidatorTyped.Command]] =
      sharding.init(Entity(Key.TypeKey) { entityContext =>
        ValidatorTyped(entityContext.entityId)
      })

    val turnstiles: Seq[ActorRef[TurnstileTyped.Command]] = (1 to amountOfTurnstiles)
      .map { x =>
        println(s"Before Starting actor of turnstiles #${x}")
        ctx.spawn(TurnstileTyped(sharding, port.toString), s"Tunstyle$x")
      }

    Thread.sleep(10000)
    for (_ <- 1 to 1000) {
      val randomTurnstileIndex = Random.nextInt(amountOfTurnstiles)
      val randomTurnstile      = turnstiles(randomTurnstileIndex)

      randomTurnstile ! TurnstileTyped.TroykaCard(UUID.randomUUID().toString, Random.nextBoolean())
      Thread.sleep(200)
    }

    Behaviors.same
  }
}

object ChistyePrudy extends MetroStationTyped(2551, 10)
object Lubyanka     extends MetroStationTyped(2552, 5)
object OkhotnyRyad  extends MetroStationTyped(2553, 15)


