package net.andimiller.mcp.http4s

import scala.util.NotGiven

import cats.Eq
import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.UUIDGen
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.server.*
import net.andimiller.mcp.core.state.SessionRefs

import com.comcast.ip4s.*
import org.http4s.*
import org.http4s.ember.server.EmberServerBuilder

type Append[A, B] = B match
  case Unit => A
  case _    => (A, B)

/** Each registered tool may carry an optional predicate over the authenticated user. The predicate is widened to
  * `Any => F[Boolean]` internally — the public DSL keeps it typed via the `A` parameter on the builder.
  */
private[http4s] type ToolEntry[F[_], Ctx] = (Tool[F, Ctx], Option[Any => F[Boolean]])

/** Streaming HTTP MCP server builder.
  *
  *   - `A` — the authenticated-user type. Defaults to `Unit` on a fresh builder; becomes `U` after `.authenticated[U]`.
  *     Tool-visibility predicates (`.withToolIf`) operate on `A`.
  *   - `Ctx` — the per-session runtime context, grown by `.stateful[S]` and (back-compat) `.authenticated[U]`.
  */
class StreamingMcpHttpBuilder[F[_]: Async, A, Ctx] private[http4s] (
    val mName: String,
    val mVersion: String,
    val mConfig: McpHttpConfig,
    val mAuthInfo: Option[StreamingMcpHttpBuilder.AuthInfo[F]],
    val mStatefulCreators: Vector[SessionContext[F] => F[Any]],
    val mAuthExtractor: Option[Request[F] => F[Option[Any]]],
    val mPlainTools: Vector[ToolEntry[F, Ctx]],
    val mContextTools: Vector[ToolEntry[F, Ctx]],
    val mPlainResources: Vector[McpResource.Resolved[F]],
    val mContextResourceResolvers: Vector[Any => McpResource.Resolved[F]],
    val mPlainResourceTemplates: Vector[ResourceTemplate.Resolved[F]],
    val mContextResourceTemplateResolvers: Vector[Any => ResourceTemplate.Resolved[F]],
    val mPlainPrompts: Vector[Prompt.Resolved[F]],
    val mContextPromptResolvers: Vector[Any => Prompt.Resolved[F]],
    val mCaps: CapabilityTracker,
    val mSessionStore: Option[SessionStore[F]],
    val mSinkFactory: Option[String => Resource[F, NotificationSink[F]]],
    val mSessionRefsFactory: Option[String => SessionRefs[F]],
    val mSessionStoreFactory: Option[SessionStoreFactory[F]],
    val mTitle: Option[String] = None,
    val mDescription: Option[String] = None,
    val mIcons: List[Icon] = Nil,
    val mWebsiteUrl: Option[String] = None,
    val mExtraRoutes: HttpRoutes[F],
    val mToolMiddlewares: List[ToolMiddleware[F, Ctx]] = Nil,
    val mObservers: List[McpObserver[F]] = Nil
):

  private def copy[A2, Ctx2](
      mName: String = this.mName,
      mVersion: String = this.mVersion,
      mConfig: McpHttpConfig = this.mConfig,
      mAuthInfo: Option[StreamingMcpHttpBuilder.AuthInfo[F]] = this.mAuthInfo,
      mStatefulCreators: Vector[SessionContext[F] => F[Any]] = this.mStatefulCreators,
      mAuthExtractor: Option[Request[F] => F[Option[Any]]] = this.mAuthExtractor,
      mPlainTools: Vector[ToolEntry[F, Ctx2]] = this.mPlainTools.asInstanceOf[Vector[ToolEntry[F, Ctx2]]],
      mContextTools: Vector[ToolEntry[F, Ctx2]] = this.mContextTools.asInstanceOf[Vector[ToolEntry[F, Ctx2]]],
      mPlainResources: Vector[McpResource.Resolved[F]] = this.mPlainResources,
      mContextResourceResolvers: Vector[Any => McpResource.Resolved[F]] = this.mContextResourceResolvers,
      mPlainResourceTemplates: Vector[ResourceTemplate.Resolved[F]] = this.mPlainResourceTemplates,
      mContextResourceTemplateResolvers: Vector[Any => ResourceTemplate.Resolved[F]] =
        this.mContextResourceTemplateResolvers,
      mPlainPrompts: Vector[Prompt.Resolved[F]] = this.mPlainPrompts,
      mContextPromptResolvers: Vector[Any => Prompt.Resolved[F]] = this.mContextPromptResolvers,
      mCaps: CapabilityTracker = this.mCaps,
      mSessionStore: Option[SessionStore[F]] = this.mSessionStore,
      mSinkFactory: Option[String => Resource[F, NotificationSink[F]]] = this.mSinkFactory,
      mSessionRefsFactory: Option[String => SessionRefs[F]] = this.mSessionRefsFactory,
      mSessionStoreFactory: Option[SessionStoreFactory[F]] = this.mSessionStoreFactory,
      mTitle: Option[String] = this.mTitle,
      mDescription: Option[String] = this.mDescription,
      mIcons: List[Icon] = this.mIcons,
      mWebsiteUrl: Option[String] = this.mWebsiteUrl,
      mExtraRoutes: HttpRoutes[F] = this.mExtraRoutes,
      mToolMiddlewares: List[ToolMiddleware[F, Ctx2]] =
        this.mToolMiddlewares.asInstanceOf[List[ToolMiddleware[F, Ctx2]]],
      mObservers: List[McpObserver[F]] = this.mObservers
  ): StreamingMcpHttpBuilder[F, A2, Ctx2] =
    new StreamingMcpHttpBuilder[F, A2, Ctx2](
      mName, mVersion, mConfig, mAuthInfo, mStatefulCreators, mAuthExtractor, mPlainTools, mContextTools,
      mPlainResources, mContextResourceResolvers, mPlainResourceTemplates, mContextResourceTemplateResolvers,
      mPlainPrompts, mContextPromptResolvers, mCaps, mSessionStore, mSinkFactory, mSessionRefsFactory,
      mSessionStoreFactory, mTitle, mDescription, mIcons, mWebsiteUrl, mExtraRoutes, mToolMiddlewares, mObservers
    )

  // ── Config ──────────────────────────────────────────────────────────

  def name(n: String): StreamingMcpHttpBuilder[F, A, Ctx] = copy(mName = n)

  def version(v: String): StreamingMcpHttpBuilder[F, A, Ctx] = copy(mVersion = v)

  def host(h: Host): StreamingMcpHttpBuilder[F, A, Ctx] = copy(mConfig = mConfig.copy(host = h))

  def port(p: Port): StreamingMcpHttpBuilder[F, A, Ctx] = copy(mConfig = mConfig.copy(port = p))

  def withExplorer(redirectToRoot: Boolean = false): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mConfig = mConfig.copy(explorerEnabled = true, rootRedirectToExplorer = redirectToRoot))

  // ── Implementation metadata ────────────────────────────────────────

  def title(t: String): StreamingMcpHttpBuilder[F, A, Ctx] = copy(mTitle = Some(t))

  def description(d: String): StreamingMcpHttpBuilder[F, A, Ctx] = copy(mDescription = Some(d))

  def icon(i: Icon): StreamingMcpHttpBuilder[F, A, Ctx] = copy(mIcons = mIcons :+ i)

  def icons(xs: List[Icon]): StreamingMcpHttpBuilder[F, A, Ctx] = copy(mIcons = xs)

  def websiteUrl(url: String): StreamingMcpHttpBuilder[F, A, Ctx] = copy(mWebsiteUrl = Some(url))

  /** Mount additional HTTP routes alongside the MCP routes (e.g. `/icon.svg`, `/favicon.ico`, health checks). Composes
    * with existing extras via `<+>`, so this can be called multiple times.
    */
  def withRoutes(extra: HttpRoutes[F]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mExtraRoutes = mExtraRoutes <+> extra)

  /** Append a server-wide tool middleware. Composed around every tool call, OUTSIDE any per-tool middleware. */
  def withToolMiddleware(mw: ToolMiddleware[F, Ctx]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mToolMiddlewares = mToolMiddlewares :+ mw)

  /** Append a transport-level observation hook. Fires on `initialize`, `tools/call` (and every other `POST /mcp`
    * method), and `DELETE /mcp`. Multiple observers can be registered — they're invoked in registration order via
    * [[McpObserver.combineAll]].
    */
  def withObserver(observer: McpObserver[F]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mObservers = mObservers :+ observer)

  // ── Session store / factory configuration ──────────────────────────

  def withSessionStore(store: SessionStore[F]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mSessionStore = Some(store))

  def withNotificationSinkFactory(f: String => Resource[F, NotificationSink[F]]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mSinkFactory = Some(f))

  def withSessionRefsFactory(f: String => SessionRefs[F]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mSessionRefsFactory = Some(f))

  def withSessionStoreFactory(factory: SessionStoreFactory[F]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mSessionStoreFactory = Some(factory))

  // ── Context accumulation ──────────────────────────────────────────

  /** Register a per-session state creator. The creator receives a [[SessionContext]] — a bundle of `id`, `channel`
    * (notifications + server-initiated requests), and `refs` (per-session named refs). Use the conveniences `ctx.sink`
    * and `ctx.requester` for the common cases, or reach for `ctx.channel` directly to grab the full bidirectional
    * channel.
    *
    * Multiple `.stateful` calls accumulate in declaration order and produce a tuple-shaped context type via
    * `Append[S, Ctx]`.
    */
  def stateful[S](create: SessionContext[F] => F[S]): StreamingMcpHttpBuilder[F, A, Append[S, Ctx]] =
    val widened: SessionContext[F] => F[Any] = ctx => create(ctx).map(_.asInstanceOf[Any])
    copy[A, Append[S, Ctx]](mStatefulCreators = mStatefulCreators :+ widened)

  /** Wire up authentication. The extractor reads credentials off the http4s `Request` (e.g. a Bearer header) and
    * returns `Some(U)` if valid or `None` to short-circuit with `onUnauthorized`. The user identity flows through to
    * tool handlers as the head of `Ctx`, and predicates registered via `.withToolIf` are typed on `U`.
    */
  def authenticated[U: Eq](
      extract: Request[F] => F[Option[U]],
      onUnauthorized: Response[F]
  ): StreamingMcpHttpBuilder[F, U, Append[U, Ctx]] =
    val eqAny: Eq[Any]                               = Eq.instance[Any]((a, b) => summon[Eq[U]].eqv(a.asInstanceOf[U], b.asInstanceOf[U]))
    val widenedExtract: Request[F] => F[Option[Any]] = req => extract(req).map(_.map(_.asInstanceOf[Any]))
    val info                                         = StreamingMcpHttpBuilder.AuthInfo[F](eqAny, widenedExtract, onUnauthorized)
    copy[U, Append[U, Ctx]](mAuthInfo = Some(info), mAuthExtractor = Some(widenedExtract))

  // ── Plain tools ────────────────────────────────────────────────────

  def withTool(tool: Tool[F, Ctx]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mPlainTools = mPlainTools :+ ((tool, None)), mCaps = mCaps.withToolAdded)

  def withTools(tools: Tool[F, Ctx]*): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(
      mPlainTools = mPlainTools ++ tools.map(t => (t, None)),
      mCaps = if tools.nonEmpty then mCaps.withToolAdded else mCaps
    )

  /** Register a plain tool that's only visible to authenticated users for whom `pred` returns true.
    *
    * Requires `.authenticated[U]` to have been called first (compile-time check via `NotGiven[A =:= Unit]`). Hidden
    * tools that a user calls anyway behave exactly like unknown tools — JSON-RPC error -32601.
    */
  def withToolIf(pred: A => Boolean)(tool: Tool[F, Ctx])(using
      NotGiven[A =:= Unit]
  ): StreamingMcpHttpBuilder[F, A, Ctx] =
    val widened: Any => F[Boolean] = a => Async[F].pure(pred(a.asInstanceOf[A]))
    copy(mPlainTools = mPlainTools :+ ((tool, Some(widened))), mCaps = mCaps.withToolAdded)

  /** Effectful version of [[withToolIf]] — predicate can perform I/O (DB lookup, feature-flag service, etc.). */
  def withToolIfF(pred: A => F[Boolean])(tool: Tool[F, Ctx])(using
      NotGiven[A =:= Unit]
  ): StreamingMcpHttpBuilder[F, A, Ctx] =
    val widened: Any => F[Boolean] = a => pred(a.asInstanceOf[A])
    copy(mPlainTools = mPlainTools :+ ((tool, Some(widened))), mCaps = mCaps.withToolAdded)

  // ── Context tools ──────────────────────────────────────────────────

  /** Register a tool that wants to read the per-session `Ctx` inside its handler. The tool's `handle` method receives
    * the same `Ctx` value that's threaded into [[ToolCallContext]] for middleware, so per-tool middleware on a
    * contextual tool sees the matching session state.
    */
  def withContextualTool(tool: Tool[F, Ctx]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mContextTools = mContextTools :+ ((tool, None)), mCaps = mCaps.withToolAdded)

  /** Visibility-gated [[withContextualTool]]. See [[withToolIf]]. */
  def withContextualToolIf(pred: A => Boolean)(tool: Tool[F, Ctx])(using
      NotGiven[A =:= Unit]
  ): StreamingMcpHttpBuilder[F, A, Ctx] =
    val widened: Any => F[Boolean] = a => Async[F].pure(pred(a.asInstanceOf[A]))
    copy(mContextTools = mContextTools :+ ((tool, Some(widened))), mCaps = mCaps.withToolAdded)

  /** Effectful visibility-gated [[withContextualTool]]. See [[withToolIfF]]. */
  def withContextualToolIfF(pred: A => F[Boolean])(tool: Tool[F, Ctx])(using
      NotGiven[A =:= Unit]
  ): StreamingMcpHttpBuilder[F, A, Ctx] =
    val widened: Any => F[Boolean] = a => pred(a.asInstanceOf[A])
    copy(mContextTools = mContextTools :+ ((tool, Some(widened))), mCaps = mCaps.withToolAdded)

  // ── Plain resources ────────────────────────────────────────────────

  def withResource(handler: McpResource.Resolved[F]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mPlainResources = mPlainResources :+ handler, mCaps = mCaps.withResourceAdded)

  def withResource(resource: McpResource[F, Unit]): StreamingMcpHttpBuilder[F, A, Ctx] =
    withResource(resource.resolve)

  def withResources(handlers: McpResource.Resolved[F]*): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(
      mPlainResources = mPlainResources ++ handlers,
      mCaps = if handlers.nonEmpty then mCaps.withResourceAdded else mCaps
    )

  // ── Context resources ──────────────────────────────────────────────

  def withContextualResource(resource: McpResource[F, Ctx]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(
      mContextResourceResolvers = mContextResourceResolvers :+ ((ctx: Any) => resource.provide(ctx.asInstanceOf[Ctx])),
      mCaps = mCaps.withResourceAdded
    )

  // ── Plain resource templates ────────────────────────────────────────

  def withResourceTemplate(handler: ResourceTemplate.Resolved[F]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mPlainResourceTemplates = mPlainResourceTemplates :+ handler, mCaps = mCaps.withResourceAdded)

  def withResourceTemplate(rt: ResourceTemplate[F, Unit]): StreamingMcpHttpBuilder[F, A, Ctx] =
    withResourceTemplate(rt.resolve)

  def withResourceTemplates(handlers: ResourceTemplate.Resolved[F]*): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(
      mPlainResourceTemplates = mPlainResourceTemplates ++ handlers,
      mCaps = if handlers.nonEmpty then mCaps.withResourceAdded else mCaps
    )

  // ── Context resource templates ──────────────────────────────────────

  def withContextualResourceTemplate(rt: ResourceTemplate[F, Ctx]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(
      mContextResourceTemplateResolvers =
        mContextResourceTemplateResolvers :+ ((ctx: Any) => rt.provide(ctx.asInstanceOf[Ctx])),
      mCaps = mCaps.withResourceAdded
    )

  // ── Plain prompts ──────────────────────────────────────────────────

  def withPrompt(handler: Prompt.Resolved[F]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mPlainPrompts = mPlainPrompts :+ handler, mCaps = mCaps.withPromptAdded)

  def withPrompt(prompt: Prompt[F, Unit]): StreamingMcpHttpBuilder[F, A, Ctx] =
    withPrompt(prompt.resolve)

  def withPrompts(handlers: Prompt.Resolved[F]*): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mPlainPrompts = mPlainPrompts ++ handlers, mCaps = if handlers.nonEmpty then mCaps.withPromptAdded else mCaps)

  // ── Context prompts ────────────────────────────────────────────────

  def withContextualPrompt(prompt: Prompt[F, Ctx]): StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(
      mContextPromptResolvers = mContextPromptResolvers :+ ((ctx: Any) => prompt.provide(ctx.asInstanceOf[Ctx])),
      mCaps = mCaps.withPromptAdded
    )

  // ── Capabilities ──────────────────────────────────────────────────

  def enableToolNotifications: StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mCaps = mCaps.withToolNotifications)

  def enableResourceSubscriptions: StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mCaps = mCaps.withResourceSubscriptions)

  def enableResourceNotifications: StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mCaps = mCaps.withResourceNotifications)

  def enablePromptNotifications: StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mCaps = mCaps.withPromptNotifications)

  def enableLogging: StreamingMcpHttpBuilder[F, A, Ctx] =
    copy(mCaps = mCaps.withLogging)

  // ── Build session ──────────────────────────────────────────────────

  /** Build a standalone Server[F] using a no-op client channel and in-memory session refs. Useful for golden testing
    * where only catalog queries (listTools, etc.) are needed — tools that issue server-initiated requests will get
    * [[ServerRequester]]'s "not configured" response if exercised against this server.
    *
    * Predicate-gated tools (`.withToolIf`) are included unconditionally — there's no auth user in this mode, so
    * predicates can't be evaluated. Use [[buildServerAs]] to materialise the catalogue as seen by a specific
    * authenticated user.
    */
  def buildServer: F[Server[F]] =
    ClientChannel.noop[F].flatMap { cc =>
      val ctx = SessionContext("noop", cc, SessionRefs.inMemory[F])
      createStatefulContext(ctx).flatMap(resolveAll(_, None, None))
    }

  /** Build a standalone Server[F] as it would appear to the given authenticated user — i.e. with `.withToolIf` /
    * `.withContextualToolIf` predicates evaluated against `authUser`. Intended for golden tests that want to snapshot
    * the catalogue per role (admin vs. guest, etc.).
    *
    * Only callable after `.authenticated[U]`; the `NotGiven[A =:= Unit]` evidence enforces this at compile time.
    */
  def buildServerAs(authUser: A)(using NotGiven[A =:= Unit]): F[Server[F]] =
    ClientChannel.noop[F].flatMap { cc =>
      val sctx = SessionContext("noop", cc, SessionRefs.inMemory[F])
      createStatefulContext(sctx)
        .map(statefulCtx => StreamingMcpHttpBuilder.prependContext(authUser, statefulCtx))
        .flatMap(resolveAll(_, None, Some(authUser)))
    }

  private def resolveTools(authUser: Option[Any]): F[List[Tool[F, Ctx]]] =
    val entries = (mPlainTools ++ mContextTools).toList
    entries.traverseFilter { case (tool, predOpt) =>
      predOpt match
        case None       => (Some(tool): Option[Tool[F, Ctx]]).pure[F]
        case Some(pred) =>
          authUser match
            case Some(u) => pred(u).map(allowed => if allowed then Some(tool) else None)
            case None    => (Some(tool): Option[Tool[F, Ctx]]).pure[F]
    }

  private def resolveAll(ctx: Any, sessionId: Option[String], authUser: Option[Any]): F[Server[F]] =
    resolveTools(authUser).flatMap { tools =>
      val resources         = mPlainResources ++ mContextResourceResolvers.map(_(ctx))
      val resourceTemplates = mPlainResourceTemplates ++ mContextResourceTemplateResolvers.map(_(ctx))
      val prompts           = mPlainPrompts ++ mContextPromptResolvers.map(_(ctx))
      DefaultServer[F, Ctx](
        info = Implementation(
          name = mName,
          version = mVersion,
          title = mTitle,
          description = mDescription,
          icons = Option.when(mIcons.nonEmpty)(mIcons),
          websiteUrl = mWebsiteUrl
        ),
        capabilities = mCaps.toServerCapabilities,
        ctx = ctx.asInstanceOf[Ctx],
        toolHandlers = tools,
        resourceHandlers = resources.toList,
        resourceTemplateHandlers = resourceTemplates.toList,
        promptHandlers = prompts.toList,
        toolMiddlewares = mToolMiddlewares,
        sessionId = sessionId
      ).widen[Server[F]]
    }

  /** Run all registered `.stateful` creators against a single [[SessionContext]] in declaration order, prepending each
    * result onto the accumulated context tuple.
    */
  private def createStatefulContext(ctx: SessionContext[F]): F[Any] =
    mStatefulCreators.foldLeft(Async[F].pure(()): F[Any]) { (ctxF, creator) =>
      ctxF.flatMap(acc => creator(ctx).map(value => StreamingMcpHttpBuilder.prependContext(value, acc)))
    }

  private[http4s] def newSessionFactory(sessionId: String): SessionContext[F] => F[Server[F]] =
    ctx => createStatefulContext(ctx).flatMap(resolveAll(_, Some(sessionId), None))

  private[http4s] def newAuthenticatedSessionFactory(sessionId: String): (Any, SessionContext[F]) => F[Server[F]] =
    (user, ctx) =>
      createStatefulContext(ctx)
        .map(statefulCtx => StreamingMcpHttpBuilder.prependContext(user, statefulCtx))
        .flatMap(resolveAll(_, Some(sessionId), Some(user)))

  /** Returns true iff any registered tool carries a visibility predicate. */
  private[http4s] def hasPredicatedTools: Boolean =
    mPlainTools.exists(_._2.nonEmpty) || mContextTools.exists(_._2.nonEmpty)

  // ── Terminal operations ─────────────────────────────────────────────

  private def sinkFactory: String => Resource[F, NotificationSink[F]] =
    mSinkFactory.getOrElse(_ => NotificationSink.create[F])

  private def refsFactory: String => SessionRefs[F] =
    mSessionRefsFactory.getOrElse(_ => SessionRefs.inMemory[F])

  def routes(using UUIDGen[F]): Resource[F, HttpRoutes[F]] =
    val observer = McpObserver.combineAll(mObservers)
    if hasPredicatedTools && mAuthInfo.isEmpty then
      Resource.eval(
        Async[F].raiseError[HttpRoutes[F]](
          new IllegalStateException(
            "withToolIf/withToolIfF requires .authenticated[U] to be called first — predicates have no user to evaluate against."
          )
        )
      )
    else
      mAuthInfo match
        case Some(info) =>
          val extractReq: Request[F] => F[Option[Any]] = mAuthExtractor.getOrElse(_ => Async[F].pure(None))
          val storeR                                   = mSessionStore match
            case Some(store: AuthenticatedSessionStore[F, Any] @unchecked) =>
              Resource.pure[F, AuthenticatedSessionStore[F, Any]](store)
            case _ => Resource.eval(AuthenticatedSessionStore.inMemory[F, Any])
          storeR.flatMap { store =>
            val serverF: (String, Any, SessionContext[F]) => F[Server[F]] =
              (id, user, ctx) => newAuthenticatedSessionFactory(id)(user, ctx)
            StreamableHttpTransport.authenticatedRoutes[F, Any](
              extractReq,
              serverF,
              Async[F].pure(info.onForbidden),
              sinkFactory,
              refsFactory,
              store,
              observer
            )(using Async[F], summon[UUIDGen[F]], info.eqAny)
          }
        case None =>
          val storeR: Resource[F, SessionStore[F]] = mSessionStoreFactory match
            case Some(factory) =>
              val reconstruct: String => F[McpSession[F]] = (id: String) =>
                for
                  sinkPair      <- sinkFactory(id).allocated
                  (sink, _)      = sinkPair
                  ccPair        <- ClientChannel.fromSink[F](sink).allocated
                  (cc, _)        = ccPair
                  ctx            = SessionContext(id, cc, refsFactory(id))
                  server        <- newSessionFactory(id)(ctx)
                  handler        = new RequestHandler[F](server, cc.requester, cc.cancellation)
                  subscriptions <- Ref.of[F, Set[String]](Set.empty)
                yield McpSession(id, handler, cc, subscriptions)
              Resource.eval(factory.create(reconstruct))
            case None =>
              mSessionStore.fold(Resource.eval(SessionStore.inMemory[F]))(Resource.pure(_))
          storeR.flatMap { store =>
            val serverF: (String, SessionContext[F]) => F[Server[F]] =
              (id, ctx) => newSessionFactory(id)(ctx)
            StreamableHttpTransport.routes(serverF, sinkFactory, refsFactory, store, observer)
          }

object StreamingMcpHttpBuilder:

  final private[http4s] case class AuthInfo[F[_]](
      eqAny: Eq[Any],
      extract: Request[F] => F[Option[Any]],
      onForbidden: Response[F]
  )

  private[http4s] def prependContext(head: Any, tail: Any): Any = tail match
    case () => head
    case _  => (head, tail)

  extension [A, Ctx](builder: StreamingMcpHttpBuilder[IO, A, Ctx])

    def serve: Resource[IO, org.http4s.server.Server] =
      builder.routes.flatMap { mcpRoutes =>
        val combined = mcpRoutes <+> builder.mExtraRoutes
        val app      = McpHttp.buildApp(combined, builder.mConfig)
        EmberServerBuilder
          .default[IO]
          .withHost(builder.mConfig.host)
          .withPort(builder.mConfig.port)
          .withHttpApp(app)
          .build
      }
