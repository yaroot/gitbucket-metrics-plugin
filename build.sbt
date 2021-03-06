organization  := "gitbucket"
name          := "gitbucket-metrics-plugin"
scalaVersion  := "2.12.4"
scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-feature")

resolvers ++= Seq(
  Resolver.jcenterRepo,
  Resolver.mavenLocal
)

libraryDependencies ++= Seq(
  "io.github.gitbucket"   %%  "gitbucket"           % "4.21.0",
  "javax.servlet"         %   "javax.servlet-api"   % "3.1.0"

)

