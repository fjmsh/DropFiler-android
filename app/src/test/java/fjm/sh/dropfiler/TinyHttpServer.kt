package fjm.sh.dropfiler

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A minimal HTTP/1.1 server on localhost for link download tests (the JDK's
 * com.sun.net.httpserver is not on the Android unit test classpath).
 */
class TinyHttpServer {

	class Response(val out: OutputStream) {
		fun send(code: Int, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, length: Long? = body?.size?.toLong()) {
			val sb = StringBuilder("HTTP/1.1 $code X\r\nConnection: close\r\n")
			headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
			length?.let { sb.append("Content-Length: $it\r\n") }
			sb.append("\r\n")
			out.write(sb.toString().toByteArray())
			body?.let { out.write(it) }
			out.flush()
		}
	}

	private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
	private val routes = HashMap<String, (Response) -> Unit>()
	@Volatile private var running = true

	val port get() = server.localPort

	fun route(path: String, handler: (Response) -> Unit) { routes[path] = handler }

	fun start() {
		thread(isDaemon = true) {
			while (running) {
				val socket = try { server.accept() } catch (e: Exception) { break }
				thread(isDaemon = true) { handle(socket) }
			}
		}
	}

	private fun handle(socket: Socket) {
		socket.use { s ->
			val reader = s.getInputStream().bufferedReader()
			val requestLine = reader.readLine() ?: return
			while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
			val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
			val response = Response(s.getOutputStream())
			try {
				routes[path]?.invoke(response) ?: response.send(404, body = ByteArray(0))
			} catch (e: Exception) {
				// Client went away
			}
		}
	}

	fun stop() {
		running = false
		server.close()
	}
}
