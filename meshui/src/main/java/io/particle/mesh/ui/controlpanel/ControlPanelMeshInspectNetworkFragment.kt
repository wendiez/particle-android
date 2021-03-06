package io.particle.mesh.ui.controlpanel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import io.particle.android.sdk.cloud.ParticleCloudSDK
import io.particle.android.sdk.cloud.ParticleNetwork
import io.particle.firmwareprotos.ctrl.mesh.Mesh
import io.particle.mesh.common.android.livedata.nonNull
import io.particle.mesh.common.android.livedata.runBlockOnUiThreadAndAwaitUpdate
import io.particle.mesh.setup.flow.DialogResult.NEGATIVE
import io.particle.mesh.setup.flow.DialogResult.POSITIVE
import io.particle.mesh.setup.flow.DialogSpec.StringDialogSpec
import io.particle.mesh.setup.flow.FlowRunnerUiListener
import io.particle.mesh.ui.R
import io.particle.mesh.ui.TitleBarOptions
import kotlinx.android.synthetic.main.fragment_controlpanel_mesh_network_info.*
import mu.KotlinLogging


class ControlPanelMeshInspectNetworkFragment : BaseControlPanelFragment() {

    override val titleBarOptions = TitleBarOptions(
        R.string.p_controlpanel_mesh_inspect_title,
        showBackButton = true
    )

    private val log = KotlinLogging.logger {}

    private val cloud = ParticleCloudSDK.getCloud()

    private var cachedMeshNetworkDataFromDevice: Mesh.NetworkInfo? = null
    private var cachedMeshNetworkDataFromCloud: ParticleNetwork? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_controlpanel_mesh_network_info, container, false)
    }

    override fun onFragmentReady(activity: FragmentActivity, flowUiListener: FlowRunnerUiListener) {
        super.onFragmentReady(activity, flowUiListener)
        log.info { "onFragmentReady()" }

        p_controlpanel_action_leave_network.setOnClickListener { leaveNetwork() }

        updateLocalNetworkInfoCaches(flowUiListener)

        onNetworkInfoUpdated(cachedMeshNetworkDataFromDevice!!)
    }

    private fun updateLocalNetworkInfoCaches(flowUiListener: FlowRunnerUiListener) {
        cachedMeshNetworkDataFromDevice = flowUiListener.mesh.currentlyJoinedNetwork!!
        cachedMeshNetworkDataFromCloud = flowUiListener.cloud.meshNetworksFromAPI!!
            .firstOrNull { cachedMeshNetworkDataFromDevice?.networkId == it.id }
    }

    override fun onResume() {
        super.onResume()
        onNetworkInfoUpdated(cachedMeshNetworkDataFromDevice!!)
    }

    private fun onNetworkInfoUpdated(networkInfo: Mesh.NetworkInfo) {
        log.info { "onNetworkInfoUpdated(): $networkInfo" }

        p_controlpanel_mesh_inspect_network_name.text = networkInfo.name
        p_controlpanel_mesh_inspect_network_pan_id.text = networkInfo.panId.toString()
        p_controlpanel_mesh_inspect_network_xpan_id.text = networkInfo.extPanId.toString()
        p_controlpanel_mesh_inspect_network_channel.text = networkInfo.channel.toString()
        p_controlpanel_mesh_inspect_network_network_id.text = networkInfo.networkId

        flowScopes.onMain {
            flowSystemInterface.showGlobalProgressSpinner(true)

            val (isGateway, count) = flowScopes.withWorker {
                val meshMemberships = cloud.getNetworkDevices(networkInfo.networkId)
                val isGw = meshMemberships.firstOrNull { it.deviceId == device.id }
                    ?.membership
                    ?.roleData
                    ?.isGateway
                return@withWorker Pair(isGw, meshMemberships.size)
            }

            val roleLabel = if (isGateway == true) "Gateway" else "Node"
            p_controlpanel_mesh_inspect_network_device_role.text = roleLabel
            p_controlpanel_mesh_inspect_network_device_count.text = count.toString()

            flowSystemInterface.showGlobalProgressSpinner(false)
        }
    }

    private fun leaveNetwork() {
        val meshName = cachedMeshNetworkDataFromDevice?.name

        flowScopes.onMain {
            val result = flowSystemInterface.dialogHack.dialogResultLD
                .nonNull(flowScopes)
                .runBlockOnUiThreadAndAwaitUpdate(flowScopes) {
                    flowSystemInterface.dialogHack.newDialogRequest(
                        StringDialogSpec(
                            "Remove this device from the current mesh network, '$meshName'?",
                            "Leave network",
                            "Cancel",
                            "Leave current network?"
                        )
                    )
                }

            when (result) {
                NEGATIVE -> { /* no-op */
                }
                POSITIVE -> {
                    startFlowWithBarcode(
                        flowRunner::startControlPanelMeshLeaveCurrentMeshNetworkFlow
                    )
                }
            }
        }
    }
}
