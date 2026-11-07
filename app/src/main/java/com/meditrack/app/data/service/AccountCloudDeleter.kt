package com.meditrack.app.data.service

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class CloudDeletionResult(
    val success: Boolean,
    val deletedItems: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val requiresReauth: Boolean = false
)

interface AccountCloudDeleter {
    suspend fun deleteAccountData(): CloudDeletionResult
}

@Singleton
class FirebaseAccountCloudDeleter @Inject constructor(
    @ApplicationContext private val context: Context
) : AccountCloudDeleter {

    override suspend fun deleteAccountData(): CloudDeletionResult {
        // If Firebase is not configured or user is not logged into Firebase Auth
        if (FirebaseApp.getApps(context).isEmpty() || FirebaseAuth.getInstance().currentUser == null) {
            return CloudDeletionResult(
                success = true,
                deletedItems = listOf("Local session only (no remote cloud data)")
            )
        }

        return try {
            val functions = FirebaseFunctions.getInstance()
            val result = functions.getHttpsCallable("deleteAccountData").call().await()
            val data = result.data as? Map<*, *> ?: emptyMap<Any, Any>()
            val success = data["success"] as? Boolean ?: false
            val deletedItems = (data["deletedItems"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val errors = (data["errors"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val requiresReauth = errors.any {
                it.contains("recent-login", ignoreCase = true) || it.contains("reauthenticate", ignoreCase = true)
            }

            CloudDeletionResult(
                success = success,
                deletedItems = deletedItems,
                errors = errors,
                requiresReauth = requiresReauth
            )
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            CloudDeletionResult(
                success = false,
                errors = listOf(e.message ?: "Re-authentication required."),
                requiresReauth = true
            )
        } catch (e: FirebaseFunctionsException) {
            val isReauth = e.code == FirebaseFunctionsException.Code.UNAUTHENTICATED ||
                    e.message?.contains("recent-login", ignoreCase = true) == true
            CloudDeletionResult(
                success = false,
                errors = listOf(e.message ?: "Cloud deletion failed: ${e.code}"),
                requiresReauth = isReauth
            )
        } catch (e: Exception) {
            CloudDeletionResult(
                success = false,
                errors = listOf(e.message ?: "Network or remote failure during account deletion.")
            )
        }
    }
}
