package capsule.profile.ui

import android.app.Activity
import android.os.Bundle

/**
 * Invisible launch target inside the work profile. Resuming a paused profile
 * is only possible by starting an activity there: the system replaces such
 * starts with its own unpause confirmation. Pointing that start at this
 * no-display activity unpause the profile without opening anything.
 */
class UnfreezeProxyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A no-display activity must finish before it is ever shown.
        finish()
    }
}
