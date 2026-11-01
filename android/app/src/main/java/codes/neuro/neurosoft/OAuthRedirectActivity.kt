package codes.neuro.neurosoft

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Сервис (Notion, Linear…) вернул владельца после входа: codes.neuro.mnemo://oauth?code=…&state=….
 * Передаём ссылку в уже открытое приложение (без второй копии поверх браузера) и закрываемся.
 * Код без секрета PKCE, который хранится на сервере, бесполезен — поэтому принимать ссылку от кого угодно безопасно.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { uri ->
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra("open", "oauth").putExtra("oauth", uri.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
