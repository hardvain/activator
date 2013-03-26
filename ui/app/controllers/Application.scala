package controllers

import play.api.mvc.{ Action, Controller, WebSocket }
import java.io.File
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.typesafe.sbtchild.SbtChildProcessMaker
import play.api.libs.json.{ JsString, JsObject, JsArray, JsNumber, JsValue }
import snap.{ RootConfig, AppConfig, AppManager, ProcessResult, Platform }
import snap.cache.TemplateMetadata
import builder.properties.BuilderProperties
import scala.util.control.NonFatal
import scala.util.Try
import play.Logger
import play.api.libs.iteratee.{ Iteratee, Enumerator, Concurrent, Input }
import akka.pattern._
import snap.CloseWebSocket

case class ApplicationModel(
  id: String,
  location: String,
  plugins: Seq[String],
  name: String,
  template: Option[String],
  recentApps: Seq[AppConfig]) {

  def jsLocation = location.replaceAll("'", "\\'")
}

case class HomeModel(
  userHome: String,
  templates: Seq[TemplateMetadata],
  recentApps: Seq[AppConfig])

// Data we get from the new application form.
case class NewAppForm(
  name: String,
  location: String,
  template: String)

case class FromLocationForm(location: String)

// Here is where we detect if we're running at a given project...
object Application extends Controller {

  /**
   * Our index page.  Either we load an app from the CWD, or we direct
   * to the homepage to create a new app.
   */
  def index = Action {
    Async {
      AppManager.loadAppIdFromLocation(cwd) map {
        case snap.ProcessSuccess(name) => Redirect(routes.Application.app(name))
        case snap.ProcessFailure(errors) =>
          // TODO FLASH THE ERROR, BABY
          Redirect(routes.Application.forceHome)
      }
    }
  }

  import play.api.data._
  import play.api.data.Forms._
  /** The new application form on the home page. */
  val newAppForm = Form(
    mapping(
      "name" -> text,
      "location" -> text,
      "template" -> text)(NewAppForm.apply)(NewAppForm.unapply))

  /** Reloads the model for the home page. */
  private def homeModel = HomeModel(
    userHome = BuilderProperties.GLOBAL_USER_HOME,
    templates = api.Templates.templateCache.metadata.toSeq,
    recentApps = RootConfig.user.applications)

  /** Loads the homepage, with a blank new-app form. */
  def forceHome = Action { implicit request =>
    // TODO - make sure template cache lives in one and only one place!
    Ok(views.html.home(homeModel, newAppForm))
  }
  /** Loads an application model and pushes to the view by id. */
  def app(id: String) = Action { implicit request =>
    Async {
      // TODO - Different results of attempting to load the application....
      AppManager.loadApp(id) map { theApp =>
        Ok(views.html.application(getApplicationModel(theApp)))
      } recover {
        case e: Exception =>
          // TODO we need to have an error message and "flash" it then
          // display it on home screen
          Logger.error("Failed to load app id " + id + ": " + e.getMessage(), e)
          Redirect(routes.Application.forceHome)
      }
    }
  }

  /**
   * Connects from an application page to the "stateful" actor/server we use
   * per-application for information.
   */
  def connectApp(id: String) = WebSocket.async[JsValue] { request =>
    Logger.info("Connect request for app id: " + id)
    val streamsFuture = AppManager.loadApp(id) flatMap { app =>
      // this is just easier to debug than a timeout; it isn't reliable
      if (app.actor.isTerminated) throw new RuntimeException("App is dead")

      import snap.WebSocketActor.timeout
      (app.actor ? snap.CreateWebSocket).map {
        case snap.WebSocketAlreadyUsed =>
          throw new RuntimeException("can only open apps in one tab at a time")
        case whatever => whatever
      }.mapTo[(Iteratee[JsValue, _], Enumerator[JsValue])].map { streams =>
        Logger.info("WebSocket streams created")
        streams
      }
    }

    streamsFuture onFailure {
      case e: Throwable =>
        Logger.warn("WebSocket failed to open: " + e.getMessage)
    }

    streamsFuture
  }

  /** List all the applications in our history as JSON. */
  def getHistory = Action { request =>
    Ok(JsArray(RootConfig.user.applications.map(_.toJson)))
  }

  /**
   * Returns the application model (for rendering the page) based on
   * the current snap App.
   */
  def getApplicationModel(app: snap.App) =
    ApplicationModel(
      app.config.id,
      Platform.getClientFriendlyFilename(app.config.location),
      // TODO - These should be drawn from the template itself...
      Seq("plugins/code/code", "plugins/compile/compile", "plugins/run/run", "plugins/test/test"),
      app.config.cachedName getOrElse app.config.id,
      // TODO - something less lame than exception here...
      app.templateID,
      RootConfig.user.applications)

  /** Opens a stream for home events. */
  def homeStream = WebSocket.using[JsValue] { request =>
    val out = {
      // THERE HAS TO BE A BETTER WAY TO DO THIS!
      // We create a local object to close over the session, so
      // play's hacking "Conncurrent.unicast" guy with callbacks can
      // actually retain state.
      object CheatingClosureBecausePlayisAnnoying {
        var session: Concurrent.Channel[JsValue] = null
        val homePageActor = snap.Akka.homeStream
        val output = Concurrent.unicast[JsValue](
          onStart = openMe,
          onComplete = closeMe,
          onError = errorMe)

        def openMe(session: Concurrent.Channel[JsValue]): Unit = {
          snap.Akka.homeStream ! snap.AddHomePageSocket(session)
          this.session = session;
        }

        def errorMe(error: String, value: Input[JsValue]): Unit = {
          if (session != null) {
            homePageActor ! snap.RemoveHomePageSocket(session)
            session = null;
          }
        }
        def closeMe(): Unit = {
          if (session != null) {
            homePageActor ! snap.RemoveHomePageSocket(session)
            session.eofAndEnd()
          }
        }
      }
      CheatingClosureBecausePlayisAnnoying.output
    }
    val in = Iteratee.foreach[JsValue] { json =>
      snap.Akka.homeStream ! json
    }
    (in, out)
  }

  /** The current working directory of the app. */
  val cwd = (new java.io.File(".").getAbsoluteFile.getParentFile)
}
