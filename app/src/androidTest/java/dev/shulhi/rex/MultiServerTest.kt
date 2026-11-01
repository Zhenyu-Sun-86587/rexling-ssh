package dev.shulhi.rex

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MultiServerTest {
    @Test fun twoHostsKeepIndependentStateAndRejectInputFromHiddenViews() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit owned-server fixtures required",args.containsKey("server_a") && args.containsKey("server_b"))
        val application=instrumentation.targetContext.applicationContext as Application
        val preferenceName="rexlet-multi-test-${System.nanoTime()}"
        val prefs=application.getSharedPreferences(preferenceName,0)
        val servers=listOf(ServerProfile.create("Linux test",args.getString("server_a")!!,"a"),ServerProfile.create("Mac test",args.getString("server_b")!!,"b"))
        prefs.edit().putString("servers",SavedServers.encode(servers)).putString("active_server","a")
            .putString("current_session:${servers[0].endpoint}",args.getString("session_a"))
            .putString("current_session:${servers[1].endpoint}",args.getString("session_b")).commit()
        fun <T> main(block: ()->T): T {
            val value=AtomicReference<T>();instrumentation.runOnMainSync {value.set(block())};return value.get()
        }
        fun waitFor(description: String,predicate: ()->Boolean) {
            val deadline=System.currentTimeMillis()+20000
            while(!main(predicate)) {
                assertTrue(description,System.currentTimeMillis()<deadline);Thread.sleep(30)
            }
        }
        val store=ViewModelStore()
        val model=main {RexModel(application,preferenceName).also {store.put("multi",it)}}
        try {
            main {model.connectServer("a")}
            val a=main {model.activeClient}
            waitFor("Linux fixture ready") {a.state.value.connected && a.state.value.selectedSession==args.getString("session_a") && a.state.value.screen!=null}
            main {model.connectServer("b")}
            val b=main {model.activeClient}
            waitFor("Mac fixture ready") {b.state.value.connected && b.state.value.selectedSession==args.getString("session_b") && b.state.value.screen!=null}
            assertTrue(main {a.state.value.connected})
            main {b.takeControl()};waitFor("Mac owns its own pane") {b.state.value.controlled}
            main {b.type("B_ONLY\n")};waitFor("Mac received input") {b.state.value.screen?.plainText()?.contains("B_ONLY")==true}
            main {model.connectServer("a")}
            assertSame(a,main {model.activeClient})
            assertTrue(main {b.state.value.connected})
            main {b.type("HIDDEN_INPUT\n");a.takeControl()}
            waitFor("Linux owns its own pane") {a.state.value.controlled}
            main {a.type("A_ONLY\n")};waitFor("Linux received input") {a.state.value.screen?.plainText()?.contains("A_ONLY")==true}
            assertFalse(main {a.state.value.screen?.plainText()?.contains("B_ONLY")==true})
            main {model.connectServer("b");b.takeControl()};waitFor("Mac control restored") {b.state.value.controlled}
            main {b.type("CHECK_B\n")};waitFor("Mac channel advanced") {b.state.value.screen?.plainText()?.contains("CHECK_B")==true}
            assertFalse(main {b.state.value.screen?.plainText()?.contains("HIDDEN_INPUT")==true})
            main {model.disconnectServer("a")};waitFor("Only Linux disconnected") {a.state.value.phase=="disconnected"}
            main {b.type("SURVIVES\n")};waitFor("Mac connection survived") {b.state.value.connected && b.state.value.screen?.plainText()?.contains("SURVIVES")==true}
            main {model.pause()};waitFor("Mac lifecycle paused") {b.state.value.phase=="paused"}
            main {model.resume()};waitFor("Mac lifecycle resumed") {b.state.value.connected && b.state.value.selectedSession==args.getString("session_b")}
            assertSame(b,main {model.activeClient})
            assertEquals(setOf("b"),prefs.getStringSet("connected_servers",emptySet()))
        } finally {
            main {store.clear()};application.deleteSharedPreferences(preferenceName)
        }
    }
}
