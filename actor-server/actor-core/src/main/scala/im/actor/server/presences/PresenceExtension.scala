package im.actor.server.presences

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._

trait PresenceExtension extends Extension {
  def subscribe(userId: Int, consumer: ActorRef): Future[Unit]
  def subscribe(userIds: Set[Int], consumer: ActorRef): Future[Unit]
  def unsubscribe(userId: Int, consumer: ActorRef): Future[Unit]
  def presenceSetOnline(userId: Int, authId: Long, timeout: Long): Unit
  def presenceSetOffline(userId: Int, authId: Long, timeout: Long): Unit
}

class PresenceExtensionImpl(system: ActorSystem) extends PresenceExtension {
  import PresenceManager._
  import Presences._
  import system.dispatcher
  implicit val timeout: Timeout = Timeout(20.seconds)

  private val region = PresenceManagerRegion.startRegion()(system)

  override def subscribe(userId: Int, consumer: ActorRef): Future[Unit] = {
    region.ref.ask(Envelope(userId, Subscribe(consumer))).mapTo[SubscribeAck].map(_ ⇒ ())
  }

  override def subscribe(userIds: Set[Int], consumer: ActorRef): Future[Unit] =
    Future.sequence(userIds map (subscribe(_, consumer))) map (_ ⇒ ())

  override def unsubscribe(userId: Int, consumer: ActorRef): Future[Unit] = {
    region.ref.ask(Envelope(userId, Unsubscribe(consumer))).mapTo[UnsubscribeAck].map(_ ⇒ ())
  }

  override def presenceSetOnline(userId: Int, authId: Long, timeout: Long): Unit = {
    region.ref ! Envelope(userId, UserPresenceChange(Online, authId, timeout))
  }

  override def presenceSetOffline(userId: Int, authId: Long, timeout: Long): Unit = {
    region.ref ! Envelope(userId, UserPresenceChange(Offline, authId, timeout))
  }
}

object PresenceExtension extends ExtensionId[PresenceExtensionImpl] with ExtensionIdProvider {
  override def lookup = PresenceExtension
  override def createExtension(system: ExtendedActorSystem) = new PresenceExtensionImpl(system)
}
