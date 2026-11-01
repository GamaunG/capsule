package capsule.profile.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Receives ACTION_PROVISIONING_SUCCESSFUL from the system and hands the
 * finalization over to DummyActivity. More reliable than doing the work in a
 * broadcast receiver.
 */
class FinalizeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(applicationContext, DummyActivity::class.java)
        i.action = DummyActivity.FINALIZE_PROVISION
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        finish()
    }
}
