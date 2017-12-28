package org.thp.cortex.services

//
import java.nio.file.{ Files, Path, Paths }
import javax.inject.{ Inject, Singleton }

import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

import play.api.{ Configuration, Logger }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import org.thp.cortex.models._

import org.elastic4play.NotFoundError
import org.elastic4play.services._

@Singleton
class AnalyzerSrv(
    analyzersPaths: Seq[Path],
    analyzerModel: AnalyzerModel,
    userSrv: UserSrv,
    createSrv: CreateSrv,
    getSrv: GetSrv,
    updateSrv: UpdateSrv,
    deleteSrv: DeleteSrv,
    findSrv: FindSrv,
    implicit val ec: ExecutionContext,
    implicit val mat: Materializer) {

  @Inject() def this(
      config: Configuration,
      analyzerModel: AnalyzerModel,
      userSrv: UserSrv,
      createSrv: CreateSrv,
      getSrv: GetSrv,
      updateSrv: UpdateSrv,
      deleteSrv: DeleteSrv,
      findSrv: FindSrv,
      ec: ExecutionContext,
      mat: Materializer) = this(
    config.get[Seq[String]]("analyzer.path").map(p ⇒ Paths.get(p)),
    analyzerModel,
    userSrv,
    createSrv,
    getSrv,
    updateSrv,
    deleteSrv,
    findSrv,
    ec,
    mat)

  private lazy val logger = Logger(getClass)
  private var analyzerMap = Map.empty[String, AnalyzerDefinition]
  private object analyzerMapLock
  scan(analyzersPaths)

  def getDefinition(analyzerId: String): Future[AnalyzerDefinition] = analyzerMap.get(analyzerId) match {
    case Some(analyzer) ⇒ Future.successful(analyzer)
    case None           ⇒ Future.failed(NotFoundError(s"Analyzer $analyzerId not found"))
  }

  def get(analyzerConfigId: String): Future[Analyzer] = getSrv[AnalyzerModel, Analyzer](analyzerModel, analyzerConfigId)

  def getForUser(userId: String, analyzerId: String): Future[Analyzer] = {
    userSrv.get(userId)
      .flatMap(user ⇒ getForOrganization(user.organization(), analyzerId))
  }

  def getForOrganization(organizationId: String, analyzerId: String): Future[Analyzer] = {
    import org.elastic4play.services.QueryDSL._
    find(
      and(withParent("organization", organizationId), "analyzerId" ~= analyzerId),
      Some("0-1"), Nil)._1
      .runWith(Sink.headOption)
      .map(_.getOrElse(throw NotFoundError(s"Configuration for analyzer $analyzerId not found for organization $organizationId")))
  }

  def listForOrganization(organizationId: String): (Source[Analyzer, NotUsed], Future[Long]) = {
    import org.elastic4play.services.QueryDSL._
    findForOrganization(organizationId, any, Some("all"), Nil)
  }

  def listForUser(userId: String): (Source[Analyzer, NotUsed], Future[Long]) = {
    import org.elastic4play.services.QueryDSL._
    findForUser(userId, any, Some("all"), Nil)
  }

  def findForUser(userId: String, queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[Analyzer, NotUsed], Future[Long]) = {
    val analyzerConfigs = for {
      user ← userSrv.get(userId)
      organizationId = user.organization()
      analyserConfigs = findForOrganization(organizationId, queryDef, range, sortBy)
    } yield analyserConfigs
    val analyserConfigSource = Source.fromFutureSource(analyzerConfigs.map(_._1)).mapMaterializedValue(_ ⇒ NotUsed)
    val analyserConfigTotal = analyzerConfigs.flatMap(_._2)
    analyserConfigSource -> analyserConfigTotal
  }

  def findForOrganization(organizationId: String, queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[Analyzer, NotUsed], Future[Long]) = {
    import org.elastic4play.services.QueryDSL._
    find(and(withParent("organization", organizationId), queryDef), range, sortBy)
  }

  def find(queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[Analyzer, NotUsed], Future[Long]) = {
    findSrv[AnalyzerModel, Analyzer](analyzerModel, queryDef, range, sortBy)
  }

  def scan(analyzerPaths: Seq[Path]): Unit = {
    val analyzers = (for {
      analyzerPath ← analyzerPaths
      analyzerDir ← Try(Files.newDirectoryStream(analyzerPath).asScala).getOrElse {
        logger.warn(s"Analyzer directory ($analyzerPath) is not found")
        Nil
      }
      if Files.isDirectory(analyzerDir)
      infoFile ← Files.newDirectoryStream(analyzerDir, "*.json").asScala
      analyzerDefinition ← AnalyzerDefinition.fromPath(infoFile).fold(
        error ⇒ {
          logger.warn("Analyzer definition file read error", error)
          Nil
        },
        ad ⇒ Seq(ad))
    } yield analyzerDefinition.id -> analyzerDefinition)
      .toMap

    analyzerMapLock.synchronized {
      analyzerMap = analyzers
    }
  }

  //  def listForType(dataType: String): Source[Analyzer with Entity, Future[Long]] = {
  //    import QueryDSL._
  //    find()
  //    //Seq[Analyzer] = list.filter(_.dataTypeList.contains(dataType))
  //  }
  //
  //  def analyze(analyzerId: String, artifact: Artifact): Future[Job] = {
  //    get(analyzerId)
  //      .map { analyzer ⇒ analyze(analyzer, artifact) }
  //      .getOrElse(throw AnalyzerNotFoundError(analyzerId))
  //  }
  //
  //  def analyze(analyzer: Analyzer, artifact: Artifact): Future[Job] = {
  //    val report = analyzer match {
  //      case ea: ExternalAnalyzer ⇒ externalAnalyzerSrv.analyze(ea, artifact)
  //      case mm: MispModule       ⇒ mispSrv.analyze(mm, artifact)
  //    }
  //    jobSrv.create(analyzer, artifact, report)
  //  }
}