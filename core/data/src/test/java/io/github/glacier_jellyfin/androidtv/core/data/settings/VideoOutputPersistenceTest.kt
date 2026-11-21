package io.github.glacier_jellyfin.androidtv.core.data.settings

import androidx.datastore.core.DataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoOutputPersistenceTest {

    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun `video output survives closing and reopening the device store`() = runTest {
        val file = File(directory.root, "settings.json")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreFactory.create(SettingsSerializer, scope = firstScope, produceFile = { file })
            store.updateData { it.copy(videoOutput = VideoOutput.TextureView) }
        } finally {
            firstScope.cancel()
            firstScope.coroutineContext.job.join()
        }

        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreFactory.create(SettingsSerializer, scope = nextScope, produceFile = { file })
            assertEquals(VideoOutput.TextureView, store.data.first().videoOutput)
        } finally {
            nextScope.cancel()
            nextScope.coroutineContext.job.join()
        }
    }
}
