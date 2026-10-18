package com.example.sanporoad

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class AuthCallbackActivity : ComponentActivity() {
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = WalkPreferences(this)

        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val error = errorMessage
                    if (error == null) {
                        CircularProgressIndicator()
                        Text("Googleログインを完了しています…", Modifier.padding(top = 16.dp))
                    } else {
                        Text("Googleログインを完了できませんでした")
                        Text(error, Modifier.padding(top = 12.dp))
                        Button(
                            onClick = {
                                startActivity(Intent(this@AuthCallbackActivity, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                                })
                                finish()
                            },
                            modifier = Modifier.padding(top = 20.dp)
                        ) { Text("アプリに戻る") }
                    }
                }
            }
        }

        LaunchedEffectBridge.run(this) {
            try {
                val (userId, email) = SupabaseRouteSync.completeGoogleSignIn(intent)
                preferences.cloudSync = false
                preferences.activeAccountId = userId
                preferences.accountEmail = email
                startActivity(Intent(this@AuthCallbackActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                })
                finish()
            } catch (e: Exception) {
                errorMessage = e.message ?: "認証処理でエラーが発生しました"
            }
        }
    }
}

private object LaunchedEffectBridge {
    fun run(activity: ComponentActivity, block: suspend () -> Unit) {
        activity.lifecycleScope.launchWhenStarted { block() }
    }
}
