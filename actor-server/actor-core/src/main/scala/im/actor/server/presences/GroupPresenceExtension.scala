package im.actor.server.presences

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._

trait GroupPresenceExtension extends Extension {
  def subscribe(groupId: Int, consumer: ActorRef): Future[Unit]

  def subscribe(groupIds: Set[Int], consumer: ActorRef): Future[Unit]

  def unsubscribe(groupId: Int, consumer: ActorRef): Future[Unit]

  def notifyGroupUserAdded(groupId: Int, userId: Int): Unit

  def notifyGroupUserRemoved(groupId: Int, userId: Int): Unit
}

class GroupPresenceExtensionImpl(system: ActorSystem) extends GroupPresenceExtension {
  import GroupPresenceManager._
  import system.dispatcher
  implicit val timeout: Timeout = Timeout(20.seconds)

  private val region = GroupPresenceManagerRegion.startRegion()(system)

  override def subscribe(groupId: Int, consumer: ActorRef): Future[Unit] = {
    region.ref.ask(Envelope(groupId, Subscribe(consumer))).mapTo[SubscribeAck].map(_ ⇒ ())
  }

  override def subscribe(groupIds: Set[Int], consumer: ActorRef): Future[Unit] =
    Future.sequence(groupIds map (subscribe(_, consumer))) map (_ ⇒ ())

  override def unsubscribe(groupId: Int, consumer: ActorRef): Future[Unit] = {
    region.ref.ask(Envelope(groupId, Unsubscribe(consumer))).mapTo[UnsubscribeAck].map(_ ⇒ ())
  }

  override def notifyGroupUserAdded(groupId: Int, userId: Int): Unit = {
    region.ref ! Envelope(groupId, UserAdded(userId))
  }

  override def notifyGroupUserRemoved(groupId: Int, userId: Int): Unit = {
    region.ref ! Envelope(groupId, UserRemoved(userId))
  }

}

object GroupPresenceExtension extends ExtensionId[GroupPresenceExtensionImpl] with ExtensionIdProvider {
  override def lookup = GroupPresenceExtension
  override def createExtension(system: ExtendedActorSystem) = new GroupPresenceExtensionImpl(system)
}
