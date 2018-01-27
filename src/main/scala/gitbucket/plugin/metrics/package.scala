package gitbucket.plugin.metrics

import java.lang.management.ManagementFactory
import java.nio.file.{ Path, Paths }
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicLong
import javax.management.{ MBeanServer, ObjectName }

import gitbucket.core.util.Directory
import org.apache.commons.io.FileUtils

import scala.concurrent.duration._

// the lifecycle is rather simple:
//   - upon start, we create a `ScheduledThreadPoolExecutor` for running periodic/io-related tasks
//   - during shutdown, we close the executor
class RepositoryMetrics {
  import RepositoryMetrics._

  // we run any IO related task on this executor
  val scheduledExecutor = new ScheduledThreadPoolExecutor(1)

  @volatile var mbeans = Map.empty[ObjectName, () => Unit]

  // we execute everything on the scheduler, so it's fine that `f` throws exception
  def schedule(f: () => Unit): Scheduled = {
    val len = FIXED_DELAY.length
    val unit = FIXED_DELAY.unit

    scheduledExecutor.execute(() => f())
    val delay = scheduledExecutor.scheduleAtFixedRate(() => f(), len, len, unit)

    // return for cancellation
    () => delay.cancel(false)
  }

  // TODO support cleanup
  def registerMBean(obj: Object, name: ObjectName): Unit = {
    // gitbucket may reload plugins, and it seems the shutdown will not be called before reload
    // so we have to check here, moreover there will be resource leaks
    if (mbeanServer().isRegistered(name)) {
      mbeanServer().unregisterMBean(name)
    }
    val instance = mbeanServer().registerMBean(obj, name)
    val name0 = Option(instance).map(_.getObjectName).getOrElse(name)
    mbeans += (name0, () => Unit)
  }

  def unregisterMBean(name: ObjectName): Unit = {
    try {
      mbeans.get(name).foreach(f => f())
    } finally {
      mbeans -= name
    }
  }

  def initialize(): Unit = {
    registerTotal()
  }

  def registerTotal(): Unit = {
    val atomicLong = new AtomicLong()
    val refresh = () => atomicLong.set(directorySize(baseDir))
    val mbean = new RepoSize(atomicLong.get(), refresh)

    schedule(refresh)

    registerMBean(mbean, objectName(SIZE_DOMAIN, "total"))
  }

  def shutdown(): Unit = {
    try {
      mbeans.keys.toVector.foreach(unregisterMBean)
    } finally {
      scheduledExecutor.shutdownNow()
    }
  }
}

object RepositoryMetrics {
  val DOMAIN = "gitbucket"
  val SIZE_DOMAIN = s"$DOMAIN.directory.repository"
  val FIXED_DELAY: FiniteDuration = 1.hour

  def baseDir: Path = Paths.get(Directory.RepositoryHome).toAbsolutePath

  def mbeanServer(): MBeanServer = ManagementFactory.getPlatformMBeanServer

  def directorySize(path: Path): Long = FileUtils.sizeOfDirectory(path.toFile)

  def objectName(domain: String, name: String): ObjectName = {
    val obj = new ObjectName(domain, "name", name)
    if (obj.isPattern) new ObjectName(domain, "name", ObjectName.quote(name))
    else obj
  }
}

trait Scheduled {
  def cancel(): Unit
}

trait RepoSizeMBean {
  def getSize: Long
  def refresh(): Unit
}

class RepoSize(size: => Long, rf: () => Unit) extends RepoSizeMBean {
  override def getSize: Long = size
  override def refresh(): Unit = rf()
}
