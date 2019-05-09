package io.particle.mesh.setup.flow

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import com.snakydesign.livedataextensions.liveDataOf
import com.snakydesign.livedataextensions.nonNull
import com.squareup.okhttp.OkHttpClient
import io.particle.android.sdk.cloud.ParticleCloud
import io.particle.android.sdk.cloud.ParticleDevice
import io.particle.mesh.bluetooth.connecting.BluetoothConnectionManager
import io.particle.mesh.common.QATool
import io.particle.mesh.common.android.livedata.awaitUpdate
import io.particle.mesh.common.android.livedata.castAndPost
import io.particle.mesh.common.android.livedata.nonNull
import io.particle.mesh.ota.FirmwareUpdateManager
import io.particle.mesh.setup.BarcodeData.CompleteBarcodeData
import io.particle.mesh.setup.connection.ProtocolTransceiverFactory
import io.particle.mesh.setup.connection.security.SecurityManager
import io.particle.mesh.setup.flow.DialogSpec.StringDialogSpec
import io.particle.mesh.setup.flow.ExceptionType.ERROR_FATAL
import io.particle.mesh.setup.flow.ExceptionType.EXPECTED_FLOW
import io.particle.mesh.setup.flow.FlowType.CELLULAR_FLOW
import io.particle.mesh.setup.flow.FlowType.CONTROL_PANEL_CELLULAR_SIM_DEACTIVATE
import io.particle.mesh.setup.flow.FlowType.CONTROL_PANEL_CELLULAR_SIM_REACTIVATE
import io.particle.mesh.setup.flow.FlowType.CONTROL_PANEL_CELLULAR_SET_NEW_DATA_LIMIT
import io.particle.mesh.setup.flow.FlowType.CONTROL_PANEL_CELLULAR_SIM_ACTION_POSTFLOW
import io.particle.mesh.setup.flow.FlowType.CONTROL_PANEL_CELLULAR_SIM_UNPAUSE
import io.particle.mesh.setup.flow.FlowType.CONTROL_PANEL_WIFI_INSPECT_NETWORK_FLOW
import io.particle.mesh.setup.flow.FlowType.CONTROL_PANEL_CELLULAR_PRESENT_OPTIONS_FLOW
import io.particle.mesh.setup.flow.FlowType.CONTROL_PANEL_MESH_INSPECT_NETWORK_FLOW
import io.particle.mesh.setup.flow.FlowType.ETHERNET_FLOW
import io.particle.mesh.setup.flow.FlowType.INTERNET_CONNECTED_PREFLOW
import io.particle.mesh.setup.flow.FlowType.JOINER_FLOW
import io.particle.mesh.setup.flow.FlowType.NETWORK_CREATOR_POSTFLOW
import io.particle.mesh.setup.flow.FlowType.PREFLOW
import io.particle.mesh.setup.flow.FlowType.SINGLE_TASK_POSTFLOW
import io.particle.mesh.setup.flow.FlowType.STANDALONE_POSTFLOW
import io.particle.mesh.setup.flow.FlowType.WIFI_FLOW
import io.particle.mesh.setup.flow.context.SetupContexts
import io.particle.mesh.setup.flow.setupsteps.*
import kotlinx.coroutines.delay
import mu.KotlinLogging


private const val FLOW_RETRIES = 10


fun buildFlowManager(
    app: Application,
    cloud: ParticleCloud,
    dialogTool: DialogTool,
    flowUi: FlowUiDelegate,
    flowTerminator: MeshFlowTerminator,
    okHttpClient: OkHttpClient = OkHttpClient(),
    securityManager: SecurityManager = SecurityManager()
): MeshFlowRunner {
    val btConMan = BluetoothConnectionManager(app)
    val transceiverFactory = ProtocolTransceiverFactory(securityManager)
    val deviceConnector = DeviceConnector(cloud, btConMan, transceiverFactory)

    val fwUpdateManager = FirmwareUpdateManager(cloud, okHttpClient)

    val deps = StepDeps(
        cloud,
        deviceConnector,
        fwUpdateManager,
        dialogTool,
        flowUi
    )

    return MeshFlowRunner(deps, flowTerminator, app)
}


class MeshFlowTerminator {

    val shouldTerminateFlowLD: LiveData<Boolean> = liveDataOf(false)

    fun terminateFlow() {
        shouldTerminateFlowLD.castAndPost(true)
    }
}


class StepDeps(
    val cloud: ParticleCloud,
    val deviceConnector: DeviceConnector,
    val firmwareUpdateManager: FirmwareUpdateManager,
    val dialogTool: DialogTool,
    val flowUi: FlowUiDelegate
)


enum class FlowIntent {
    FIRST_TIME_SETUP,
    SINGLE_TASK_FLOW
}


enum class FlowType {
    PREFLOW,
    JOINER_FLOW,
    INTERNET_CONNECTED_PREFLOW,
    ETHERNET_FLOW,
    WIFI_FLOW,
    CELLULAR_FLOW,
    NETWORK_CREATOR_POSTFLOW,
    STANDALONE_POSTFLOW,
    CONTROL_PANEL_CELLULAR_PRESENT_OPTIONS_FLOW,
    CONTROL_PANEL_CELLULAR_SET_NEW_DATA_LIMIT,
    CONTROL_PANEL_CELLULAR_SIM_DEACTIVATE,
    CONTROL_PANEL_CELLULAR_SIM_REACTIVATE,
    CONTROL_PANEL_CELLULAR_SIM_UNPAUSE,
    CONTROL_PANEL_CELLULAR_SIM_ACTION_POSTFLOW,
    CONTROL_PANEL_MESH_INSPECT_NETWORK_FLOW,
    CONTROL_PANEL_WIFI_INSPECT_NETWORK_FLOW,
    SINGLE_TASK_POSTFLOW
}


class MeshFlowRunner(
    private val deps: StepDeps,
    private val flowTerminator: MeshFlowTerminator,
    private val everythingNeedsAContext: Application
) {

    private val log = KotlinLogging.logger {}

    // set up a placeholder listener that will be overwritten when a new flow starts
    var listener: FlowRunnerUiListener = FlowRunnerUiListener(SetupContexts())

    private var contexts: SetupContexts? = null

    @MainThread
    fun startFlow() {
        log.info { "startFlow()" }
        initNewFlow(FlowIntent.FIRST_TIME_SETUP)

        contexts?.currentFlow = listOf(FlowType.PREFLOW)

        runCurrentFlow()
    }

    @MainThread
    fun startNewFlowWithCommissioner() {
        log.info { "startNewFlowWithCommissioner()" }

        val oldContexts = contexts!!
        initNewFlow(FlowIntent.FIRST_TIME_SETUP)
        val newContexts = contexts!!
    }

    @MainThread
    fun startControlPanelWifiConfigFlow(device: ParticleDevice, barcode: CompleteBarcodeData) {
        initNewFlow(FlowIntent.SINGLE_TASK_FLOW)
        val ctxs = contexts!!

        ctxs.updateGetReadyNextButtonClicked(true)

        ctxs.targetDevice.deviceId = device.id
        ctxs.cloud.updatePricingImpactConfirmed(true)
        ctxs.cloud.updateShouldConnectToDeviceCloudConfirmed(true)
        val deviceName = ctxs.targetDevice.transceiverLD.value?.bleBroadcastName
        ctxs.singleStepCongratsMessage = "Wi-Fi credentials were successfully added to $deviceName"

        ctxs.scopes.onWorker {
            ctxs.targetDevice.updateBarcode(barcode, deps.cloud)
            ctxs.targetDevice.barcode.nonNull(ctxs.scopes).awaitUpdate(ctxs.scopes)

            ctxs.currentFlow = listOf(
                FlowType.PREFLOW,
                FlowType.WIFI_FLOW
            )
            runCurrentFlow()
        }
    }

    @MainThread
    fun startControlPanelInspectCurrentWifiNetworkFlow(
        device: ParticleDevice,
        barcode: CompleteBarcodeData
    ) {
        initNewFlow(FlowIntent.SINGLE_TASK_FLOW)
        val ctxs = contexts!!

        ctxs.updateGetReadyNextButtonClicked(true)

        ctxs.targetDevice.deviceId = device.id

        ctxs.scopes.onWorker {
            ctxs.targetDevice.updateBarcode(barcode, deps.cloud)
            ctxs.targetDevice.barcode.nonNull(ctxs.scopes).awaitUpdate(ctxs.scopes)

            ctxs.currentFlow = listOf(
                FlowType.PREFLOW,
                FlowType.CONTROL_PANEL_WIFI_INSPECT_NETWORK_FLOW
            )
            runCurrentFlow()
        }
    }

    @MainThread
    fun startShowControlPanelCellularOptionsFlow(device: ParticleDevice) {
        initNewFlow(FlowIntent.SINGLE_TASK_FLOW)
        val ctxs = contexts!!

        ctxs.updateGetReadyNextButtonClicked(true)

        ctxs.targetDevice.deviceId = device.id
        ctxs.targetDevice.iccid = device.iccid

        ctxs.scopes.onWorker {
            ctxs.currentFlow = listOf(
                FlowType.CONTROL_PANEL_CELLULAR_PRESENT_OPTIONS_FLOW
            )
            runCurrentFlow()
        }
    }

    @MainThread
    fun startControlPanelMeshInspectCurrentNetworkFlow(
        device: ParticleDevice,
        barcode: CompleteBarcodeData
    ) {
        initNewFlow(FlowIntent.SINGLE_TASK_FLOW)
        val ctxs = contexts!!

        ctxs.updateGetReadyNextButtonClicked(true)

        ctxs.targetDevice.deviceId = device.id

        ctxs.scopes.onWorker {

            ctxs.targetDevice.updateBarcode(barcode, deps.cloud)
            ctxs.targetDevice.barcode.nonNull(ctxs.scopes).awaitUpdate(ctxs.scopes)

            ctxs.currentFlow = listOf(
                FlowType.PREFLOW,
                FlowType.CONTROL_PANEL_MESH_INSPECT_NETWORK_FLOW
            )
            runCurrentFlow()
        }
    }

    @MainThread
    fun startSimDeactivateFlow(device: ParticleDevice) {
//        CONTROL_PANEL_CELLULAR_SIM_ACTION_POSTFLOW
    }

    @MainThread
    fun startSimReactivateFlow(device: ParticleDevice) {
//        CONTROL_PANEL_CELLULAR_SIM_ACTION_POSTFLOW
    }

    @MainThread
    fun startSimUnpauseFlow(device: ParticleDevice) {
//        CONTROL_PANEL_CELLULAR_SIM_ACTION_POSTFLOW
    }

    @MainThread
    fun startSetNewDataLimitFlow(device: ParticleDevice) {
        initNewFlow(FlowIntent.SINGLE_TASK_FLOW)
        val ctxs = contexts!!

        ctxs.updateGetReadyNextButtonClicked(true)

        ctxs.targetDevice.deviceId = device.id
        ctxs.targetDevice.iccid = device.iccid

        ctxs.scopes.onWorker {
            ctxs.currentFlow = listOf(
                FlowType.CONTROL_PANEL_CELLULAR_SET_NEW_DATA_LIMIT
            )
            runCurrentFlow()
        }
    }

    // FIXME: disambiguate this vs ending the current *flow*
    fun endSetup() {
        log.info { "endSetup()" }
        flowTerminator.terminateFlow()
    }

    fun endCurrentFlow() {
        log.info { "endCurrentFlow()" }
        contexts?.let {
            log.info { "Clearing state and cancelling scopes..." }
            it.clearState()
        }
    }

    fun getString(@StringRes stringRes: Int): String {
        return everythingNeedsAContext.getString(stringRes)
    }

    fun getString(@StringRes stringRes: Int, vararg formatArgs: String): String {
        return everythingNeedsAContext.getString(stringRes, formatArgs)
    }

    private fun initNewFlow(intent: FlowIntent) {
        val ctxs = SetupContexts()
        contexts = ctxs
        listener = FlowRunnerUiListener(ctxs)
        ctxs.flowIntent = intent
    }

    private fun runCurrentFlow() {
        log.info { "runCurrentFlow()" }

        fun assembleSteps(ctxs: SetupContexts): List<MeshSetupStep> {
            val flow = ctxs.currentFlow.toMutableList()
            log.info { "assembleSteps(), steps=$flow" }
            val steps = mutableListOf<MeshSetupStep>()
            for (type in flow) {
                val newSteps = getFlowSteps(type)
                steps.addAll(newSteps)
            }
            return steps
        }

        suspend fun doRunFlow(flowSteps: List<MeshSetupStep>) {
            for (step in flowSteps) {
                contexts?.let {
                    step.runStep(it, it.scopes)
                }
            }
        }

        val ctxs = contexts
        ctxs?.scopes?.onWorker {
            var error: Exception? = null

            var i = 0
            while (i < FLOW_RETRIES) {
                try {
                    val steps = assembleSteps(ctxs)
                    doRunFlow(steps)
                    log.info { "FLOW COMPLETED SUCCESSFULLY!" }
                    return@onWorker

                } catch (ex: Exception) {
                    deps.flowUi.showGlobalProgressSpinner(false)

                    if (ex is MeshSetupFlowException && ex.severity == EXPECTED_FLOW) {
                        log.info { "Received EXPECTED_FLOW exception; retrying." }
                        continue  // avoid incrementing the counter, since this was expected flow
                    }

                    if (ex is MeshSetupFlowException && ex.severity == ERROR_FATAL) {
                        log.info(ex) { "Hit fatal error, exiting setup: " }
                        QATool.log(ex.message ?: "(no message)")
                        endSetup()
                        return@onWorker
                    }

                    delay(1000)
                    QATool.report(ex)
                    error = ex

                    i++
                }
            }

            // we got through all the retries and we finally failed on a specific error.
            // Quit and notify the user of the error we died on
            quitSetupfromError(ctxs.scopes, error)
        }
    }

    private suspend fun quitSetupfromError(scopes: Scopes, ex: Exception?) {
        val preMsg = ex?.message ?: "Setup has encountered an error and cannot continue."
        scopes.withMain {
            deps.dialogTool.newDialogRequest(StringDialogSpec(preMsg))
            deps.dialogTool.clearDialogResult()
            deps.dialogTool.dialogResultLD.nonNull().awaitUpdate(scopes)
            endSetup()
        }
    }

    private fun getFlowSteps(flowType: FlowType): List<MeshSetupStep> {

        return when (flowType) {

            PREFLOW -> listOf(
                StepGetTargetDeviceInfo(deps.flowUi),
                StepShowGetReadyForSetup(deps.flowUi),
                StepConnectToTargetDevice(deps.flowUi, deps.deviceConnector),
                StepEnsureCorrectEthernetFeatureStatus(),
                StepEnsureLatestFirmware(deps.flowUi, deps.firmwareUpdateManager),
                StepFetchDeviceId(),
                StepGetAPINetworks(deps.cloud),
                StepCheckIfTargetDeviceShouldBeClaimed(deps.cloud, deps.flowUi),
                StepEnsureTargetDeviceIsNotOnMeshNetwork(deps.cloud, deps.dialogTool),
                StepSetClaimCode(),
                StepShowTargetPairingSuccessful(deps.flowUi),
                StepDetermineFlowAfterPreflow(deps.flowUi)
            )


            JOINER_FLOW -> listOf(
                StepCollectMeshNetworkToJoinSelection(deps.flowUi),
                StepCollectCommissionerDeviceInfo(deps.flowUi),
                StepEnsureCommissionerConnected(deps.flowUi, deps.deviceConnector),
                StepEnsureCommissionerNetworkMatches(deps.flowUi, deps.cloud),
                StepCollectMeshNetworkToJoinPassword(deps.flowUi),
                StepShowJoiningMeshNetworkUi(deps.flowUi),
                StepJoinSelectedNetwork(deps.cloud),
                StepSetSetupDone(),
                StepEnsureListeningStoppedForBothDevices(),
                StepEnsureConnectionToCloud(),
                StepCheckDeviceGotClaimed(deps.cloud),
                StepSetNewDeviceName(deps.flowUi, deps.cloud),
                StepPublishDeviceSetupDoneEvent(deps.cloud),
                StepShowJoinerSetupFinishedUi(deps.flowUi)
            )


            INTERNET_CONNECTED_PREFLOW -> listOf(
                StepAwaitSetupStandAloneOrWithNetwork(deps.cloud, deps.flowUi)
            )


            ETHERNET_FLOW -> listOf(
                StepShowPricingImpact(deps.flowUi, deps.cloud),
                StepShowConnectingToDeviceCloudUi(deps.flowUi),
                StepSetSetupDone(),
                StepEnsureListeningStoppedForBothDevices(),
                StepEnsureEthernetHasIpAddress(deps.flowUi),
                StepEnsureConnectionToCloud(),
                StepCheckDeviceGotClaimed(deps.cloud),
                StepPublishDeviceSetupDoneEvent(deps.cloud)
            )


            WIFI_FLOW -> listOf(
                StepShowPricingImpact(deps.flowUi, deps.cloud),
                StepShowShouldConnectToDeviceCloudConfirmation(deps.flowUi),
                StepCollectUserWifiNetworkSelection(deps.flowUi),
                StepCollectSelectedWifiNetworkPassword(deps.flowUi),
                StepEnsureSelectedWifiNetworkJoined(deps.flowUi),
                // FIXME: this last sequence is virtually the same across setup flows -- unify them.
                StepSetSetupDone(),
                StepEnsureListeningStoppedForBothDevices(),
                StepShowWifiConnectingToDeviceCloudUi(deps.flowUi),
                StepEnsureConnectionToCloud(),
                StepCheckDeviceGotClaimed(deps.cloud),
                StepShowConnectedToCloudSuccessUi(deps.flowUi),
                StepPublishDeviceSetupDoneEvent(deps.cloud)
            )


            CELLULAR_FLOW -> listOf(
                StepEnsureCardOnFile(deps.flowUi, deps.cloud),
                StepFetchIccid(),
                StepEnsureSimActivationStatusUpdated(deps.cloud),
                StepShowPricingImpact(deps.flowUi, deps.cloud),
                StepShowShouldConnectToDeviceCloudConfirmation(deps.flowUi),
                StepShowCellularConnectingToDeviceCloudUi(deps.flowUi),
                StepEnsureSimActivated(deps.cloud),
                StepSetSetupDone(),
                StepEnsureListeningStoppedForBothDevices(),
                StepEnsureConnectionToCloud(),
                StepCheckDeviceGotClaimed(deps.cloud),
                StepShowConnectedToCloudSuccessUi(deps.flowUi),
                StepPublishDeviceSetupDoneEvent(deps.cloud)
            )


            NETWORK_CREATOR_POSTFLOW -> listOf(
                StepSetNewDeviceName(deps.flowUi, deps.cloud),
                StepGetNewMeshNetworkName(deps.flowUi),
                StepGetNewMeshNetworkPassword(deps.flowUi),
                StepShowCreateNewMeshNetworkUi(deps.flowUi),
                StepCreateNewMeshNetworkOnCloud(deps.cloud),
                StepCreateNewMeshNetworkOnLocalDevice(),
                StepEnsureConnectionToCloud(),
                StepShowCreateNetworkFinished(deps.flowUi)
            )


            CONTROL_PANEL_WIFI_INSPECT_NETWORK_FLOW -> listOf(
                StepEnsureListeningStoppedForBothDevices(),
                StepInspectCurrentWifiNetwork(deps.flowUi)
            )


            CONTROL_PANEL_CELLULAR_PRESENT_OPTIONS_FLOW -> listOf(
                StepFetchIccidFromCloud(deps.cloud, deps.flowUi),
                StepFetchFullSimData(deps.cloud, deps.flowUi),
                StepShowCellularOptionsUi(deps.flowUi)
            )


            CONTROL_PANEL_CELLULAR_SIM_DEACTIVATE -> listOf(
                StepShowSimDeactivateUi(deps.flowUi),
                StepDeactivateSim(deps.cloud)
            )


            CONTROL_PANEL_CELLULAR_SIM_REACTIVATE -> listOf(
                StepShowSimReactivateUi(deps.flowUi),
                StepReactivateSim(deps.cloud)
            )


            CONTROL_PANEL_CELLULAR_SIM_UNPAUSE -> listOf(
                StepShowSimUnpauseUi(deps.flowUi),
                StepUnpauseSim(deps.cloud)
            )

            CONTROL_PANEL_CELLULAR_SET_NEW_DATA_LIMIT -> listOf(
                StepShowSetDataLimitUi(deps.flowUi),
                StepSetDataLimit(deps.flowUi, deps.cloud),
                StepUnsetFullSimData(),
                StepFetchFullSimData(deps.cloud, deps.flowUi),
                StepPopBackStack(deps.flowUi)
            )

            CONTROL_PANEL_CELLULAR_SIM_ACTION_POSTFLOW -> listOf(
                StepUnsetFullSimData(),
                StepFetchFullSimData(deps.cloud, deps.flowUi),
                StepPopBackStack(deps.flowUi)
            )


            CONTROL_PANEL_MESH_INSPECT_NETWORK_FLOW -> listOf(
                StepFetchCurrentMeshNetwork(deps.flowUi),
                StepShowMeshInspectNetworkUi(deps.flowUi)
            )

            STANDALONE_POSTFLOW -> listOf(
                StepSetNewDeviceName(deps.flowUi, deps.cloud)
                // StepOfferToAddOneMoreDevice()  // FIXME: add support for this
            )


            SINGLE_TASK_POSTFLOW -> listOf(
                StepShowSingleTaskCongratsScreen(deps.flowUi)
            )
        }
    }

}