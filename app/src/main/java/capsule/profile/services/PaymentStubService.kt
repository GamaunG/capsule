package capsule.profile.services

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Deliberately inert NFC payment stub. Some Android versions refuse to let
 * the user pick a payment app inside the work profile unless one also exists
 * in the main profile; this dummy service satisfies that check.
 */
class PaymentStubService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray? {
        // We do not handle anything.
        notifyUnhandled()
        return null
    }

    override fun onDeactivated(reason: Int) {
        // Nothing to clean up.
    }
}
