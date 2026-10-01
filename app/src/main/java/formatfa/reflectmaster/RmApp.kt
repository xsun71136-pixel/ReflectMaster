package formatfa.reflectmaster

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import formatfa.reflectmaster.core.ConfigStore
import formatfa.reflectmaster.core.RmLog

/**
 * Module application.
 *
 * Also the place where the 1.x anti-tamper check lived: `MainActivity` compared
 * the app label against a char-array obfuscated copy of "反射大师" and called
 * `s.substring(888)` to crash the process when somebody renamed the app. That is
 * gone; a debugging tool that sabotages its own users is not a feature.
 */
class RmApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            // Follow the system dark theme; the console has its own palette.
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } catch (t: Throwable) {
            RmLog.w("夜间模式设置失败: " + t.message)
        }
        // Touch the store once so the preference file exists before any target
        // process tries to read it through XSharedPreferences.
        try {
            ConfigStore.load(this)
        } catch (t: Throwable) {
            RmLog.w("配置初始化失败: " + t.message)
        }
    }
}
