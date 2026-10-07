package app.cayresim.core.boundary

import app.cayresim.core.boundary.contract.CameraBoundaryContract
import app.cayresim.core.boundary.fake.FakeCameraBoundary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Der Fake muss denselben Vertrag erfuellen wie der echte Adapter, sonst luegen die ViewModel-Tests. */
class FakeCameraBoundaryContractTest {
    @Test fun `Fake erfuellt den ganzen Vertrag`() = runTest {
        CameraBoundaryContract.all.forEach { (_, case) -> case(FakeCameraBoundary()) }
    }

    @Test fun `Fake faellt bei fehlender Extension zurueck`() = runTest {
        CameraBoundaryContract.unavailableModeFallsBack(FakeCameraBoundary(availableModes = emptySet()), PhotoMode.NIGHT)
    }
}
