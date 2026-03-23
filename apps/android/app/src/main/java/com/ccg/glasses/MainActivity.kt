package com.ccg.glasses

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * Entry point activity. Scans a QR code containing a ccg:// connection URI,
 * then launches [HudActivity] with the parsed WebSocket URL and auth token.
 *
 * QR code format: ccg://connect?url=ws://192.168.1.100:9200&token=abc123
 *
 * This is a regular [AppCompatActivity], not a BaseMirrorActivity, because
 * the camera preview doesn't need binocular mirroring -- it only runs during
 * the initial pairing step.
 *
 * On resume, if a previous session exists in SharedPreferences, the user
 * can tap to reconnect without scanning again.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusText: android.widget.TextView

    private val prefsName = "ccg_session"
    private val prefKeyUrl = "ws_url"
    private val prefKeyToken = "ws_token"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        barcodeView = findViewById(R.id.barcodeView)
        statusText = findViewById(R.id.statusText)

        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let { handleScan(it) }
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()

        // Check for saved session
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val savedUrl = prefs.getString(prefKeyUrl, null)
        if (savedUrl != null) {
            statusText.text = "Tap to reconnect"
        }
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return barcodeView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_UP) {
            // Tap to reconnect with saved session
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            val savedUrl = prefs.getString(prefKeyUrl, null)
            val savedToken = prefs.getString(prefKeyToken, null)
            if (savedUrl != null) {
                launchHud(savedUrl, savedToken ?: "")
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleScan(raw: String) {
        val uri = try {
            Uri.parse(raw)
        } catch (_: Exception) {
            statusText.text = "Invalid QR code"
            return
        }

        if (uri.scheme != "ccg" || uri.host != "connect") {
            statusText.text = "Invalid QR code"
            return
        }

        val url = uri.getQueryParameter("url")
        val token = uri.getQueryParameter("token")

        if (url.isNullOrBlank()) {
            statusText.text = "Invalid QR code: missing url"
            return
        }

        // Save session for quick reconnect
        getSharedPreferences(prefsName, MODE_PRIVATE).edit().apply {
            putString(prefKeyUrl, url)
            putString(prefKeyToken, token ?: "")
            apply()
        }

        launchHud(url, token ?: "")
    }

    private fun launchHud(url: String, token: String) {
        val intent = Intent(this, HudActivity::class.java).apply {
            putExtra("url", url)
            putExtra("token", token)
        }
        startActivity(intent)
    }
}
