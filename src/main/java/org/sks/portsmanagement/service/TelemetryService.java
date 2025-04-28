package org.sks.portsmanagement.service;

import org.sks.portsmanagement.wsconfig.TelemetryWebSocketHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class TelemetryService {

    private final MavlinkMessageHandlerService messageHandlerService;
    // Timeout period in milliseconds (5 seconds)
    private final long TELEMETRY_TIMEOUT_MS = 5000;
    // A formatter to update the timestamp field.
    private final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public TelemetryService(MavlinkMessageHandlerService messageHandlerService) {
        this.messageHandlerService = messageHandlerService;
    }

    // This method runs every second.
    @Scheduled(fixedRate = 1000)
    public void printAndSendTelemetry() {
        long now = System.currentTimeMillis();
        // Remove ports that haven't been updated within the timeout period.
        Set<Integer> activePorts = messageHandlerService.getActivePorts();
        activePorts.removeIf(port -> {
            Long lastUpdate = messageHandlerService.getLastTelemetryUpdate(port);
            return lastUpdate == null || (now - lastUpdate > TELEMETRY_TIMEOUT_MS);
        });

        // Retrieve telemetry data only for active ports.
        LinkedHashMap<Integer, LinkedHashMap<String, Object>> telemetryData = messageHandlerService.getTelemetryData();

        // Update the timestamp for each active port so that it appears fresh per second.
        for (Integer port : activePorts) {
            Map<String, Object> data = telemetryData.get(port);
            if (data != null) {
                data.put("timestamp", timestampFormat.format(new Date()));
            }
        }

        // Print telemetry data in a table format with columns for each active port.
//        printTelemetryTable(telemetryData, activePorts);

        // Build a list of telemetry data to send (only for ports with a valid GCS_IP)
        List<Map<String, Object>> telemetryList = new ArrayList<>();
        for (Integer port : activePorts) {
            Map<String, Object> data = telemetryData.get(port);
            if (data != null && !"Unknown".equals(data.get("GCS_IP"))) {
                telemetryList.add(data);
            }
        }
        if (!telemetryList.isEmpty()) {
            Map<String, Object> payload = new HashMap<>();
            System.out.println(telemetryList);
            payload.put("drones", telemetryList);
            TelemetryWebSocketHandler.sendTelemetryData(payload);
        }
    }

    // Helper method to print telemetry data in table format.
    private void printTelemetryTable(LinkedHashMap<Integer, LinkedHashMap<String, Object>> telemetryData, Set<Integer> activePorts) {
        // Get keys in insertion order from one of the telemetry maps.
        List<String> keys = new ArrayList<>();
        if (!telemetryData.isEmpty()) {
            keys.addAll(telemetryData.values().iterator().next().keySet());
        }
        // Print header row.
        System.out.printf("%-25s", "Telemetry Key");
        for (Integer port : activePorts) {
            System.out.printf("| %-20s", "Port " + port);
        }
        System.out.println();
        System.out.println("-----------------------------------------------------------------------");

        // Print each telemetry key as a row.
        for (String key : keys) {
            System.out.printf("%-25s", key);
            for (Integer port : activePorts) {
                Map<String, Object> data = telemetryData.get(port);
                String value = (data != null && data.get(key) != null) ? String.valueOf(data.get(key)) : "";
                System.out.printf("| %-20s", value);
            }
            System.out.println();
        }
    }
}