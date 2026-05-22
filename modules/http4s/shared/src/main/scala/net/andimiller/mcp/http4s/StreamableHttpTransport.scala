package net.andimiller.mcp.http4s

import java.nio.charset.StandardCharsets
import java.time.Instant

import cats.Eq
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.UUIDGen
import cats.syntax.all.*

import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.Implementation
import net.andimiller.mcp.core.protocol.InitializeRequest
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.server.ClientChannel
import net.andimiller.mcp.core.server.NotificationSink
import net.andimiller.mcp.core.server.RequestHandler
import net.andimiller.mcp.core.server.Server
import net.andimiller.mcp.core.server.SessionContext
import net.andimiller.mcp.core.state.SessionRefs

import fs2.Chunk
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.*

/** Streamable HTTP transport for MCP (spec 2025-03-26).
  *
  * Provides three endpoints on a single path:
  *   - '''POST /mcp''' — send JSON-RPC requests/notifications
  *   - '''GET /mcp''' — open an SSE stream for server-initiated notifications
  *   - '''DELETE /mcp''' — terminate a session
  *
  * Sessions are created on `initialize` and identified by the `Mcp-Session-Id` header. The `serverFactory` receives a
  * [[SessionContext]] containing the session id, the bidirectional [[ClientChannel]], and a per-session
  * [[SessionRefs]].
  *
  * An optional [[McpObserver]] can be supplied to observe session lifecycle and per-request events for instrumentation
  * (metrics, structured logging, usage recording). Defaults to [[McpObserver.noop]].
  */
object StreamableHttpTransport:

  private val mcpSessionId = ci"Mcp-Session-Id"

  private val eventStreamMediaType = new MediaType("text", "event-stream")

  /** Convenience: build routes with default in-memory sinks, refs, and session store. */
  def routes[F[_]: Async: UUIDGen](
      serverFactory: (String, SessionContext[F]) => F[Server[F]]
  ): Resource[F, HttpRoutes[F]] =
    routes(serverFactory, McpObserver.noop[F])

  /** Convenience: build routes with default in-memory sinks, refs, and session store, plus an observer. */
  def routes[F[_]: Async: UUIDGen](
      serverFactory: (String, SessionContext[F]) => F[Server[F]],
      observer: McpObserver[F]
  ): Resource[F, HttpRoutes[F]] =
    Resource.eval(SessionStore.inMemory[F]).map { store =>
      unauthenticatedRoutes(serverFactory, defaultSinkFactory[F], defaultRefsFactory[F], store, observer)
    }

  /** Build routes with user-supplied per-session factories.
    *
    * @param serverFactory
    *   receives the new session's id and [[SessionContext]] and produces a `Server[F]`
    * @param sinkFactory
    *   builds a [[NotificationSink]] per session id (default: in-memory)
    * @param refsFactory
    *   builds a [[SessionRefs]] per session id (default: in-memory)
    * @param store
    *   where to persist [[McpSession]]s by id
    * @param observer
    *   transport-level observation hook; defaults to [[McpObserver.noop]]
    */
  def routes[F[_]: Async: UUIDGen](
      serverFactory: (String, SessionContext[F]) => F[Server[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: SessionStore[F]
  ): Resource[F, HttpRoutes[F]] =
    routes(serverFactory, sinkFactory, refsFactory, store, McpObserver.noop[F])

  /** Same as [[routes]] but with a transport-level observation hook. */
  def routes[F[_]: Async: UUIDGen](
      serverFactory: (String, SessionContext[F]) => F[Server[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: SessionStore[F],
      observer: McpObserver[F]
  ): Resource[F, HttpRoutes[F]] =
    Resource.pure(unauthenticatedRoutes(serverFactory, sinkFactory, refsFactory, store, observer))

  /** Build authenticated HTTP routes. On `initialize`, the extracted user identity is stored alongside the session;
    * subsequent requests must present credentials for the same user (enforced via `Eq[U]`).
    */
  def authenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
      authenticate: Request[F] => F[Option[U]],
      serverFactory: (String, U, SessionContext[F]) => F[Server[F]],
      onUnauthorized: F[Response[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: AuthenticatedSessionStore[F, U]
  ): Resource[F, HttpRoutes[F]] =
    authenticatedRoutes(
      authenticate,
      serverFactory,
      onUnauthorized,
      sinkFactory,
      refsFactory,
      store,
      McpObserver.noop[F]
    )

  /** Same as [[authenticatedRoutes]] but with a transport-level observation hook. */
  def authenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
      authenticate: Request[F] => F[Option[U]],
      serverFactory: (String, U, SessionContext[F]) => F[Server[F]],
      onUnauthorized: F[Response[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: AuthenticatedSessionStore[F, U],
      observer: McpObserver[F]
  ): Resource[F, HttpRoutes[F]] =
    Resource.pure(
      authedRoutes(authenticate, serverFactory, onUnauthorized, sinkFactory, refsFactory, store, observer)
    )

  /** Convenience: authenticated routes with default in-memory factories and store. */
  def authenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
      authenticate: Request[F] => F[Option[U]],
      serverFactory: (String, U, SessionContext[F]) => F[Server[F]],
      onUnauthorized: F[Response[F]]
  ): Resource[F, HttpRoutes[F]] =
    authenticatedRoutes(authenticate, serverFactory, onUnauthorized, McpObserver.noop[F])

  /** Convenience: authenticated routes with default in-memory factories and store, plus an observer. */
  def authenticatedRoutes[F[_]: Async: UUIDGen, U: Eq](
      authenticate: Request[F] => F[Option[U]],
      serverFactory: (String, U, SessionContext[F]) => F[Server[F]],
      onUnauthorized: F[Response[F]],
      observer: McpObserver[F]
  ): Resource[F, HttpRoutes[F]] =
    Resource.eval(AuthenticatedSessionStore.inMemory[F, U]).map { store =>
      authedRoutes(
        authenticate,
        serverFactory,
        onUnauthorized,
        defaultSinkFactory[F],
        defaultRefsFactory[F],
        store,
        observer
      )
    }

  // ── Defaults ───────────────────────────────────────────────────────

  private def defaultSinkFactory[F[_]: Async]: String => Resource[F, NotificationSink[F]] =
    _ => NotificationSink.create[F]

  private def defaultRefsFactory[F[_]: Async]: String => SessionRefs[F] =
    _ => SessionRefs.inMemory[F]

  // ── Helpers ────────────────────────────────────────────────────────

  private def getSessionId[F[_]](req: Request[F]): Option[String] =
    req.headers.get(mcpSessionId).map(_.head.value)

  /** Read the request body fully into a `Chunk[Byte]`, decode it to a JSON-RPC `Message`, and hand back both the raw
    * bytes (for replay to the inner handler) and the parsed envelope. The byte count is `chunk.size.toLong`.
    */
  private def readBody[F[_]: Async](req: Request[F]): F[Either[String, (Chunk[Byte], Message)]] =
    req.body.compile.to(Chunk).map { chunk =>
      val body = new String(chunk.toArray, StandardCharsets.UTF_8)
      decode[Message](body).bimap(
        err => s"Invalid JSON-RPC message: ${err.getMessage}",
        msg => (chunk, msg)
      )
    }

  /** Pull `clientInfo` out of an `initialize` request envelope. Returns `None` if the params are missing or malformed —
    * the observer's `onSessionInit` is then skipped, since there's no meaningful client identity to record.
    */
  private def extractClientInfo(message: Message): Option[Implementation] = message match
    case Message.Request(_, _, _, Some(params)) =>
      params.as[InitializeRequest].toOption.map(_.clientInfo)
    case _ => None

  /** Internal carrier for the data we need to build an `McpRequestEvent` after the response body has finished
    * streaming. `message` is `None` when the request didn't even parse (auth failure, malformed JSON-RPC body) — in
    * which case no observer event fires.
    */
  final private case class PostOutcome[F[_]](
      response: Response[F],
      message: Option[Message],
      responseEnvelope: Option[Message.Response],
      requestBytes: Long,
      sessionId: Option[String]
  )

  /** Run the full POST /mcp pipeline up to (but not including) response-body wrapping. Returns a [[PostOutcome]] that
    * the caller wraps in a byte-counting `evalTap` + observer `onRequest` finalizer.
    */
  private def handlePost[F[_]: Async, A](
      req: Request[F],
      authCheck: Request[F] => F[Either[Response[F], A]],
      initSession: A => F[McpSession[F]],
      validateSession: (A, String) => F[Either[Response[F], Unit]],
      store: SessionStore[F],
      observer: McpObserver[F]
  ): F[PostOutcome[F]] =
    val dsl = Http4sDsl[F]
    import dsl.*

    authCheck(req).flatMap {
      case Left(resp) =>
        Async[F].pure(PostOutcome[F](resp, None, None, 0L, None))
      case Right(authState) =>
        readBody(req).flatMap {
          case Left(err) =>
            BadRequest(err).map(PostOutcome[F](_, None, None, 0L, None))
          case Right((bodyChunk, message)) =>
            val reqBytes = bodyChunk.size.toLong
            message match
              case r @ Message.Request(_, _, "initialize", _) =>
                initSession(authState).flatMap { session =>
                  session.handler.handle(r).flatMap { handlerResp =>
                    val responseEnvelope          = handlerResp.collect { case resp: Message.Response => resp }
                    val httpRespF: F[Response[F]] = handlerResp match
                      case Some(response) =>
                        Ok(response.asJson.noSpaces).map(
                          _.putHeaders(
                            Header.Raw(mcpSessionId, session.id),
                            `Content-Type`(MediaType.application.json)
                          )
                        )
                      case None => Accepted()
                    for
                      httpResp <- httpRespF
                      _        <- extractClientInfo(r).traverse_(ci => observer.onSessionInit(session.id, ci))
                    yield PostOutcome[F](httpResp, Some(message), responseEnvelope, reqBytes, Some(session.id))
                  }
                }

              case msg =>
                getSessionId(req) match
                  case None =>
                    BadRequest("Missing Mcp-Session-Id header")
                      .map(PostOutcome[F](_, Some(message), None, reqBytes, None))
                  case Some(sid) =>
                    validateSession(authState, sid).flatMap {
                      case Left(resp) =>
                        Async[F].pure(PostOutcome[F](resp, Some(message), None, reqBytes, Some(sid)))
                      case Right(()) =>
                        store.get(sid).flatMap {
                          case None =>
                            NotFound("Session not found")
                              .map(PostOutcome[F](_, Some(message), None, reqBytes, Some(sid)))
                          case Some(session) =>
                            msg match
                              case _: Message.Notification | _: Message.Response =>
                                session.handler
                                  .handle(msg)
                                  .as(
                                    PostOutcome[F](
                                      Response[F](Status.Accepted),
                                      Some(message),
                                      None,
                                      reqBytes,
                                      Some(sid)
                                    )
                                  )
                              case _: Message.Request =>
                                session.handler.handle(msg).flatMap { handlerResp =>
                                  val responseEnvelope          = handlerResp.collect { case resp: Message.Response => resp }
                                  val httpRespF: F[Response[F]] = handlerResp match
                                    case Some(response) =>
                                      Ok(response.asJson.noSpaces)
                                        .map(_.withContentType(`Content-Type`(MediaType.application.json)))
                                    case None => Accepted()
                                  httpRespF.map(httpResp =>
                                    PostOutcome[F](httpResp, Some(message), responseEnvelope, reqBytes, Some(sid))
                                  )
                                }
                        }
                    }
        }
    }

  /** Build a session given a SessionContext-aware factory. */
  private def createSessionFromContext[F[_]: Async: UUIDGen](
      serverFactory: (String, SessionContext[F]) => F[Server[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: SessionStore[F]
  ): F[McpSession[F]] =
    for
      id            <- UUIDGen[F].randomUUID.map(_.toString)
      sinkPair      <- sinkFactory(id).allocated
      (sink, _)      = sinkPair
      ccPair        <- ClientChannel.fromSink[F](sink).allocated
      (cc, _)        = ccPair
      ctx            = SessionContext[F](id, cc, refsFactory(id))
      server        <- serverFactory(id, ctx)
      handler        = new RequestHandler[F](server, cc.requester, cc.cancellation)
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
      session        = McpSession(id, handler, cc, subscriptions)
      _             <- store.put(session)
    yield session

  /** Same as [[createSessionFromContext]] but additionally binds the user identity in the authenticated session store. */
  private def createAuthenticatedSession[F[_]: Async: UUIDGen, U](
      serverFactory: (String, U, SessionContext[F]) => F[Server[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: AuthenticatedSessionStore[F, U],
      user: U
  ): F[McpSession[F]] =
    for
      id            <- UUIDGen[F].randomUUID.map(_.toString)
      sinkPair      <- sinkFactory(id).allocated
      (sink, _)      = sinkPair
      ccPair        <- ClientChannel.fromSink[F](sink).allocated
      (cc, _)        = ccPair
      ctx            = SessionContext[F](id, cc, refsFactory(id))
      server        <- serverFactory(id, user, ctx)
      handler        = new RequestHandler[F](server, cc.requester, cc.cancellation)
      subscriptions <- Ref.of[F, Set[String]](Set.empty)
      session        = McpSession(id, handler, cc, subscriptions)
      _             <- store.put(session)
      _             <- store.putUser(id, user)
    yield session

  // ── Routes assembly ────────────────────────────────────────────────

  /** Thin wrapper for the unauthenticated path — supplies no-op auth/validation hooks. */
  private def unauthenticatedRoutes[F[_]: Async: UUIDGen](
      serverFactory: (String, SessionContext[F]) => F[Server[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: SessionStore[F],
      observer: McpObserver[F]
  ): HttpRoutes[F] =
    buildRoutes[F, Unit](
      authCheck = _ => Async[F].pure(Right(())),
      initSession = _ => createSessionFromContext(serverFactory, sinkFactory, refsFactory, store),
      validateSession = (_, _) => Async[F].pure(Right(())),
      store = store,
      observer = observer
    )

  /** Thin wrapper for the authenticated path — wires `authenticate`, `getUser`-based validation, and per-user session
    * binding into the unified route builder.
    */
  private def authedRoutes[F[_]: Async: UUIDGen, U: Eq](
      authenticate: Request[F] => F[Option[U]],
      serverFactory: (String, U, SessionContext[F]) => F[Server[F]],
      onUnauthorized: F[Response[F]],
      sinkFactory: String => Resource[F, NotificationSink[F]],
      refsFactory: String => SessionRefs[F],
      store: AuthenticatedSessionStore[F, U],
      observer: McpObserver[F]
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    buildRoutes[F, U](
      authCheck = req =>
        authenticate(req).flatMap {
          case Some(user) => Async[F].pure(Right(user))
          case None       => onUnauthorized.map(Left(_))
        },
      initSession = user => createAuthenticatedSession(serverFactory, sinkFactory, refsFactory, store, user),
      validateSession = (user, sessionId) =>
        store.getUser(sessionId).flatMap {
          case Some(stored) if stored === user => Async[F].pure(Right(()))
          case Some(_)                         => Forbidden("Credential mismatch").map(Left(_))
          case None                            => NotFound("Session not found").map(Left(_))
        },
      store = store,
      observer = observer
    )

  /** Unified route builder for both the unauthenticated and authenticated paths.
    *
    * Three injection points capture the differences between the two flavours:
    *
    *   - `authCheck` runs once per request before any session lookup. `Left` short-circuits the response;
    *     `Right(authState)` threads through to subsequent steps. The unauthenticated path uses `A = Unit` and always
    *     returns `Right(())`.
    *   - `initSession` creates a brand-new session on `initialize`. The authenticated path closes over the user so it
    *     can bind the identity in the session store.
    *   - `validateSession` runs once the request's `Mcp-Session-Id` is in hand (but before `store.get(sid)`). `Left`
    *     short-circuits; `Right(())` proceeds. The authenticated path enforces "the credentials match what was bound at
    *     `initialize` time"; the unauthenticated path always returns `Right(())`.
    *
    * The DELETE path intentionally consults `validateSession` first so the authenticated impl can reject credential
    * mismatches before the session is touched. On `Right(())`, the existing session (if any) is cancelled and removed —
    * matching the original unauthenticated behaviour of silently 200-ing on DELETE of a missing session.
    *
    * Per-POST byte counting + lifecycle observation is wired through `observer`. `observer.onRequest` fires once per
    * `POST /mcp` after the response body is fully consumed; `observer.onSessionInit` fires synchronously after a new
    * session is created on `initialize`, before the response is forwarded; `observer.onSessionTerminate` fires after
    * `DELETE /mcp` removes the session from the store.
    */
  private def buildRoutes[F[_]: Async, A](
      authCheck: Request[F] => F[Either[Response[F], A]],
      initSession: A => F[McpSession[F]],
      validateSession: (A, String) => F[Either[Response[F], Unit]],
      store: SessionStore[F],
      observer: McpObserver[F]
  ): HttpRoutes[F] =
    val dsl = Http4sDsl[F]
    import dsl.*

    HttpRoutes.of[F] {
      // ── POST /mcp ────────────────────────────────────────────────────
      case req @ POST -> Root / "mcp" =>
        for
          startNanos   <- Async[F].realTime
          startInstant  = Instant.ofEpochMilli(startNanos.toMillis)
          respBytesRef <- Ref.of[F, Long](0L)
          outcome      <- handlePost(req, authCheck, initSession, validateSession, store, observer)
          wrappedResp   = outcome.message.fold(outcome.response) { msg =>
                          val wrappedBody = outcome.response.body.chunks
                            .evalTap(c => respBytesRef.update(_ + c.size.toLong))
                            .unchunks
                            .onFinalize {
                              for
                                endNanos  <- Async[F].realTime
                                respBytes <- respBytesRef.get
                                event      = McpRequestEvent(
                                          request = msg,
                                          response = outcome.responseEnvelope,
                                          sessionId = outcome.sessionId,
                                          requestBytes = outcome.requestBytes,
                                          responseBytes = respBytes,
                                          status = outcome.response.status,
                                          startTime = startInstant,
                                          elapsed = endNanos - startNanos
                                        )
                                _ <- observer.onRequest(event)
                              yield ()
                            }
                          outcome.response.withBodyStream(wrappedBody)
                        }
        yield wrappedResp

      // ── GET /mcp ─────────────────────────────────────────────────────
      case req @ GET -> Root / "mcp" =>
        authCheck(req).flatMap {
          case Left(resp)       => Async[F].pure(resp)
          case Right(authState) =>
            getSessionId(req) match
              case None      => BadRequest("Missing Mcp-Session-Id header")
              case Some(sid) =>
                validateSession(authState, sid).flatMap {
                  case Left(resp) => Async[F].pure(resp)
                  case Right(())  =>
                    store.get(sid).flatMap {
                      case None          => NotFound("Session not found")
                      case Some(session) =>
                        val sseStream: Stream[F, String] = session.clientChannel.subscribe.map { msg =>
                          s"event: message\ndata: ${msg.asJson.noSpaces}\n\n"
                        }
                        Ok(sseStream).map(_.withContentType(`Content-Type`(eventStreamMediaType)))
                    }
                }
        }

      // ── DELETE /mcp ──────────────────────────────────────────────────
      case req @ DELETE -> Root / "mcp" =>
        authCheck(req).flatMap {
          case Left(resp)       => Async[F].pure(resp)
          case Right(authState) =>
            getSessionId(req) match
              case None      => BadRequest("Missing Mcp-Session-Id header")
              case Some(sid) =>
                validateSession(authState, sid).flatMap {
                  case Left(resp) => Async[F].pure(resp)
                  case Right(())  =>
                    store.get(sid).flatMap(_.traverse_(_.clientChannel.cancellation.cancelAll)) *>
                      store.remove(sid) *>
                      observer.onSessionTerminate(sid) *>
                      Ok("Session terminated")
                }
        }
    }
