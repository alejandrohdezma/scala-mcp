package net.andimiller.mcp.http4s

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.Ref
import cats.effect.SyncIO
import cats.syntax.all.*

import net.andimiller.mcp.core.codecs.CirceCodecs.given
import net.andimiller.mcp.core.protocol.*
import net.andimiller.mcp.core.protocol.content.Content
import net.andimiller.mcp.core.protocol.jsonrpc.Message
import net.andimiller.mcp.core.protocol.jsonrpc.RequestId
import net.andimiller.mcp.core.server.*

import io.circe.Json
import io.circe.syntax.*
import munit.AnyFixture
import munit.Assertions.fail
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.implicits.*
import org.typelevel.ci.*

class McpObserverSuite extends munit.Http4sSuite:

  // ── MCP request helpers ───────────────────────────────────────────

  private def initializeRequest(clientName: String = "test"): Request[IO] =
    val body = Message.request(
      RequestId.fromLong(1L),
      "initialize",
      Some(InitializeRequest("2025-11-25", ClientCapabilities.empty, Implementation(clientName, "1")).asJson)
    )

    POST(body, uri"/mcp")

  private def toolRequest(tool: String, prior: Response[IO]): IO[Request[IO]] =
    val body = Message.request(
      RequestId.fromLong(2L),
      "tools/call",
      Some(CallToolRequest(tool, Json.obj()).asJson)
    )

    val sidHeader = prior.headers
      .get(ci"Mcp-Session-Id")
      .map(_.head)
      .getOrElse(fail("Missing Mcp-Session-Id"))

    POST(body, uri"/mcp", Header.Raw(ci"Mcp-Session-Id", sidHeader.value)).pure[IO]

  private def notificationRequest(method: String, prior: Response[IO]): IO[Request[IO]] =
    val body = Message.notification(method, None)

    val sidHeader = prior.headers
      .get(ci"Mcp-Session-Id")
      .map(_.head)
      .getOrElse(fail("Missing Mcp-Session-Id"))

    POST(body, uri"/mcp", Header.Raw(ci"Mcp-Session-Id", sidHeader.value)).pure[IO]

  // ── Server + observer wiring ──────────────────────────────────────

  private def echoTool: Tool[IO, Unit] =
    new Tool[IO, Unit]:
      val name                                                          = "echo"
      val description                                                   = ""
      val inputSchema                                                   = Json.obj()
      val outputSchema                                                  = None
      def handle(call: ToolCallContext[IO, Unit]): IO[CallToolResponse] =
        IO.pure(CallToolResponse(List(Content.Text("ok")), None, false))

  enum Event:

    case SessionInit(sessionId: String, clientInfo: Implementation)

    case SessionTerminate(sessionId: String)

    case Request(event: McpRequestEvent)

  // ── Fixtures ──────────────────────────────────────────────────────

  private lazy val captured = ResourceTestLocalFixture("captured", Ref.of[IO, List[Event]](Nil).toResource)

  override def http4sMUnitClientFixture: SyncIO[FunFixture[Client[IO]]] = ResourceFunFixture[Client[IO]] {
    val observer = new McpObserver[IO]:

      def onSessionInit(sessionId: String, clientInfo: Implementation) =
        captured().update(_ :+ Event.SessionInit(sessionId, clientInfo))

      def onSessionTerminate(sessionId: String) =
        captured().update(_ :+ Event.SessionTerminate(sessionId))

      def onRequest(event: McpRequestEvent) =
        captured().update(_ :+ Event.Request(event))

    val buildServer: (String, SessionContext[IO]) => IO[Server[IO]] =
      (_, _) =>
        DefaultServer[IO](
          info = Implementation("t", "0"),
          capabilities = ServerCapabilities(),
          toolHandlers = List(echoTool)
        ).widen[Server[IO]]

    StreamableHttpTransport.routes[IO](buildServer, observer).map(_.orFail).map(Client.fromHttpApp)
  }

  override def munitFixtures: Seq[AnyFixture[?]] = super.munitFixtures :+ captured

  // ── Tests ─────────────────────────────────────────────────────────

  test(initializeRequest("claude-desktop"))
    .alias("onSessionInit fires with the clientInfo from the initialize request") { response =>
      for
        _    <- response.body.compile.drain
        recs <- captured().get
      yield
        assertEquals(response.status, Status.Ok)
        val inits = recs.collect { case e: Event.SessionInit => e }
        assertEquals(inits.length, 1, s"expected one SessionInit, got $recs")
        assertEquals(inits.head.clientInfo.name, "claude-desktop")
    }

  test(initializeRequest())
    .andThen(toolRequest("echo", _))
    .alias("onRequest fires for tools/call with the correct envelope and pre-extracted fields") { response =>
      for
        _    <- response.body.compile.drain
        recs <- captured().get
      yield
        val toolsCallEvents = recs.collect {
          case Event.Request(e) if e.method.contains("tools/call") => e
        }
        assertEquals(toolsCallEvents.length, 1, s"expected one tools/call event, got $recs")
        val event = toolsCallEvents.head
        assertEquals(event.method, Some("tools/call"))
        assertEquals(event.toolName, Some("echo"))
        assertEquals(event.id, Some(RequestId.fromLong(2L)))
        assertEquals(event.status, Status.Ok)
        assertEquals(event.isError, false)
        assert(event.sessionId.isDefined, "expected sessionId on tools/call event")
        assert(event.response.isDefined, "expected a JSON-RPC response envelope for tools/call")
        assert(event.requestBytes > 0L, "expected non-zero request bytes")
        assert(event.responseBytes > 0L, "expected non-zero response bytes")
    }

  test(initializeRequest()).andThen { response =>
    // Drain the initialize body so its `onRequest` event fires before we run the next request — the body's
    // `onFinalize` is what emits the event.
    response.body.compile.drain *> notificationRequest("notifications/initialized", response)
  }
    .alias("onRequest fires for both initialize and notification methods") { response =>
      for
        _    <- response.body.compile.drain
        recs <- captured().get
      yield
        val methods = recs.collect { case Event.Request(e) => e.method.getOrElse("-") }
        assertEquals(methods, List("initialize", "notifications/initialized"))
    }

  test(initializeRequest()).andThen { response =>
    val sidHeader = response.headers.get(ci"Mcp-Session-Id").map(_.head).getOrElse(fail("Missing Mcp-Session-Id"))
    IO.pure(
      Request[IO](Method.DELETE, uri"/mcp").putHeaders(Header.Raw(ci"Mcp-Session-Id", sidHeader.value))
    )
  }
    .alias("onSessionTerminate fires on DELETE /mcp") { response =>
      for
        _    <- response.body.compile.drain
        recs <- captured().get
      yield
        assertEquals(response.status, Status.Ok)
        val terminates = recs.collect { case e: Event.SessionTerminate => e }
        assertEquals(terminates.length, 1)
    }

  test(initializeRequest())
    .alias("requestBytes matches the exact number of bytes in the request body") { response =>
      for
        expected <- initializeRequest("test").body.compile.toList.map(_.length.toLong)
        _        <- response.body.compile.drain
        recs     <- captured().get
      yield
        val initEvent = recs.collect { case Event.Request(e) if e.method.contains("initialize") => e }.head
        assertEquals(initEvent.requestBytes, expected)
    }

  test("combineAll fans out callbacks to every registered observer") {
    val event = McpRequestEvent(
      request = Message.notification("ping", None),
      response = None,
      sessionId = None,
      requestBytes = 0L,
      responseBytes = 0L,
      status = Status.Accepted,
      startTime = java.time.Instant.EPOCH,
      elapsed = 0.millis
    )

    def counting(r: Ref[IO, Int]): McpObserver[IO] = new McpObserver[IO]:
      def onSessionInit(sessionId: String, clientInfo: Implementation) = IO.unit
      def onSessionTerminate(sessionId: String)                        = IO.unit
      def onRequest(e: McpRequestEvent)                                = r.update(_ + 1)

    for
      refA    <- Ref.of[IO, Int](0)
      refB    <- Ref.of[IO, Int](0)
      composed = McpObserver.combineAll(List(counting(refA), counting(refB)))
      _       <- composed.onRequest(event)
      a       <- refA.get
      b       <- refB.get
    yield
      assertEquals(a, 1)
      assertEquals(b, 1)
  }
