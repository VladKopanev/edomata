package edomata.core

type D[T] = Decision[String, Int, T]
type AppG[Env, T] =
  ServiceMonad[Option, Env, Rejection, Event, Notification, T]
type App[T] = AppG[Int, T]
type AppContra[T] = AppG[T, Unit]

type Rejection = String
type Event = Int

type SUT = D[Long]

type Res[T] = ResponseMonad[Rejection, Event, Notification, T]

final case class Notification(value: String = "")