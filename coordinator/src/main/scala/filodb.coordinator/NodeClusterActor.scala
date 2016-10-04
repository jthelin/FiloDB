package filodb.coordinator

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.event.LoggingReceive
import scala.collection.mutable.{HashMap, HashSet}
import scala.concurrent.duration._

import filodb.core.ErrorResponse

object NodeClusterActor {
  // Forwards message to one random recipient that has the given role.  Any replies go back to originator.
  case class ForwardToOne(role: String, msg: Any)
  case object NoSuchRole extends ErrorResponse

  // Gets all the ActorRefs for a specific role.  Returns a Set[ActorRef].
  case class GetRefs(role: String)

  // Forwards message to all recipients with given role.  Sending actor must handle separate replies.
  case class ForwardToAll(role: String, msg: Any)

  // Registers sending actor to receive PartitionMapUpdate whenever it changes.  DeathWatch will be used
  // on the sending actors to watch for updates.
  case object SubscribePartitionUpdates
  case class PartitionMapUpdate(map: PartitionMapper)

  // Internal message
  case class AddCoordActor(roles: Set[String], addr: Address, ref: ActorRef)
  case object EverybodyLeave  // Make every member leave, should be used only for testing

  /**
   * Creates a new NodeClusterActor.
   * @param cluster the Cluster to subscribe to for membership messages
   * @param nodeCoordRole String, for the role containing the NodeCoordinatorActor or ingestion nodes
   * @param resolveActorTimeout the timeout to use to resolve NodeCoordinatorActor refs for new nodes
   */
  def props(cluster: Cluster,
            nodeCoordRole: String,
            resolveActorTimeout: FiniteDuration = 10.seconds): Props =
    Props(classOf[NodeClusterActor], cluster, nodeCoordRole, resolveActorTimeout)

  class RemoteAddressExtensionImpl(system: ExtendedActorSystem) extends Extension {
    def address: Address = system.provider.getDefaultAddress
  }

  object RemoteAddressExtension extends ExtensionKey[RemoteAddressExtensionImpl]
}

/**
 * An actor that subscribes to membership events for a FiloDB cluster, updating a state of coordinators.
 * It also can send messages to one or all coordinators of a given role.
 *
 * There should only be ONE of these for a given cluster.  The partition map has to agree on all nodes,
 * and all who want updates should send a SubscribePartitionUpdates message to this single instance.
 * Otherwise we would have diverging maps, and that's a bad thing, yeah?
 *
 * Compared to the standard cluster aware routers, it has a couple advantages:
 * - It tracks all roles, not just one, and can send messages to a specific role per invocation
 * - It has much lower latency - direct sending to ActorRefs, no need to resolve ActorSelections
 *   (but of course it assumes the destination actor is always up... TODO watch for other actor dying)
 * - It broadcasts messages to all members of a role and sends back a collected response in one message,
 *   making it very easy to handle for non-actors.
 * - It can notify when some address joins
 * - It keeps track of mapping of partitions to different nodes for ingest record routing
 */
private[filodb] class NodeClusterActor(cluster: Cluster,
                                       nodeCoordRole: String,
                                       resolveActorTimeout: FiniteDuration) extends BaseActor {
  import NodeClusterActor._

  val roleToCoords = new HashMap[String, Set[ActorRef]]().withDefaultValue(Set.empty[ActorRef])
  var partMapper = PartitionMapper.empty
  val partMapSubscribers = new HashSet[ActorRef]

  import context.dispatcher

  // subscribe to cluster changes, re-subscribe when restart
  override def preStart(): Unit = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[UnreachableMember])
  }

  // TODO: leave cluster?
  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    cluster.leave(cluster.selfAddress)
  }

  private def withRole(role: String)(f: Set[ActorRef] => Unit): Unit = roleToCoords.get(role) match {
    case None       => sender ! NoSuchRole
    case Some(refs) => f(refs)
  }

  private def sendUpdatedPartitionMap(): Unit = {
    val update = PartitionMapUpdate(partMapper)
    partMapSubscribers.foreach(_ ! update)
  }

  val localRemoteAddr = RemoteAddressExtension(context.system).address
  var everybodyLeftSender: Option[ActorRef] = None

  def membershipHandler: Receive = LoggingReceive {
    case MemberUp(member) =>
      logger.info(s"Member is Up: ${member.address} with roles ${member.roles}")
      val memberCoordActor = RootActorPath(member.address) / "user" / "coordinator"
      val fut = context.actorSelection(memberCoordActor).resolveOne(resolveActorTimeout)
      fut.foreach { ref => self ! AddCoordActor(member.roles, member.address, ref) }
      fut.recover {
        case e: Exception =>
          logger.warn(s"Unable to resolve coordinator at $memberCoordActor, ignoring. " +
                      "Maybe NodeCoordinatorActor did not start up before node joined cluster.", e)
      }

    case UnreachableMember(member) =>
      logger.info(s"Member detected as unreachable: $member")

    case MemberRemoved(member, previousStatus) =>
      logger.info(s"Member is Removed: ${member.address} after $previousStatus")
      roleToCoords.keys.foreach { role =>
        roleToCoords(role) = roleToCoords(role).filterNot { ref =>
          // if we don't do this cannot properly match when self node is asked to leave
          val addr = if (ref.path.address.hasLocalScope) localRemoteAddr else ref.path.address
          addr == member.address
        }
      }
      roleToCoords.retain { case (role, refs) => refs.nonEmpty }
      val prevNumNodes = partMapper.numNodes
      partMapper = partMapper.removeNode(member.address)
      if (partMapper.numNodes < prevNumNodes) sendUpdatedPartitionMap()
      if (roleToCoords.isEmpty) {
        logger.info("All members removed!")
        everybodyLeftSender.foreach { ref => ref ! EverybodyLeave }
        everybodyLeftSender = None
      }

    case Terminated(ref) =>
      partMapSubscribers -= ref

    case SubscribePartitionUpdates =>
      logger.debug(s"Registered $sender to receive updates on partition maps")
      partMapSubscribers += sender
      context.watch(sender)

    case _: MemberEvent => // ignore
  }

  def routerEvents: Receive = LoggingReceive {
    case ForwardToOne(role, msg) =>
      withRole(role) { refs => refs.toSeq.apply(util.Random.nextInt(refs.size)).forward(msg) }

    case GetRefs(role) =>
      withRole(role) { refs => sender ! refs }

    case ForwardToAll(role, msg) =>
      withRole(role) { refs => refs.foreach(_.forward(msg)) }

    case AddCoordActor(roles, addr, coordRef) =>
      roles.foreach { role => roleToCoords(role) += coordRef }
      if (roles contains nodeCoordRole) {
        partMapper = partMapper.addNode(addr, coordRef)
        logger.info(s"Partition map updated: ${partMapper.numNodes} nodes")
        sendUpdatedPartitionMap()
      }
      logger.debug(s"Updated roleToCoords: $roleToCoords")

    case EverybodyLeave =>
      if (roleToCoords.isEmpty) { sender ! EverybodyLeave }
      else if (everybodyLeftSender.isEmpty) {
        logger.info(s"Removing all members from cluster...")
        cluster.state.members.map(_.address).foreach(cluster.leave)
        everybodyLeftSender = Some(sender)
      } else {
        logger.warn(s"Ignoring EverybodyLeave, somebody already sent it")
      }
  }

  def receive: Receive = membershipHandler orElse routerEvents
}