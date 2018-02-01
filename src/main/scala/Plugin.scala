
import javax.servlet.ServletContext

import gitbucket.core.controller.Context
import gitbucket.core.plugin.{Link, PluginRegistry, RepositoryHook}
import gitbucket.core.service.RepositoryService.RepositoryInfo
import gitbucket.core.service.SystemSettingsService
import gitbucket.plugin.metrics.{MetricsHook, RepositoryMetrics}
import io.github.gitbucket.solidbase.migration.{LiquibaseMigration, SqlMigration}
import io.github.gitbucket.solidbase.model.Version

class Plugin extends gitbucket.core.plugin.Plugin {
  override val pluginId = "metrics"
  override val pluginName = "Metrics plugin"
  override val description = "Metrics for GitBucket"
  override val versions = List(
    new Version("0.1.0"),
  )

  val repositoryMetrics = new RepositoryMetrics
  repositoryMetrics.initialize()

  override def shutdown(registry: PluginRegistry, context: ServletContext, settings: SystemSettingsService.SystemSettings): Unit = {
    repositoryMetrics.shutdown()
  }

  override val repositoryHooks: Seq[RepositoryHook] = Seq(MetricsHook(repositoryMetrics))
}

