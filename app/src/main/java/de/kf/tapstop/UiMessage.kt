package de.kf.tapstop

import android.content.Context
import androidx.annotation.StringRes
import java.io.IOException

/** Keeps long-lived status independent of the locale in effect when it was produced. */
class UiMessage(@param:StringRes val resource: Int, vararg val arguments: Any) {
    fun resolve(context: Context): String = context.getString(resource, *arguments)
}

internal open class UiFailure(
    val text: UiMessage, cause: Throwable? = null, detail: String? = cause?.message
) : IOException(detail, cause)

internal fun Throwable.uiMessage(@StringRes fallback: Int): UiMessage =
    (this as? UiFailure)?.text ?: UiMessage(fallback)

internal fun checkUi(condition: Boolean, @StringRes message: Int, vararg arguments: Any) {
    if (!condition) throw UiFailure(UiMessage(message, *arguments))
}
