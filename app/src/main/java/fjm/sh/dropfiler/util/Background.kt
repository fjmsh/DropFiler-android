package fjm.sh.dropfiler.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs storage and network work off the main thread and delivers results on it.
 */
object Background {

	val executor: ExecutorService = Executors.newCachedThreadPool { r ->
		Thread(r, "DropFiler-io").apply { isDaemon = true }
	}

	private val main = Handler(Looper.getMainLooper())

	fun <T> run(work: () -> T, then: (T) -> Unit) {
		executor.execute {
			val result = work()
			main.post { then(result) }
		}
	}

	fun onMain(block: () -> Unit) {
		main.post(block)
	}
}
