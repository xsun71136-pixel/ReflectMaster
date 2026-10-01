package formatfa.reflectmaster.util

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import formatfa.reflectmaster.R
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Small helpers for the module's own (Material 3) UI. */
object Ui {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2) { r ->
        val t = Thread(r, "rm-ui")
        t.isDaemon = true
        t
    }

    /** Run [task] off the main thread, deliver the result back on it. */
    fun <T> async(task: Callable<T>, onUi: (T) -> Unit): Future<T> {
        // ExecutorService.submit is overloaded for Runnable and Callable; without
        // the explicit SAM constructor Kotlin binds the Runnable one and the
        // return type degrades to Future<*>.
        return pool.submit(Callable<T> {
            val result = task.call()
            main.post { onUi(result) }
            result
        })
    }

    fun postUi(body: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) body() else main.post { body() }
    }

    fun toast(ctx: Context, msg: String) {
        postUi {
            try {
                Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show()
            } catch (ignored: Throwable) {
            }
        }
    }

    fun alert(ctx: Context, title: String, message: String) {
        postUi {
            try {
                AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.close, null)
                    .show()
            } catch (ignored: Throwable) {
            }
        }
    }

    fun confirm(ctx: Context, title: String, message: String, onYes: () -> Unit) {
        postUi {
            try {
                AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setMessage(message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok) { _, _ -> onYes() }
                    .show()
            } catch (ignored: Throwable) {
            }
        }
    }

    fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    @Suppress("DEPRECATION")
    fun versionCode(ctx: Context): Int = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    } catch (t: Throwable) {
        0
    }

    fun toolbar(activity: AppCompatActivity, toolbar: MaterialToolbar, back: Boolean) {
        activity.setSupportActionBar(toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(back)
        if (back) {
            // Every sub-screen declares parentActivityName, so "up" == finish().
            toolbar.setNavigationOnClickListener { activity.finish() }
        }
    }

    fun setVisible(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun isPackageInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (t: PackageManager.NameNotFoundException) {
        false
    } catch (t: Throwable) {
        false
    }

    /** Launch helper for the LSPosed / Xposed manager. */
    fun openFrameworkManager(activity: Activity): Boolean {
        val candidates = listOf(
            "org.lsposed.manager",
            "de.robv.android.xposed.installer"
        )
        for (pkg in candidates) {
            try {
                val intent = activity.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                    return true
                }
            } catch (ignored: Throwable) {
            }
        }
        // LSPosed can be launched through its hidden action as well.
        try {
            val intent = android.content.Intent("org.lsposed.manager.LAUNCH")
                .setPackage("org.lsposed.manager")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            return true
        } catch (ignored: Throwable) {
        }
        return false
    }
}
