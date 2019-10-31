package controllers

import ch.qos.logback.classic.{Level, LoggerContext}
import controllers.actions.SecuredAuthContext
import domains.user.User
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsArray, Json}
import play.api.mvc.{AbstractController, ActionBuilder, AnyContent, ControllerComponents}

class BackOfficeController(AuthAction: ActionBuilder[SecuredAuthContext, AnyContent], cc: ControllerComponents)
    extends AbstractController(cc) {

  def changeLogLevel(name: String, newLevel: Option[String]) = AuthAction { ctx =>
    if (isAdmin(ctx)) {
      val loggerContext =
        LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
      val _logger = loggerContext.getLogger(name)
      val oldLevel =
        Option(_logger.getLevel).map(_.levelStr).getOrElse(Level.OFF.levelStr)
      _logger.setLevel(newLevel.map(v => Level.valueOf(v)).getOrElse(Level.ERROR))
      Ok(Json.obj("name" -> name, "oldLevel" -> oldLevel, "newLevel" -> _logger.getLevel.levelStr))
    } else {
      Unauthorized
    }
  }

  def getLogLevel(name: String) = AuthAction { ctx =>
    if (isAdmin(ctx)) {
      val loggerContext =
        LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
      val _logger = loggerContext.getLogger(name)
      Ok(Json.obj("name" -> name, "level" -> _logger.getLevel.levelStr))
    } else {
      Unauthorized
    }
  }

  def getAllLoggers() = AuthAction { ctx =>
    if (isAdmin(ctx)) {
      import collection.JavaConverters._
      val loggerContext =
        LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
      val rawLoggers = loggerContext.getLoggerList.asScala.toIndexedSeq
      val loggers = JsArray(rawLoggers.map(logger => {
        val level: String =
          Option(logger.getLevel).map(_.levelStr).getOrElse("OFF")
        Json.obj("name" -> logger.getName, "level" -> level)
      }))
      Ok(loggers)
    } else {
      Unauthorized
    }
  }

  private def isAdmin(ctx: SecuredAuthContext[AnyContent]) =
    ctx.auth.exists {
      case u: User => u.admin
      case _       => false
    }

}
