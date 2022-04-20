package edomata.backend

import cats.data.EitherNec
import cats.data.NonEmptyChain
import cats.effect.Concurrent
import cats.effect.implicits.*
import cats.effect.kernel.Async
import cats.effect.kernel.Clock
import cats.effect.kernel.Resource
import cats.effect.kernel.Temporal
import cats.implicits.*
import edomata.core.*
import fs2.Stream
import skunk.Codec
import skunk.Session
import skunk.data.Identifier

import java.time.OffsetDateTime
import java.time.ZoneOffset
import scala.concurrent.duration.*

object SkunkBackend {
  def apply[F[_]: Async](pool: Resource[F, Session[F]]): PartialBuilder[F] =
    PartialBuilder(pool)

  final class PartialBuilder[F[_]: Async](pool: Resource[F, Session[F]]) {
    inline def builder[C, S, E, R, N](
        domain: Domain[C, S, E, R, N],
        inline namespace: String
    )(using
        model: ModelTC[S, E, R]
    ): DomainBuilder[F, C, S, E, R, N] =
      DomainBuilder(
        pool,
        domain,
        model,
        PGNamespace(namespace),
        Resource.eval(SnapshotStore.inMem(1000))
      )
  }

  final case class DomainBuilder[
      F[_]: Async,
      C,
      S,
      E,
      R,
      N
  ] private[backend] (
      private val pool: Resource[F, Session[F]],
      private val domain: Domain[C, S, E, R, N],
      private val model: ModelTC[S, E, R],
      val namespace: PGNamespace,
      private val snapshot: Resource[F, SnapshotStore[F, S]],
      val maxRetry: Int = 5,
      val retryInitialDelay: FiniteDuration = 2.seconds,
      val cached: Boolean = true
  ) {

    def persistedSnapshot(
        storage: SnapshotPersistence[F, S],
        maxInMem: Int = 1000,
        maxBuffer: Int = 100,
        maxWait: FiniteDuration = 1.minute
    ): DomainBuilder[F, C, S, E, R, N] = copy(snapshot =
      SnapshotStore
        .persisted(
          storage,
          size = maxInMem,
          maxBuffer = maxBuffer,
          maxWait
        )
        .widen
    )

    def disableCache: DomainBuilder[F, C, S, E, R, N] = copy(cached = false)

    def inMemSnapshot(
        maxInMem: Int = 1000
    ): DomainBuilder[F, C, S, E, R, N] =
      copy(snapshot = Resource.eval(SnapshotStore.inMem(maxInMem)))

    def withSnapshot(
        s: Resource[F, SnapshotStore[F, S]]
    ): DomainBuilder[F, C, S, E, R, N] = copy(snapshot = s)

    def withRetryConfig(
        maxRetry: Int = maxRetry,
        retryInitialDelay: FiniteDuration = retryInitialDelay
    ): DomainBuilder[F, C, S, E, R, N] =
      copy(maxRetry = maxRetry, retryInitialDelay = retryInitialDelay)

    private def _setup(using
        event: BackendCodec[E],
        notifs: BackendCodec[N]
    ) = {
      val jQ = Queries.Journal(namespace, event)
      val nQ = Queries.Outbox(namespace, notifs)
      val cQ = Queries.Commands(namespace)

      pool
        .use(s =>
          s.execute(Queries.setupSchema(namespace)) >>
            s.execute(jQ.setup) >> s.execute(nQ.setup) >> s.execute(cQ.setup)
        )
        .as((jQ, nQ, cQ))
    }

    def setup(using
        event: BackendCodec[E],
        notifs: BackendCodec[N]
    ): F[Unit] = _setup.void

    def build(using
        event: BackendCodec[E],
        notifs: BackendCodec[N]
    ): Resource[F, Backend[F, S, E, R, N]] = for {
      qs <- Resource.eval(_setup)
      (jQ, nQ, cQ) = qs
      given ModelTC[S, E, R] = model
      s <- snapshot
      _outbox = SkunkOutboxReader(pool, nQ)
      _journal = SkunkJournalReader(pool, jQ)
      _repo = RepositoryReader(_journal, s)
      skRepo = SkunkRepository(pool, jQ, nQ, cQ, _repo)
      compiler <-
        if cached then
          Resource
            .eval(CommandStore.inMem(100))
            .map(CachedRepository(skRepo, _, s))
        else Resource.pure(skRepo)

    } yield new {
      def compile[C](
          app: Edomaton[F, RequestContext[C, S], R, E, N, Unit]
      ): DomainService[F, CommandMessage[C], R] = cmd =>
        CommandHandler.retry(maxRetry, retryInitialDelay) {
          CommandHandler(compiler, app).apply(cmd)
        }
      val outbox: OutboxReader[F, N] = _outbox
      val journal: JournalReader[F, E] = _journal
      val repository: RepositoryReader[F, S, E, R] = _repo

    }
  }
}
