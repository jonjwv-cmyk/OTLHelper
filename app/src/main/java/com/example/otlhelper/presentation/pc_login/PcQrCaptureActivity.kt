package com.example.otlhelper.presentation.pc_login

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.otlhelper.R
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * §TZ-2.5.4 — Кастомная activity для QR-скана. Без красной laser-линии и
 * центральной reticle от стандартного ZXing — только camera preview и
 * simple framing rect (управляется через layout). Portrait locked
 * (`screenOrientation="portrait"` в manifest).
 */
class PcQrCaptureActivity : AppCompatActivity() {

    private lateinit var capture: CaptureManager
    private lateinit var barcodeView: DecoratedBarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pc_qr_capture)
        barcodeView = findViewById(R.id.zxing_barcode_scanner)
        // Hide laser line + reticle entirely.
        barcodeView.viewFinder?.visibility = View.GONE
        // Disable status text and beep.
        barcodeView.statusView?.visibility = View.GONE

        capture = CaptureManager(this, barcodeView)
        capture.initializeFromIntent(intent, savedInstanceState)
        capture.setShowMissingCameraPermissionDialog(false)
        capture.decode()
    }

    override fun onResume() { super.onResume(); capture.onResume() }
    override fun onPause() { super.onPause(); capture.onPause() }
    override fun onDestroy() { super.onDestroy(); capture.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); capture.onSaveInstanceState(outState)
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        capture.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        barcodeView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
}
