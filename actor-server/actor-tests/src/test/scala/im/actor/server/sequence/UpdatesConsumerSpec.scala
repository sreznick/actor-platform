package im.actor.server.sequence

import akka.pattern.ask
import akka.testkit._
import akka.actor.{ ActorRef, Actor, Props }
import scala.concurrent.Future
import com.google.protobuf.ByteString
import com.google.protobuf.wrappers.StringValue
import com.typesafe.config._
import im.actor.api.rpc.contacts.{ UpdateContactRegistered, UpdateContactsAdded }
import im.actor.server._
import im.actor.server.model.{ SerializedUpdate, UpdateMapping }
import im.actor.server.persist.sequence.UserSequenceRepo
import im.actor.server.sequence.UserSequenceCommands.{ DeliverUpdate, Envelope }
import org.scalatest.time.{ Seconds, Span }
import im.actor.server.presences.PresenceExtension

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class SubscriberActor extends Actor {
  def receive = {
    case NewUpdate(a, b) ⇒
  }
}

class MockPresenceExtension extends PresenceExtension {
  val subscribeSingleAttempts = new Counters
  val subscribeMultiAttempts = new Counters
  val unsubscribeAttempts = new Counters
  val onlineAttempts = new Counters
  val offlineAttempts = new Counters

  def subscribe(userId: Int, consumer: ActorRef): Future[Unit] = {
    subscribeSingleAttempts.incr(userId)
    Future.successful({})
  }
  def subscribe(userIds: Set[Int], consumer: ActorRef): Future[Unit] = {
    for (userId ← userIds) {
      subscribeMultiAttempts.incr(userId)
    }
    Future.successful({})
  }
  def unsubscribe(userId: Int, consumer: ActorRef): Future[Unit] = {
    unsubscribeAttempts.incr(userId)
    Future.successful({})
  }

  def presenceSetOnline(userId: Int, authId: Long, timeout: Long): Unit = {
    onlineAttempts.incr(userId)
  }

  def presenceSetOffline(userId: Int, authId: Long, timeout: Long): Unit = {
    offlineAttempts.incr(userId)
  }
}

class Counters {
  import java.util.concurrent.atomic._
  val values = new java.util.concurrent.ConcurrentHashMap[Int, AtomicInteger]

  def incr(v: Int) = {
    values.putIfAbsent(v, new AtomicInteger)
    values.get(v).getAndIncrement
  }

  def get(v: Int) = Option(values.get(v)).map(_.get).getOrElse(0)

  override def toString() = {
    values.toString
  }
}

trait FiniteFails {
  val numberOfFails: (Int) ⇒ Int

  def finiteThrow(counters: Counters, key: Int) = {
    if (counters.get(key) < numberOfFails(key)) {
      counters.incr(key)
      throw new RuntimeException()
    }
  }
}

class FiniteSubscribeFailPE(override val numberOfFails: (Int) ⇒ Int)
  extends MockPresenceExtension with FiniteFails {
  val subscribeFails = new Counters

  override def subscribe(userId: Int, consumer: ActorRef): Future[Unit] = {
    super.subscribe(userId, consumer)
    Future {
      finiteThrow(subscribeFails, userId)
    }
  }
}

class FiniteUnsubscribeFailPE(override val numberOfFails: (Int) ⇒ Int)
  extends MockPresenceExtension with FiniteFails {
  val unsubscribeFails = new Counters

  override def unsubscribe(userId: Int, consumer: ActorRef): Future[Unit] = {
    super.unsubscribe(userId, consumer)
    Future {
      finiteThrow(unsubscribeFails, userId)
    }
  }
}

trait Fails {
  def fail(counters: Counters, key: Int) = {
    counters.incr(key)
    throw new RuntimeException()
  }
}

class SubscribeFailPE extends MockPresenceExtension with Fails {
  val subscribeFails = new Counters

  override def subscribe(userId: Int, consumer: ActorRef): Future[Unit] = {
    super.subscribe(userId, consumer)
    Future {
      fail(subscribeFails, userId)
    }
  }
}

class UnsubscribeFailPE extends MockPresenceExtension with Fails {
  val unsubscribeFails = new Counters

  override def unsubscribe(userId: Int, consumer: ActorRef): Future[Unit] = {
    super.unsubscribe(userId, consumer)
    Future {
      fail(unsubscribeFails, userId)
    }
  }
}

final class UpdatesConsumerSpec extends BaseAppSuite(
  ActorSpecification.createSystem(
    ConfigFactory.parseString(""" push.seq-updates-manager.receive-timeout = 1 second """)
  )
) with ServiceSpecHelpers with ImplicitAuthService with ImplicitSessionRegion {
  behavior of "UpdatesConsumer"

  val subscribeActor = system.actorOf(Props[SubscriberActor], "subscriber-actor-test")

  it should "pass with positive PrescenceExtension" in positive
  it should "retry only failed ids for subscribe" in subscribeFiniteFails
  it should "retry only failed ids for unsubscribe" in unsubscribeFiniteFails
  it should "retries for subscribe use timeout" in subscribeFails
  it should "retries for unsubscribe use timeout" in unsubscribeFails

  import UpdatesConsumerMessage._

  def createUCActor(pe: PresenceExtension, suffix: String) = {
    val ucProps = UpdatesConsumer.props(111, 2345, subscribeActor, Some(pe))
    system.actorOf(ucProps, s"updates-consumer-$suffix")
  }

  val UserIdsRange = Range(1, 8)

  def positive() = {
    val mockPE = new MockPresenceExtension
    val updatesConsumerPositive = createUCActor(mockPE, "positive")

    for (v ← UserIdsRange) {
      mockPE.subscribeSingleAttempts.get(v) shouldEqual 0
      mockPE.unsubscribeAttempts.get(v) shouldEqual 0
    }

    updatesConsumerPositive ! SubscribeToUserPresences(UserIdsRange.toSet)

    Thread.sleep(1000)

    for (v ← UserIdsRange) {
      mockPE.subscribeSingleAttempts.get(v) shouldEqual 1
      mockPE.unsubscribeAttempts.get(v) shouldEqual 0
    }

    updatesConsumerPositive ! UnsubscribeFromUserPresences(UserIdsRange.toSet)

    Thread.sleep(1000)

    for (v ← UserIdsRange) {
      mockPE.subscribeSingleAttempts.get(v) shouldEqual 1
      mockPE.unsubscribeAttempts.get(v) shouldEqual 1
    }

    Thread.sleep(1000)
  }

  def oddOrZero(v: Int): Int = {
    if (v % 2 == 0) {
      0
    } else {
      v
    }
  }

  def subscribeFiniteFails() = {
    val finiteFailsPE = new FiniteSubscribeFailPE(oddOrZero)
    val finiteActor = createUCActor(finiteFailsPE, "subscribe-finite")

    for (v ← UserIdsRange) {
      finiteFailsPE.subscribeSingleAttempts.get(v) shouldEqual 0
      finiteFailsPE.subscribeFails.get(v) shouldEqual 0
    }

    finiteActor ! SubscribeToUserPresences(UserIdsRange.toSet)

    Thread.sleep(10000)

    for (v ← UserIdsRange) {
      finiteFailsPE.subscribeSingleAttempts.get(v) shouldEqual (oddOrZero(v) + 1)
      finiteFailsPE.subscribeFails.get(v) shouldEqual oddOrZero(v)
    }
  }

  def unsubscribeFiniteFails() = {
    val finiteFailsPE = new FiniteUnsubscribeFailPE(oddOrZero)
    val finiteActor = createUCActor(finiteFailsPE, "unsubscribe-finite")

    for (v ← UserIdsRange) {
      finiteFailsPE.unsubscribeAttempts.get(v) shouldEqual 0
      finiteFailsPE.unsubscribeFails.get(v) shouldEqual 0
    }

    finiteActor ! UnsubscribeFromUserPresences(UserIdsRange.toSet)

    Thread.sleep(10000)

    for (v ← UserIdsRange) {
      finiteFailsPE.unsubscribeAttempts.get(v) shouldEqual (oddOrZero(v) + 1)
      finiteFailsPE.unsubscribeFails.get(v) shouldEqual oddOrZero(v)
    }

    system.stop(finiteActor)
  }

  def subscribeFails() = {
    val failsPE = new SubscribeFailPE
    val failsActor = createUCActor(failsPE, "subscribe-fails")

    for (v ← UserIdsRange) {
      failsPE.subscribeSingleAttempts.get(v) shouldEqual 0
      failsPE.subscribeFails.get(v) shouldEqual 0
    }

    failsActor ! SubscribeToUserPresences(UserIdsRange.toSet)

    Thread.sleep(5000)

    system.stop(failsActor)

    for (v ← UserIdsRange) {
      failsPE.subscribeSingleAttempts.get(v) should be < 10
      failsPE.subscribeSingleAttempts.get(v) should be > 3
      failsPE.subscribeFails.get(v) should be < 10
      failsPE.subscribeFails.get(v) should be > 3
    }
  }

  def unsubscribeFails() = {
    val failsPE = new UnsubscribeFailPE
    val failsActor = createUCActor(failsPE, "unsubscribe-fails")

    for (v ← UserIdsRange) {
      failsPE.unsubscribeAttempts.get(v) shouldEqual 0
      failsPE.unsubscribeFails.get(v) shouldEqual 0
    }

    failsActor ! UnsubscribeFromUserPresences(UserIdsRange.toSet)

    Thread.sleep(5000)

    system.stop(failsActor)

    for (v ← UserIdsRange) {
      failsPE.unsubscribeAttempts.get(v) should be < 10
      failsPE.unsubscribeAttempts.get(v) should be > 3
      failsPE.unsubscribeFails.get(v) should be < 10
      failsPE.unsubscribeFails.get(v) should be > 3
    }
  }

}
