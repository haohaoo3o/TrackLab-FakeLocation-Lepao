package io.github.haohaoo3o.tracklab.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断言契约见 docs/CONTRACTS.md（三态 + 默认态；蜂窝模拟仅限 DI 层，禁 Telephony 伪装）。
 */
class ConnectivityScenarioRepositoryTest {

    // ---------------------------------------------------------------- 三态 + 默认态（断言覆盖）

    @Test
    fun enumIsExactlyThreeScenariosPlusDefault() {
        assertEquals(
            listOf(
                ConnectivityScenarioRepository.Scenario.DEFAULT,
                ConnectivityScenarioRepository.Scenario.CELLULAR,
                ConnectivityScenarioRepository.Scenario.WIFI,
                ConnectivityScenarioRepository.Scenario.OFFLINE,
            ),
            ConnectivityScenarioRepository.Scenario.entries.toList(),
        )
    }

    @Test
    fun initialStateIsDefault() {
        val repo = ConnectivityScenarioRepository()
        assertEquals(ConnectivityScenarioRepository.Scenario.DEFAULT, repo.current())
        assertFalse("默认态不是模拟态", repo.isSimulated())
        assertNull("默认态不给模拟离线值（调用方走真实状态）", repo.simulatedOffline())
        assertEquals(ConnectivityScenarioRepository.Scenario.DEFAULT, repo.scenario.value)
    }

    @Test
    fun threeScenariosAreSelectable() {
        val repo = ConnectivityScenarioRepository()

        repo.select(ConnectivityScenarioRepository.Scenario.CELLULAR)
        assertEquals(ConnectivityScenarioRepository.Scenario.CELLULAR, repo.current())
        assertTrue(repo.isSimulated())
        assertEquals(false, repo.simulatedOffline())

        repo.select(ConnectivityScenarioRepository.Scenario.WIFI)
        assertEquals(ConnectivityScenarioRepository.Scenario.WIFI, repo.current())
        assertTrue(repo.isSimulated())
        assertEquals(false, repo.simulatedOffline())

        repo.select(ConnectivityScenarioRepository.Scenario.OFFLINE)
        assertEquals(ConnectivityScenarioRepository.Scenario.OFFLINE, repo.current())
        assertTrue(repo.isSimulated())
        assertEquals(true, repo.simulatedOffline())
    }

    @Test
    fun resetReturnsToDefault() {
        val repo = ConnectivityScenarioRepository()
        repo.select(ConnectivityScenarioRepository.Scenario.OFFLINE)
        repo.reset()
        assertEquals(ConnectivityScenarioRepository.Scenario.DEFAULT, repo.current())
        assertFalse(repo.isSimulated())
        assertNull(repo.simulatedOffline())
    }

    @Test
    fun selectDefaultExplicitlyIsAlsoDefault() {
        val repo = ConnectivityScenarioRepository()
        repo.select(ConnectivityScenarioRepository.Scenario.WIFI)
        repo.select(ConnectivityScenarioRepository.Scenario.DEFAULT)
        assertEquals(ConnectivityScenarioRepository.Scenario.DEFAULT, repo.current())
        assertNull(repo.simulatedOffline())
    }
}
