package com.ibs.configapp.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.tasks.await

object FirebaseAuthHelper {
    private const val TAG = "FirebaseAuthHelper"
    private const val EXPECTED_PROJECT_ID = "ibs-system-cb7dc"

    fun verifyProjectConfig(context: Context): Boolean {
        return try {
            val app = FirebaseApp.getInstance()
            val projectId = app.options.projectId
            val appId = app.options.applicationId
            val matches = projectId == EXPECTED_PROJECT_ID
            Log.i(TAG, "Firebase projectId=$projectId appId=$appId matches=$matches")
            matches
        } catch (e: Exception) {
            Log.e(TAG, "Firebase not configured", e)
            false
        }
    }

    suspend fun ensureSignedIn(): String {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.uid?.let { return it }
        return try {
            Log.i(TAG, "FirebaseAuth.getInstance().signInAnonymously() ...")
            val result = FirebaseAuth.getInstance().signInAnonymously().await()
            val uid = result.user?.uid
                ?: throw IllegalStateException("Anonymous sign-in returned no user")
            Log.i(TAG, "Anonymous auth OK uid=$uid")
            uid
        } catch (e: FirebaseAuthException) {
            Log.e(TAG, "Anonymous auth failed: ${e.errorCode} - ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous auth failed: ${e.message}", e)
            throw e
        }
    }

    suspend fun testConnection(context: Context): Result<Unit> {
        return try {
            if (!verifyProjectConfig(context)) {
                return Result.failure(
                    IllegalStateException("Firebase project mismatch. Expected $EXPECTED_PROJECT_ID")
                )
            }
            ensureSignedIn()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Firebase connection test failed", e)
            Result.failure(e)
        }
    }
}
