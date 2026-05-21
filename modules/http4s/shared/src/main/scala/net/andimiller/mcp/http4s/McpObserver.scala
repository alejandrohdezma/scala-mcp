package net.andimiller.mcp.http4s

import java.time.Instant

import scala.concurrent.duration.FiniteDuration

import cats.Applicative
import cats.syntax.all.*

import net.andimiller.mcp.core.protocol.Implementation
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId

import org.http4s.Status

/** Backend-neutral observation hook for the streaming HTTP transport.
  *
  * Implementations feed events into whatever store they prefer — Prometheus, structured logs, a usage recorder,
  * trace4cats spans — without scala-mcp itself taking a dependency on any of them. Register one or more via
  * `StreamingMcpHttpBuilder.withObserver(...)`; the transport fires callbacks at three lifecycle points:
  *
  *   - `onSessionInit` — fires synchronously on `initialize` after a session is created but BEFORE the response is
  *     forwarded to the client. This ordering matters: it means the first follow-up `tools/call` from the same session
  *     cannot race the observer's persistence write (e.g. writing `clientInfo.name` into an external lookup table).
  *   - `onSessionTerminate` — fires on `DELETE /mcp` for the matched session, after the session has been removed from
  *     the store and in-flight requests cancelled.
  *   - `onRequest` — fires once per `POST /mcp` after the response body has been fully consumed (so the byte counts in
  *     [[McpRequestEvent]] reflect the complete exchange).
  *
  * `GET /mcp` (SSE) is intentionally not surfaced — its long-lived lifecycle doesn't fit a request-completion event.
  *
  * When multiple observers are registered, they're invoked in registration order via [[McpObserver.combineAll]];
  * exceptions raised by one observer propagate and short-circuit later observers for that callback.
  */
trait McpObserver[F[_]]:

  def onSessionInit(sessionId: String, clientInfo: Implementation): F[Unit]

  def onSessionTerminate(sessionId: String): F[Unit]

  def onRequest(event: McpRequestEvent): F[Unit]

object McpObserver:

  /** The do-nothing observer — used by the transport when no observer is registered, and convenient for tests. */
  def noop[F[_]: Applicative]: McpObserver[F] = new McpObserver[F]:
    def onSessionInit(sessionId: String, clientInfo: Implementation) = Applicative[F].unit
    def onSessionTerminate(sessionId: String)                        = Applicative[F].unit
    def onRequest(event: McpRequestEvent)                            = Applicative[F].unit

  /** Compose a list of observers — every callback fans out to each observer in registration order via `traverse_`. An
    * empty list produces the same behaviour as [[noop]].
    */
  def combineAll[F[_]: Applicative](xs: List[McpObserver[F]]): McpObserver[F] = new McpObserver[F]:
    def onSessionInit(sessionId: String, clientInfo: Implementation) =
      xs.traverse_(_.onSessionInit(sessionId, clientInfo))
    def onSessionTerminate(sessionId: String) = xs.traverse_(_.onSessionTerminate(sessionId))
    def onRequest(event: McpRequestEvent)     = xs.traverse_(_.onRequest(event))

/** A single `POST /mcp` request observed by the transport.
  *
  * `method`, `toolName`, `id`, and `isError` are derived `lazy val`s on the event — observers pay nothing for the
  * fields they don't read, but the convenient accessors are still right there on the event.
  *
  * @param request
  *   the parsed JSON-RPC envelope — observers can introspect `params`, `_meta`, etc. directly without re-parsing the
  *   body.
  * @param response
  *   the JSON-RPC response envelope, when one was produced. `None` for notifications and client-to-server responses,
  *   both of which return `202 Accepted` with an empty body.
  * @param sessionId
  *   the session id resolved from the `Mcp-Session-Id` header (subsequent requests) or freshly allocated (initialize).
  *   `None` only on malformed or session-less requests that didn't reach the handler.
  * @param requestBytes
  *   number of bytes received in the request body. Counted from the raw byte chunks, so accurate for non-ASCII bodies.
  * @param responseBytes
  *   number of bytes written to the response body. Counted from the raw byte chunks.
  * @param status
  *   the HTTP status returned to the client.
  * @param startTime
  *   wall-clock instant when the transport began handling this request. Pair with `elapsed` for downstream correlation
  *   against external systems.
  * @param elapsed
  *   wall-clock duration from when the transport began handling the request to when the response body finished
  *   streaming.
  */
case class McpRequestEvent(
    request: Message,
    response: Option[Message.Response],
    sessionId: Option[String],
    requestBytes: Long,
    responseBytes: Long,
    status: Status,
    startTime: Instant,
    elapsed: FiniteDuration
):

  /** `Some` for [[Message.Request]] / [[Message.Notification]], `None` for [[Message.Response]]. */
  lazy val method: Option[String] = request match
    case Message.Request(_, _, m, _)   => Some(m)
    case Message.Notification(_, m, _) => Some(m)
    case _: Message.Response           => None

  /** `Some` only when `method == "tools/call"` and `params.name` is a string. */
  lazy val toolName: Option[String] = request match
    case Message.Request(_, _, "tools/call", Some(params)) =>
      params.hcursor.downField("name").as[String].toOption
    case _ => None

  /** `Some` for [[Message.Request]] / [[Message.Response]], `None` for [[Message.Notification]]. */
  lazy val id: Option[RequestId] = request match
    case Message.Request(_, rid, _, _)  => Some(rid)
    case Message.Response(_, rid, _, _) => Some(rid)
    case _: Message.Notification        => None

  /** `true` if the JSON-RPC response carries an `error` object. Tool-level errors (the `isError: true` flag inside a
    * successful `tools/call` response's `result` payload) are NOT reflected here — observers that care about those
    * should read them from `response.result`.
    */
  lazy val isError: Boolean = response.exists(_.error.isDefined)
