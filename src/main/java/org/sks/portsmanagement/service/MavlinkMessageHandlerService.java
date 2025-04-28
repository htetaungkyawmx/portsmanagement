package org.sks.portsmanagement.service;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.ardupilotmega.Wind;
import io.dronefleet.mavlink.common.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MavlinkMessageHandlerService {

    // Shared telemetry data maps using LinkedHashMap to preserve order.
    private final Map<Integer, LinkedHashMap<String, Object>> telemetryUdpDataMap = new ConcurrentHashMap<>();
    private final Map<Integer, List<Map<String, Object>>> waypointsPerPort = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> totalMissionItems = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> requestedMissionList = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, Double>> homeLocations = new ConcurrentHashMap<>();
    private final Set<Integer> activePorts = ConcurrentHashMap.newKeySet();
    // Store the last update timestamp for each port.
    private final Map<Integer, Long> lastTelemetryUpdate = new ConcurrentHashMap<>();


    private LinkedHashMap<String, Object> initializeTelemetryData() {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("port",0.0);
        data.put("GCS_IP", "Unknown");
        data.put("system_id", "Unknown");
        data.put("lat", 0.0);
        data.put("lon", 0.0);
        data.put("alt", 0.0);
        data.put("dist_traveled", 0.0);
        data.put("wp_dist", 0);
        data.put("dist_to_home", 0.0);
        data.put("vertical_speed", 0.0);
        data.put("ground_speed", 0.0);
        data.put("wind_vel", 0.0);
        data.put("airspeed", 0.0);
        data.put("gps_hdop", 0.0);
        data.put("roll", 0.0);
        data.put("pitch", 0.0);
        data.put("yaw", 0.0);
        data.put("ch3percent", 0.0);
        data.put("ch9out", 0.0);
        data.put("tot", 0.0);
        data.put("toh", 0.0);
        data.put("time_in_air", 0.0);
        data.put("ch10out", 0.0);
        data.put("ch11out", 0.0);
        data.put("ch12out", 0.0);
        data.put("battery_voltage", 0);
        data.put("battery_current", 0.0);
        data.put("waypoints_count", 0);
        return data;
    }



    public void handleMessage(MavlinkMessage<?> message, int port, DatagramSocket udpSocket,
                              InetAddress senderAddress, int senderPort) {


        // Get (or initialize) telemetry data for this port.
        LinkedHashMap<String, Object> telemetryData =
                telemetryUdpDataMap.computeIfAbsent(port, k -> initializeTelemetryData());
        telemetryData.put("port", port);
        telemetryData.put("GCS_IP", senderAddress.getHostAddress());
        telemetryData.put("system_id", message.getOriginSystemId());
        telemetryData.put("home_location", homeLocations.get(port));
        telemetryData.put("waypoints", waypointsPerPort.get(port));
        activePorts.add(port);

        // Update the last update timestamp
        lastTelemetryUpdate.put(port, System.currentTimeMillis());

        // Process different types of MAVLink messages.
        if (message.getPayload() instanceof MissionCount missionCount) {
            System.out.println("✅ Received MISSION_COUNT on port " + port + ": " + missionCount.count());
            totalMissionItems.put(port, missionCount.count());
            waypointsPerPort.put(port, new ArrayList<>());
            requestMissionItemsUdp(senderAddress, senderPort, port, udpSocket);
        } else if (message.getPayload() instanceof MissionItemInt missionItemInt) {
            saveMissionItem(port, missionItemInt);
        } else if (message.getPayload() instanceof GlobalPositionInt globalPositionInt) {
            double currentLat = globalPositionInt.lat() / 1e7;
            double currentLon = globalPositionInt.lon() / 1e7;
            double currentAlt = globalPositionInt.relativeAlt() / 1000.0;
            Map<String, Double> homeLocation = homeLocations.getOrDefault(port, Map.of("lat", 0.0, "lon", 0.0));
           double  distToHome = calculateDistance(currentLat, currentLon, homeLocation.get("lat"), homeLocation.get("lon")) * 1000.0;
            telemetryData.put("dist_to_home", distToHome);
            telemetryData.put("lat", currentLat);
            telemetryData.put("lon", currentLon);
            telemetryData.put("alt", currentAlt);
        } else if (message.getPayload() instanceof NavControllerOutput navControllerOutput) {
            telemetryData.put("wp_dist", navControllerOutput.wpDist());

        } else if (message.getPayload() instanceof VfrHud vfrHud) {
            double groundSpeed = vfrHud.groundspeed(); // Speed in m/s
            telemetryData.put("airspeed", vfrHud.airspeed());
            telemetryData.put("ground_speed", vfrHud.groundspeed());
            telemetryData.put("vertical_speed", vfrHud.climb());


            int wpDist = (int) telemetryData.get("wp_dist");


            double totSeconds =(groundSpeed > 0)? (wpDist / groundSpeed) : 0;
            totSeconds =  (Math.round(totSeconds * 100.0) / 100.0);
            telemetryData.put("tot", totSeconds);


            double distToHome = (double) telemetryData.get("dist_to_home");

            double tohSeconds = (groundSpeed > 0) ? (distToHome / groundSpeed) : 0;
            tohSeconds = Math.round(tohSeconds * 100.0) / 100.0;
            telemetryData.put("toh",tohSeconds);


        } else if (message.getPayload() instanceof Attitude attitude) {
            telemetryData.put("roll", String.format("%.2f", Math.toDegrees(attitude.roll())));
            telemetryData.put("pitch", String.format("%.2f", Math.toDegrees(attitude.pitch())));
            telemetryData.put("yaw", String.format("%.2f", Math.toDegrees(attitude.yaw())));
        } else if (message.getPayload() instanceof SysStatus sysStatus) {
            telemetryData.put("battery_voltage", sysStatus.voltageBattery());
            telemetryData.put("battery_current", sysStatus.currentBattery());
        } else if (message.getPayload() instanceof ServoOutputRaw servoOutputRaw) {
            telemetryData.put("ch3out", servoOutputRaw.servo3Raw());
            telemetryData.put("ch3percent", String.format("%.2f", ((servoOutputRaw.servo3Raw() - 1000.0) / 1000.0) * 100));
            telemetryData.put("ch9out", servoOutputRaw.servo9Raw());
            telemetryData.put("ch10out", servoOutputRaw.servo10Raw());
            telemetryData.put("ch11out", servoOutputRaw.servo11Raw());
            telemetryData.put("ch12out", servoOutputRaw.servo12Raw());
        } else if (message.getPayload() instanceof Wind wind) {
            telemetryData.put("wind_vel", wind.speed());
        } else if (message.getPayload() instanceof GpsRawInt gpsRawInt) {
            telemetryData.put("gps_hdop", gpsRawInt.eph() / 100.0);
        }

        if (!requestedMissionList.getOrDefault(port, false)) {
            requestMissionListUdp(senderAddress, senderPort, port, udpSocket);
            requestedMissionList.put(port, true);
        }
    }

    private void saveMissionItem(int port, MissionItemInt missionItemInt) {
        Map<String, Object> waypoint = new LinkedHashMap<>();
        waypoint.put("seq", missionItemInt.seq());
        waypoint.put("lat", missionItemInt.x() / 1e7);
        waypoint.put("lon", missionItemInt.y() / 1e7);
        waypoint.put("alt", missionItemInt.z());
        waypointsPerPort.computeIfAbsent(port, k -> new ArrayList<>()).add(waypoint);

        if (missionItemInt.seq() == 0) {
            Map<String, Double> homeLocation = new HashMap<>();
            homeLocation.put("lat", missionItemInt.x() / 1e7);
            homeLocation.put("lon", missionItemInt.y() / 1e7);
            homeLocations.put(port, homeLocation);
        }
    }

    private void requestMissionListUdp(InetAddress address, int port, int udpPort, DatagramSocket udpSocket) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MavlinkConnection connection = MavlinkConnection.create(null, outputStream);
            connection.send1(255, 0, MissionRequestList.builder().targetSystem(1).targetComponent(1).build());
            DatagramPacket packet = new DatagramPacket(outputStream.toByteArray(), outputStream.size(), address, port);
            udpSocket.send(packet);
        } catch (Exception e) {
            System.err.println("❌ Error requesting Mission List on port " + udpPort + ": " + e.getMessage());
        }
    }

    private void requestMissionItemsUdp(InetAddress address, int port, int udpPort, DatagramSocket udpSocket) {
        int missionCount = totalMissionItems.getOrDefault(udpPort, -1);
        if (missionCount <= 0) return;
        try {
            for (int i = 0; i < missionCount; i++) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                MavlinkConnection connection = MavlinkConnection.create(null, outputStream);
                connection.send1(255, 0, MissionRequestInt.builder().targetSystem(1).targetComponent(1).seq(i).build());
                DatagramPacket packet = new DatagramPacket(outputStream.toByteArray(), outputStream.size(), address, port);
                udpSocket.send(packet);
            }
        } catch (Exception e) {
            System.err.println("❌ Error requesting Mission Items on port " + udpPort + ": " + e.getMessage());
        }
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371; // Earth's radius in km
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }


    // Expose telemetry data and active ports for TelemetryService.
    public LinkedHashMap<Integer, LinkedHashMap<String, Object>> getTelemetryData() {
        LinkedHashMap<Integer, LinkedHashMap<String, Object>> data = new LinkedHashMap<>();
        for (Map.Entry<Integer, LinkedHashMap<String, Object>> entry : telemetryUdpDataMap.entrySet()) {
            data.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return data;
    }

    public Set<Integer> getActivePorts() {
        return activePorts;
    }

    // Expose last update timestamp for a given port.
    public Long getLastTelemetryUpdate(int port) {
        return lastTelemetryUpdate.get(port);
    }
}