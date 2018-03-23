package controllers

import controllers.actions.AuthContext
import domains.AuthInfo
import domains.user.User
import env.Env
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc._

class HomeController(_env: Env, AuthAction: ActionBuilder[AuthContext, AnyContent], cc: ControllerComponents)
    extends AbstractController(cc) {

  lazy val enabledUserManagement: Boolean = _env.izanamiConfig.filter match {
    case default: env.Default => true
    case _                    => false
  }
  lazy val contextPath: String = _env.contextPath
  lazy val logout: String = if (_env.izanamiConfig.logout.url.startsWith("http")) {
    _env.izanamiConfig.logout.url
  } else {
    s"$contextPath${_env.izanamiConfig.logout.url}"
  }

  def index() = AuthAction { ctx =>
    Logger.info("Here")
    ctx.auth match {
      case Some(_) =>
        Ok(
          views.html
            .index(_env, contextPath, logout, enabledUserManagement, toJson(ctx.auth))
        )
      case None =>
        Redirect(s"$contextPath/login")
    }
  }

  def login() = AuthAction { ctx =>
    Ok(views.html.index(_env, contextPath, logout, enabledUserManagement, toJson(ctx.auth)))
  }

  def otherRoutes(anyPath: String) = index()

  private def toJson(auth: Option[AuthInfo]): JsValue = auth match {
    case Some(u: User) => Json.toJson(u).as[JsObject] - "id"
    case _             => Json.obj()
  }
}
