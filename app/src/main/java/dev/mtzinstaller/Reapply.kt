package dev.mtzinstaller

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** Ярлык и плитка «Вернуть тему»: повторно применяют последнюю установленную тему. */
object Reapply {

    const val ACTION = "dev.mtzinstaller.REAPPLY"
    private const val SHORTCUT_ID = "reapply_last"

    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun shortcut(context: Context, title: String): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel("Вернуть тему")
            .setLongLabel("Применить «$title»")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_reapply))
            .setIntent(intent(context))
            .build()

    /** Обновляет ярлык в долгом нажатии на иконку приложения и плитку в шторке. */
    fun onThemeApplied(context: Context, title: String) {
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut(context, title)) }
        runCatching {
            TileService.requestListeningState(
                context, ComponentName(context, ReapplyTileService::class.java),
            )
        }
    }

    /** Предлагает лаунчеру закрепить ярлык на рабочем столе. */
    fun requestPin(context: Context, title: String): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context) &&
            runCatching {
                ShortcutManagerCompat.requestPinShortcut(context, shortcut(context, title), null)
            }.getOrDefault(false)
}
