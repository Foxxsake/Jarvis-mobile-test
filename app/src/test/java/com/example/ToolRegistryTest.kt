package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.example.data.AccessPolicy
import com.example.engine.ToolRegistry
import com.example.engine.ToolType
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPackageManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolRegistryTest {

    private lateinit var context: Context
    private lateinit var shadowPackageManager: ShadowPackageManager
    private lateinit var fakeAppPolicyDao: FakeAppPolicyDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowPackageManager = shadowOf(context.packageManager)
        fakeAppPolicyDao = FakeAppPolicyDao()
    }

    @Test
    fun `test policy enforcement updates tool policy`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val toolRegistry = ToolRegistry(context, fakeAppPolicyDao, ioDispatcher = testDispatcher)

        toolRegistry.setAppPolicy("github", AccessPolicy.BLOCK)
        toolRegistry.refreshTools()

        val updatedMockApp = toolRegistry.tools.value.find { it.id == "github" }
        assertNotNull(updatedMockApp)
        assertEquals(AccessPolicy.BLOCK, updatedMockApp?.policy)
        assertFalse("App should be disabled if policy is BLOCK", updatedMockApp!!.enabled)
    }

    @Test
    fun `test allow policy preserves tool enabled state`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val toolRegistry = ToolRegistry(context, fakeAppPolicyDao, ioDispatcher = testDispatcher)

        toolRegistry.setAppPolicy("github", AccessPolicy.ALLOW)
        toolRegistry.refreshTools()

        val tool = toolRegistry.tools.value.find { it.id == "github" }
        assertNotNull(tool)
        assertEquals(AccessPolicy.ALLOW, tool?.policy)
        assertTrue(tool!!.enabled)
    }

    @Test
    fun `test ask each time policy preserves tool enabled state`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val toolRegistry = ToolRegistry(context, fakeAppPolicyDao, ioDispatcher = testDispatcher)

        toolRegistry.setAppPolicy("github", AccessPolicy.ASK_EACH_TIME)
        toolRegistry.refreshTools()

        val tool = toolRegistry.tools.value.find { it.id == "github" }
        assertNotNull(tool)
        assertEquals(AccessPolicy.ASK_EACH_TIME, tool?.policy)
        assertTrue(tool!!.enabled)
    }
}
