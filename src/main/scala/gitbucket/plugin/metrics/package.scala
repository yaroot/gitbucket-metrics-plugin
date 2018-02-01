package gitbucket.plugin.metrics

import java.lang.management.ManagementFactory
import java.nio.file.{ Files, Path, Paths }
import java.util
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong }
import javax.management.{ MBeanServer, ObjectName }

import gitbucket.core.model.Profile
import gitbucket.core.plugin.RepositoryHook
import gitbucket.core.service.{ AccountService, RepositoryService }
import gitbucket.core.util.Directory
import org.apache.commons.io.FileUtils

import scala.concurrent.duration._
import scala.util.Try

class RepositoryMetrics {
  import RepositoryMetrics._

  private val closed = new AtomicBoolean(false)
  def isClosed: Boolean = closed.get()

  // any IO related operation should be run on this threadpool
  // assuming single thread is okay for everyone
  val scheduledExecutor = new ScheduledThreadPoolExecutor(1)

  val mbeanServer: MBeanServer = defaultMBeanServer()
  @volatile var registeredMBeans = Map.empty[ObjectName, CleanUp]
  @volatile var repoBeans = Map.empty[(String, String), Vector[ObjectName]] // (user, repo) -> [mbean names]

  def run(runnable: Runnable): Unit = {
    if (!isClosed)
      scheduledExecutor.execute(runnable)
  }

  // we execute everything on the scheduler, so it's fine that `f` throws exception
  def runPeriodically(f: () => Unit): Scheduled = {
    val len = FIXED_DELAY.length
    val unit = FIXED_DELAY.unit

    val delay = scheduledExecutor.scheduleAtFixedRate(() => f(), len, len, unit)

    // return for cancellation
    () => delay.cancel(false)
  }

  def registerRepo(user: String, repo: String): Unit = {
    if (!isClosed) {
      val objectNames = Vector(
        "git" -> Directory.getRepositoryDir _,
        "wiki" -> Directory.getWikiRepositoryDir _,
        "misc" -> Directory.getRepositoryFilesDir _
      ).map {
          case (typ, pf) =>
            // create and register mbean, return object name for cancellation
            val name = repoName("RepositorySize", user, repo, typ)
            val path = pf(user, repo).toPath.toAbsolutePath
            val (repoSize, cancel) = createRepoSize(path)
            registerMBean(repoSize, name, () => cancel.cancel())
            name
        }

      repoBeans += ((user, repo) -> objectNames)
    }
  }

  def deregisterRepo(user: String, repo: String): Unit = {
    try {
      repoBeans.get(user -> repo).foreach(_.foreach {
        name => Try(unregisterMBean(name))
      })
    } finally {
      repoBeans -= (user -> repo)
    }
  }

  def registerMBean(obj: Object, name: ObjectName, cleanup: CleanUp): Unit = {
    if (mbeanServer.isRegistered(name)) {
      mbeanServer.unregisterMBean(name)
    }
    val instance = mbeanServer.registerMBean(obj, name)
    val name0 = Option(instance).map(_.getObjectName).getOrElse(name)
    registeredMBeans += (name0 -> cleanup)
  }

  def unregisterMBean(name: ObjectName): Unit = {
    try {
      registeredMBeans.get(name).foreach(_.cleanup())
    } finally {
      registeredMBeans -= name
      mbeanServer.unregisterMBean(name)
    }
  }

  def initialize(): Unit = {
    run(() => registerTotalSize())
    run(() => initializeUserRepos())
  }

  def initializeUserRepos(): Unit = {
    listAllUserRepos().foreach { case (user, repo) => run(() => registerRepo(user, repo)) }
  }

  def listAllUserRepos(): List[(String, String)] = {
    import gitbucket.core.model.Profile.profile.blockingApi._
    val userRepoService = new RepositoryService with AccountService
    gitbucket.core.servlet.Database() withSession { implicit session =>
      for {
        user <- userRepoService.getAllUsers()
        repo <- userRepoService.getRepositoryNamesOfUser(user.userName)
      } yield (user.userName, repo)
    }
  }

  def registerTotalSize(): Unit = {
    val (repoSize, cancel) = createRepoSize(Paths.get(Directory.RepositoryHome).toAbsolutePath)
    registerMBean(repoSize, totalName("TotalSize"), () => cancel.cancel())
  }

  def createRepoSize(path: Path): (RepoSize, Scheduled) = {
    val size = new AtomicLong()
    val refresh = () => {
      Try { if (Files.exists(path)) size.set(directorySize(path)) }
      ()
    }
    val mbean = new RepoSize(size.get(), refresh)

    val cancel = runPeriodically(refresh)

    // initialize the value before publishing
    refresh()

    (mbean, cancel)
  }

  def shutdown(): Unit = {
    closed.set(true)
    registeredMBeans.foreach {
      case (n, c) =>
        Try(mbeanServer.unregisterMBean(n))
        Try(c.cleanup())
    }
    scheduledExecutor.shutdownNow()
  }
}

object RepositoryMetrics {
  val DOMAIN = "io.github.gitbucket"
  val SIZE_DOMAIN = s"$DOMAIN.repository"
  val FIXED_DELAY: FiniteDuration = 1.hour

  def defaultMBeanServer(): MBeanServer = ManagementFactory.getPlatformMBeanServer

  def directorySize(path: Path): Long = FileUtils.sizeOfDirectory(path.toFile)

  def repoName(name: String, user: String, repo: String, storage: String): ObjectName = {
    val ht = new util.Hashtable[String, String]()
    ht.put("user", user)
    ht.put("repo", repo)
    ht.put("storage", storage)
    new ObjectName(name, ht)
  }

  def totalName(name: String): ObjectName = {
    new ObjectName(SIZE_DOMAIN, "name", name)
  }
}

trait CleanUp {
  def cleanup(): Unit
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

class MetricsHook(
    create: (String, String) => Unit,
    remove: (String, String) => Unit
) extends RepositoryHook {
  import Profile.profile.api.Session

  override def created(owner: String, repository: String)(implicit session: Session): Unit = {
    create(owner, repository)
  }

  override def deleted(owner: String, repository: String)(implicit session: Session): Unit = {
    remove(owner, repository)
  }

  override def renamed(owner: String, repository: String, newRepository: String)(implicit session: Session): Unit = {
    remove(owner, repository)
    create(owner, newRepository)
  }

  override def transferred(owner: String, newOwner: String, repository: String)(implicit session: Session): Unit = {
    remove(owner, repository)
    create(newOwner, repository)
  }

  override def forked(owner: String, newOwner: String, repository: String)(implicit session: Session): Unit = {
    create(newOwner, repository)
  }
}

object MetricsHook {
  def apply(svc: RepositoryMetrics): MetricsHook = {
    val create = (user: String, repo: String) => {
      svc.run(() => svc.registerRepo(user, repo))
    }
    val remove = (user: String, repo: String) => {
      svc.run(() => svc.deregisterRepo(user, repo))
    }
    new MetricsHook(create, remove)
  }
}
