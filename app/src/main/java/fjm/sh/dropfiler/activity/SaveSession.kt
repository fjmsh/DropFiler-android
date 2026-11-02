package fjm.sh.dropfiler.activity

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import fjm.sh.dropfiler.R
import fjm.sh.dropfiler.save.DestinationWriter
import fjm.sh.dropfiler.save.FailReason
import fjm.sh.dropfiler.save.ItemResult
import fjm.sh.dropfiler.save.PreparedItem
import fjm.sh.dropfiler.save.SaveCoordinator
import fjm.sh.dropfiler.save.SaveReport
import fjm.sh.dropfiler.save.WriteOutcome
import fjm.sh.dropfiler.util.Background
import fjm.sh.dropfiler.util.SaveNotifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The state of one share, kept across activity recreation (rotation, theme change) so a
 * save is never started twice and its result is reported exactly once.
 */
class SaveSession(application: Application) : AndroidViewModel(application) {

	enum class Phase { NEW, CHECKING, CHOOSING, PICKING_DOCUMENT, SAVING, DONE }

	var phase = Phase.NEW

	lateinit var coordinator: SaveCoordinator
	var prepared: List<PreparedItem> = emptyList()

	/** Save as: items still waiting for the system "create document" picker. */
	val documentQueue: MutableList<PreparedItem> = ArrayList()
	val documentResults: MutableList<ItemResult> = ArrayList()

	/** A destination waiting for the legacy storage permission (Android 6 - 9). */
	var pendingDestinationId: String? = null

	val cancelled = AtomicBoolean(false)

	/** Called on the main thread when the save is finished; set by the current activity. */
	var onFinished: (() -> Unit)? = null

	/** Set when the result came in while no activity was attached. */
	var finishPending = false

	private val app get() = getApplication<Application>()

	/** Saves all prepared items with [writer] in the background, then reports. */
	fun save(writer: DestinationWriter, location: String?, openWith: Boolean) {
		if (phase == Phase.SAVING || phase == Phase.DONE) return
		phase = Phase.SAVING
		val items = prepared
		Background.run({ coordinator.save(items, writer, cancelled) }) { report(it, location, openWith) }
	}

	/** Save as: writes one item into the document the user created. */
	fun saveDocument(item: PreparedItem, document: Uri?, then: () -> Unit) {
		if (document == null) {
			// Picker cancelled: this and the remaining items are not saved
			documentResults.add(ItemResult(item.name, WriteOutcome.Failed(FailReason.CANCELLED)))
			documentQueue.forEach { documentResults.add(ItemResult(it.name, WriteOutcome.Failed(FailReason.CANCELLED))) }
			documentQueue.clear()
			then()
			return
		}
		Background.run({ coordinator.save(listOf(item), coordinator.writerFor(document), cancelled) }) {
			documentResults.addAll(it.results)
			then()
		}
	}

	/** Shows the toast and result notification once, then lets the activity finish. */
	fun report(report: SaveReport, location: String?, openWith: Boolean) {
		if (phase == Phase.DONE) return
		phase = Phase.DONE
		val saved = report.saved
		val total = report.results.size
		val reason = report.failReason?.let { messageFor(it, openWith) }
		when {
			report.allSaved -> {
				toast(app.getString(R.string.toast_save_successful))
				SaveNotifier.notifySaved(app, saved.map { it.name }, location)
			}
			report.cancelled && saved.isEmpty() -> toast(app.getString(R.string.toast_save_cancelled))
			saved.isEmpty() -> {
				toast(reason ?: app.getString(R.string.toast_save_failed))
				SaveNotifier.notifyFailed(app, reason, report.results.map { it.requestedName })
			}
			else -> {
				toast(app.resources.getQuantityString(R.plurals.notification_partial_text, total, saved.size, total,
					SaveNotifier.describeLocation(app, location)))
				SaveNotifier.notifyPartial(app, saved.size, total, location, reason)
			}
		}
		finish()
	}

	/** Reports a failure that happened before anything was written. */
	fun fail(message: String) {
		if (phase == Phase.DONE) return
		phase = Phase.DONE
		toast(message)
		SaveNotifier.notifyFailed(app, message, emptyList())
		finish()
	}

	private fun finish() {
		onFinished?.invoke() ?: run { finishPending = true }
	}

	private fun toast(text: CharSequence) = Toast.makeText(app, text, Toast.LENGTH_SHORT).show()

	fun messageFor(reason: FailReason, openWith: Boolean): String = app.getString(when (reason) {
		FailReason.EXISTS -> R.string.toast_save_file_exists_fail
		FailReason.SELF_OVERWRITE -> R.string.toast_save_file_exists_self_abort
		FailReason.SOURCE_UNREADABLE -> if (openWith) R.string.toast_open_with_unreadable else R.string.toast_save_source_unreadable
		FailReason.DESTINATION_UNAVAILABLE -> R.string.toast_save_destination_unavailable
		FailReason.NO_PERMISSION -> R.string.toast_save_no_permission
		FailReason.NO_SPACE -> R.string.toast_save_no_space
		FailReason.TOO_MANY_DUPLICATES -> R.string.toast_save_too_many
		FailReason.CANCELLED -> R.string.toast_save_cancelled
		FailReason.NETWORK -> R.string.toast_save_network
		FailReason.IO -> R.string.toast_save_failed
	})
}
