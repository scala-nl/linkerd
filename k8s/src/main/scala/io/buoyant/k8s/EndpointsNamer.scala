package io.buoyant.k8s

import com.twitter.conversions.time._
import com.twitter.finagle.{Service => _, _}
import com.twitter.finagle.service.Backoff
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util._
import scala.collection.mutable

class MultiNsNamer(
  idPrefix: Path,
  labelName: Option[String],
  mkApi: String => v1.NsApi,
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds)
)(implicit timer: Timer = DefaultTimer) extends EndpointsNamer(idPrefix, mkApi, backoff)(timer) {

  val PrefixLen = 3
  private[this] val variablePrefixLength = PrefixLen + labelName.size

  /**
   * Accepts names in the form:
   *   /<namespace>/<port-name>/<svc-name>[/<label-value>]/residual/path
   *
   * and attempts to bind an Addr by resolving named endpoint from the
   * kubernetes master.
   */
  def lookup(path: Path): Activity[NameTree[Name]] = {
    val lowercasePath = path.take(variablePrefixLength) match {
      case Path.Utf8(segments@_*) => Path.Utf8(segments.map(_.toLowerCase): _*)
    }
    (lowercasePath, labelName) match {
      case (id@Path.Utf8(nsName, portName, serviceName), None) =>
        val residual = path.drop(variablePrefixLength)
        log.debug("k8s lookup: %s %s", id.show, path.show)
        lookupServices(nsName, portName, serviceName, id, residual)

      case (id@Path.Utf8(nsName, portName, serviceName, labelValue), Some(label)) =>
        val residual = path.drop(variablePrefixLength)
        log.debug("k8s lookup: %s %s %s", id.show, label, path.show)
        val labelSelector = Some(s"$label=$labelValue")
        lookupServices(nsName, portName, serviceName, id, residual)

      case (id@Path.Utf8(nsName, portName, serviceName), Some(label)) =>
        log.debug("k8s lookup: ns %s service %s label value segment missing for label %s", nsName, serviceName, portName, label)
        Activity.value(NameTree.Neg)

      case _ =>
        Activity.value(NameTree.Neg)
    }
  }
}

class SingleNsNamer(
  idPrefix: Path,
  labelName: Option[String],
  nsName: String,
  mkApi: String => v1.NsApi,
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds)
)(implicit timer: Timer = DefaultTimer) extends EndpointsNamer(idPrefix, mkApi, backoff)(timer) {

  val PrefixLen = 2
  private[this] val variablePrefixLength = PrefixLen + labelName.size

  /**
   * Accepts names in the form:
   *   /<port-name>/<svc-name>[/<label-value>]/residual/path
   *
   * and attempts to bind an Addr by resolving named endpoint from the
   * kubernetes master.
   */
  def lookup(path: Path): Activity[NameTree[Name]] = {
    val lowercasePath = path.take(variablePrefixLength) match {
      case Path.Utf8(segments@_*) => Path.Utf8(segments.map(_.toLowerCase): _*)
    }
    (lowercasePath, labelName) match {
      case (id@Path.Utf8(portName, serviceName), None) =>
        val residual = path.drop(variablePrefixLength)
        log.debug("k8s lookup: %s %s", id.show, path.show)
        lookupServices(nsName, portName, serviceName, id, residual)

      case (id@Path.Utf8(portName, serviceName, labelValue), Some(label)) =>
        val residual = path.drop(variablePrefixLength)
        log.debug("k8s lookup: %s %s %s", id.show, label, path.show)
        val labelSelector = Some(s"$label=$labelValue")
        lookupServices(nsName, portName, serviceName, id, residual)

      case (id@Path.Utf8(portName, serviceName), Some(label)) =>
        log.debug("k8s lookup: ns %s service %s label value segment missing for label %s", nsName, serviceName, portName, label)
        Activity.value(NameTree.Neg)

      case _ =>
        Activity.value(NameTree.Neg)
    }
  }
}

abstract class EndpointsNamer(
  idPrefix: Path,
  mkApi: String => v1.NsApi,
  backoff: Stream[Duration] = Backoff.exponentialJittered(10.milliseconds, 10.seconds)
)(implicit timer: Timer = DefaultTimer) extends Namer {

  val cache = new EndpointsCache

  private[k8s] def lookupServices(
    nsName: String,
    portName: String,
    serviceName: String,
    id: Path,
    residual: Path
  ): Activity[NameTree[Name]] = {
    // wish i didn't have to call initialize then get here, just to get the addrs
    // from the Endpoints object. who wrote this api anyway? /me raises hand. whoops.
    val toNameTree: v1.Endpoints => NameTree[Name] = endpoints => {
      cache.initialize(v1.EndpointsList(items = Seq(endpoints)))
      cache.get(nsName, portName, serviceName) match {
        case Some(addr) => NameTree.Leaf(Name.Bound(addr, id, residual))
        case None => NameTree.Neg
      }
    }
    mkApi(nsName)
      .endpoints(serviceName)
      .activity(toNameTree) { (nameTree, event) =>
        // and similarly update then get is not great programming either
        cache.update(event)
        cache.get(nsName, portName, serviceName) match {
          case Some(addr) => NameTree.Leaf(Name.Bound(addr, id, residual))
          case None => NameTree.Neg
        }
      }
  }
}

// maybe this shouldn't extend ObjectCache? that trait assumes that there's a list endpoint
// for initialization, but we're never calling the list endpoint for... endpoints.
class EndpointsCache extends Ns.ObjectCache[v1.Endpoints, v1.EndpointsWatch, v1.EndpointsList] {

  private[this] case class CacheKey(nsName: String, portName: String, serviceName: String)

  private[this]type VarUp[T] = Var[T] with Updatable[T]

  private[this] var cache = Map.empty[CacheKey, VarUp[Addr]]

  def initialize(list: v1.EndpointsList): Unit =
    for { endpoints <- list.items } synchronized {
      add(endpoints)
    }

  def update(watch: v1.EndpointsWatch): Unit = watch match {
    case v1.EndpointsError(e) => log.error("k8s watch error: %s", e)
    case v1.EndpointsAdded(endpoints) => add(endpoints)
    case v1.EndpointsModified(endpoints) => modify(endpoints)
    case v1.EndpointsDeleted(endpoints) => delete(endpoints)
  }

  def get(nsName: String, portName: String, serviceName: String): Option[Var[Addr]] =
    cache.get(CacheKey(nsName, portName, serviceName))

  private[this] def toMap(endpoints: v1.Endpoints): Map[CacheKey, Addr] = {
    val endpointMap = mutable.Map.empty[CacheKey, Addr]
    for {
      metadata <- endpoints.metadata
      namespace <- metadata.namespace
      name <- metadata.name
    } {
      getAddresses(endpoints.subsets).foreach {
        case (portName, addresses) =>
          val key = CacheKey(namespace, portName, name)
          endpointMap(key) = Addr.Bound(addresses: _*)
      }
    }
    endpointMap.toMap
  }

  // omg i hate this method so much
  private[this] def getAddresses(subsets: Option[Seq[v1.EndpointSubset]]): Map[String, Seq[Address]] =
    (for {
      subset <- subsets.getOrElse(Nil)
      address <- subset.addresses.getOrElse(Nil)
      port <- subset.ports.getOrElse(Nil) if port.name.isDefined
    } yield {
      (port.name.get, Address(address.ip, port.port))
    }).groupBy(_._1).mapValues(_.map(_._2))

  private[this] def add(endpoints: v1.Endpoints): Unit =
    for { (key, addr) <- toMap(endpoints) } synchronized {
      log.debug("k8s ns %s added service: %s; port: %s", key.nsName, key.serviceName, key.portName)
      cache += key -> Var(addr)
    }

  private[this] def modify(endpoints: v1.Endpoints): Unit =
    for { (key, modified) <- toMap(endpoints) } synchronized {
      cache.get(key) match {
        case Some(addr) =>
          log.info("k8s ns %s modified service: %s; port %s", key.nsName, key.serviceName, key.portName)
          addr.update(modified)
        case None =>
          log.warning(
            "k8s ns %s received modified watch for unknown service %s; port %s",
            key.nsName, key.serviceName, key.portName
          )
      }
    }

  private[this] def delete(endpoints: v1.Endpoints): Unit =
    for { (key, _) <- toMap(endpoints) } synchronized {
      cache.get(key) match {
        case Some(addr) => addr.update(Addr.Neg)
        case None =>
          log.warning(
            "k8s ns %s received delete watch for unknown service %s; port %s",
            key.nsName, key.serviceName, key.portName
          )
      }
    }
}
