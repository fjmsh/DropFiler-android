package fjm.sh.dropfiler.ui

import android.graphics.Paint
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.TextViewCompat
import com.hchen.himiuix.MiuixAppBar
import fjm.sh.dropfiler.R
import kotlin.math.roundToInt

/**
 * Shows a decorative icon immediately before the large (expanded) title of a [MiuixAppBar].
 *
 * MiuixAppBar builds its large title as a single TextView in code, so the icon is attached to
 * that TextView as a start compound drawable instead of rebuilding the library's header. This
 * keeps the title text, typography, colour, height and collapse animation exactly as they are;
 * the icon simply fades and moves with the title. Compound drawables are not announced by
 * screen readers, so the title is still read once, as plain text.
 *
 * The icon is tinted with a theme colour attribute, by default ?attr/fsColorTitleIcon. That is
 * ?attr/fsColorOnAccentContainer, the colour of the folder icons in the destination list, which
 * ThemeHelper swaps for the Monet palette when dynamic colours are enabled. Theme changes recreate the activity (see BaseActivity), so the tint is
 * resolved again on creation.
 */
object AppBarTitleIcon {

	/** Icon size and spacing at the default 34sp title size. */
	private const val ICON_SIZE_DP = 28f
	private const val ICON_SPACING_DP = 10f
	private const val REFERENCE_TITLE_SP = 34f

	fun apply(appBar: MiuixAppBar, @DrawableRes icon: Int, @AttrRes tintAttr: Int = R.attr.fsColorTitleIcon): Boolean {
		val title = findLargeTitle(appBar) ?: return false
		val context = title.context
		val drawable = AppCompatResources.getDrawable(context, icon)?.mutate() ?: return false
		val wrapped = DrawableCompat.wrap(drawable)
		DrawableCompat.setTint(wrapped, resolveColor(title, tintAttr))

		val metrics = context.resources.displayMetrics
		val baseSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, ICON_SIZE_DP, metrics)
		// Unscaled size of the 34sp title (sp would apply the same, possibly non-linear, font scale)
		val referenceText = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, REFERENCE_TITLE_SP, metrics)
		// Grow with larger accessibility font sizes so the icon stays balanced with the text,
		// but never beyond the (fixed) height of the title row.
		val available = (title.layoutParams?.height ?: 0).takeIf { it > 0 } ?: Int.MAX_VALUE
		val size = (baseSize * (title.textSize / referenceText).coerceAtLeast(1f))
			.roundToInt().coerceAtMost(available)

		// The text is centred on its line box, which includes the descender and font padding, so
		// shift the icon to the optical centre of the capital letters.
		val offset = capCenterOffset(title)
		wrapped.setBounds(0, offset, size, size + offset)

		TextViewCompat.setCompoundDrawablesRelative(title, wrapped, null, null, null)
		title.compoundDrawablePadding = TypedValue.applyDimension(
			TypedValue.COMPLEX_UNIT_DIP, ICON_SPACING_DP, metrics).roundToInt()
		return true
	}

	/**
	 * The large title TextView. MiuixAppBar keeps it in a private field (`largeTitleView`), inside
	 * its private CollapsibleTitleLayout. The field is read first; if a future Miuix XML version
	 * renames it, the view is found by its container instead. Screen content (for example the
	 * built-in "DropFiler" destination row) and the collapsed Toolbar title are never matched.
	 */
	internal fun findLargeTitle(appBar: MiuixAppBar): TextView? {
		runCatching {
			val field = MiuixAppBar::class.java.getDeclaredField("largeTitleView").apply { isAccessible = true }
			(field.get(appBar) as? TextView)?.let { return it }
		}
		fun search(view: View): TextView? = when {
			view is Toolbar -> null
			view is TextView && view.parent?.javaClass?.simpleName == "CollapsibleTitleLayout" -> view
			view is ViewGroup && view.id == View.NO_ID ->
				(0 until view.childCount).firstNotNullOfOrNull { search(view.getChildAt(it)) }
			else -> null
		}
		return (0 until appBar.childCount).firstNotNullOfOrNull { search(appBar.getChildAt(it)) }
	}

	private fun resolveColor(view: View, @AttrRes attr: Int): Int {
		val value = TypedValue()
		view.context.theme.resolveAttribute(attr, value, true)
		return if (value.resourceId != 0) ContextCompat.getColor(view.context, value.resourceId) else value.data
	}

	private fun capCenterOffset(title: TextView): Int {
		val paint: Paint = title.paint
		val fm = paint.fontMetricsInt
		val lineCenter = if (title.includeFontPadding) (fm.top + fm.bottom) / 2f else (fm.ascent + fm.descent) / 2f
		val caps = Rect()
		paint.getTextBounds("H", 0, 1, caps)
		return (caps.exactCenterY() - lineCenter).roundToInt()
	}
}
