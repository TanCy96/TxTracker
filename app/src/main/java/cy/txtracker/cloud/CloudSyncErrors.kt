package cy.txtracker.cloud

/** Sealed hierarchy used by [DriveClient] and consumed by [CloudSyncWorker] to decide
 *  whether to retry (transient) or fail (permanent) a sync attempt. */
sealed class CloudSyncException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** No signed-in Google account, or Sign-In returned null. Caller surfaces a re-auth UI. */
class NotSignedInException : CloudSyncException("No signed-in account")

/** Token refresh failed (consent revoked, account deleted, etc.). User must re-sign-in. */
class AuthExpiredException(cause: Throwable? = null) :
    CloudSyncException("Sign-in expired — please re-authenticate", cause)

/** Network blip, 5xx, 429. WorkManager should retry with backoff. */
class TransientNetworkException(message: String, cause: Throwable? = null) :
    CloudSyncException(message, cause)

/** Drive API returned a 4xx other than 401/403/429. Permanent — surface the message. */
class DriveApiException(val httpCode: Int, message: String) :
    CloudSyncException("Drive API error $httpCode: $message")
