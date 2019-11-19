This module consists solely of the Java source code generated by `protoc` for [the DeviceOS protobuf definitions](https://github.com/particle-iot/firmware-protobuf).

To regenerate the Java protobuf sources:

1. Check out the protobuf repo linked above
1. `cd` to the root of the protobuf repo
1. Run `mkdir java`
1. Run `cd control`
1. In `common.proto`, remove/comment out the message `IPAddress` (But _do not_ remove `IpAddress`: note the case difference!), and do the same in `network.proto` for the messages/enums `NetworkGetStatusRequest`, `NetworkGetStatusReply`, `NetworkGetConfigurationRequest`, `NetworkSetConfigurationRequest`, `NetworkState`, `IPConfiguration`, `DNSConfiguration`, and `NetworkConfiguration` (See this bug for more information: https://github.com/particle-iot/firmware-protobuf/issues/8)
1. Run: `protoc --java_out=../java/ cellular.proto cloud.proto common.proto config.proto extensions.proto mesh.proto network.proto storage.proto wifi_new.proto`
1. Copy the Java sources under the `java` dir to the `src/main/java` dir of this module.
