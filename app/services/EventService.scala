package services

import javax.inject.{Inject, Singleton}

import models.dao.{EventDao, GroupDao}
import models.event.Event
import models.user.User
import play.api.libs.concurrent.Execution.Implicits._
import services.authorization.EventSda
import utils.errors.{BadRequestError, NotFoundError}
import utils.implicits.FutureLifting._
import utils.listmeta.ListMeta

/**
  * Event service.
  */
@Singleton
class EventService @Inject()(
  protected val eventDao: EventDao,
  protected val groupDao: GroupDao,
  protected val eventJobService: EventJobService
) extends ServiceResults[Event] {

  /**
    * Returns event by ID
    */
  def getById(id: Long)(implicit account: User): SingleResult = {
    eventDao.findById(id)
      .liftRight {
        NotFoundError.Event(id)
      }
  }

  /**
    * Returns events list filtered by given criteria.
    *
    * @param status        event status
    * @param projectId     event containing project ID
    * @param onlyAvailable only events user has access to
    */
  def list(
    status: Option[Event.Status],
    projectId: Option[Long],
    onlyAvailable: Boolean = false
  )(implicit account: User, meta: ListMeta): ListResult = {

    val groupFromFilter = account.role match {
      case User.Role.Admin if !onlyAvailable => None.toFuture
      case _ => groupDao.findGroupIdsByUserId(account.id).map(Some(_))
    }

    for {
      groupFromIds <- groupFromFilter.lift
      events <- eventDao.getList(
        optId = None,
        optStatus = status,
        optProjectId = projectId,
        optGroupFromIds = groupFromIds
      ).lift
    } yield events
  }

  /**
    * Creates new event.
    *
    * @param event event model
    */
  def create(event: Event)(implicit account: User): SingleResult = {
    for {
      _ <- validateEvent(event)
      created <- eventDao.create(event).lift
      _ <- eventJobService.createJobs(created).lift
    } yield created
  }

  /**
    * Updates event.
    *
    * @param draft event draft
    */
  def update(draft: Event)(implicit account: User): SingleResult = {
    for {
      original <- getById(draft.id)
      _ <- validateEvent(draft)
      _ <- EventSda.canUpdate(original, draft).liftLeft

      updated <- eventDao.update(draft).lift
      _ <- eventJobService.createJobs(updated).lift
    } yield updated
  }

  /**
    * Removes event.
    *
    * @param id event ID
    */
  def delete(id: Long)(implicit account: User): UnitResult = {
    for {
      _ <- getById(id)

      _ <- eventDao.delete(id).lift
    } yield ()
  }

  private def validateEvent(event: Event): UnitResult = {
    for {
      _ <- ensure(!(event.start after event.end)) {
        BadRequestError.Event.StartAfterEnd
      }

      isUniqueNotifications = event.notifications
        .map(x => (x.kind, x.recipient)).distinct.length == event.notifications.length
      _ <- ensure(isUniqueNotifications) {
        BadRequestError.Event.NotUniqueNotifications
      }
    } yield ()
  }
}
