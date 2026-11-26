package dev.oms.remote.pairing

import android.content.Context
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.oms.remote.R

/**
 * Google code scanner (Play services UI): no camera permission, the camera runs in Play services.
 * The module is installed at app install time via the `com.google.mlkit.vision.DEPENDENCIES`
 * manifest entry.
 */
object QrScanner {
    sealed interface Outcome {
        data class Scanned(val text: String) : Outcome

        data object Cancelled : Outcome

        data class Error(val message: String) : Outcome
    }

    /**
     * Starts a scan. [onOutcome] runs on the main thread. The callback is not tied to the calling
     * activity's lifecycle (the scanner UI stops it), so it must not capture that activity.
     */
    fun scan(context: Context, onOutcome: (Outcome) -> Unit) {
        val appContext = context.applicationContext
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                val text = barcode.rawValue
                onOutcome(
                    if (text.isNullOrBlank()) Outcome.Error(appContext.getString(R.string.error_qr_empty))
                    else Outcome.Scanned(text),
                )
            }
            .addOnCanceledListener { onOutcome(Outcome.Cancelled) }
            .addOnFailureListener { e -> onOutcome(failure(appContext, e)) }
    }

    private fun failure(context: Context, e: Exception): Outcome = when ((e as? MlKitException)?.errorCode) {
        MlKitException.CANCELLED, MlKitException.CODE_SCANNER_CANCELLED -> Outcome.Cancelled
        MlKitException.UNAVAILABLE, MlKitException.CODE_SCANNER_UNAVAILABLE ->
            Outcome.Error(context.getString(R.string.error_scanner_unavailable))
        else -> Outcome.Error(context.getString(R.string.error_scan_failed, e.message ?: e.javaClass.simpleName))
    }
}
