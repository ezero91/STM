package org.matsim.other.Strategies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FitnessWriter {

    public static void write(
            String outputDirectory,
            FitnessMetrics metrics
    ) {

        String json = String.format(
                """
                {
                  "avg_score": %.4f,
                  "avg_travel_time_min": %.4f,
                  "avg_wait_time_min": %.4f,
                  "pt_share": %.4f,
                  "vehicle_km": %.4f,
                  "vehicle_hours": %.4f,
                  "fleet_size": %.0f,
                  "avg_occupancy": %.4f,
                  "operational_cost": %.4f
                }
                """,

                metrics.avgScore,
                metrics.avgTravelTimeMin,
                metrics.avgWaitTimeMin,
                metrics.ptShare,
                metrics.vehicleKm,
                metrics.vehicleHours,
                metrics.fleetSize,
                metrics.avgOccupancy,
                metrics.operationalCost
        );

        try {

            Files.writeString(
                    Path.of(
                            outputDirectory,
                            "fitness.json"
                    ),
                    json
            );

            System.out.println(
                    "fitness.json criado em: "
                            + outputDirectory
            );

        } catch (IOException e) {

            throw new RuntimeException(
                    "Erro criando fitness.json",
                    e
            );
        }
    }
}