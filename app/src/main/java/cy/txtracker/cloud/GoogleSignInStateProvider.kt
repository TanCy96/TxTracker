package cy.txtracker.cloud

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around Google Sign-In. Owns the [GoogleSignInClient] configuration and the
 * token-fetch path, so production callers can be tested by swapping a fake at the
 * `SignInTokenSource` interface (introduced in the DriveClient task) without touching
 * Play Services machinery.
 *
 * The Drive AppData scope is non-sensitive — no app verification needed.
 */
@Singleton
class GoogleSignInStateProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : SignInTokenSource {
    val signInClient: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_APPDATA_SCOPE))
            .build()
        GoogleSignIn.getClient(context, options)
    }

    /** Email of the currently signed-in account, or null if not signed in. */
    override fun currentAccountEmail(): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    /** Returns an OAuth access token for Drive AppData. Throws [NotSignedInException] if
     *  not signed in or [AuthExpiredException] if refresh fails. */
    override suspend fun currentAccessToken(): String = withContext(Dispatchers.IO) {
        val account: GoogleSignInAccount = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw NotSignedInException()
        val androidAccount: Account = account.androidAccount()
            ?: throw NotSignedInException()
        try {
            GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$DRIVE_APPDATA_SCOPE")
        } catch (e: UserRecoverableAuthException) {
            throw AuthExpiredException(e)
        } catch (t: Throwable) {
            throw AuthExpiredException(t)
        }
    }

    private companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}

/** Internal helper: derive an `android.accounts.Account` from a GoogleSignInAccount.
 *  `GoogleAuthUtil.getToken` takes the platform Account, not the GSI account directly. */
private fun GoogleSignInAccount.androidAccount(): Account? =
    email?.let { Account(it, "com.google") }
